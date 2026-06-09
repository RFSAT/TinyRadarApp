package com.rfsat.tinyrad.viewmodel

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rfsat.tinyrad.data.models.*
import com.rfsat.tinyrad.data.repository.AppLog
import com.rfsat.tinyrad.data.repository.PreferencesRepository
import com.rfsat.tinyrad.data.repository.RecordingRepository
import com.rfsat.tinyrad.data.usb.TinyRadUsbManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ─── Permission action — MUST match the action used in PendingIntent ──────────
//
// Bug fix (v1.4): the action string is baked into both the PendingIntent and the
// IntentFilter at registration time.  They must be identical.  Previously the
// PendingIntent was constructed with Intent(ACTION_USB_PERMISSION) but the
// filter was constructed with IntentFilter(ACTION_USB_PERMISSION) — that part
// was correct, but the PendingIntent was created with requestCode=0 every time,
// meaning on Android 12+ the OS may reuse an older PendingIntent whose action
// no longer matches the live receiver.  Using FLAG_UPDATE_CURRENT fixes that.
//
// Additionally the device was extracted from the permission-result intent, but
// on some ROMs it arrives null there — fall back to the remembered pending device.
const val ACTION_USB_PERMISSION = "com.rfsat.tinyrad.USB_PERMISSION"

class TinyRadViewModel(application: Application) : AndroidViewModel(application) {

    val usbManager     = TinyRadUsbManager(application)
    private val recRepo  = RecordingRepository(application)
    private val prefRepo = PreferencesRepository(application)

    private val _uiState = MutableStateFlow(TinyRadUiState())
    val uiState: StateFlow<TinyRadUiState> = _uiState.asStateFlow()

    private var frameSamples = ArrayDeque<Long>(20)

    // Remember the device we requested permission for so we can connect
    // even if EXTRA_DEVICE comes back null (some ROM quirk).
    private var pendingDevice: UsbDevice? = null

    // ── USB permission receiver ───────────────────────────────────────────────

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return

            // Extract device — prefer intent extra, fall back to remembered device
            val device: UsbDevice? = (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            ) ?: pendingDevice

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

            AppLog.info("Permission result: granted=$granted device=${device?.deviceName}")

            if (granted && device != null) {
                pendingDevice = null
                usbManager.connect(device)
                observeFrames()
            } else {
                pendingDevice = null
                _uiState.update {
                    it.copy(
                        connectionState = UsbConnectionState.ERROR,
                        errorMessage    = "USB permission denied by user"
                    )
                }
            }
        }
    }

    init {
        // Register the permission receiver.
        // Use RECEIVER_NOT_EXPORTED on API 33+ — the broadcast is sent only by
        // the OS USB stack so it never crosses package boundaries.
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            application.registerReceiver(
                usbPermissionReceiver, filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        else
            application.registerReceiver(usbPermissionReceiver, filter)

        viewModelScope.launch {
            usbManager.connectionState.collect { cs ->
                _uiState.update { it.copy(connectionState = cs) }
            }
        }
        viewModelScope.launch {
            usbManager.deviceName.collect { name ->
                _uiState.update { it.copy(deviceName = name) }
            }
        }
        viewModelScope.launch {
            prefRepo.configFlow.first().also { cfg ->
                _uiState.update { it.copy(config = cfg) }
            }
        }
    }

    // ── USB connection ────────────────────────────────────────────────────────

    fun findAndConnect() {
        val app     = getApplication<Application>()
        val devices = usbManager.findTinyRadDevices()

        if (devices.isEmpty()) {
            AppLog.warn("No USB devices found")
            _uiState.update {
                it.copy(
                    connectionState = UsbConnectionState.ERROR,
                    errorMessage    = "No USB device found — check OTG cable and try again"
                )
            }
            return
        }

        val device = devices.first()
        AppLog.info(
            "Found device: ${device.productName ?: device.deviceName} " +
            "VID=${device.vendorId.toString(16).uppercase()} " +
            "PID=${device.productId.toString(16).uppercase()}"
        )

        if (usbManager.hasPermission(device)) {
            AppLog.info("Permission already held — connecting directly")
            usbManager.connect(device)
            observeFrames()
        } else {
            AppLog.info("Requesting USB permission for ${device.deviceName}")
            pendingDevice = device
            _uiState.update { it.copy(connectionState = UsbConnectionState.REQUESTING_PERMISSION) }

            // FLAG_UPDATE_CURRENT ensures the PendingIntent extras are refreshed
            // if an old intent with the same action already existed in the system.
            val pi = PendingIntent.getBroadcast(
                app,
                device.deviceId,          // unique requestCode per device
                Intent(ACTION_USB_PERMISSION).apply {
                    `package` = app.packageName   // scope to this app only
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, pi)
        }
    }

    /**
     * Connect to a specific [device] chosen from the manual device picker.
     * Requests permission if not already held.
     */
    fun connectDevice(device: UsbDevice) {
        val app = getApplication<Application>()
        AppLog.info(
            "Manual connect: ${device.productName ?: device.deviceName} " +
            "VID=${device.vendorId.toString(16).uppercase()} " +
            "PID=${device.productId.toString(16).uppercase()}"
        )
        if (usbManager.hasPermission(device)) {
            usbManager.connect(device)
            observeFrames()
        } else {
            pendingDevice = device
            _uiState.update { it.copy(connectionState = UsbConnectionState.REQUESTING_PERMISSION) }
            val pi = PendingIntent.getBroadcast(
                app,
                device.deviceId,
                Intent(ACTION_USB_PERMISSION).apply { `package` = app.packageName },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, pi)
        }
    }

    fun disconnect() {
        stopStreaming()
        usbManager.disconnect()
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    fun startStreaming() {
        val cfg = _uiState.value.config
        usbManager.startStreaming(cfg)
        _uiState.update { it.copy(isStreaming = true) }
    }

    fun stopStreaming() {
        usbManager.stopStreaming()
        _uiState.update { it.copy(isStreaming = false) }
    }

    private fun observeFrames() {
        viewModelScope.launch {
            usbManager.frameFlow.filterNotNull().collect { frame ->
                val now = System.currentTimeMillis()
                frameSamples.addLast(now)
                while (frameSamples.size > 20) frameSamples.removeFirst()
                val fps = if (frameSamples.size >= 2)
                    1000f * (frameSamples.size - 1) /
                        (frameSamples.last() - frameSamples.first()).toFloat()
                else 0f

                if (_uiState.value.isRecording) {
                    recRepo.writeFrame(frame)
                    _uiState.update { it.copy(recordingRows = recRepo.currentRows()) }
                }

                _uiState.update {
                    it.copy(
                        currentFrame   = frame,
                        trackedObjects = frame.detectedObjects,
                        frameRate      = fps,
                        totalFrames    = frame.frameIndex
                    )
                }
            }
        }
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    fun startRecording(tag: String = "") {
        viewModelScope.launch {
            val path = recRepo.startRecording(tag)
            _uiState.update { it.copy(isRecording = true, recordingPath = path, recordingRows = 0) }
            AppLog.info("Recording started → $path")
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            val path = recRepo.stopRecording()
            _uiState.update { it.copy(isRecording = false) }
            AppLog.info("Recording saved → $path")
        }
    }

    fun listRecordings() = recRepo.listRecordings()

    // ── Config ────────────────────────────────────────────────────────────────

    fun applyConfig(cfg: TinyRadConfig) {
        viewModelScope.launch {
            prefRepo.saveConfig(cfg)
            usbManager.sendConfig(cfg)
            _uiState.update { it.copy(config = cfg) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) {}
        usbManager.cleanup()
    }
}
