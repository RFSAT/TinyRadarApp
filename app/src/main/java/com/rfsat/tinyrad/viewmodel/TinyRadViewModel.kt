package com.rfsat.tinyrad.viewmodel

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rfsat.tinyrad.data.models.*
import com.rfsat.tinyrad.data.repository.AppLog
import com.rfsat.tinyrad.data.repository.PreferencesRepository
import com.rfsat.tinyrad.data.repository.RecordingRepository
import com.rfsat.tinyrad.data.usb.TinyRadUsbManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

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

            // Extract device — prefer intent extra, fall back to remembered device.
            // IntentCompat handles the API 33 / pre-33 split internally.
            val device: UsbDevice? =
                IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    ?: pendingDevice

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

            AppLog.info("Permission result: granted=$granted device=${device?.deviceName}")

            if (granted && device != null) {
                pendingDevice = null
                // claimInterface must not run on the main thread on some Android versions
                viewModelScope.launch(Dispatchers.IO) {
                    usbManager.connect(device)
                    withContext(Dispatchers.Main) { observeFrames() }
                }
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
        // Register the permission receiver with RECEIVER_NOT_EXPORTED on all
        // API levels via ContextCompat — satisfies the Android U lint requirement
        // without an API-level branch.  The broadcast is only ever sent by this
        // app's own PendingIntent so it must never be exported.
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            application,
            usbPermissionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

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
            viewModelScope.launch(Dispatchers.IO) {
                usbManager.connect(device)
                withContext(Dispatchers.Main) { observeFrames() }
            }
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
            viewModelScope.launch(Dispatchers.IO) {
                usbManager.connect(device)
                withContext(Dispatchers.Main) { observeFrames() }
            }
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
            try {
                // FPS measured over complete FMCW frames (one frame = 80 chirps)
                var lastFrameMs = 0L
                usbManager.frameFlow.filterNotNull().collect { frame ->
                val now = System.currentTimeMillis()
                val fps = if (lastFrameMs > 0 && now > lastFrameMs)
                    1000f / (now - lastFrameMs).toFloat()
                else 0f
                lastFrameMs = now

                if (_uiState.value.isRecording) {
                    recRepo.writeFrame(frame)
                    _uiState.update { it.copy(recordingRows = recRepo.currentRows()) }
                }

                // ── Scan / Track mode transitions ─────────────────────────────
                val objects  = frame.detectedObjects
                val curState = _uiState.value
                val (newMode, newTargetId) = when (curState.operatingMode) {
                    RadarOperatingMode.SCANNING -> {
                        if (objects.isNotEmpty()) {
                            // Transition to TRACKING on the strongest-SNR object
                            val best = objects.maxByOrNull { it.snrDb }
                            AppLog.info("Mode → TRACKING  target=${best?.trackId} snr=${"%.1f".format(best?.snrDb ?: 0f)}dB")
                            RadarOperatingMode.TRACKING to best?.trackId
                        } else {
                            RadarOperatingMode.SCANNING to null
                        }
                    }
                    RadarOperatingMode.TRACKING -> {
                        val targetStillVisible = curState.trackTargetId?.let { tid ->
                            objects.any { it.trackId == tid }
                        } ?: false
                        if (targetStillVisible) {
                            // Stay in tracking on the same target
                            RadarOperatingMode.TRACKING to curState.trackTargetId
                        } else if (objects.isNotEmpty()) {
                            // Original target lost — re-acquire strongest
                            val best = objects.maxByOrNull { it.snrDb }
                            AppLog.info("Mode: re-acquired target=${best?.trackId}")
                            RadarOperatingMode.TRACKING to best?.trackId
                        } else {
                            // Nothing detected — return to scanning
                            AppLog.info("Mode → SCANNING  (target lost)")
                            RadarOperatingMode.SCANNING to null
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        currentFrame   = frame,
                        trackedObjects = objects,
                        frameRate      = fps,
                        totalFrames    = frame.frameIndex,
                        operatingMode  = newMode,
                        trackTargetId  = newTargetId
                    )
                }
            }
            } catch (e: Exception) {
                AppLog.error("observeFrames error: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    fun startRecording(tag: String = "") {
        viewModelScope.launch {
            val path = recRepo.startRecording(tag)
            _uiState.update { it.copy(isRecording = true, recordingRows = 0) }
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

    /** Override button: force return to SCANNING regardless of detections */
    fun overrideToScanning() {
        _uiState.update { it.copy(
            operatingMode = RadarOperatingMode.SCANNING,
            trackTargetId = null
        )}
        AppLog.info("Mode → SCANNING  (manual override)")
    }

    fun applyConfig(cfg: TinyRadConfig) {
        viewModelScope.launch {
            prefRepo.saveConfig(cfg)
            _uiState.update { it.copy(config = cfg) }
            // If currently streaming, restart to apply new parameters to hardware
            if (_uiState.value.isStreaming) {
                usbManager.stopStreaming()
                delay(200)
                usbManager.startStreaming(cfg)
                AppLog.info("Config applied — streaming restarted")
            } else {
                usbManager.sendConfig(cfg)
            }
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
