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

// ─── TinyRAD / ADSP-BF706 "BF707 Bulk Device" USB constants ─────────────────
//
// The EV-TINYRAD24G (UG-1709) uses an ADSP-BF706 Blackfin DSP.
// On the USB bus it appears as "BF707 Bulk Device" — this is a
// vendor-specific bulk-transfer device, NOT a CDC-ACM serial device.
//
// Interface class: 0xFF (vendor-specific)
// Transfer type:   BULK (not interrupt, not isochronous)
// The board has ONE configuration with ONE vendor interface containing
// one BULK-IN and one BULK-OUT endpoint.
//
// The firmware communicates via a proprietary ADI framing protocol:
//   Host → Board : 4-byte command frames
//   Board → Host : data frames (ADC IQ data or processed results)
//
// Command format (host → board), all uint16 little-endian:
//   [CmdCode : uint16][Value : uint16]
//
// Known command codes (from TinyRadTool MATLAB/Python library reverse):
//   0x0001  CMD_INIT      initialise radar front-end
//   0x0002  CMD_TRIGGER   trigger a measurement
//   0x0003  CMD_SETPARAM  set parameter: [CmdCode][ParamId][Value]
//   0x0004  CMD_GETSYSINF request system info (board replies with ASCII)
//   0x0100  CMD_CLKRST    clock/reset
//
// Data frame (board → host):
//   [FrameLen : uint32][NChirps : uint16][NSamples : uint16][NRx : uint16]
//   followed by NChirps × NRx × NSamples × int16 IQ samples
//   (I and Q interleaved per sample: I0 Q0 I1 Q1 …)

private const val TAG = "TinyRadUSB"

// All known Analog Devices TinyRAD / BF70x Bulk Device VID/PID pairs.
// VID 0x0456 = Analog Devices, Inc.
// PIDs confirmed from Demo_Driver INF files and community reports.
// NOTE: if none match your board, use "Browse all USB devices" to connect
// manually, then check the Log screen for the board's actual VID/PID.
private val TINYRAD_VID_PID = listOf(
    0x0456 to 0xB60F,   // ADSP-BF70x Bulk Device — primary (most common)
    0x0456 to 0xB671,   // EV-TINYRAD24G alternate firmware
    0x0456 to 0xB62C,   // DemoRad / older TinyRad firmware
    0x0456 to 0xB623,   // DemoRad alternate
    0x0483 to 0x5740    // STM32 CDC-ACM (fallback / custom firmware)
)

// Bulk transfer timeout — generous to handle slow DSP boot
private const val BULK_TIMEOUT_MS   = 200
private const val READ_BUF_SIZE     = 65536     // one ADC frame ≤ 64 KB
private const val MAX_FRAME_BYTES   = 524288    // hard safety cap 512 KB

// ADI command codes (host → board, little-endian uint16 pairs)
private const val CMD_INIT          = 0x0001.toShort()
private const val CMD_TRIGGER       = 0x0002.toShort()
private const val CMD_GETSYSINF     = 0x0004.toShort()

class TinyRadUsbManager(private val context: Context) {

    // ── Public state ──────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow(UsbConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _frameFlow = MutableStateFlow<RadarFrame?>(null)
    val frameFlow = _frameFlow.asStateFlow()

    private val _deviceName = MutableStateFlow("TinyRAD (not connected)")
    val deviceName = _deviceName.asStateFlow()

    // ── Internal USB handles ──────────────────────────────────────────────────

    private var usbConnection:  UsbDeviceConnection? = null
    private var bulkIn:         UsbEndpoint?          = null
    private var bulkOut:        UsbEndpoint?          = null
    private var claimedIface:   UsbInterface?         = null

    // ── DSP / streaming ───────────────────────────────────────────────────────

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
                "Opened device: ${device.productName ?: device.deviceName}  " +
                "VID=0x${device.vendorId.toHex(4)}  PID=0x${device.productId.toHex(4)}  " +
                "interfaces=${device.interfaceCount}"
            )

            // Log all interfaces for diagnostics
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                AppLog.debug(
                    "  iface[$i]: class=0x${iface.interfaceClass.toHex(2)} " +
                    "sub=0x${iface.interfaceSubclass.toHex(2)} " +
                    "proto=0x${iface.interfaceProtocol.toHex(2)} " +
                    "eps=${iface.endpointCount}"
                )
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    AppLog.debug(
                        "    ep[$j]: addr=0x${ep.address.toHex(2)} " +
                        "dir=${if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"} " +
                        "type=${ep.type} maxPkt=${ep.maxPacketSize}"
                    )
                }
            }

            // Find the bulk interface.
            // BF70x Bulk Device: class 0xFF (vendor-specific), one interface,
            // two bulk endpoints (IN + OUT).
            // Fallback: accept any interface that has both a bulk-IN and bulk-OUT.
            var dataIface: UsbInterface? = null
            var foundIn:   UsbEndpoint?  = null
            var foundOut:  UsbEndpoint?  = null

            outer@ for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var epIn:  UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        when (ep.direction) {
                            UsbConstants.USB_DIR_IN  -> epIn  = ep
                            UsbConstants.USB_DIR_OUT -> epOut = ep
                        }
                    }
                }
                if (epIn != null && epOut != null) {
                    // Prefer vendor class, but accept anything with bulk IN+OUT
                    if (dataIface == null || iface.interfaceClass == 0xFF) {
                        dataIface = iface
                        foundIn   = epIn
                        foundOut  = epOut
                    }
                    if (iface.interfaceClass == 0xFF) break@outer   // perfect match
                }
            }

            requireNotNull(dataIface) {
                "No bulk interface found on ${device.productName}. " +
                "Check the Log screen for interface details."
            }

            conn.claimInterface(dataIface, true)
            AppLog.info(
                "Claimed interface ${dataIface.id} " +
                "(class=0x${dataIface.interfaceClass.toHex(2)}) — " +
                "bulkIN ep=0x${foundIn!!.address.toHex(2)} " +
                "bulkOUT ep=0x${foundOut!!.address.toHex(2)}"
            )

            usbConnection = conn
            bulkIn        = foundIn
            bulkOut       = foundOut
            claimedIface  = dataIface

            _deviceName.value = "${device.productName ?: "TinyRAD"}  " +
                "(VID:${device.vendorId.toHex(4)} PID:${device.productId.toHex(4)})"
            _connectionState.value = UsbConnectionState.CONNECTED

            // Request device info (board replies with ASCII string)
            sendCommand(CMD_GETSYSINF, 0)

        } catch (e: Exception) {
            AppLog.error("USB connect failed: ${e.message}")
            _connectionState.value = UsbConnectionState.ERROR
        }
    }

    fun startStreaming(cfg: TinyRadConfig = TinyRadConfig()) {
        config    = cfg
        streamJob = scope.launch { streamLoop() }
        AppLog.info("Streaming started")
    }

    fun stopStreaming() {
        streamJob?.cancel()
        AppLog.info("Streaming stopped")
    }

    fun disconnect() {
        stopStreaming()
        claimedIface?.let { usbConnection?.releaseInterface(it) }
        usbConnection?.close()
        usbConnection  = null
        bulkIn         = null
        bulkOut        = null
        claimedIface   = null
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

    // ── Device discovery ──────────────────────────────────────────────────────

    fun findTinyRadDevices(): List<UsbDevice> {
        val mgr = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val matched = mgr.deviceList.values.filter { dev ->
            TINYRAD_VID_PID.any { (vid, pid) ->
                dev.vendorId == vid && dev.productId == pid
            }
        }
        return matched.ifEmpty { mgr.deviceList.values.toList() }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        val mgr = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        return mgr.hasPermission(device)
    }

    fun requestPermission(device: UsbDevice, pi: android.app.PendingIntent) {
        val mgr = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        mgr.requestPermission(device, pi)
    }

    // ── Streaming loop ────────────────────────────────────────────────────────
    //
    // The BF706 firmware sends ADC frames continuously after CMD_TRIGGER.
    // Each frame starts with a 12-byte header:
    //   uint32 frame_len   total bytes in this frame (including header)
    //   uint16 n_chirps    number of chirps
    //   uint16 n_samples   samples per chirp per channel
    //   uint16 n_rx        number of active receive channels (max 4)
    //   uint16 reserved
    // Followed by n_chirps × n_rx × n_samples × int16 IQ pairs.

    private suspend fun streamLoop() = withContext(Dispatchers.IO) {
        sendCommand(CMD_INIT,    0)
        delay(100)
        sendCommand(CMD_TRIGGER, 0)

        val readBuf  = ByteArray(READ_BUF_SIZE)
        val frameBuf = ByteBuffer.allocate(MAX_FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)

        while (isActive && usbConnection != null) {
            val n = usbConnection!!.bulkTransfer(
                bulkIn, readBuf, readBuf.size, BULK_TIMEOUT_MS
            )
            if (n <= 0) { delay(1); continue }

            frameBuf.put(readBuf, 0, n)
            frameBuf.flip()

            // Consume complete frames from the buffer
            while (frameBuf.remaining() >= 12) {
                val mark     = frameBuf.position()
                val frameLen = frameBuf.int.toLong() and 0xFFFFFFFFL

                if (frameLen < 12 || frameLen > MAX_FRAME_BYTES) {
                    // Bad length — discard one byte and re-sync
                    frameBuf.position(mark + 1)
                    frameBuf.compact(); frameBuf.flip()
                    break
                }

                if (frameBuf.remaining() < frameLen - 4) {
                    // Not enough data yet — put length back and wait
                    frameBuf.position(mark)
                    break
                }

                val nChirps  = frameBuf.short.toInt() and 0xFFFF
                val nSamples = frameBuf.short.toInt() and 0xFFFF
                val nRx      = frameBuf.short.toInt() and 0xFFFF
                frameBuf.short // reserved

                val dataLen  = (frameLen - 12).toInt()
                val data     = ByteArray(dataLen)
                frameBuf.get(data)

                processAdcData(nChirps, nSamples, nRx, data)
            }
            frameBuf.compact()
        }
    }

    // ── ADC → DSP pipeline ────────────────────────────────────────────────────

    private fun processAdcData(nChirps: Int, nSamples: Int, nRx: Int, data: ByteArray) {
        val expected = nChirps * nRx * nSamples * 4  // int16 I + int16 Q
        if (data.size < expected || nChirps == 0 || nSamples == 0 || nRx == 0) {
            AppLog.debug("Frame size mismatch: got ${data.size}, expected $expected")
            return
        }

        val buf  = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Build per-RX-channel per-chirp IQ arrays
        // Layout: chirp0_rx0_s0 chirp0_rx0_s1 … chirp0_rx1_s0 … chirp1_rx0_s0 …
        val adcI = Array(nChirps) { Array(nRx) { FloatArray(nSamples) } }
        val adcQ = Array(nChirps) { Array(nRx) { FloatArray(nSamples) } }
        for (c in 0 until nChirps) {
            for (r in 0 until nRx) {
                for (s in 0 until nSamples) {
                    adcI[c][r][s] = buf.short.toFloat()
                    adcQ[c][r][s] = buf.short.toFloat()
                }
            }
        }

        // Use RX channel 0 for range-Doppler; MIMO DBF would combine all channels
        val rangeFftSize = config.rangeFftSize.coerceAtLeast(nSamples).nextPow2()
        val rangeSpec    = Array(nChirps) { c ->
            complexFft(adcI[c][0], adcQ[c][0], rangeFftSize)
        }

        // Doppler FFT (slow-time)
        val dopplerN = nChirps.nextPow2()
        val rdMag    = Array(rangeFftSize / 2) { FloatArray(dopplerN) }
        for (r in 0 until rangeFftSize / 2) {
            val cI = FloatArray(nChirps) { c -> rangeSpec[c][r * 2] }
            val cQ = FloatArray(nChirps) { c -> rangeSpec[c][r * 2 + 1] }
            val ds = complexFft(cI, cQ, dopplerN)
            for (d in 0 until dopplerN) {
                val re = ds[d * 2]; val im = ds[d * 2 + 1]
                rdMag[r][d] = 10f * log10(re * re + im * im + 1e-12f)
            }
        }

        // Physics
        val rangeResM    = 3e8f / (2f * config.bandwidthMHz * 1e6f)
        val dopplerResMs = (3e8f / (config.startFreqGHz * 1e9f)) /
                           (2f * nChirps * config.chirpRepUs * 1e-6f)

        val detections = cfarDetect(rdMag, rangeResM, dopplerResMs)
        val objects    = classifyAndTrack(detections)

        val flat = FloatArray(rangeFftSize / 2 * dopplerN)
        for (r in 0 until rangeFftSize / 2)
            for (d in 0 until dopplerN)
                flat[r * dopplerN + d] = rdMag[r][d]

        _frameFlow.value = RadarFrame(
            frameIndex      = ++frameIndex,
            timestampMs     = System.currentTimeMillis(),
            detectedObjects = objects,
            rangeDopplerMag = flat,
            rangeBins       = rangeFftSize / 2,
            dopplerBins     = dopplerN,
            rangeResM       = rangeResM,
            dopplerResMs    = dopplerResMs
        )
    }

    // ── DSP helpers ───────────────────────────────────────────────────────────

    private fun complexFft(inI: FloatArray, inQ: FloatArray, n: Int): FloatArray {
        val out  = FloatArray(n * 2)
        val bits = log2(n.toFloat()).toInt()
        for (i in 0 until n) {
            val j    = bitReverse(i, bits)
            out[j * 2]     = if (i < inI.size) inI[i] else 0f
            out[j * 2 + 1] = if (i < inQ.size) inQ[i] else 0f
        }
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
                    val nu = uRe * wRe - uIm * wIm
                    uIm    = uRe * wIm + uIm * wRe
                    uRe    = nu
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
        val speedMps:  Float get() {
            val half   = 32
            val signed = if (dopplerBin > half) dopplerBin - half * 2 else dopplerBin
            return signed * dopplerResMs
        }
    }

    private fun cfarDetect(
        rdMag: Array<FloatArray>, rangeResM: Float, dopplerResMs: Float
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
                var sum  = 0f; var cnt = 0
                for (tr in -(train + guard)..(train + guard)) {
                    for (td in -(train + guard)..(train + guard)) {
                        if (abs(tr) > guard || abs(td) > guard) {
                            sum += rdMag[r + tr][d + td]; cnt++
                        }
                    }
                }
                val noise = if (cnt > 0) sum / cnt else 0f
                if (cell - noise >= thresh)
                    results.add(RawDetection(r, d, cell - noise, rangeResM, dopplerResMs))
            }
        }
        return results
    }

    private fun classifyAndTrack(detections: List<RawDetection>): List<DetectedObject> {
        val now     = System.currentTimeMillis()
        val results = mutableListOf<DetectedObject>()

        for (det in detections) {
            val dist  = det.distanceM
            val speed = abs(det.speedMps)
            val snr   = det.snrDb
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
                ObjectClass.HUMAN          -> (1f - dist / 20f).coerceIn(0.4f, 0.95f)
                ObjectClass.GROUND_VEHICLE -> (snr / 40f).coerceIn(0.5f, 0.99f)
                ObjectClass.AERIAL_VEHICLE -> 0.7f
                ObjectClass.ANIMAL         -> 0.6f
                ObjectClass.UNKNOWN        -> 0.3f
            }

            val dir     = Direction.AHEAD
            val trackId = tracker.entries.minByOrNull { (_, p) ->
                abs(p.distanceM - dist) + abs(p.speedMps - det.speedMps)
            }?.takeIf { (_, p) -> abs(p.distanceM - dist) < 2f }?.key ?: nextTrackId++

            val obj = DetectedObject(
                trackId = trackId, objectClass = cls,
                distanceM = dist, azimuthDeg = 0f, elevationDeg = 0f,
                speedMps = det.speedMps, direction = dir,
                snrDb = snr, confidence = confidence, timestampMs = now
            )
            tracker[trackId] = obj
            results.add(obj)
        }

        val stale = tracker.entries.filter { (_, v) -> now - v.timestampMs > 2000 }.map { it.key }
        stale.forEach { tracker.remove(it) }
        return results.sortedBy { it.distanceM }
    }

    // ── Command output ────────────────────────────────────────────────────────
    //
    // BF706 command frame: [CmdCode:uint16 LE][Value:uint16 LE]

    private fun sendCommand(cmd: Short, value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(cmd)
        buf.putShort(value.toShort())
        val bytes = buf.array()
        val n = usbConnection?.bulkTransfer(bulkOut, bytes, bytes.size, 500) ?: -1
        AppLog.debug("CMD 0x${cmd.toInt().and(0xFFFF).toHex(4)} val=$value → sent $n bytes")
    }

    fun sendConfig(cfg: TinyRadConfig) {
        // Parameter IDs are ADI-internal; send as best-effort
        // 0x0010 = bandwidth, 0x0011 = frame rate
        sendCommand(0x0003.toShort(), cfg.bandwidthMHz.toInt())
        sendCommand(0x0003.toShort(), cfg.framesPerSec)
    }
}

// ── Extension helpers ─────────────────────────────────────────────────────────

private fun Int.toHex(digits: Int)   = and(0xFFFF).toString(16).padStart(digits, '0').uppercase()
private fun Short.toHex(digits: Int) = toInt().and(0xFFFF).toString(16).padStart(digits, '0').uppercase()
private fun Int.nextPow2(): Int {
    var v = this; if (v <= 1) return 1; v--
    v = v or (v shr 1); v = v or (v shr 2); v = v or (v shr 4)
    v = v or (v shr 8); v = v or (v shr 16); return v + 1
}
