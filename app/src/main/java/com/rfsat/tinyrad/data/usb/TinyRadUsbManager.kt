package com.rfsat.tinyrad.data.usb

import android.content.Context
import android.hardware.usb.*
import android.util.Log
import com.rfsat.tinyrad.data.models.*
import com.rfsat.tinyrad.data.repository.AppLog
import com.rfsat.tinyrad.data.repository.LogLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

// ─── TinyRAD USB CDC-ACM protocol constants ───────────────────────────────────
//
// The TinyRAD evaluation board presents as a USB CDC-ACM serial device.
// Commands are sent as ASCII text terminated with '\n'; the device replies
// with binary radar frames or ASCII acknowledgements.

private const val TAG = "TinyRadUSB"

// Known VID/PID pairs for TinyRAD
private val TINYRAD_VID_PID = listOf(
    0x0456 to 0xB671,   // Analog Devices TinyRAD
    0x0483 to 0x5740    // STM32 CDC-ACM fallback
)

// USB CDC class/subclass constants
private const val USB_CLASS_CDC         = 0x02
private const val USB_CLASS_CDC_DATA    = 0x0A

// Frame sync bytes from TinyRAD firmware
private const val FRAME_MAGIC_0 = 0xA5.toByte()
private const val FRAME_MAGIC_1 = 0x5A.toByte()

// TinyRAD command strings (ASCII, newline-terminated)
private const val CMD_START     = "START\n"
private const val CMD_STOP      = "STOP\n"
private const val CMD_QUERY     = "QUERY\n"   // request device info
private const val CMD_CONFIG    = "CONFIG"    // CONFIG key=val\n

// Bulk transfer timeout ms
private const val BULK_TIMEOUT_MS = 100

// Read buffer size — one radar frame ≤ 64 KB
private const val READ_BUF_SIZE = 65536

/**
 * Manages the USB connection to the TinyRAD FMCW radar board.
 *
 * Architecture mirrors ShimmerBluetoothManager: a coroutine loop reads raw USB
 * bulk data, reassembles frames, runs a lightweight DSP pipeline (range FFT →
 * Doppler FFT → CFAR → object classification), and publishes [RadarFrame]
 * objects to [frameFlow].
 */
class TinyRadUsbManager(private val context: Context) {

    // ── Public state ──────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow(UsbConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _frameFlow = MutableStateFlow<RadarFrame?>(null)
    val frameFlow = _frameFlow.asStateFlow()

    private val _deviceName = MutableStateFlow("TinyRAD (not connected)")
    val deviceName = _deviceName.asStateFlow()

    // ── Internal USB handles ──────────────────────────────────────────────────

    private var usbManager:     UsbManager?     = null
    private var usbDevice:      UsbDevice?      = null
    private var usbConnection:  UsbDeviceConnection? = null
    private var bulkIn:         UsbEndpoint?    = null
    private var bulkOut:        UsbEndpoint?    = null

    // ── DSP / streaming ───────────────────────────────────────────────────────

    private var streamJob:      Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var frameIndex      = 0L
    private var config          = TinyRadConfig()

    // Object tracker state: trackId → last DetectedObject
    private val tracker         = mutableMapOf<Int, DetectedObject>()
    private var nextTrackId     = 1

    // ── Connect / Disconnect ──────────────────────────────────────────────────

    /**
     * Attempt to open and configure the first recognised TinyRAD USB device.
     * Caller must already hold USB permission (obtained via [requestPermission]).
     */
    fun connect(device: UsbDevice) {
        _connectionState.value = UsbConnectionState.CONNECTING
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        try {
            val conn = usbManager!!.openDevice(device)
                ?: throw IllegalStateException("openDevice returned null — no permission?")

            // Find CDC-ACM data interface (class 0x0A) and its bulk endpoints
            var dataIface: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == USB_CLASS_CDC_DATA) {
                    dataIface = iface; break
                }
            }

            // Fallback: use first interface with bulk endpoints if no CDC data iface found
            if (dataIface == null) {
                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    for (j in 0 until iface.endpointCount) {
                        if (iface.getEndpoint(j).type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            dataIface = iface; break
                        }
                    }
                    if (dataIface != null) break
                }
            }

            requireNotNull(dataIface) { "No suitable bulk interface found on TinyRAD device" }
            conn.claimInterface(dataIface, true)

            for (j in 0 until dataIface.endpointCount) {
                val ep = dataIface.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    when (ep.direction) {
                        UsbConstants.USB_DIR_IN  -> bulkIn  = ep
                        UsbConstants.USB_DIR_OUT -> bulkOut = ep
                    }
                }
            }

            requireNotNull(bulkIn)  { "No bulk-IN endpoint" }
            requireNotNull(bulkOut) { "No bulk-OUT endpoint" }

            usbDevice     = device
            usbConnection = conn
            _deviceName.value = device.productName ?: "TinyRAD ${device.deviceName}"
            _connectionState.value = UsbConnectionState.CONNECTED

            AppLog.info("USB connected: ${_deviceName.value}  " +
                    "VID=${device.vendorId.toHex(4)} PID=${device.productId.toHex(4)}")
        } catch (e: Exception) {
            AppLog.error("USB connect failed: ${e.message}")
            _connectionState.value = UsbConnectionState.ERROR
        }
    }

    /** Begin streaming radar data from the TinyRAD. */
    fun startStreaming(cfg: TinyRadConfig = TinyRadConfig()) {
        config    = cfg
        streamJob = scope.launch { streamLoop() }
        AppLog.info("Streaming started")
    }

    /** Stop streaming without disconnecting. */
    fun stopStreaming() {
        streamJob?.cancel()
        sendCommand(CMD_STOP)
        AppLog.info("Streaming stopped")
    }

    /** Gracefully disconnect. */
    fun disconnect() {
        stopStreaming()
        usbConnection?.close()
        usbConnection  = null
        usbDevice      = null
        bulkIn         = null
        bulkOut        = null
        _connectionState.value = UsbConnectionState.DISCONNECTED
        _deviceName.value      = "TinyRAD (not connected)"
        frameIndex             = 0L
        tracker.clear()
        AppLog.info("USB disconnected")
    }

    fun cleanup() {
        disconnect()
        scope.cancel()
    }

    // ── Permission request helper ─────────────────────────────────────────────

    /**
     * Scan the USB host bus and return all recognised TinyRAD devices, or
     * any device attached if none match the known VID/PID list.
     */
    fun findTinyRadDevices(): List<UsbDevice> {
        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return mgr.deviceList.values.filter { dev ->
            TINYRAD_VID_PID.any { (vid, pid) -> dev.vendorId == vid && dev.productId == pid }
        }.ifEmpty {
            // Return all USB devices so user can pick manually
            mgr.deviceList.values.toList()
        }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return mgr.hasPermission(device)
    }

    fun requestPermission(device: UsbDevice, pi: android.app.PendingIntent) {
        _connectionState.value = UsbConnectionState.REQUESTING_PERMISSION
        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        mgr.requestPermission(device, pi)
    }

    // ── Streaming loop ────────────────────────────────────────────────────────

    private suspend fun streamLoop() = withContext(Dispatchers.IO) {
        sendCommand(CMD_START)

        val readBuf  = ByteArray(READ_BUF_SIZE)
        val frameBuf = ByteBuffer.allocate(READ_BUF_SIZE * 4).order(ByteOrder.LITTLE_ENDIAN)
        var synced   = false

        while (isActive && usbConnection != null) {
            val n = usbConnection!!.bulkTransfer(bulkIn, readBuf, readBuf.size, BULK_TIMEOUT_MS)
            if (n <= 0) { delay(1); continue }

            frameBuf.put(readBuf, 0, n)
            frameBuf.flip()

            while (frameBuf.remaining() >= 8) {
                if (!synced) {
                    // Hunt for sync word 0xA5 0x5A
                    if (frameBuf.get() != FRAME_MAGIC_0) continue
                    if (frameBuf.remaining() < 1) break
                    if (frameBuf.get() != FRAME_MAGIC_1) continue
                    synced = true
                }

                // Header after sync: [uint16 payload_len][uint8 frame_type][uint8 reserved]
                if (frameBuf.remaining() < 4) break
                val payloadLen  = frameBuf.short.toInt() and 0xFFFF
                val frameType   = frameBuf.get().toInt() and 0xFF
                @Suppress("UNUSED_VARIABLE")
                val reserved    = frameBuf.get()

                if (payloadLen > frameBuf.capacity() - 8) {
                    // Implausible length — resync
                    synced = false
                    frameBuf.compact(); continue
                }
                if (frameBuf.remaining() < payloadLen) {
                    // Wait for more data
                    frameBuf.position(frameBuf.position() - 6) // rewind past header + sync
                    break
                }

                val payload = ByteArray(payloadLen)
                frameBuf.get(payload)
                synced = false  // require sync on every frame

                processFrame(frameType, payload)
            }

            frameBuf.compact()
        }
    }

    // ── Frame processing / DSP pipeline ───────────────────────────────────────
    //
    // TinyRAD frame types:
    //   0x01 = ADC data frame   (raw IF samples)
    //   0x02 = Processed frame  (device-side FFT, CFAR results)
    //   0x03 = Config ACK
    //   0x04 = Device info string

    private fun processFrame(type: Int, payload: ByteArray) {
        when (type) {
            0x01 -> processAdcFrame(payload)
            0x02 -> processDeviceProcessedFrame(payload)
            0x03 -> AppLog.debug("Config ACK: ${payload.decodeToString().trim()}")
            0x04 -> {
                val info = payload.decodeToString().trim()
                _deviceName.value = "TinyRAD — $info"
                AppLog.info("Device info: $info")
            }
            else -> AppLog.debug("Unknown frame type 0x${type.toHex(2)}, len=${payload.size}")
        }
    }

    /**
     * Process raw ADC frame through the host-side DSP pipeline:
     *   1. Reshape ADC data into [chirps × samples] matrix
     *   2. Range FFT along fast-time axis
     *   3. Doppler FFT along slow-time axis
     *   4. Log-magnitude → CFAR threshold
     *   5. Peak extraction → distance, velocity
     *   6. Object classification
     */
    private fun processAdcFrame(payload: ByteArray) {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        // Header: numChirps(uint16) × samplesPerChirp(uint16) × adcBits(uint8) × reserved(1)
        if (buf.remaining() < 6) return
        val numChirps   = buf.short.toInt() and 0xFFFF
        val numSamples  = buf.short.toInt() and 0xFFFF
        @Suppress("UNUSED_VARIABLE")
        val adcBits     = buf.get().toInt()
        buf.get() // reserved

        val expectedBytes = numChirps * numSamples * 4  // int16 I + int16 Q per sample
        if (buf.remaining() < expectedBytes) return

        // Read IQ samples
        val adcI = Array(numChirps) { FloatArray(numSamples) }
        val adcQ = Array(numChirps) { FloatArray(numSamples) }
        for (c in 0 until numChirps) {
            for (s in 0 until numSamples) {
                adcI[c][s] = buf.short.toFloat()
                adcQ[c][s] = buf.short.toFloat()
            }
        }

        // Range FFT (along samples axis — fast time)
        val rangeFftSize = config.rangeFftSize.coerceAtLeast(numSamples).nextPow2()
        val rangeSpectrum = Array(numChirps) {
            complexFft(adcI[it], adcQ[it], rangeFftSize)
        }

        // Doppler FFT (along chirps axis — slow time) for each range bin
        val rdMag = Array(rangeFftSize / 2) { FloatArray(numChirps) }
        for (r in 0 until rangeFftSize / 2) {
            val chirpI = FloatArray(numChirps) { c -> rangeSpectrum[c][r * 2] }
            val chirpQ = FloatArray(numChirps) { c -> rangeSpectrum[c][r * 2 + 1] }
            val dopplerSpec = complexFft(chirpI, chirpQ, numChirps.nextPow2())
            for (d in 0 until numChirps) {
                val re = dopplerSpec[d * 2]; val im = dopplerSpec[d * 2 + 1]
                rdMag[r][d] = 10f * log10(re * re + im * im + 1e-12f)
            }
        }

        // Physics: range resolution = c / (2 × bandwidth)
        val rangeResM   = 3e8f / (2f * config.bandwidthMHz * 1e6f)
        val dopplerResMs= (3e8f / config.startFreqGHz / 1e9f) / (2f * numChirps * config.chirpRepUs * 1e-6f)

        // CFAR detection
        val detections  = cfarDetect(rdMag, rangeResM, dopplerResMs)

        // Classification and tracking
        val objects     = classifyAndTrack(detections)

        // Flatten RD map for UI
        val flat = FloatArray(rangeFftSize / 2 * numChirps)
        for (r in 0 until rangeFftSize / 2)
            for (d in 0 until numChirps)
                flat[r * numChirps + d] = rdMag[r][d]

        val frame = RadarFrame(
            frameIndex      = ++frameIndex,
            timestampMs     = System.currentTimeMillis(),
            detectedObjects = objects,
            rangeDopplerMag = flat,
            rangeBins       = rangeFftSize / 2,
            dopplerBins     = numChirps,
            rangeResM       = rangeResM,
            dopplerResMs    = dopplerResMs
        )
        _frameFlow.value = frame
    }

    /**
     * Process a frame that has already been processed by TinyRAD firmware-side DSP.
     * Payload contains a list of detections: [count(uint8)] × [range_bin(uint16)
     * doppler_bin(int16) snr(float32)] — firmware packs each detection as 8 bytes.
     */
    private fun processDeviceProcessedFrame(payload: ByteArray) {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 5) return

        val rangeBins    = buf.short.toInt() and 0xFFFF
        val dopplerBins  = buf.short.toInt() and 0xFFFF
        val count        = buf.get().toInt() and 0xFF
        val rangeResM    = if (buf.remaining() >= 4) buf.float else (3e8f / (2f * config.bandwidthMHz * 1e6f))
        val dopplerResMs = if (buf.remaining() >= 4) buf.float else 0.1f

        val rawDets = mutableListOf<RawDetection>()
        repeat(count) {
            if (buf.remaining() >= 8) {
                val rb    = buf.short.toInt() and 0xFFFF
                val db    = buf.short.toInt()
                val snr   = buf.float
                rawDets.add(RawDetection(rb, db, snr, rangeResM, dopplerResMs))
            }
        }

        val objects = classifyAndTrack(rawDets)
        val frame   = RadarFrame(
            frameIndex       = ++frameIndex,
            timestampMs      = System.currentTimeMillis(),
            detectedObjects  = objects,
            rangeBins        = rangeBins,
            dopplerBins      = dopplerBins,
            rangeResM        = rangeResM,
            dopplerResMs     = dopplerResMs
        )
        _frameFlow.value = frame
    }

    // ── DSP helpers ───────────────────────────────────────────────────────────

    /** In-place complex FFT (Cooley-Tukey radix-2 DIT, length must be power of 2).
     *  Returns interleaved [Re0, Im0, Re1, Im1, …] of length 2*N. */
    private fun complexFft(inI: FloatArray, inQ: FloatArray, n: Int): FloatArray {
        val out = FloatArray(n * 2)
        // Zero-pad / truncate input into bit-reversed order
        val bits = log2(n.toFloat()).toInt()
        for (i in 0 until n) {
            val j = bitReverse(i, bits)
            val srcI = if (i < inI.size) inI[i] else 0f
            val srcQ = if (i < inQ.size) inQ[i] else 0f
            out[j * 2]     = srcI
            out[j * 2 + 1] = srcQ
        }
        // Butterfly stages
        var len = 2
        while (len <= n) {
            val wRe = cos(-2.0 * PI / len).toFloat()
            val wIm = sin(-2.0 * PI / len).toFloat()
            var k   = 0
            while (k < n) {
                var uRe = 1f; var uIm = 0f
                for (j in 0 until len / 2) {
                    val pRe = out[(k + j) * 2]; val pIm = out[(k + j) * 2 + 1]
                    val qRe = out[(k + j + len / 2) * 2]; val qIm = out[(k + j + len / 2) * 2 + 1]
                    val tRe = uRe * qRe - uIm * qIm
                    val tIm = uRe * qIm + uIm * qRe
                    out[(k + j) * 2]               = pRe + tRe
                    out[(k + j) * 2 + 1]           = pIm + tIm
                    out[(k + j + len / 2) * 2]     = pRe - tRe
                    out[(k + j + len / 2) * 2 + 1] = pIm - tIm
                    val newURe = uRe * wRe - uIm * wIm
                    uIm = uRe * wIm + uIm * wRe; uRe = newURe
                }
                k += len
            }
            len *= 2
        }
        return out
    }

    private fun bitReverse(x: Int, bits: Int): Int {
        var v = x; var r = 0
        repeat(bits) { r = (r shl 1) or (v and 1); v = v shr 1 }
        return r
    }

    private data class RawDetection(
        val rangeBin:   Int,
        val dopplerBin: Int,
        val snrDb:      Float,
        val rangeResM:  Float,
        val dopplerResMs: Float
    ) {
        val distanceM:  Float get() = rangeBin   * rangeResM
        val speedMps:   Float get() {
            // Doppler bin 0 = 0 m/s; bins > N/2 are negative velocities
            val half = 32  // assume 64-bin Doppler
            val signed = if (dopplerBin > half) dopplerBin - half * 2 else dopplerBin
            return signed * dopplerResMs
        }
    }

    /** CA-CFAR 2D detector on the range-Doppler magnitude map. */
    private fun cfarDetect(
        rdMag: Array<FloatArray>,
        rangeResM: Float,
        dopplerResMs: Float
    ): List<RawDetection> {
        val results = mutableListOf<RawDetection>()
        val nRange   = rdMag.size
        val nDoppler = if (rdMag.isNotEmpty()) rdMag[0].size else 0
        val guard    = config.cfar_guard
        val train    = config.cfar_training
        val thresh   = config.cfar_threshold

        for (r in (guard + train) until (nRange - guard - train)) {
            for (d in (guard + train) until (nDoppler - guard - train)) {
                val cell = rdMag[r][d]
                // Compute noise level from training cells (exclude guard)
                var sum = 0f; var cnt = 0
                for (tr in -train - guard until train + guard + 1) {
                    for (td in -train - guard until train + guard + 1) {
                        if (abs(tr) > guard || abs(td) > guard) {
                            sum += rdMag[r + tr][d + td]; cnt++
                        }
                    }
                }
                val noiseFloor = if (cnt > 0) sum / cnt else 0f
                if (cell - noiseFloor >= thresh) {
                    results.add(RawDetection(r, d, cell - noiseFloor, rangeResM, dopplerResMs))
                }
            }
        }
        return results
    }

    /**
     * Classify each raw detection into an [ObjectClass] using a rule-based
     * heuristic and apply nearest-neighbour tracking.
     *
     * Classification rules (can be extended with an ML model later):
     *   Human:          0.3–10 m/s, distance < 30 m, low RCS
     *   Animal:         0.1–15 m/s, variable RCS
     *   Ground vehicle: > 5 m/s OR stationary large RCS
     *   Aerial vehicle: any speed, distance > 5 m, high Doppler variance
     */
    private fun classifyAndTrack(detections: List<RawDetection>): List<DetectedObject> {
        val now     = System.currentTimeMillis()
        val results = mutableListOf<DetectedObject>()

        for (det in detections) {
            val dist  = det.distanceM
            val speed = abs(det.speedMps)
            val snr   = det.snrDb

            if (dist > config.maxRangeM || speed > config.maxSpeedMps || snr < config.minSnrDb)
                continue

            val cls = when {
                // Aerial: not close to ground range (simplified: any high-speed distant object)
                dist > 10f && speed > 15f              -> ObjectClass.AERIAL_VEHICLE
                // Ground vehicle: fast or high-RCS slow object
                speed > 5f && snr > 20f                -> ObjectClass.GROUND_VEHICLE
                speed < 1f && snr > 25f                -> ObjectClass.GROUND_VEHICLE // parked
                // Human micro-Doppler: slow, relatively close, moderate SNR
                speed in 0.3f..4f && dist < 20f        -> ObjectClass.HUMAN
                // Animal: slower than vehicle, faster than stationary
                speed in 0.1f..8f && snr < 20f         -> ObjectClass.ANIMAL
                else                                   -> ObjectClass.UNKNOWN
            }

            val confidence = when (cls) {
                ObjectClass.HUMAN          -> (1f - dist / 20f).coerceIn(0.4f, 0.95f)
                ObjectClass.GROUND_VEHICLE -> (snr / 40f).coerceIn(0.5f, 0.99f)
                ObjectClass.AERIAL_VEHICLE -> 0.7f
                ObjectClass.ANIMAL         -> 0.6f
                ObjectClass.UNKNOWN        -> 0.3f
            }

            val azimuth = 0f  // TinyRAD is single-TX/RX; azimuth requires antenna array
            val dir = Direction.entries.minByOrNull {
                abs(it.azimuthDeg - azimuth)
            } ?: Direction.AHEAD

            // Nearest-neighbour tracker
            val trackId = tracker.entries.minByOrNull { (_, prev) ->
                abs(prev.distanceM - dist) + abs(prev.speedMps - det.speedMps)
            }?.takeIf { (_, prev) ->
                abs(prev.distanceM - dist) < 2f
            }?.key ?: nextTrackId++

            val obj = DetectedObject(
                trackId      = trackId,
                objectClass  = cls,
                distanceM    = dist,
                azimuthDeg   = azimuth,
                elevationDeg = 0f,
                speedMps     = det.speedMps,
                direction    = dir,
                snrDb        = snr,
                confidence   = confidence,
                timestampMs  = now
            )
            tracker[trackId] = obj
            results.add(obj)
        }

        // Expire stale tracks (not updated in last 2 seconds)
        val stale = tracker.entries.filter { (_, v) -> now - v.timestampMs > 2000 }.map { it.key }
        stale.forEach { tracker.remove(it) }

        return results.sortedBy { it.distanceM }
    }

    // ── Command output ────────────────────────────────────────────────────────

    private fun sendCommand(cmd: String) {
        val bytes = cmd.toByteArray(Charsets.UTF_8)
        usbConnection?.bulkTransfer(bulkOut, bytes, bytes.size, 500)
    }

    fun sendConfig(cfg: TinyRadConfig) {
        sendCommand("$CMD_CONFIG bw=${cfg.bandwidthMHz}\n")
        sendCommand("$CMD_CONFIG fps=${cfg.framesPerSec}\n")
        sendCommand("$CMD_CONFIG maxrange=${cfg.maxRangeM}\n")
        sendCommand("$CMD_CONFIG cfar_thr=${cfg.cfar_threshold}\n")
    }
}

// ── Extension helpers ─────────────────────────────────────────────────────────

private fun Int.toHex(digits: Int) = toString(16).padStart(digits, '0').uppercase()
private fun Int.nextPow2(): Int {
    var v = this
    if (v <= 1) return 1
    v--; v = v or (v shr 1); v = v or (v shr 2); v = v or (v shr 4)
    v = v or (v shr 8); v = v or (v shr 16); return v + 1
}
