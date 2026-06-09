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

private const val ACTION_USB_PERMISSION = "com.rfsat.tinyrad.USB_PERMISSION"

class TinyRadViewModel(application: Application) : AndroidViewModel(application) {

    val usbManager     = TinyRadUsbManager(application)
    private val recRepo  = RecordingRepository(application)
    private val prefRepo = PreferencesRepository(application)

    // ── UI state ──────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(TinyRadUiState())
    val uiState: StateFlow<TinyRadUiState> = _uiState.asStateFlow()

    // Frame rate tracking
    private var lastFrameMs = 0L
    private var frameSamples = ArrayDeque<Long>(20)

    // ── USB permission receiver ───────────────────────────────────────────────

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                usbManager.connect(device)
                observeFrames()
            } else {
                _uiState.update { it.copy(
                    connectionState = UsbConnectionState.ERROR,
                    errorMessage    = "USB permission denied"
                ) }
            }
        }
    }

    init {
        // Register USB permission receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            application.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            application.registerReceiver(usbPermissionReceiver, filter)

        // Mirror USB connection state
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

        // Load persisted config
        viewModelScope.launch {
            prefRepo.configFlow.first().also { cfg ->
                _uiState.update { it.copy(config = cfg) }
            }
        }
    }

    // ── USB connection ────────────────────────────────────────────────────────

    fun findAndConnect() {
        val app    = getApplication<Application>()
        val devices = usbManager.findTinyRadDevices()
        if (devices.isEmpty()) {
            _uiState.update { it.copy(
                errorMessage = "No TinyRAD device found. Check USB-OTG cable."
            ) }
            return
        }
        val device = devices.first()
        if (usbManager.hasPermission(device)) {
            usbManager.connect(device)
            observeFrames()
        } else {
            val pi = PendingIntent.getBroadcast(
                app, 0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
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
                // Frame rate
                val now = System.currentTimeMillis()
                frameSamples.addLast(now)
                while (frameSamples.size > 20) frameSamples.removeFirst()
                val fps = if (frameSamples.size >= 2) {
                    1000f * (frameSamples.size - 1) / (frameSamples.last() - frameSamples.first()).toFloat()
                } else 0f

                // Record if active
                if (_uiState.value.isRecording) {
                    recRepo.writeFrame(frame)
                    _uiState.update { it.copy(recordingRows = recRepo.currentRows()) }
                }

                _uiState.update { it.copy(
                    currentFrame    = frame,
                    trackedObjects  = frame.detectedObjects,
                    frameRate       = fps,
                    totalFrames     = frame.frameIndex
                ) }
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
        try { getApplication<Application>().unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        usbManager.cleanup()
    }
}
