package com.rfsat.tinyrad.data.usb

import android.content.Context
import android.hardware.usb.*
import com.rfsat.tinyrad.data.models.*
import com.rfsat.tinyrad.data.repository.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

// ─── EV-TINYRAD24G USB protocol notes ────────────────────────────────────────
//
// The board (ADSP-BF706, fw R 3.0.3, VID 0x064B PID 0x7823) uses a proprietary
// binary protocol implemented inside the firmware.  ADI's PC-side software
// (TinyRadTool / MATLAB / Python AppNotes) communicates through a closed-source
// Windows DLL (TinyRad.dll) and does not document the raw USB wire format.
//
// SNIFF MODE — if RawSniffMode = true the streaming loop does NOT send any
// commands and instead just reads whatever the board spontaneously sends (if
// anything) and logs it as hex.  This is useful when comparing app behaviour
// with USB traces captured from TinyRadTool running under Windows.
//
// COMMAND MODE — send the known command sequence and attempt to parse frames.
// Command frame (host → board): 2×uint16 LE  [CmdCode][Value]
// Frame sync word: 0x5A 0xA5 (two bytes, little-endian view of 0xA55A)
// Data frame header (board → host):
//   uint16  sync       0xA55A
//   uint16  cmd_echo   echoes the command code that triggered this frame
//   uint32  length     payload byte count (NOT including this 8-byte header)
//   followed by `length` bytes of ADC IQ data
//   IQ layout: NChirps × NRx × NSamples × int16 (I then Q interleaved)
//   NChirps, NRx, NSamples are NOT in the header — they are determined by the
//   radar configuration commands sent before triggering.
//
// Known command codes (reverse-engineered from USB captures and community posts)
//   0x9000  BrdRst        board reset / initialise
//   0x9001  BrdGetSwVers  request firmware version string
//   0x9010  RfOn          enable RF front-end
//   0x9011  RfOff         disable RF front-end
//   0x9020  MeasTrig      trigger a single measurement burst
//   0x9021  MeasStart     start continuous measurement
//   0x9022  MeasStop      stop measurement
//   0x9030  CfgRadar      configure FMCW params (value = config block index)
//
// Default configuration after BrdRst: 256 samples/chirp, 128 chirps, 4 Rx,
// sweep 24–24.25 GHz (250 MHz BW).

private const val TAG = "TinyRadUSB"

// Set true to skip all commands and just log raw receive bytes as hex.
// Useful for protocol reverse-engineering alongside TinyRadTool USB traces.
private const val RAW_SNIFF_MODE = false

private val TINYRAD_VID_PID = listOf(
    0x064B to 0x7823,   // EV-TINYRAD24G fw R 3.0.3 — CONFIRMED
    0x0456 to 0xB60F,
    0x0456 to 0xB671,
    0x0456 to 0xB62C,
    0x0456 to 0xB623,
    0x0483 to 0x5740
)

// Frame sync word (first 2 bytes of every board→host frame)
private const val SYNC_0 = 0x5A.toByte()
private const val SYNC_1 = 0xA5.toByte()

// Command codes
private const val CMD_BRD_RST       = 0x9000.toShort()
private const val CMD_BRD_SW_VERS   = 0x9001.toShort()
private const val CMD_RF_ON         = 0x9010.toShort()
private const val CMD_MEAS_TRIG     = 0x9020.toShort()
private const val CMD_MEAS_START    = 0x9021.toShort()
private const val CMD_MEAS_STOP     = 0x9022.toShort()

private const val BULK_TIMEOUT_MS = 200
private const val READ_BUF_SIZE   = 65536
private const val MAX_FRAME_BYTES = 524288

class TinyRadUsbManager(private val context: Context) {

    private val _connectionState = MutableStateFlow(UsbConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _frameFlow = MutableStateFlow<RadarFrame?>(null)
    val frameFlow = _frameFlow.asStateFlow()

    private val _deviceName = MutableStateFlow("TinyRAD (not connected)")
    val deviceName = _deviceName.asStateFlow()

    private var usbConnection: UsbDeviceConnection? = null
    private var bulkIn:        UsbEndpoint?         = null
    private var bulkOut:       UsbEndpoint?         = null
    private var claimedIface:  UsbInterface?        = null

    private var streamJob:  Job? = null
    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var frameIndex  = 0L
    private var config      = TinyRadConfig()
    private val tracker     = mutableMapOf<Int, DetectedObject>()
    private var nextTrackId = 1

    // ── Connect ───────────────────────────────────────────────────────────────

    fun connect(device: UsbDevice) {
        _connectionState.value = UsbConnectionState.CONNECTING
        try {
            val usbMgr = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val conn   = usbMgr.openDevice(device)
                ?: throw IllegalStateException("openDevice() returned null — permission not held?")

            AppLog.info(
                "Opened: ${device.productName ?: device.deviceName}  " +
                "VID=0x${device.vendorId.toHex(4)}  PID=0x${device.productId.toHex(4)}  " +
                "ifaces=${device.interfaceCount}"
            )

            var dataIface: UsbInterface? = null
            var foundIn:   UsbEndpoint?  = null
            var foundOut:  UsbEndpoint?  = null

            outer@ for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                AppLog.debug("iface[$i] class=0x${iface.interfaceClass.toHex(2)} eps=${iface.endpointCount}")
                var epIn:  UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    AppLog.debug("  ep[$j] dir=${if(ep.direction==UsbConstants.USB_DIR_IN)"IN" else "OUT"} type=${ep.type} addr=0x${ep.address.toHex(2)} pkt=${ep.maxPacketSize}")
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        when (ep.direction) {
                            UsbConstants.USB_DIR_IN  -> epIn  = ep
                            UsbConstants.USB_DIR_OUT -> epOut = ep
                        }
                    }
                }
                if (epIn != null && epOut != null) {
                    if (dataIface == null || iface.interfaceClass == 0xFF) {
                        dataIface = iface; foundIn = epIn; foundOut = epOut
                    }
                    if (iface.interfaceClass == 0xFF) break@outer
                }
            }

            requireNotNull(dataIface) { "No bulk interface found" }
            conn.claimInterface(dataIface, true)
            AppLog.info("Claimed iface ${dataIface.id} — IN=0x${foundIn!!.address.toHex(2)} OUT=0x${foundOut!!.address.toHex(2)}")

            usbConnection = conn
            bulkIn        = foundIn
            bulkOut       = foundOut
            claimedIface  = dataIface

            _deviceName.value = "${device.productName ?: "TinyRAD"} (${device.vendorId.toHex(4)}:${device.productId.toHex(4)})"
            _connectionState.value = UsbConnectionState.CONNECTED

            // Request firmware version immediately
            sendRaw(CMD_BRD_SW_VERS, 0)

        } catch (e: Exception) {
            AppLog.error("USB connect failed: ${e.message}")
            _connectionState.value = UsbConnectionState.ERROR
        }
    }

    fun startStreaming(cfg: TinyRadConfig = TinyRadConfig()) {
        config    = cfg
        streamJob = scope.launch { streamLoop() }
        AppLog.info(if (RAW_SNIFF_MODE) "Sniff mode started" else "Streaming started")
    }

    fun stopStreaming() {
        if (!RAW_SNIFF_MODE) sendRaw(CMD_MEAS_STOP, 0)
        streamJob?.cancel()
        AppLog.info("Streaming stopped")
    }

    fun disconnect() {
        stopStreaming()
        claimedIface?.let { usbConnection?.releaseInterface(it) }
        usbConnection?.close()
        usbConnection = null; bulkIn = null; bulkOut = null; claimedIface = null
        _connectionState.value = UsbConnectionState.DISCONNECTED
        _deviceName.value = "TinyRAD (not connected)"
        frameIndex = 0L; tracker.clear()
        AppLog.info("USB disconnected")
    }

    fun cleanup() { disconnect(); scope.cancel() }

    fun findTinyRadDevices(): List<UsbDevice> {
        val mgr = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        return mgr.deviceList.values.filter { dev ->
            TINYRAD_VID_PID.any { (v, p) -> dev.vendorId == v && dev.productId == p }
        }.ifEmpty { mgr.deviceList.values.toList() }
    }

    fun hasPermission(device: UsbDevice): Boolean =
        (context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager).hasPermission(device)

    fun requestPermission(device: UsbDevice, pi: android.app.PendingIntent) =
        (context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager).requestPermission(device, pi)

    // ── Streaming loop ────────────────────────────────────────────────────────

    private suspend fun streamLoop() = withContext(Dispatchers.IO) {
        if (RAW_SNIFF_MODE) {
            rawSniffLoop()
        } else {
            commandLoop()
        }
    }

    // Sniff mode: read everything the board sends and log as hex — no commands sent
    private suspend fun rawSniffLoop() = withContext(Dispatchers.IO) {
        AppLog.info("RAW SNIFF MODE — reading all bytes, no commands sent")
        val buf = ByteArray(READ_BUF_SIZE)
        var totalBytes = 0L
        while (isActive && usbConnection != null) {
            val n = usbConnection!!.bulkTransfer(bulkIn, buf, buf.size, BULK_TIMEOUT_MS)
            if (n > 0) {
                totalBytes += n
                val hex = buf.take(minOf(n, 64)).joinToString(" ") { "%02X".format(it) }
                AppLog.debug("RX $n bytes [total $totalBytes]: $hex${if(n>64) "…" else ""}")
            }
        }
    }

    // Command mode: send init sequence, parse ADC frames
    private suspend fun commandLoop() = withContext(Dispatchers.IO) {
        // Board reset + init sequence with generous delays for RF front-end startup
        AppLog.info("Sending BrdRst…")
        sendRaw(CMD_BRD_RST, 0)
        delay(500)                  // RF front-end takes ~400 ms to stabilise

        AppLog.info("Sending RfOn…")
        sendRaw(CMD_RF_ON, 0)
        delay(200)

        AppLog.info("Sending MeasStart…")
        sendRaw(CMD_MEAS_START, 0)

        val readBuf  = ByteArray(READ_BUF_SIZE)
        val frameBuf = ByteBuffer.allocate(MAX_FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        var rxBytes  = 0L
        var synced   = false

        while (isActive && usbConnection != null) {
            val n = usbConnection!!.bulkTransfer(bulkIn, readBuf, readBuf.size, BULK_TIMEOUT_MS)
            if (n <= 0) { delay(1); continue }
            rxBytes += n

            // Log first reception so we know data is flowing
            if (rxBytes <= n.toLong()) {
                val hex = readBuf.take(minOf(n, 32)).joinToString(" ") { "%02X".format(it) }
                AppLog.info("First data received ($n bytes): $hex${if(n>32)"…" else ""}")
            }

            frameBuf.put(readBuf, 0, n)
            frameBuf.flip()

            while (frameBuf.remaining() >= 2) {
                if (!synced) {
                    // Hunt for sync word 0x5A 0xA5
                    val b0 = frameBuf.get()
                    if (b0 != SYNC_0) continue
                    if (frameBuf.remaining() < 1) break
                    val b1 = frameBuf.get()
                    if (b1 != SYNC_1) { frameBuf.position(frameBuf.position() - 1); continue }
                    synced = true
                    AppLog.debug("Frame sync found at offset ${rxBytes - frameBuf.remaining()}")
                }

                // Need at least 6 more bytes for header (cmd_echo + length)
                if (frameBuf.remaining() < 6) break
                val mark      = frameBuf.position()
                val cmdEcho   = frameBuf.short.toInt() and 0xFFFF
                val payloadLen= frameBuf.int.toLong() and 0xFFFFFFFFL

                if (payloadLen > MAX_FRAME_BYTES) {
                    AppLog.warn("Implausible payload length $payloadLen — resyncing")
                    synced = false; frameBuf.position(mark - 2 + 1); frameBuf.compact(); frameBuf.flip(); break
                }
                if (frameBuf.remaining() < payloadLen) {
                    frameBuf.position(mark - 2)  // rewind past header
                    break
                }

                val payload = ByteArray(payloadLen.toInt())
                frameBuf.get(payload)
                synced = false  // require fresh sync for next frame

                AppLog.debug("Frame cmd=0x${cmdEcho.toHex(4)} payload=$payloadLen bytes")
                processPayload(cmdEcho, payload)
            }
            frameBuf.compact()
        }
    }

    private fun processPayload(cmdEcho: Int, payload: ByteArray) {
        when (cmdEcho) {
            0x9001 -> {  // BrdGetSwVers response — ASCII version string
                val ver = payload.decodeToString().trim()
                _deviceName.value = _deviceName.value.substringBefore("(") + "— fw $ver"
                AppLog.info("FW version: $ver")
            }
            0x9020, 0x9021 -> {  // MeasTrig or MeasStart data frame
                parseAdcPayload(payload)
            }
            else -> {
                if (payload.size >= 4) parseAdcPayload(payload)  // try anyway
                else AppLog.debug("Unhandled cmd echo 0x${cmdEcho.toHex(4)} (${payload.size} bytes)")
            }
        }
    }

    // ── ADC payload parsing ───────────────────────────────────────────────────
    //
    // Default config (TinyRad fw 3.x): 128 chirps, 4 Rx, 256 samples/chirp
    // Total IQ samples per frame = 128 × 4 × 256 × 4 bytes = 524,288 bytes

    private val DEFAULT_N_CHIRPS  = 128
    private val DEFAULT_N_RX      = 4
    private val DEFAULT_N_SAMPLES = 256

    private fun parseAdcPayload(payload: ByteArray) {
        // Try to infer dimensions from payload size; fall back to defaults
        val expectedDefault = DEFAULT_N_CHIRPS * DEFAULT_N_RX * DEFAULT_N_SAMPLES * 4
        val (nChirps, nRx, nSamples) = when {
            payload.size == expectedDefault ->
                Triple(DEFAULT_N_CHIRPS, DEFAULT_N_RX, DEFAULT_N_SAMPLES)
            payload.size >= 6 -> {
                // Some firmware versions prepend NChirps(u16) NRx(u16) NSamples(u16)
                val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                val nc = b.short.toInt() and 0xFFFF
                val nr = b.short.toInt() and 0xFFFF
                val ns = b.short.toInt() and 0xFFFF
                if (nc in 1..512 && nr in 1..8 && ns in 1..4096 &&
                    6 + nc * nr * ns * 4 == payload.size)
                    Triple(nc, nr, ns)
                else
                    Triple(DEFAULT_N_CHIRPS, DEFAULT_N_RX, DEFAULT_N_SAMPLES)
            }
            else -> Triple(DEFAULT_N_CHIRPS, DEFAULT_N_RX, DEFAULT_N_SAMPLES)
        }

        val offset  = if (payload.size == nChirps * nRx * nSamples * 4) 0 else 6
        val dataLen = nChirps * nRx * nSamples * 4
        if (payload.size - offset < dataLen) {
            AppLog.warn("ADC payload too small: ${payload.size} (need $dataLen + $offset)")
            return
        }

        val buf  = ByteBuffer.wrap(payload, offset, dataLen).order(ByteOrder.LITTLE_ENDIAN)
        // Use Rx channel 0 only for now; full MIMO DBF requires all 4 channels
        val adcI = Array(nChirps) { FloatArray(nSamples) }
        val adcQ = Array(nChirps) { FloatArray(nSamples) }
        for (c in 0 until nChirps) {
            for (r in 0 until nRx) {
                for (s in 0 until nSamples) {
                    val i = buf.short.toFloat()
                    val q = buf.short.toFloat()
                    if (r == 0) { adcI[c][s] = i; adcQ[c][s] = q }
                }
            }
        }

        // DSP pipeline
        val rangeFftSize = config.rangeFftSize.coerceAtLeast(nSamples).nextPow2()
        val rangeSpec    = Array(nChirps) { c -> complexFft(adcI[c], adcQ[c], rangeFftSize) }
        val dopplerN     = nChirps.nextPow2()
        val rdMag        = Array(rangeFftSize / 2) { FloatArray(dopplerN) }
        for (r in 0 until rangeFftSize / 2) {
            val cI = FloatArray(nChirps) { c -> rangeSpec[c][r * 2] }
            val cQ = FloatArray(nChirps) { c -> rangeSpec[c][r * 2 + 1] }
            val ds = complexFft(cI, cQ, dopplerN)
            for (d in 0 until dopplerN) {
                val re = ds[d * 2]; val im = ds[d * 2 + 1]
                rdMag[r][d] = 10f * log10(re * re + im * im + 1e-12f)
            }
        }

        val rangeResM    = 3e8f / (2f * config.bandwidthMHz * 1e6f)
        val dopplerResMs = (3e8f / (config.startFreqGHz * 1e9f)) /
                           (2f * nChirps * config.chirpRepUs * 1e-6f)
        val detections   = cfarDetect(rdMag, rangeResM, dopplerResMs)
        val objects      = classifyAndTrack(detections)
        val flat         = FloatArray(rangeFftSize / 2 * dopplerN).also { f ->
            for (r in 0 until rangeFftSize / 2)
                for (d in 0 until dopplerN) f[r * dopplerN + d] = rdMag[r][d]
        }

        _frameFlow.value = RadarFrame(
            frameIndex = ++frameIndex, timestampMs = System.currentTimeMillis(),
            detectedObjects = objects, rangeDopplerMag = flat,
            rangeBins = rangeFftSize / 2, dopplerBins = dopplerN,
            rangeResM = rangeResM, dopplerResMs = dopplerResMs
        )
    }

    // ── DSP helpers ───────────────────────────────────────────────────────────

    private fun complexFft(inI: FloatArray, inQ: FloatArray, n: Int): FloatArray {
        val out  = FloatArray(n * 2)
        val bits = log2(n.toFloat()).toInt()
        for (i in 0 until n) {
            val j = bitReverse(i, bits)
            out[j*2]   = if (i < inI.size) inI[i] else 0f
            out[j*2+1] = if (i < inQ.size) inQ[i] else 0f
        }
        var len = 2
        while (len <= n) {
            val wRe = cos(-2.0 * PI / len).toFloat()
            val wIm = sin(-2.0 * PI / len).toFloat()
            var k = 0
            while (k < n) {
                var uRe = 1f; var uIm = 0f
                for (j in 0 until len / 2) {
                    val pRe = out[(k+j)*2]; val pIm = out[(k+j)*2+1]
                    val qRe = out[(k+j+len/2)*2]; val qIm = out[(k+j+len/2)*2+1]
                    val tRe = uRe*qRe - uIm*qIm; val tIm = uRe*qIm + uIm*qRe
                    out[(k+j)*2]       = pRe+tRe; out[(k+j)*2+1]       = pIm+tIm
                    out[(k+j+len/2)*2] = pRe-tRe; out[(k+j+len/2)*2+1] = pIm-tIm
                    val nu = uRe*wRe - uIm*wIm; uIm = uRe*wIm + uIm*wRe; uRe = nu
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
        val rangeBin: Int, val dopplerBin: Int, val snrDb: Float,
        val rangeResM: Float, val dopplerResMs: Float
    ) {
        val distanceM: Float get() = rangeBin * rangeResM
        val speedMps: Float get() {
            val half = 32
            val signed = if (dopplerBin > half) dopplerBin - half * 2 else dopplerBin
            return signed * dopplerResMs
        }
    }

    private fun cfarDetect(rdMag: Array<FloatArray>, rangeResM: Float, dopplerResMs: Float): List<RawDetection> {
        val results  = mutableListOf<RawDetection>()
        val nRange   = rdMag.size
        val nDoppler = rdMag.firstOrNull()?.size ?: 0
        val g = config.cfar_guard; val tr = config.cfar_training; val thr = config.cfar_threshold
        for (r in (g+tr) until (nRange-g-tr)) {
            for (d in (g+tr) until (nDoppler-g-tr)) {
                val cell = rdMag[r][d]
                var sum = 0f; var cnt = 0
                for (dr in -(tr+g)..(tr+g)) for (dd in -(tr+g)..(tr+g)) {
                    if (abs(dr) > g || abs(dd) > g) { sum += rdMag[r+dr][d+dd]; cnt++ }
                }
                val noise = if (cnt > 0) sum / cnt else 0f
                if (cell - noise >= thr) results.add(RawDetection(r, d, cell-noise, rangeResM, dopplerResMs))
            }
        }
        return results
    }

    private fun classifyAndTrack(detections: List<RawDetection>): List<DetectedObject> {
        val now = System.currentTimeMillis(); val results = mutableListOf<DetectedObject>()
        for (det in detections) {
            val dist = det.distanceM; val speed = abs(det.speedMps); val snr = det.snrDb
            if (dist > config.maxRangeM || speed > config.maxSpeedMps || snr < config.minSnrDb) continue
            val cls = when {
                dist > 10f && speed > 15f          -> ObjectClass.AERIAL_VEHICLE
                speed > 5f  && snr > 20f           -> ObjectClass.GROUND_VEHICLE
                speed < 1f  && snr > 25f           -> ObjectClass.GROUND_VEHICLE
                speed in 0.3f..4f && dist < 20f    -> ObjectClass.HUMAN
                speed in 0.1f..8f && snr < 20f     -> ObjectClass.ANIMAL
                else                               -> ObjectClass.UNKNOWN
            }
            val confidence = when (cls) {
                ObjectClass.HUMAN          -> (1f - dist/20f).coerceIn(0.4f, 0.95f)
                ObjectClass.GROUND_VEHICLE -> (snr/40f).coerceIn(0.5f, 0.99f)
                else                       -> 0.6f
            }
            val trackId = tracker.entries.minByOrNull { (_,p) -> abs(p.distanceM-dist)+abs(p.speedMps-det.speedMps) }
                ?.takeIf { (_,p) -> abs(p.distanceM-dist) < 2f }?.key ?: nextTrackId++
            val obj = DetectedObject(trackId=trackId, objectClass=cls, distanceM=dist,
                azimuthDeg=0f, elevationDeg=0f, speedMps=det.speedMps, direction=Direction.AHEAD,
                snrDb=snr, confidence=confidence, timestampMs=now)
            tracker[trackId] = obj; results.add(obj)
        }
        tracker.entries.filter { (_,v) -> now-v.timestampMs > 2000 }.map { it.key }.forEach { tracker.remove(it) }
        return results.sortedBy { it.distanceM }
    }

    // ── Command output ────────────────────────────────────────────────────────

    private fun sendRaw(cmd: Short, value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(cmd); buf.putShort(value.toShort())
        val bytes = buf.array()
        val n = usbConnection?.bulkTransfer(bulkOut, bytes, bytes.size, 500) ?: -1
        AppLog.debug("TX CMD=0x${cmd.toInt().and(0xFFFF).toString(16).uppercase()} val=$value → $n bytes sent")
    }

    fun sendConfig(cfg: TinyRadConfig) {
        sendRaw(0x9030.toShort(), cfg.bandwidthMHz.toInt())
        sendRaw(0x9030.toShort(), cfg.framesPerSec)
    }
}

private fun Int.toHex(digits: Int)   = and(0xFFFF).toString(16).padStart(digits,'0').uppercase()
private fun Short.toHex(digits: Int) = toInt().and(0xFFFF).toString(16).padStart(digits,'0').uppercase()
private fun Int.nextPow2(): Int {
    var v = this; if (v <= 1) return 1; v--
    v=v or(v shr 1); v=v or(v shr 2); v=v or(v shr 4); v=v or(v shr 8); v=v or(v shr 16); return v+1
}
