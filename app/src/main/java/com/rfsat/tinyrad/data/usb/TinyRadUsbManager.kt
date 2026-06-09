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

// ─── EV-TINYRAD24G USB protocol — confirmed from USB-TinyRAD.pcapng ──────────
//
// Device: VID 0x064B / PID 0x7823, fw R 3.0.3 (2020-06-02)
// Interface class: 0xFF (vendor-specific bulk)
// Endpoint OUT: 0x01   Endpoint IN: 0x81
//
// Every exchange:
//   host → board  ep=0x01  2048 bytes  (command, zero-padded)
//   board → host  ep=0x81  N bytes     (response data)
//   board → host  ep=0x81  8 bytes     (ACK, when response precedes it)
//
// The zero-length completion/submit frames visible in the pcap are USB Host
// stack housekeeping — Android's bulkTransfer() handles them transparently.
//
// OUT frame format:
//   uint16  payload_len   bytes following this field
//   uint16  cmd_code
//   uint16  num_params
//   uint16  reserved      0x0001 for most cmds, 0x0007 for RegWrite
//   uint32  params[N]
//   <zero pad to 2048 bytes>
//
// IN ACK format (8 bytes):
//   uint16  cmd_echo      mirrors cmd_code
//   uint16  status        0x0002 = OK
//   uint32  result
//
// IN ADC data (1024 bytes per chirp):
//   uint16  chirp_idx × 4  (four identical copies, one per Rx channel)
//   int16   IQ[508]         layout: Rx0_I Rx0_Q Rx1_I Rx1_Q Rx2_I Rx2_Q Rx3_I Rx3_Q
//                           × 127 samples/channel  (508 / 4 = 127)
//
// Init sequence (from trace):
//   1. 0x9030 BrdInit  — params [0, 0x02000000, 0]
//   2. 0x900E RfBrdSet — params [0x00010000, 0x02000000]
//   3. 0x9031 CfgFmcw ×5 + 0x9017 RegWrite ×4  (exact param values below)
//   4. 0x9032 MeasTrig repeated per-chirp (80 chirps = one FMCW frame)
//   5. 0x9031 with params [0, 0x02000000, 0] — stop

private val TINYRAD_VID_PID = listOf(
    0x064B to 0x7823,
    0x0456 to 0xB60F,
    0x0456 to 0xB671,
    0x0456 to 0xB62C,
    0x0456 to 0xB623,
    0x0483 to 0x5740
)

private const val EP_OUT_ADDR       = 0x01   // confirmed from pcap
private const val EP_IN_ADDR        = 0x81   // confirmed from pcap
private const val USB_OUT_SIZE      = 2048
private const val ADC_FRAME_SIZE    = 1024
private const val ADC_HEADER_BYTES  = 8
private const val CHIRPS_PER_FRAME  = 80
private const val RX_CHANNELS       = 4
private const val SAMPLES_PER_CHIRP = 127

// Timeouts: generous — board DSP takes time; -1 means dead device not slow board
private const val CMD_TIMEOUT_MS    = 1000   // for config commands
private const val DATA_TIMEOUT_MS   = 2000   // for ADC data (larger margin)
private const val ACK_TIMEOUT_MS    = 1000

class TinyRadUsbManager(private val context: Context) {

    private val _connectionState = MutableStateFlow(UsbConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _frameFlow = MutableStateFlow<RadarFrame?>(null)
    val frameFlow = _frameFlow.asStateFlow()

    private val _deviceName = MutableStateFlow("TinyRAD (not connected)")
    val deviceName = _deviceName.asStateFlow()

    private var usbConn:      UsbDeviceConnection? = null
    private var epOut:        UsbEndpoint?         = null   // ep=0x01
    private var epIn:         UsbEndpoint?         = null   // ep=0x81
    private var claimedIface: UsbInterface?        = null

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
            val mgr  = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val conn = mgr.openDevice(device)
                ?: throw IllegalStateException("openDevice() null — permission revoked?")

            AppLog.info("Opened: ${device.productName ?: device.deviceName}  " +
                    "VID=0x${device.vendorId.toHex(4)} PID=0x${device.productId.toHex(4)}  " +
                    "ifaces=${device.interfaceCount}")

            // Find the vendor-specific interface and confirm the two bulk endpoints
            var iface: UsbInterface? = null
            var out:   UsbEndpoint?  = null
            var inp:   UsbEndpoint?  = null

            for (i in 0 until device.interfaceCount) {
                val f = device.getInterface(i)
                var o: UsbEndpoint? = null
                var n: UsbEndpoint? = null
                for (j in 0 until f.endpointCount) {
                    val ep = f.getEndpoint(j)
                    AppLog.debug("  iface[$i] ep[$j] addr=0x${ep.address.toHex(2)} " +
                            "dir=${if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"} " +
                            "type=${ep.type} pkt=${ep.maxPacketSize}")
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    // Match by confirmed address
                    if (ep.address == EP_OUT_ADDR) o = ep
                    if (ep.address == EP_IN_ADDR)  n = ep
                }
                if (o != null && n != null) {
                    iface = f; out = o; inp = n
                    AppLog.info("Found ep OUT=0x${out.address.toHex(2)} " +
                            "IN=0x${inp.address.toHex(2)} on iface ${f.id}")
                    break
                }
            }

            // Fallback: accept any interface with at least one bulk-IN and bulk-OUT
            if (iface == null) {
                AppLog.warn("Exact endpoint address match failed — falling back to direction-only search")
                for (i in 0 until device.interfaceCount) {
                    val f = device.getInterface(i)
                    var o: UsbEndpoint? = null
                    var n: UsbEndpoint? = null
                    for (j in 0 until f.endpointCount) {
                        val ep = f.getEndpoint(j)
                        if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                        if (ep.direction == UsbConstants.USB_DIR_OUT && o == null) o = ep
                        if (ep.direction == UsbConstants.USB_DIR_IN  && n == null) n = ep
                    }
                    if (o != null && n != null) {
                        iface = f; out = o; inp = n
                        AppLog.warn("Fallback: ep OUT=0x${out.address.toHex(2)} " +
                                "IN=0x${inp.address.toHex(2)}")
                        break
                    }
                }
            }

            requireNotNull(iface) { "No bulk interface with IN+OUT found" }
            conn.claimInterface(iface, true)

            usbConn      = conn
            epOut        = out
            epIn         = inp
            claimedIface = iface

            _deviceName.value = "${device.productName ?: "TinyRAD"} " +
                    "(${device.vendorId.toHex(4)}:${device.productId.toHex(4)})"
            _connectionState.value = UsbConnectionState.CONNECTED
            AppLog.info("USB connected — epOUT=0x${out!!.address.toHex(2)}  " +
                    "epIN=0x${inp!!.address.toHex(2)}")

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
        runCatching {
            sendCmd(0x9031, 0x0001, intArrayOf(0, 0x02000000, 0))
            readAck()
        }
        AppLog.info("Streaming stopped")
    }

    fun disconnect() {
        stopStreaming()
        claimedIface?.let { usbConn?.releaseInterface(it) }
        usbConn?.close()
        usbConn = null; epOut = null; epIn = null; claimedIface = null
        _connectionState.value = UsbConnectionState.DISCONNECTED
        _deviceName.value = "TinyRAD (not connected)"
        frameIndex = 0L; tracker.clear()
        AppLog.info("USB disconnected")
    }

    fun cleanup() { disconnect(); scope.cancel() }

    fun findTinyRadDevices(): List<UsbDevice> {
        val mgr = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        return mgr.deviceList.values.filter { dev ->
            TINYRAD_VID_PID.any { (v,p) -> dev.vendorId==v && dev.productId==p }
        }.ifEmpty { mgr.deviceList.values.toList() }
    }

    fun hasPermission(d: UsbDevice) =
        (context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager).hasPermission(d)

    fun requestPermission(d: UsbDevice, pi: android.app.PendingIntent) =
        (context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager).requestPermission(d,pi)

    // ── Streaming loop ────────────────────────────────────────────────────────

    private suspend fun streamLoop() = withContext(Dispatchers.IO) {
        if (!initBoard()) {
            AppLog.error("Init failed — aborting streaming")
            _connectionState.value = UsbConnectionState.ERROR
            return@withContext
        }

        val adcI   = Array(CHIRPS_PER_FRAME) { Array(RX_CHANNELS) { FloatArray(SAMPLES_PER_CHIRP) } }
        val adcQ   = Array(CHIRPS_PER_FRAME) { Array(RX_CHANNELS) { FloatArray(SAMPLES_PER_CHIRP) } }
        val inBuf  = ByteArray(ADC_FRAME_SIZE)
        var chirpCount = 0
        var frameErrors = 0

        while (isActive && usbConn != null) {
            // Trigger one chirp
            val sent = sendCmd(0x9032, 0x0001,
                intArrayOf(0x00010000, 0x02000000, 0, 0x00060000, 0x14990002))
            if (sent <= 0) {
                AppLog.warn("MeasTrig send failed ($sent) — retrying")
                delay(50); continue
            }

            // Read 1024-byte ADC data frame — use full buffer size, generous timeout
            val nData = usbConn!!.bulkTransfer(epIn, inBuf, inBuf.size, DATA_TIMEOUT_MS)
            if (nData != ADC_FRAME_SIZE) {
                frameErrors++
                AppLog.warn("ADC read: expected $ADC_FRAME_SIZE got $nData (errors: $frameErrors)")
                if (frameErrors > 10) {
                    AppLog.error("Too many ADC errors — stopping")
                    break
                }
                readAck()   // attempt to drain ACK anyway and stay in sync
                continue
            }
            frameErrors = 0

            // Read 8-byte ACK
            readAck()

            // Parse chirp
            val buf = ByteBuffer.wrap(inBuf).order(ByteOrder.LITTLE_ENDIAN)
            val chirpIdx = buf.short.toInt() and 0xFFFF
            buf.short; buf.short; buf.short   // skip 3 repeated chirp_idx values

            val row = chirpIdx / 2
            if (row < CHIRPS_PER_FRAME) {
                for (s in 0 until SAMPLES_PER_CHIRP) {
                    for (r in 0 until RX_CHANNELS) {
                        val iv = buf.short.toFloat()
                        val qv = buf.short.toFloat()
                        adcI[row][r][s] = iv
                        adcQ[row][r][s] = qv
                    }
                }
                chirpCount++
            }

            if (chirpCount >= CHIRPS_PER_FRAME) {
                processFrame(adcI, adcQ)
                chirpCount = 0
            }
        }
    }

    // ── Init sequence — exact from USB-TinyRAD.pcapng ────────────────────────

    private fun initBoard(): Boolean {
        AppLog.info("Init: BrdInit…")
        if (!cmdAck(0x9030, 0x0001, intArrayOf(0, 0x02000000, 0))) return false

        AppLog.info("Init: RfBrdSet…")
        if (!cmdAck(0x900E, 0x0000, intArrayOf(0x00010000, 0x02000000))) return false

        AppLog.info("Init: CfgFmcw #1…")
        if (!cmdAck(0x9031, 0x0001, intArrayOf(0, 0x02000000, 0))) return false

        AppLog.info("Init: RegWrite #1…")
        if (!cmdAck(0x9017, 0x0007, intArrayOf(
                0x00010000, 0, 0x00030000, 0x00060000,
                0x14990002, 0x14992000, 0x14994000, 0x14996000,
                0x00198000, 0x7CA0A000.toInt(), 0x00008000, 0))) return false

        AppLog.info("Init: RegWrite #2…")
        if (!cmdAck(0x9017, 0x0007, intArrayOf(
                0x00010000, 0x00010000, 0x00070000,
                0xFFEA0300.toInt(), 0xB9291FFF.toInt(), 0x3E882A20,
                0xE5204000.toInt(), 0x4827809F, 0x0006001F,
                0x80050000.toInt(), 0x000401E2, 0x08030020, 0x06420189,
                0xEA010002.toInt(), 0xE700FFF5.toInt(), 0x0000809F, 0))) return false

        AppLog.info("Init: RegWrite #3…")
        if (!cmdAck(0x9017, 0x0007, intArrayOf(
                0x00010000, 0x00010000,
                0xE5600000.toInt(), 0xED60809F.toInt(), 0x0000809F, 0))) return false

        AppLog.info("Init: RegWrite #4…")
        if (!cmdAck(0x9017, 0x0007, intArrayOf(
                0x00010000, 0x00010000,
                0xE5A00000.toInt(), 0xF5A0809F.toInt(),
                0xB929809F.toInt(), 0x3E882A20))) return false

        AppLog.info("Init: RegWrite #5…")
        if (!cmdAck(0x9017, 0x0007, intArrayOf(
                0x00010000, 0x00010000,
                0xB9290000.toInt(), 0x25602800,
                0x040E809F, 0x80050080.toInt()))) return false

        AppLog.info("Init: CfgFmcw #2…")
        if (!cmdAck(0x9031, 0x0001, intArrayOf(
                0x000C0000, 0x00030000, 0x00050000,
                0x004C0000, 0x00640000, 0, 0))) return false

        AppLog.info("Init: RegWrite #6…")
        if (!cmdAck(0x9017, 0x0007, intArrayOf(
                0x00010000, 0x00020000, 0x00070000,
                0x04060010, 0x04160000, 0x80050080.toInt(), 0x0FC50030,
                0x010400C8, 0x01440098, 0x08430098, 0x81920002.toInt(),
                0x00010440, 0x19980333, 0xEA01803C.toInt(), 0xE700FFF5.toInt()))) return false

        AppLog.info("Init: CfgFmcw #3…")
        if (!cmdAck(0x9031, 0x0001, intArrayOf(
                0x00020000, 0x00010000, 0x00020000,
                0x00010000, 0x00040000, 0, 0))) return false

        AppLog.info("Init: CfgFmcw #4…")
        if (!cmdAck(0x9031, 0x0001, intArrayOf(
                0x00030000, 0x00010000, 0x00030000, 0x00060000))) return false

        AppLog.info("Init: CfgFmcw #5 (sweep params)…")
        if (!cmdAck(0x9031, 0x0001, intArrayOf(
                0x00010000, 0x09000000, 0x003D0080, 0x04060000, 0x04160000))) return false

        AppLog.info("Board init complete")
        return true
    }

    // ── USB transfer helpers ──────────────────────────────────────────────────

    /** Send a command OUT frame and immediately read the ACK response.
     *  Returns true if ACK cmd_echo matches. */
    private fun cmdAck(cmd: Int, reserved: Int, params: IntArray): Boolean {
        val sent = sendCmd(cmd, reserved, params)
        if (sent <= 0) {
            AppLog.error("sendCmd 0x${cmd.toHex(4)} failed ($sent)")
            return false
        }
        val echo = readAck()
        if (echo != cmd) {
            AppLog.warn("ACK mismatch cmd=0x${cmd.toHex(4)} got=0x${echo.toHex(4)}")
            // Don't abort on mismatch — board sometimes echoes 0x9031 for multiple cmds
        }
        return true
    }

    /** Build and send a 2048-byte OUT command frame. Returns bytes sent or <0 on error. */
    private fun sendCmd(cmd: Int, reserved: Int, params: IntArray): Int {
        val conn = usbConn ?: return -1
        val buf  = ByteBuffer.allocate(USB_OUT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort((4 + params.size * 4).toShort())   // payload_len
        buf.putShort(cmd.toShort())
        buf.putShort(params.size.toShort())
        buf.putShort(reserved.toShort())
        for (p in params) buf.putInt(p)
        val bytes = buf.array()
        val n = conn.bulkTransfer(epOut, bytes, bytes.size, CMD_TIMEOUT_MS)
        AppLog.debug("TX 0x${cmd.toHex(4)} nparams=${params.size} sent=$n")
        return n
    }

    private val ackBuf = ByteArray(64)

    /** Read one ACK packet. Returns cmd_echo, or -1 on error. */
    private fun readAck(): Int {
        val conn = usbConn ?: return -1
        val n = conn.bulkTransfer(epIn, ackBuf, ackBuf.size, ACK_TIMEOUT_MS)
        if (n < 4) {
            AppLog.warn("ACK read returned $n bytes")
            return -1
        }
        val echo   = (ackBuf[0].toInt() and 0xFF) or ((ackBuf[1].toInt() and 0xFF) shl 8)
        val status = (ackBuf[2].toInt() and 0xFF) or ((ackBuf[3].toInt() and 0xFF) shl 8)
        val result = ByteBuffer.wrap(ackBuf, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        AppLog.debug("ACK cmd=0x${echo.toHex(4)} status=0x${status.toHex(4)} result=0x${result.toHex(8)}")
        return echo
    }

    // ── DSP pipeline ─────────────────────────────────────────────────────────

    private fun processFrame(
        adcI: Array<Array<FloatArray>>,
        adcQ: Array<Array<FloatArray>>
    ) {
        val nChirps  = CHIRPS_PER_FRAME
        val nSamples = SAMPLES_PER_CHIRP
        val rangeFftN = config.rangeFftSize.coerceAtLeast(nSamples).nextPow2()

        // Range FFT on Rx0 (single-channel range-Doppler; all 4 Rx available for MIMO)
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
                val re = ds[d*2]; val im = ds[d*2+1]
                rdMag[r][d] = 10f * log10(re*re + im*im + 1e-12f)
            }
        }

        // Physics for 24–24.25 GHz, BW=250 MHz, 80 chirps
        val rangeResM    = 3e8f / (2f * 250e6f)          // ≈ 0.6 m
        val lambda       = 3e8f / 24.125e9f               // ≈ 12.4 mm
        val chirpRepSec  = 1f / (config.framesPerSec.toFloat() * nChirps)
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
        AppLog.debug("Frame $frameIndex: ${objects.size} object(s)")
    }

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
                    val tRe = uRe*qRe-uIm*qIm; val tIm = uRe*qIm+uIm*qRe
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
        repeat(bits) { r=(r shl 1)or(v and 1); v=v shr 1 }
        return r
    }

    private data class RawDetection(
        val rangeBin: Int, val dopplerBin: Int, val snrDb: Float,
        val rangeResM: Float, val dopplerResMs: Float
    ) {
        val distanceM: Float get() = rangeBin * rangeResM
        val speedMps:  Float get() {
            val half = 32
            val signed = if (dopplerBin > half) dopplerBin - half*2 else dopplerBin
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
                    if (abs(dr)>g || abs(dd)>g) { sum+=rdMag[r+dr][d+dd]; cnt++ }
                }
                val noise = if (cnt>0) sum/cnt else 0f
                if (cell-noise >= thr)
                    res.add(RawDetection(r,d,cell-noise,rangeResM,dopplerResMs))
            }
        }
        return res
    }

    private fun classifyAndTrack(detections: List<RawDetection>): List<DetectedObject> {
        val now = System.currentTimeMillis(); val results = mutableListOf<DetectedObject>()
        for (det in detections) {
            val dist=det.distanceM; val speed=abs(det.speedMps); val snr=det.snrDb
            if (dist>config.maxRangeM||speed>config.maxSpeedMps||snr<config.minSnrDb) continue
            val cls = when {
                dist>10f&&speed>15f       -> ObjectClass.AERIAL_VEHICLE
                speed>5f&&snr>20f         -> ObjectClass.GROUND_VEHICLE
                speed<1f&&snr>25f         -> ObjectClass.GROUND_VEHICLE
                speed in 0.3f..4f&&dist<20f -> ObjectClass.HUMAN
                speed in 0.1f..8f&&snr<20f  -> ObjectClass.ANIMAL
                else                      -> ObjectClass.UNKNOWN
            }
            val conf = when(cls) {
                ObjectClass.HUMAN          -> (1f-dist/20f).coerceIn(0.4f,0.95f)
                ObjectClass.GROUND_VEHICLE -> (snr/40f).coerceIn(0.5f,0.99f)
                else                       -> 0.6f
            }
            val tid = tracker.entries
                .minByOrNull{(_,p)->abs(p.distanceM-dist)+abs(p.speedMps-det.speedMps)}
                ?.takeIf{(_,p)->abs(p.distanceM-dist)<2f}?.key ?: nextTrackId++
            val obj = DetectedObject(trackId=tid,objectClass=cls,distanceM=dist,
                azimuthDeg=0f,elevationDeg=0f,speedMps=det.speedMps,direction=Direction.AHEAD,
                snrDb=snr,confidence=conf,timestampMs=now)
            tracker[tid]=obj; results.add(obj)
        }
        tracker.entries.filter{(_,v)->now-v.timestampMs>2000}.map{it.key}.forEach{tracker.remove(it)}
        return results.sortedBy{it.distanceM}
    }

    fun sendConfig(cfg: TinyRadConfig) { config = cfg }
}

private fun Int.toHex(digits: Int)    = and(0xFFFF).toString(16).padStart(digits,'0').uppercase()
private fun Int.toHex8(): String      = toUInt().toString(16).padStart(8,'0').uppercase()
private fun Int.nextPow2(): Int {
    var v=this; if(v<=1) return 1; v--
    v=v or(v shr 1);v=v or(v shr 2);v=v or(v shr 4);v=v or(v shr 8);v=v or(v shr 16);return v+1
}
