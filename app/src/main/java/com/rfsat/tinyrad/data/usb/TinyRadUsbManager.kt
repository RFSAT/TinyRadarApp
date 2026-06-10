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

// ─── EV-TINYRAD24G USB protocol — confirmed from Python source + pcap ─────────
//
// Source: TinyRadTool/src/cmd_modules/Connection.py  (CmdBuild / CmdSend / CmdRecv)
//         TinyRadTool/src/cmd_modules/TinyRad.py     (init sequence / Dsp_GetDat)
//
// ── OUT frame (host → board, always 2048 bytes zero-padded) ──────────────────
//
//   Python CmdBuild:
//     LenData   = len(DspCmd) + 1          ← total uint32 words incl. word0
//     word0     = (Ack<<24) | (LenData<<16) | CmdCod
//     TxData    = [word0, DspCmd[0], DspCmd[1], ...]
//
//   Python CmdSend (USB path):
//     byte_len  = len(TxData) * 4  = LenData * 4
//     frame[0:2]= byte_len as uint16 LE
//     frame[2:] = TxData as uint32 LE array
//     → write 2048 bytes (zero-padded)
//
//   Android equivalent:
//     offset 0:  uint16 LE   byte_len = LenData * 4
//     offset 2:  uint32 LE   word0    = (Ack<<24)|(LenData<<16)|CmdCod
//     offset 6+: uint32 LE   DspCmd[0], DspCmd[1], …
//     <zero pad to 2048>
//
// ── IN frame (board → host) ───────────────────────────────────────────────────
//
//   Python CmdRecv (USB path):
//     Reads 128-byte header, word0 = HeaderData[0:4]
//     RxDataLen = (HeaderData[2] - 1) * 4   ← (LenData-1)*4 payload bytes
//     Response follows same word0 encoding
//
//   ACK:      small response, word0 echoes cmd+LenData
//   ADC data: raw int16 bulk data, read separately via ConGetUsbData
//
// ── Measurement flow (Dsp_GetDat in TinyRad.py) ──────────────────────────────
//
//   For each chirp trigger:
//     1. Send  0x9032  DspCmd=[1, 1, Len, 0]   (Len = int16 count to return)
//     2. Read  Len*2 bytes ADC data             (= ConGetUsbData(Len*2))
//     3. Read  ACK                              (= CmdRecv)
//
//   Len per chirp = N * NrChn = 128 * 4 = 512 int16 → 1024 bytes ✓ (pcap confirmed)
//
// ── Init sequence (pcap F319–F439, decoded against Python source) ─────────────
//
//   F319  0x9030  BrdGetUID         DspCmd=[1,0]
//   F323  0x900E  GetSwVers         DspCmd=[0]
//   F327  0x9031  BrdRst            DspCmd=[1,0]
//   F331  0x9017  SpiData Rx        DspCmd=[7,1,0, <8 ADF5904 regs>]
//   F335  0x9017  SpiData Tx pt1    DspCmd=[7,1,1, <13 ADF5901 regs>]
//   F339  0x9017  SpiData Tx pt2    DspCmd=[7,1,1, <4 words>]
//   F343  0x9017  SpiData Tx pt3    DspCmd=[7,1,1, <4 words>]
//   F347  0x9017  SpiData Tx pt4    DspCmd=[7,1,1, <4 words>]
//   F351  0x9031  CfgAdarPll        DspCmd=[1,12, X=3,R=5,N=76,M=100]
//   F355  0x9017  SpiData Pll       DspCmd=[7,1,2, <11 ADF4159 regs>]
//   F359  0x9031  CfgMeasSiz        DspCmd=[1,2, nSeq=1,FrmSiz=2,FrmMeasSiz=1,CycSiz=4]
//   F363  0x9031  CfgMeasSeq        DspCmd=[1,3, Seq=1]
//   F367  0x9031  StrtMeas          DspCmd=[1,1, Period_10ns=4000000, N=128]
//   F439  0x9032  GetDat (trigger)  DspCmd=[1,1, Len=512, 0]  → read 1024 bytes

private val TINYRAD_VID_PID = listOf(
    0x064B to 0x7823,   // EV-TINYRAD24G fw R 3.0.3 — confirmed
    0x0456 to 0xB60F,
    0x0456 to 0xB671,
    0x0456 to 0xB62C,
    0x0456 to 0xB623,
    0x0483 to 0x5740
)

private const val EP_OUT_ADDR    = 0x01   // confirmed from pcap
private const val EP_IN_ADDR     = 0x81   // confirmed from pcap
private const val USB_OUT_SIZE   = 2048

// Measurement geometry — confirmed from pcap + TinyRad.py
private const val RAD_N            = 128   // samples/chirp (StrtMeas N=128)
private const val NR_CHN           = 4     // Rx channels
private const val CHIRPS_PER_FRAME = 80    // chirp_idx 0,2,4…158
private const val MEAS_LEN         = RAD_N * NR_CHN   // 512 int16 per trigger
private const val ADC_BYTES        = MEAS_LEN * 2     // 1024 bytes per trigger
private const val ACK_BYTES        = 8                // 8-byte ACK word
private const val COMBINED_BYTES   = ACK_BYTES + ADC_BYTES  // 1032 — fits ACK + full ADC

// Chirp period from pcap F367: 4_000_000 × 10 ns = 40 ms
private const val CHIRP_PERIOD_10NS = 4_000_000

// RF parameters from TinyRad.py: Rf_fStrt=24e9, Rf_fStop=24.256e9
private val BW_HZ   = (24.256e9 - 24.0e9).toFloat()   // 256 MHz
private val FC_HZ   = ((24.256e9 + 24.0e9) / 2.0).toFloat()

private const val CMD_TIMEOUT_MS  = 1_000
private const val DATA_TIMEOUT_MS = 2_000
private const val ACK_TIMEOUT_MS  = 1_000

class TinyRadUsbManager(private val context: Context) {

    private val _connectionState = MutableStateFlow(UsbConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _frameFlow = MutableStateFlow<RadarFrame?>(null)
    val frameFlow = _frameFlow.asStateFlow()

    private val _deviceName = MutableStateFlow("TinyRAD (not connected)")
    val deviceName = _deviceName.asStateFlow()

    private var usbConn:      UsbDeviceConnection? = null
    private var epOut:        UsbEndpoint?         = null
    private var epIn:         UsbEndpoint?         = null
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
                ?: throw IllegalStateException("openDevice() null — permission not held?")

            AppLog.info(
                "Opened: ${device.productName ?: device.deviceName}  " +
                "VID=0x${device.vendorId.hex4()} PID=0x${device.productId.hex4()}  " +
                "ifaces=${device.interfaceCount}"
            )

            // Find the interface with ep=0x01 (OUT) and ep=0x81 (IN)
            var iface: UsbInterface? = null
            var out:   UsbEndpoint?  = null
            var inp:   UsbEndpoint?  = null

            outer@ for (i in 0 until device.interfaceCount) {
                val f = device.getInterface(i)
                var o: UsbEndpoint? = null
                var n: UsbEndpoint? = null
                for (j in 0 until f.endpointCount) {
                    val ep = f.getEndpoint(j)
                    AppLog.debug(
                        "  iface[$i] ep[$j] addr=0x${ep.address.hex2()} " +
                        "dir=${if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"} " +
                        "type=${ep.type} pkt=${ep.maxPacketSize}"
                    )
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.address == EP_OUT_ADDR) o = ep
                    if (ep.address == EP_IN_ADDR)  n = ep
                }
                if (o != null && n != null) { iface = f; out = o; inp = n; break@outer }
            }

            // Fallback: accept any interface with bulk IN + OUT by direction
            if (iface == null) {
                AppLog.warn("Exact ep address match failed — falling back to direction search")
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
                    if (o != null && n != null) { iface = f; out = o; inp = n; break }
                }
            }

            requireNotNull(iface) { "No bulk IN+OUT interface found" }
            conn.claimInterface(iface, true)
            AppLog.info(
                "Claimed iface ${iface.id} — " +
                "epOUT=0x${out!!.address.hex2()}  epIN=0x${inp!!.address.hex2()}"
            )

            usbConn = conn; epOut = out; epIn = inp; claimedIface = iface
            _deviceName.value = "${device.productName ?: "TinyRAD"} " +
                "(${device.vendorId.hex4()}:${device.productId.hex4()})"
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
            TINYRAD_VID_PID.any { (v, p) -> dev.vendorId == v && dev.productId == p }
        }.ifEmpty { mgr.deviceList.values.toList() }
    }

    fun hasPermission(d: UsbDevice) =
        (context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager)
            .hasPermission(d)

    fun requestPermission(d: UsbDevice, pi: android.app.PendingIntent) =
        (context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager)
            .requestPermission(d, pi)

    // ── Streaming loop ────────────────────────────────────────────────────────

    private suspend fun streamLoop() = withContext(Dispatchers.IO) {
        if (!initBoard()) {
            AppLog.error("Board init failed — see Log for details")
            _connectionState.value = UsbConnectionState.ERROR
            return@withContext
        }

        val combinedBuf = ByteArray(COMBINED_BYTES)   // ACK(8) + ADC(1024) in one read
        val adcI   = Array(CHIRPS_PER_FRAME) { Array(NR_CHN) { FloatArray(RAD_N) } }
        val adcQ   = Array(CHIRPS_PER_FRAME) { Array(NR_CHN) { FloatArray(RAD_N) } }
        var chirpCount       = 0
        var consecutiveErrors = 0

        while (isActive && usbConn != null) {
            // Send 0x9032 trigger
            val sent = sendCmd(0x9032, intArrayOf(1, 1, MEAS_LEN, 0))
            if (sent <= 0) {
                consecutiveErrors++
                AppLog.warn("0x9032 send failed ($sent), errors=$consecutiveErrors")
                if (consecutiveErrors > 5) { AppLog.error("Too many send failures"); break }
                delay(50); continue
            }

            // The board sends the 8-byte ACK and the 1024-byte ADC data back-to-back,
            // so fast that both arrive before our first read. Using a 256-byte ackBuf
            // caused -1 (Android USB overflow when received > buffer size).
            //
            // The drain in session 2 confirmed the combined packet sizes:
            //   520 bytes = 8 (ACK) + 512 (first USB packet of ADC data)
            //   endpoint maxPacketSize = 512
            //
            // Solution: one large read that accommodates ACK + full ADC frame,
            // then parse based on the actual byte count returned.
            val n = usbConn!!.bulkTransfer(epIn, combinedBuf, combinedBuf.size, DATA_TIMEOUT_MS)
            when {
                n == ADC_BYTES + ACK_BYTES -> {
                    // Perfect: ACK (8 bytes) immediately followed by ADC (1024 bytes)
                    consecutiveErrors = 0
                    parseCombinedBuffer(combinedBuf, ackOffset = 0, adcOffset = ACK_BYTES, chirpCount, adcI, adcQ)
                }
                n == ADC_BYTES -> {
                    // ADC data only — no separate ACK (board sent data without ACK)
                    consecutiveErrors = 0
                    parseCombinedBuffer(combinedBuf, ackOffset = -1, adcOffset = 0, chirpCount, adcI, adcQ)
                }
                n > ACK_BYTES && n < ADC_BYTES -> {
                    // Partial ADC data — board still sending, read the remainder
                    val remaining = usbConn!!.bulkTransfer(
                        epIn, combinedBuf, combinedBuf.size - n, DATA_TIMEOUT_MS
                    )
                    AppLog.debug("Partial read: $n + $remaining bytes")
                    consecutiveErrors = 0
                    // Use what we have even if remainder timed out
                    parseCombinedBuffer(combinedBuf, ackOffset = if (n >= ACK_BYTES) 0 else -1,
                        adcOffset = ACK_BYTES, chirpCount, adcI, adcQ)
                }
                n <= 0 -> {
                    consecutiveErrors++
                    AppLog.warn("0x9032 read: $n bytes, errors=$consecutiveErrors")
                    if (consecutiveErrors > 10) { AppLog.error("Too many read errors"); break }
                    continue
                }
                else -> {
                    AppLog.warn("0x9032 unexpected size: $n bytes")
                    consecutiveErrors = 0
                }
            }

            chirpCount++
            if (chirpCount >= CHIRPS_PER_FRAME) {
                processFrame(adcI, adcQ)
                chirpCount = 0
            }
        }
    }

    /** Parse ADC samples from the combined receive buffer into adcI/adcQ arrays. */
    private fun parseCombinedBuffer(
        buf:        ByteArray,
        ackOffset:  Int,   // offset of 8-byte ACK, or -1 if not present
        adcOffset:  Int,   // offset where ADC int16 data starts
        chirpCount: Int,
        adcI:       Array<Array<FloatArray>>,
        adcQ:       Array<Array<FloatArray>>
    ) {
        if (adcOffset + ADC_BYTES > buf.size) return
        val chirpRow = chirpCount % CHIRPS_PER_FRAME
        val bb = ByteBuffer.wrap(buf, adcOffset, ADC_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (s in 0 until RAD_N) {
            for (r in 0 until NR_CHN) {
                adcI[chirpRow][r][s] = bb.short.toFloat()
                adcQ[chirpRow][r][s] = bb.short.toFloat()
            }
        }
    }

    // ── Init sequence — byte-exact from pcap, confirmed against TinyRad.py ────

    private fun initBoard(): Boolean {
        // Clear any endpoint halt/stall state from a previous session.
        // UsbDeviceConnection.clearHalt() requires API 28; we target API 26,
        // so send the equivalent USB CLEAR_FEATURE(ENDPOINT_HALT) control
        // transfer directly. bmRequestType=0x02 (endpoint), bRequest=1 (CLEAR_FEATURE),
        // wValue=0 (ENDPOINT_HALT), wIndex=endpoint address.
        fun clearHaltCompat(ep: UsbEndpoint?) {
            ep ?: return
            usbConn?.controlTransfer(
                0x02,           // bmRequestType: host→device, standard, endpoint
                0x01,           // bRequest: CLEAR_FEATURE
                0x0000,         // wValue: ENDPOINT_HALT feature selector
                ep.address,     // wIndex: endpoint address
                null, 0, 200
            )
        }
        clearHaltCompat(epOut)
        clearHaltCompat(epIn)

        // Drain any bytes the board may have buffered from the previous session.
        // Use COMBINED_BYTES (1032) so a full ACK+ADC response fits in one read.
        val drainBuf = ByteArray(COMBINED_BYTES)
        repeat(6) {   // up to 6 trigger responses could be buffered
            val n = usbConn?.bulkTransfer(epIn, drainBuf, drainBuf.size, 50) ?: 0
            if (n > 0) AppLog.info("Drained $n stale bytes from IN endpoint")
        }

        AppLog.info("Init: BrdGetUID…")
        if (!cmdAck(0x9030, intArrayOf(1, 0))) return false

        AppLog.info("Init: GetSwVers…")
        cmdAck(0x900E, intArrayOf(0))   // non-fatal; just reads fw version

        AppLog.info("Init: BrdRst…")
        if (!cmdAck(0x9031, intArrayOf(1, 0))) return false
        Thread.sleep(200)   // RF front-end needs time to power-cycle after reset

        AppLog.info("Init: SpiData Rx (ADF5904)…")
        if (!cmdAck(0x9017, intArrayOf(
                7, 1, 0,
                0x00020006, 0x20001499, 0x40001499, 0x60001499,
                0x80001499.toInt(), 0xA0000019.toInt(), 0x80007CA0.toInt(), 0x00000000
            ))) return false

        AppLog.info("Init: SpiData Tx (ADF5901) pt1…")
        if (!cmdAck(0x9017, intArrayOf(
                7, 1, 1,
                0x03000007, 0x1FFFFFEA, 0x2A20B929, 0x40003E88,
                0x809FE520.toInt(), 0x011F4827, 0x00000006,
                0x01E28005, 0x00200004, 0x01890803, 0x00020642,
                0xFFF5EA01.toInt(), 0x809FE700.toInt()
            ))) return false

        AppLog.info("Init: SpiData Tx (ADF5901) pt2…")
        if (!cmdAck(0x9017, intArrayOf(
                7, 1, 1,
                0x809FE560.toInt(), 0x809FED60.toInt(), 0x00000000, 0x00000000
            ))) return false

        AppLog.info("Init: SpiData Tx (ADF5901) pt3…")
        if (!cmdAck(0x9017, intArrayOf(
                7, 1, 1,
                0x809FE5A0.toInt(), 0x809FF5A0.toInt(), 0x00000000, 0x00000000
            ))) return false

        AppLog.info("Init: SpiData Tx (ADF5901) pt4…")
        if (!cmdAck(0x9017, intArrayOf(
                7, 1, 1,
                0x2800B929, 0x809F2560.toInt(), 0x00000000, 0x00000000
            ))) return false

        AppLog.info("Init: CfgAdarPll…")
        if (!cmdAck(0x9031, intArrayOf(1, 12, 3, 5, 76, 100))) return false

        AppLog.info("Init: SpiData Pll (ADF4159)…")
        if (!cmdAck(0x9017, intArrayOf(
                7, 1, 2,
                0x00100007, 0x00000406, 0x00800416, 0x00308005,
                0x00C80FC5, 0x00980104, 0x00980144, 0x00020843,
                0x04408192, 0x03330001, 0x803C1998.toInt()
            ))) return false

        AppLog.info("Init: CfgMeasSiz…")
        if (!cmdAck(0x9031, intArrayOf(1, 2, 1, 2, 1, 4))) return false

        AppLog.info("Init: CfgMeasSeq…")
        if (!cmdAck(0x9031, intArrayOf(1, 3, 1))) return false

        AppLog.info("Init: StrtMeas (Period=40ms, N=128)…")
        if (!cmdAck(0x9031, intArrayOf(1, 1, CHIRP_PERIOD_10NS, RAD_N))) return false

        AppLog.info("Board init complete")
        return true
    }

    // ── USB transfer helpers ──────────────────────────────────────────────────

    /**
     * Build and send one 2048-byte OUT command frame.
     *
     * Mirrors Python CmdBuild + CmdSend (USB path):
     *   LenData = data.size + 1
     *   word0   = (0<<24) | (LenData<<16) | cmd
     *   frame   = [LenData*4 : u16][word0 : u32][data : u32…][zeros to 2048]
     */
    private fun sendCmd(cmd: Int, data: IntArray): Int {
        val conn    = usbConn ?: return -1
        val lenData = data.size + 1
        val byteLen = lenData * 4
        val word0   = (lenData shl 16) or (cmd and 0xFFFF)

        val frame = ByteBuffer.allocate(USB_OUT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        frame.putShort(byteLen.toShort())
        frame.putInt(word0)
        for (d in data) frame.putInt(d)

        val n = conn.bulkTransfer(epOut, frame.array(), USB_OUT_SIZE, CMD_TIMEOUT_MS)
        // Only log non-measurement commands — 0x9032 fires 80x per frame and floods the log
        if (cmd != 0x9032) AppLog.debug("TX cmd=0x${cmd.hex4()} lenData=$lenData → $n bytes")
        return n
    }

    /** Send command, read ACK. Returns false only on send failure. */
    private fun cmdAck(cmd: Int, data: IntArray): Boolean {
        val sent = sendCmd(cmd, data)
        if (sent <= 0) {
            AppLog.error("sendCmd 0x${cmd.hex4()} failed ($sent)")
            return false
        }
        val echo = readAck()
        if (echo < 0) AppLog.warn("ACK timeout for cmd=0x${cmd.hex4()}")
        return true
    }

    private val ackBuf = ByteArray(256)

    /** Read one ACK/response packet. Returns cmd echo from word0, or -1. */
    private fun readAck(): Int {
        val conn = usbConn ?: return -1
        val n = conn.bulkTransfer(epIn, ackBuf, ackBuf.size, ACK_TIMEOUT_MS)
        if (n < 4) { AppLog.warn("ACK: $n bytes"); return -1 }
        val word0   = ByteBuffer.wrap(ackBuf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val cmdEcho = word0 and 0xFFFF
        val lenData = (word0 shr 16) and 0xFF
        // Suppress 0x9032 ACK noise during streaming — logged at frame level instead
        if (cmdEcho != 0x9032) AppLog.debug("ACK echo=0x${cmdEcho.hex4()} lenData=$lenData ($n bytes)")
        return cmdEcho
    }

    // ── DSP pipeline ──────────────────────────────────────────────────────────

    private fun processFrame(
        adcI: Array<Array<FloatArray>>,
        adcQ: Array<Array<FloatArray>>
    ) {
        val rangeFftN = config.rangeFftSize.coerceAtLeast(RAD_N).nextPow2()

        val rangeSpec = Array(CHIRPS_PER_FRAME) { c ->
            complexFft(adcI[c][0], adcQ[c][0], rangeFftN)
        }

        val dopplerN = CHIRPS_PER_FRAME.nextPow2()
        val rdMag    = Array(rangeFftN / 2) { FloatArray(dopplerN) }
        for (r in 0 until rangeFftN / 2) {
            val cI = FloatArray(CHIRPS_PER_FRAME) { c -> rangeSpec[c][r * 2] }
            val cQ = FloatArray(CHIRPS_PER_FRAME) { c -> rangeSpec[c][r * 2 + 1] }
            val ds = complexFft(cI, cQ, dopplerN)
            for (d in 0 until dopplerN) {
                val re = ds[d * 2]; val im = ds[d * 2 + 1]
                rdMag[r][d] = 10f * log10(re * re + im * im + 1e-12f)
            }
        }

        val rangeResM    = 3e8f / (2f * BW_HZ)
        val lambda       = 3e8f / FC_HZ
        val chirpPeriodS = CHIRP_PERIOD_10NS * 10e-9.toFloat()
        val dopplerResMs = lambda / (2f * CHIRPS_PER_FRAME * chirpPeriodS)

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
        AppLog.debug("Frame $frameIndex: ${objects.size} object(s)  rangeRes=${"%.2f".format(rangeResM)}m")
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
        repeat(bits) { r=(r shl 1)or(v and 1); v=v shr 1 }
        return r
    }

    private data class RawDetection(
        val rangeBin: Int, val dopplerBin: Int, val snrDb: Float,
        val rangeResM: Float, val dopplerResMs: Float
    ) {
        val distanceM: Float get() = rangeBin * rangeResM
        val speedMps:  Float get() {
            val signed = if (dopplerBin > 32) dopplerBin - 64 else dopplerBin
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
                for (dr in -(tr+g)..(tr+g)) for (dd in -(tr+g)..(tr+g))
                    if (abs(dr)>g || abs(dd)>g) { sum+=rdMag[r+dr][d+dd]; cnt++ }
                val noise = if (cnt>0) sum/cnt else 0f
                if (cell-noise >= thr)
                    res.add(RawDetection(r, d, cell-noise, rangeResM, dopplerResMs))
            }
        }
        return res
    }

    private fun classifyAndTrack(detections: List<RawDetection>): List<DetectedObject> {
        val now = System.currentTimeMillis()
        val results = mutableListOf<DetectedObject>()
        for (det in detections) {
            val dist=det.distanceM; val speed=abs(det.speedMps); val snr=det.snrDb
            if (dist>config.maxRangeM||speed>config.maxSpeedMps||snr<config.minSnrDb) continue
            val cls = when {
                dist>10f&&speed>15f         -> ObjectClass.AERIAL_VEHICLE
                speed>5f&&snr>20f           -> ObjectClass.GROUND_VEHICLE
                speed<1f&&snr>25f           -> ObjectClass.GROUND_VEHICLE
                speed in 0.3f..4f&&dist<20f -> ObjectClass.HUMAN
                speed in 0.1f..8f&&snr<20f  -> ObjectClass.ANIMAL
                else                        -> ObjectClass.UNKNOWN
            }
            val conf = when(cls) {
                ObjectClass.HUMAN          -> (1f-dist/20f).coerceIn(0.4f,0.95f)
                ObjectClass.GROUND_VEHICLE -> (snr/40f).coerceIn(0.5f,0.99f)
                else                       -> 0.6f
            }
            val tid = tracker.entries
                .minByOrNull{(_,p)->abs(p.distanceM-dist)+abs(p.speedMps-det.speedMps)}
                ?.takeIf{(_,p)->abs(p.distanceM-dist)<2f}?.key ?: nextTrackId++
            val obj = DetectedObject(
                trackId=tid, objectClass=cls, distanceM=dist,
                azimuthDeg=0f, elevationDeg=0f, speedMps=det.speedMps,
                direction=Direction.AHEAD, snrDb=snr, confidence=conf, timestampMs=now
            )
            tracker[tid]=obj; results.add(obj)
        }
        tracker.entries.filter{(_,v)->now-v.timestampMs>2000}
            .map{it.key}.forEach{tracker.remove(it)}
        return results.sortedBy{it.distanceM}
    }

    fun sendConfig(cfg: TinyRadConfig) { config = cfg }
}

private fun Int.hex2() = and(0xFF).toString(16).padStart(2,'0').uppercase()
private fun Int.hex4() = and(0xFFFF).toString(16).padStart(4,'0').uppercase()
private fun Int.nextPow2(): Int {
    var v=this; if(v<=1) return 1; v--
    v=v or(v shr 1);v=v or(v shr 2);v=v or(v shr 4);v=v or(v shr 8);v=v or(v shr 16)
    return v+1
}
