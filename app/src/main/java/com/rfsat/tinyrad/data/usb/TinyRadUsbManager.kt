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

// ─── EV-TINYRAD24G USB protocol ──────────────────────────────────────────────
//
// Reverse-engineered from USB capture (USB-TinyRAD.pcapng, Jun 2026).
// VID 0x064B / PID 0x7823, firmware R 3.0.3.
//
// ── OUT frame (host → board, always 2048 bytes padded with zeros) ────────────
//   uint16  payload_len   bytes after this field that carry real data
//   uint16  cmd_code      command identifier (0x9xxx)
//   uint16  num_params    number of uint32 parameters that follow
//   uint16  reserved      typically 0x0001 for config cmds, 0x0007 for RegWrite
//   uint32  params[N]     command parameters
//   <zero padding to 2048 bytes>
//
// ── IN frame (board → host) ─────────────────────────────────────────────────
//   Type A — ACK (8 bytes):
//     uint16  cmd_echo   mirrors the OUT cmd_code
//     uint16  status     0x0002 = OK, 0x0003/0x0004 for init responses
//     uint32  result     1 = success, or a returned value
//
//   Type B — ADC data (1024 bytes per chirp):
//     uint16  chirp_idx  x4 (four identical copies: Rx0..Rx3 chirp counter)
//     int16   iq[508]    interleaved IQ for all 4 Rx channels:
//                        Rx0_I, Rx0_Q, Rx1_I, Rx1_Q, Rx2_I, Rx2_Q, Rx3_I, Rx3_Q
//                        × (1024-8)/8 = 127 samples per channel
//
// ── Command sequence (from trace) ────────────────────────────────────────────
//   1. CMD_INIT    (0x9030) — board init, params=[0, 0x02000000, 0]
//   2. CMD_RFSET   (0x900E) — RF board config, params=[0x00010000, 0x02000000]
//   3. CMD_CFG     (0x9031) × N — FMCW configuration (multiple parameter blocks)
//   4. CMD_REGW    (0x9017) × N — hardware register writes
//   5. CMD_TRIG    (0x9032) — trigger ONE chirp; repeat per-chirp
//      Response per chirp: 1024-byte ADC frame then 8-byte ACK
//   6. CMD_CFG     (0x9031) with zero params — stop
//
// ── Measurement geometry ─────────────────────────────────────────────────────
//   CHIRPS_PER_FRAME = 80  (chirp_idx increments by 2: 0,2,4...158)
//   RX_CHANNELS      = 4
//   SAMPLES_PER_CHIRP = 127  (integer, from 508 IQ pairs / 4 Rx)
//   USB packet per chirp: 1024 bytes = 8 header + 508×int16 IQ = 8+1016=1024 ✓

private const val TAG = "TinyRadUSB"

private val TINYRAD_VID_PID = listOf(
    0x064B to 0x7823,   // EV-TINYRAD24G fw R 3.0.3 — confirmed
    0x0456 to 0xB60F,
    0x0456 to 0xB671,
    0x0456 to 0xB62C,
    0x0456 to 0xB623,
    0x0483 to 0x5740
)

// Frame geometry — confirmed from pcap
private const val CHIRPS_PER_FRAME  = 80
private const val RX_CHANNELS       = 4
private const val SAMPLES_PER_CHIRP = 127   // complex samples per Rx per chirp
private const val USB_OUT_SIZE      = 2048  // all OUT transfers padded to this
private const val ADC_FRAME_SIZE    = 1024  // exactly 1024 bytes per chirp IN
private const val ADC_HEADER_BYTES  = 8     // 4× uint16 chirp_idx
private const val IQ_PER_FRAME      = (ADC_FRAME_SIZE - ADC_HEADER_BYTES) / 2  // 508 int16

private const val BULK_TIMEOUT_MS   = 500

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
                ?: throw IllegalStateException("openDevice() null — permission not held?")

            AppLog.info("Opened: ${device.productName ?: device.deviceName} " +
                    "VID=0x${device.vendorId.toHex(4)} PID=0x${device.productId.toHex(4)} " +
                    "ifaces=${device.interfaceCount}")

            var dataIface: UsbInterface? = null
            var foundIn:   UsbEndpoint?  = null
            var foundOut:  UsbEndpoint?  = null

            outer@ for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                AppLog.debug("iface[$i] class=0x${iface.interfaceClass.toHex(2)} eps=${iface.endpointCount}")
                var epIn: UsbEndpoint? = null; var epOut: UsbEndpoint? = null
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK)
                        when (ep.direction) {
                            UsbConstants.USB_DIR_IN  -> epIn  = ep
                            UsbConstants.USB_DIR_OUT -> epOut = ep
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
            AppLog.info("Claimed iface ${dataIface.id} — " +
                    "IN=0x${foundIn!!.address.toHex(2)} OUT=0x${foundOut!!.address.toHex(2)}")

            usbConnection = conn; bulkIn = foundIn; bulkOut = foundOut; claimedIface = dataIface
            _deviceName.value = "${device.productName ?: "TinyRAD"} " +
                    "(${device.vendorId.toHex(4)}:${device.productId.toHex(4)})"
            _connectionState.value = UsbConnectionState.CONNECTED
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
        // Send stop command (CfgFmcw with zero params = stop, from trace frame 805)
        sendCmd(0x9031, shortArrayOf(0x0001), intArrayOf(0, 0x02000000, 0))
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
        (context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager)
            .hasPermission(device)

    fun requestPermission(device: UsbDevice, pi: android.app.PendingIntent) =
        (context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager)
            .requestPermission(device, pi)

    // ── Streaming loop ────────────────────────────────────────────────────────

    private suspend fun streamLoop() = withContext(Dispatchers.IO) {
        if (!initBoard()) return@withContext

        // Accumulate chirps into complete FMCW frames
        // ADC buffer: [CHIRPS_PER_FRAME][RX_CHANNELS][SAMPLES_PER_CHIRP] complex (I,Q)
        val adcI = Array(CHIRPS_PER_FRAME) { Array(RX_CHANNELS) { FloatArray(SAMPLES_PER_CHIRP) } }
        val adcQ = Array(CHIRPS_PER_FRAME) { Array(RX_CHANNELS) { FloatArray(SAMPLES_PER_CHIRP) } }
        var chirpCount = 0
        val inBuf = ByteArray(ADC_FRAME_SIZE)

        while (isActive && usbConnection != null) {
            // Send trigger for one chirp
            val sent = sendCmd(0x9032, shortArrayOf(0x0001),
                intArrayOf(0x00010000, 0x02000000, 0, 0x00060000, 0x14990002))
            if (sent < 0) { delay(10); continue }

            // Read ADC data frame (1024 bytes)
            val nData = bulkRead(inBuf, ADC_FRAME_SIZE)
            if (nData != ADC_FRAME_SIZE) {
                AppLog.warn("Expected $ADC_FRAME_SIZE bytes, got $nData")
                if (nData > 0) readAck()  // consume ack anyway
                continue
            }

            // Read ACK (8 bytes)
            readAck()

            // Parse chirp data
            val buf = ByteBuffer.wrap(inBuf).order(ByteOrder.LITTLE_ENDIAN)
            val chirpIdx = buf.short.toInt() and 0xFFFF
            buf.short; buf.short; buf.short   // skip 3 duplicate chirp_idx values

            // IQ layout per sample: Rx0_I Rx0_Q Rx1_I Rx1_Q Rx2_I Rx2_Q Rx3_I Rx3_Q
            val chirpRow = chirpIdx / 2   // step-by-2 index → row 0..79
            if (chirpRow < CHIRPS_PER_FRAME) {
                for (s in 0 until SAMPLES_PER_CHIRP) {
                    for (r in 0 until RX_CHANNELS) {
                        val i = buf.short.toFloat()
                        val q = buf.short.toFloat()
                        adcI[chirpRow][r][s] = i
                        adcQ[chirpRow][r][s] = q
                    }
                }
                chirpCount++
            }

            // When we have a full FMCW frame, process it
            if (chirpCount >= CHIRPS_PER_FRAME) {
                processFrame(adcI, adcQ)
                chirpCount = 0
            }
        }
    }

    // ── Board init sequence — exact replay from USB trace ──────────────────────

    private suspend fun initBoard(): Boolean = withContext(Dispatchers.IO) {
        AppLog.info("Board init sequence starting…")

        // 1. BrdInit (0x9030): params=[0, 0x02000000, 0]
        if (sendCmd(0x9030, shortArrayOf(0x0001),
                intArrayOf(0, 0x02000000, 0)) < 0) {
            AppLog.error("BrdInit send failed"); return@withContext false
        }
        if (!readAckExpect(0x9030)) { AppLog.error("BrdInit ack failed"); return@withContext false }
        AppLog.info("BrdInit OK")

        // 2. RfBrdSet (0x900E): params=[0x00010000, 0x02000000]
        sendCmd(0x900E, shortArrayOf(0x0000), intArrayOf(0x00010000, 0x02000000))
        readAckExpect(0x900E)
        AppLog.info("RfBrdSet OK")

        // 3. CfgFmcw (0x9031) #1: params=[0, 0x02000000, 0]
        sendCmd(0x9031, shortArrayOf(0x0001), intArrayOf(0, 0x02000000, 0))
        readAckExpect(0x9031)

        // 4. RegWrite (0x9017) — hardware register init blocks from trace
        regWrite(intArrayOf(0x00010000, 0, 0x00030000, 0x00060000,
            0x14990002, 0x14992000, 0x14994000, 0x14996000,
            0x00198000.toInt(), 0x7CA0A000.toInt(), 0x00008000, 0))
        readAckExpect(0x9017)

        regWrite(intArrayOf(0x00010000, 0x00010000, 0x00070000,
            0xFFEA0300.toInt(), 0xB9291FFF.toInt(), 0x3E882A20,
            0xE5204000.toInt(), 0x4827809F, 0x00060011.toInt() or 0x10000,
            0x80050000.toInt(), 0x000401E2, 0x08030020, 0x06420189,
            0xEA010002.toInt(), 0xE700FFF5.toInt(), 0x0000809F, 0))
        readAckExpect(0x9017)

        regWrite(intArrayOf(0x00010000, 0x00010000,
            0xE5600000.toInt(), 0xED60809F.toInt(), 0x0000809F, 0))
        readAckExpect(0x9017)

        regWrite(intArrayOf(0x00010000, 0x00010000,
            0xE5A00000.toInt(), 0xF5A0809F.toInt(), 0xB929809F.toInt(), 0x3E882A20))
        readAckExpect(0x9017)

        regWrite(intArrayOf(0x00010000, 0x00010000,
            0xB9290000.toInt(), 0x25602800, 0x040E809F, 0x80050080.toInt()))
        readAckExpect(0x9017)

        // 5. CfgFmcw #2: timing + geometry params
        sendCmd(0x9031, shortArrayOf(0x0001),
            intArrayOf(0x000C0000, 0x00030000, 0x00050000,
                0x004C0000, 0x00640000, 0, 0))
        readAckExpect(0x9031)

        // 6. More RegWrite
        regWrite(intArrayOf(0x00010000, 0x00020000, 0x00070000,
            0x04060010, 0x04160000, 0x80050080.toInt(), 0x0FC50030,
            0x010400C8, 0x01440098, 0x08430098, 0x81920002.toInt(),
            0x00010440, 0x19980333, 0xEA01803C.toInt(), 0xE700FFF5.toInt()))
        readAckExpect(0x9017)

        // 7. CfgFmcw #3
        sendCmd(0x9031, shortArrayOf(0x0001),
            intArrayOf(0x00020000, 0x00010000, 0x00020000,
                0x00010000, 0x00040000, 0, 0))
        readAckExpect(0x9031)

        // 8. CfgFmcw #4
        sendCmd(0x9031, shortArrayOf(0x0001),
            intArrayOf(0x00030000, 0x00010000, 0x00030000, 0x00060000))
        readAckExpect(0x9031)

        // 9. CfgFmcw #5 — sweep parameters
        sendCmd(0x9031, shortArrayOf(0x0001),
            intArrayOf(0x00010000, 0x09000000, 0x003D0080, 0x04060000, 0x04160000))
        readAckExpect(0x9031)

        AppLog.info("Board init complete — starting chirp acquisition")
        true
    }

    // ── Per-frame DSP pipeline ────────────────────────────────────────────────

    private fun processFrame(
        adcI: Array<Array<FloatArray>>,
        adcQ: Array<Array<FloatArray>>
    ) {
        // Use Rx channel 0 for range-Doppler; all 4 channels available for MIMO DBF
        val nChirps   = CHIRPS_PER_FRAME
        val nSamples  = SAMPLES_PER_CHIRP

        val rangeFftN = config.rangeFftSize.coerceAtLeast(nSamples).nextPow2()
        val rangeSpec = Array(nChirps) { c ->
            complexFft(adcI[c][0], adcQ[c][0], rangeFftN)
        }

        val dopplerN = nChirps.nextPow2()
        val rdMag    = Array(rangeFftN / 2) { FloatArray(dopplerN) }
        for (r in 0 until rangeFftN / 2) {
            val cI = FloatArray(nChirps) { c -> rangeSpec[c][r * 2] }
            val cQ = FloatArray(nChirps) { c -> rangeSpec[c][r * 2 + 1] }
            val ds = complexFft(cI, cQ, dopplerN)
            for (d in 0 until dopplerN) {
                val re = ds[d * 2]; val im = ds[d * 2 + 1]
                rdMag[r][d] = 10f * log10(re * re + im * im + 1e-12f)
            }
        }

        // Physics — TinyRAD: 24–24.25 GHz, BW = 250 MHz
        val rangeResM    = 3e8f / (2f * 250e6f)   // ~0.6 m
        val lambda       = 3e8f / 24.125e9f        // ~12.4 mm
        val chirpRepSec  = 1f / (config.framesPerSec * nChirps)
        val dopplerResMs = lambda / (2f * nChirps * chirpRepSec)

        val detections = cfarDetect(rdMag, rangeResM, dopplerResMs)
        val objects    = classifyAndTrack(detections)

        val flat = FloatArray(rangeFftN / 2 * dopplerN)
        for (r in 0 until rangeFftN / 2)
            for (d in 0 until dopplerN)
                flat[r * dopplerN + d] = rdMag[r][d]

        _frameFlow.value = RadarFrame(
            frameIndex = ++frameIndex, timestampMs = System.currentTimeMillis(),
            detectedObjects = objects, rangeDopplerMag = flat,
            rangeBins = rangeFftN / 2, dopplerBins = dopplerN,
            rangeResM = rangeResM, dopplerResMs = dopplerResMs
        )
    }

    // ── USB helpers ───────────────────────────────────────────────────────────

    /**
     * Build and send a command frame.
     * [reserved] is an array so callers can pass different per-command values.
     */
    private fun sendCmd(
        cmd:      Int,
        reserved: ShortArray,
        params:   IntArray
    ): Int {
        val buf = ByteBuffer.allocate(USB_OUT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val payloadLen = (4 + params.size * 4).toShort()  // nparams+reserved fields + params
        buf.putShort(payloadLen)
        buf.putShort(cmd.toShort())
        buf.putShort(params.size.toShort())
        buf.putShort(if (reserved.isNotEmpty()) reserved[0] else 0)
        for (p in params) buf.putInt(p)
        // Remaining bytes are already zero (ByteBuffer.allocate zeroes)
        val bytes = buf.array()
        val n = usbConnection?.bulkTransfer(bulkOut, bytes, bytes.size, BULK_TIMEOUT_MS) ?: -1
        AppLog.debug("TX 0x${cmd.toHex(4)}  ${params.size} params  → $n bytes")
        return n
    }

    /** Build and send a RegWrite (0x9017) with reserved=0x0007. */
    private fun regWrite(params: IntArray) =
        sendCmd(0x9017, shortArrayOf(0x0007), params)

    /** Read exactly `expect` bytes from bulk-IN. */
    private fun bulkRead(buf: ByteArray, expect: Int): Int =
        usbConnection?.bulkTransfer(bulkIn, buf, expect, BULK_TIMEOUT_MS) ?: -1

    private val ackBuf = ByteArray(64)

    /** Drain a single ACK packet (8 bytes) and return the cmd echo. */
    private fun readAck(): Int {
        val n = bulkRead(ackBuf, ackBuf.size)
        if (n < 4) return -1
        val echo   = (ackBuf[0].toInt() and 0xFF) or ((ackBuf[1].toInt() and 0xFF) shl 8)
        val status = (ackBuf[2].toInt() and 0xFF) or ((ackBuf[3].toInt() and 0xFF) shl 8)
        AppLog.debug("ACK cmd=0x${echo.toHex(4)} status=0x${status.toHex(4)}")
        return echo
    }

    private fun readAckExpect(expectedCmd: Int): Boolean {
        val echo = readAck()
        if (echo != expectedCmd)
            AppLog.warn("ACK mismatch: expected 0x${expectedCmd.toHex(4)} got 0x${echo.toHex(4)}")
        return echo == expectedCmd
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
                    out[(k+j)*2]=pRe+tRe; out[(k+j)*2+1]=pIm+tIm
                    out[(k+j+len/2)*2]=pRe-tRe; out[(k+j+len/2)*2+1]=pIm-tIm
                    val nu=uRe*wRe-uIm*wIm; uIm=uRe*wIm+uIm*wRe; uRe=nu
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
            val half = 32
            val signed = if (dopplerBin > half) dopplerBin - half * 2 else dopplerBin
            return signed * dopplerResMs
        }
    }

    private fun cfarDetect(
        rdMag: Array<FloatArray>, rangeResM: Float, dopplerResMs: Float
    ): List<RawDetection> {
        val res = mutableListOf<RawDetection>()
        val nr = rdMag.size; val nd = rdMag.firstOrNull()?.size ?: 0
        val g = config.cfar_guard; val tr = config.cfar_training; val thr = config.cfar_threshold
        for (r in (g+tr) until (nr-g-tr)) {
            for (d in (g+tr) until (nd-g-tr)) {
                val cell = rdMag[r][d]; var sum = 0f; var cnt = 0
                for (dr in -(tr+g)..(tr+g)) for (dd in -(tr+g)..(tr+g)) {
                    if (abs(dr) > g || abs(dd) > g) { sum += rdMag[r+dr][d+dd]; cnt++ }
                }
                val noise = if (cnt > 0) sum / cnt else 0f
                if (cell - noise >= thr) res.add(RawDetection(r, d, cell-noise, rangeResM, dopplerResMs))
            }
        }
        return res
    }

    private fun classifyAndTrack(detections: List<RawDetection>): List<DetectedObject> {
        val now = System.currentTimeMillis(); val results = mutableListOf<DetectedObject>()
        for (det in detections) {
            val dist = det.distanceM; val speed = abs(det.speedMps); val snr = det.snrDb
            if (dist > config.maxRangeM || speed > config.maxSpeedMps || snr < config.minSnrDb) continue
            val cls = when {
                dist > 10f && speed > 15f       -> ObjectClass.AERIAL_VEHICLE
                speed > 5f  && snr > 20f        -> ObjectClass.GROUND_VEHICLE
                speed < 1f  && snr > 25f        -> ObjectClass.GROUND_VEHICLE
                speed in 0.3f..4f && dist < 20f -> ObjectClass.HUMAN
                speed in 0.1f..8f && snr < 20f  -> ObjectClass.ANIMAL
                else                            -> ObjectClass.UNKNOWN
            }
            val confidence = when (cls) {
                ObjectClass.HUMAN          -> (1f - dist/20f).coerceIn(0.4f, 0.95f)
                ObjectClass.GROUND_VEHICLE -> (snr/40f).coerceIn(0.5f, 0.99f)
                else                       -> 0.6f
            }
            val trackId = tracker.entries
                .minByOrNull { (_,p) -> abs(p.distanceM-dist)+abs(p.speedMps-det.speedMps) }
                ?.takeIf { (_,p) -> abs(p.distanceM-dist) < 2f }?.key ?: nextTrackId++
            val obj = DetectedObject(trackId=trackId, objectClass=cls, distanceM=dist,
                azimuthDeg=0f, elevationDeg=0f, speedMps=det.speedMps, direction=Direction.AHEAD,
                snrDb=snr, confidence=confidence, timestampMs=now)
            tracker[trackId] = obj; results.add(obj)
        }
        tracker.entries.filter { (_,v) -> now-v.timestampMs > 2000 }.map { it.key }
            .forEach { tracker.remove(it) }
        return results.sortedBy { it.distanceM }
    }

    fun sendConfig(cfg: TinyRadConfig) { config = cfg }
}

private fun Int.toHex(digits: Int) = and(0xFFFF).toString(16).padStart(digits,'0').uppercase()
private fun Int.nextPow2(): Int {
    var v = this; if (v <= 1) return 1; v--
    v=v or(v shr 1); v=v or(v shr 2); v=v or(v shr 4); v=v or(v shr 8); v=v or(v shr 16); return v+1
}
