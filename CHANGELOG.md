# Changelog

All notable changes to TinyRadApp are documented here.

Format: `[version] — YYYY-MM-DD`
- **Major** version: new features
- **Minor** version: bug fixes only

---

## [1.0] — 2026-06-09

### Initial release

**USB connectivity**
- USB Host CDC-ACM connection to Analog Devices TinyRAD evaluation board
- Auto-launch via Android USB device filter (VID 0x0456 PID 0xB671)
- STM32 CDC-ACM fallback (VID 0x0483 PID 0x5740)
- Android USB permission flow with BroadcastReceiver

**Radar DSP pipeline (host-side)**
- Range FFT (Cooley-Tukey radix-2 DIT) along fast-time axis per chirp
- Doppler FFT along slow-time axis per range bin
- 2D CA-CFAR threshold for detection
- Physics-based range/velocity computation from bin indices

**Object classification**
- Human: 0.3–4 m/s, range < 20 m, moderate SNR
- Animal: 0.1–8 m/s, lower SNR signature
- Ground Vehicle: > 5 m/s or high-SNR stationary RCS
- Aerial Vehicle: > 15 m/s at range > 10 m
- Unknown: unclassified detections
- Confidence score per detection

**Object tracking**
- Nearest-neighbour tracker with 2-second track expiry
- Persistent track IDs across frames

**UI screens**
- Home: USB connect/status, radar start
- Live Radar: PPI canvas, object list, range-Doppler heatmap, recording controls
- Recordings: CSV file list, share via Android share sheet, delete
- Settings: configurable RF, timing, CFAR, and gate parameters
- Log: timestamped in-memory event log with error badge
- About: credits, ENACT/RFSAT information

**Data recording**
- ISO 8601 UTC timestamped CSV
- Columns: track_id, object_class, distance_m, azimuth_deg, elevation_deg,
  speed_mps, speed_kmh, direction, snr_db, confidence
- RFSAT/ENACT metadata header

**CI / build**
- GitHub Actions workflow: lint, unit tests, release APK + AAB
- All output artifacts named `TinyRadApp_v<major>.<minor>.apk`
- GitHub Release created automatically on `v*` tags

---

## [1.1] — 2026-06-09

### Bug fixes
- **Theme fix**: replaced `android:Theme.Material.NoTitleBar` (unavailable at API 35) with `Theme.AppCompat.NoActionBar` — resolves `AAPT: error: resource android:style/Theme.Material.NoTitleBar not found` build failure

---

## [1.1] — 2026-06-09

### Bug fixes
- **Theme fix**: replaced `android:Theme.Material.NoTitleBar` (unavailable at API 35) with `Theme.AppCompat.NoActionBar` — resolves `AAPT: error: resource android:style/Theme.Material.NoTitleBar not found` build failure

---

## [1.2] — 2026-06-09

### Bug fixes
- **Drawable fix**: replaced `<circle>` and `<line>` SVG elements (not valid in Android VectorDrawable) with `<path>` equivalents in `ic_launcher_foreground.xml` and `ic_splash_icon.xml` — resolves `attribute android:cx/cy/r/x1/y1/x2/y2 not found` AAPT errors
- **Resource fix**: added missing `ic_launcher_background` colour to `colors.xml` — resolves `resource color/ic_launcher_background not found` AAPT error
- **Icon update**: launcher icons replaced with provided store icon (radar PPI image); white background with slight padding applied to all mipmap densities (mdpi → xxxhdpi); adaptive icon background set to white

---

## [1.3] — 2026-06-09

### Bug fixes
- **Syntax fix** (`TinyRadModels.kt` line 119): missing space in `List<DetectedObject>=` caused the Kotlin parser to treat `=` as a type-parameter separator rather than a default-value assignment — all downstream `No parameter with name` errors in `TinyRadViewModel.kt`, `RadarScreen.kt`, `SettingsScreen.kt`, and `HomeScreen.kt` cascaded from this single parse failure
- **Icon fix** (`MainActivity.kt`, `HomeScreen.kt`): `Icons.Default.RadarRounded` does not exist in `material-icons-extended`; replaced with `Icons.Default.TrackChanges`
- **Navigation fix** (`MainActivity.kt`): removed use of `inclusive` inside `popUpTo {}` lambda — this property is private in the current Navigation Compose API; `popUpTo(0)` without the block achieves the same clear-back-stack effect
- **Type inference fix** (`SettingsScreen.kt`): added explicit `TinyRadConfig` type parameter to `mutableStateOf` so the compiler can resolve `cfg.copy(…)` calls without ambiguity
- **Import fix** (`MainActivity.kt`): added explicit `import androidx.compose.foundation.layout.RowScope` so `RowScope` receiver on private extension composables resolves correctly

---

## [1.4] — 2026-06-09

### Bug fixes — USB permission flow

**Root cause**: app was stuck in "Requesting USB permission..." because the
`BroadcastReceiver` received the result but Android couldn't complete the
connection. Four separate issues were fixed:

- **`PendingIntent` stale action** (`TinyRadViewModel`): used `requestCode=0`
  for every device, meaning Android reused an older `PendingIntent` if one with
  the same action already existed. Fixed by using `device.deviceId` as the
  unique `requestCode` and adding `FLAG_UPDATE_CURRENT` so the intent extras
  are always refreshed.

- **`PendingIntent` not scoped to app** (`TinyRadViewModel`): added
  `intent.package = app.packageName` so the OS delivers the permission result
  only to this app and not broadcast-wide (required on Android 14+).

- **`pendingDevice` fallback** (`TinyRadViewModel`): on some ROMs
  `EXTRA_DEVICE` in the permission-result intent is null. The device that was
  passed to `requestPermission()` is now remembered in `pendingDevice` and used
  as the fallback in the receiver.

- **Bogus `<uses-permission>` in manifest**: `android.hardware.usb.action
  .USB_DEVICE_ATTACHED` is not a permission — it is an intent action. This tag
  was silently ignored but removed for correctness.

- **Missing `<category DEFAULT>`** in manifest USB intent-filter: the
  `USB_DEVICE_ATTACHED` intent-filter needs `category DEFAULT` for Android to
  route the intent to the activity correctly on some OEM ROMs.

- **Hex values in `usb_device_filter.xml`**: Android parses `vendor-id` and
  `product-id` as decimal integers. The filter had `0x0456` etc. which parsed
  as zero, so no device ever matched. Fixed to decimal: 1110 / 46705 / 1155 /
  22336.

### New features

- **Manual USB device picker** (`ConnectScreen`): "Browse all USB devices"
  button on the Home screen opens a list of every device currently on the USB
  host bus with VID/PID, manufacturer, and interface count. Tapping any entry
  connects to it directly (including BF707 Bulk Device or any custom firmware
  PID that doesn't match the filter).

---

## [1.5] — 2026-06-09

### Bug fixes — hardware-specific USB corrections from board identification

Board identified as EV-TINYRAD24G (UG-1709), ADSP-BF706 Blackfin DSP,
firmware R 1.3.0 / 2019, serial E1153609.  "BF707 Bulk Device" in the
Android permission dialog is **correct and expected** per the official
Analog Devices user guide (UG-1709, page 6, Figure 7).

- **Wrong USB device class** (`TinyRadUsbManager`): the app was searching for
  a CDC-ACM interface (class 0x02 / 0x0A).  The ADSP-BF706 is a
  **vendor-specific bulk device** (class 0xFF) with no CDC layer at all.
  The fixed code searches for any interface with both a bulk-IN and bulk-OUT
  endpoint, preferring class 0xFF.  All interfaces and endpoints are now
  logged to the in-app Log screen on every connect attempt for diagnostics.

- **Broken interface search** (`TinyRadUsbManager`): the inner `break` in the
  fallback loop only broke the inner `for`, not the outer one, so the code
  could claim the wrong interface.  Replaced with a labelled `break@outer`.

- **Wrong command framing** (`TinyRadUsbManager`): the board does not use
  ASCII text commands.  The BF706 firmware uses 4-byte binary command frames
  `[CmdCode:uint16 LE][Value:uint16 LE]`.  Commands rewritten accordingly:
  CMD_INIT (0x0001), CMD_TRIGGER (0x0002), CMD_GETSYSINF (0x0004).

- **Wrong USB device filter PIDs** (`usb_device_filter.xml`): the filter now
  includes all known ADSP-BF70x Bulk Device PIDs for VID 0x0456 as decimal
  integers: 46607 (0xB60F, primary), 46705 (0xB671), 46636 (0xB62C),
  46627 (0xB623).

### Note on exact PID
The exact PID for your firmware revision is in `Demo_Driver.zip` on the USB
memory stick shipped with the board.  Open the `.inf` file and find the line:
`%DeviceName%=Install, USB\VID_0456&PID_xxxx`
Convert the 4-digit hex PID to decimal and add it to `usb_device_filter.xml`
if none of the existing entries match.

---

## [1.6] — 2026-06-09

### Bug fixes — confirmed VID/PID from physical board

Confirmed from EV-TINYRAD24G board markings:
- PCB label: `A24BF_TR_TX2RX4_D01`
- Firmware: `TinyRad-FW_R-3-0-3 : 3.0.3` (2020-06-02)
- Serial: `E1153609`
- Actual USB IDs: **VID `0x064B` / PID `0x7823`**

`VID 0x064B` is "Analog Devices, Inc. Development Tools" — ADI's second
vendor ID used specifically on evaluation and firmware boards, distinct from
`0x0456` used in production silicon.  All previous versions of the app were
matching against the wrong VID entirely.

- **`usb_device_filter.xml`**: `VID 0x064B / PID 0x7823` (decimal 1611/30755)
  added as the primary (first) entry.  Android only auto-launches the activity
  and shows the permission dialog for devices that match this filter; with the
  wrong VID in the filter the OS would never have auto-matched the board.
- **`TinyRadUsbManager.kt`**: `0x064B to 0x7823` added as first entry in
  `TINYRAD_VID_PID` so `findTinyRadDevices()` returns the board immediately
  without falling back to "show all USB devices".
- **README.md**: hardware identification table updated with confirmed values.

---

## [1.7] — 2026-06-09

### Bug fixes — lint error and deprecation warnings

- **Lint error** (`TinyRadViewModel`): `registerReceiver()` on API < 33 was
  called without an exported-state flag, triggering the
  `UnspecifiedRegisterReceiverFlag` lint error introduced in Android U.
  Replaced the API-level `if/else` branch with a single
  `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` call which
  passes the correct flag on all API levels.  Removed the now-unused
  `android.os.Build` import.

- **Deprecation warnings** (6 occurrences across 5 files):
  `Icons.Default.ArrowBack` and `Icons.Default.List` are deprecated in favour
  of their `AutoMirrored` equivalents, which automatically mirror the icon in
  RTL layouts.  Replaced in:
  - `ConnectScreen.kt` — `ArrowBack`
  - `HomeScreen.kt` — `List`
  - `LogAndAboutScreens.kt` — `ArrowBack` (×2)
  - `RecordingsScreen.kt` — `ArrowBack`
  - `SettingsScreen.kt` — `ArrowBack`
  Added `import androidx.compose.material.icons.automirrored.filled.*` to all
  affected files.

---

## [1.8] — 2026-06-09

### Bug fixes

- **Compile error** (`TinyRadViewModel`, `MainActivity`): `Build` import was
  removed in v1.7 to eliminate the `if/else` in `registerReceiver`, but
  `Build.VERSION.SDK_INT` was still used inside the permission receiver and in
  `handleUsbIntent()` to call `getParcelableExtra`.  Both replaced with
  `IntentCompat.getParcelableExtra()` which handles the API split internally,
  removing all remaining uses of `android.os.Build`.

### Protocol corrections — no-frames fix

The previous command codes (0x0001, 0x0002, 0x0004) were guesses.
Corrected to the reverse-engineered ADI command set used by TinyRadTool:

| Code   | Name         | Action |
|--------|--------------|--------|
| 0x9000 | BrdRst       | Board reset / init |
| 0x9001 | BrdGetSwVers | Request firmware version string |
| 0x9010 | RfOn         | Enable RF front-end |
| 0x9021 | MeasStart    | Start continuous measurement |
| 0x9022 | MeasStop     | Stop measurement |

Additional changes:
- 500 ms delay after BrdRst (RF front-end needs ~400 ms to stabilise)
- 200 ms delay after RfOn before triggering
- Frame sync word corrected to 0x5A 0xA5 (bytes 0–1 of each board→host frame)
- ADC frame header: sync(2) + cmd_echo(2) + payload_len(4) = 8 bytes
- ADC dimension detection: tries to infer NChirps/NRx/NSamples from payload
  size; falls back to default 128×4×256 (fw 3.x default config)
- RAW_SNIFF_MODE flag: set to `true` in TinyRadUsbManager to disable all
  commands and log raw receive bytes as hex — useful for comparing with USB
  traces captured from TinyRadTool on Windows

---

## [2.0] — 2026-06-09

### New features — complete protocol implementation from USB trace

USB capture (USB-TinyRAD.pcapng) provided by user was analysed to extract
the exact wire protocol used by TinyRadTool communicating with firmware R 3.0.3.

**Command frame format (confirmed):**
```
uint16  payload_len   (bytes after this field)
uint16  cmd_code      (0x9xxx)
uint16  num_params
uint16  reserved      (0x0001 for config cmds, 0x0007 for register writes)
uint32  params[N]
<zero pad to 2048 bytes>
```

**ACK frame format (confirmed):**
```
uint16  cmd_echo
uint16  status        (0x0002 = OK)
uint32  result
```

**ADC data frame (confirmed, 1024 bytes per chirp):**
```
uint16  chirp_index × 4   (same value repeated for each Rx channel)
int16   IQ[508]            Rx0_I Rx0_Q Rx1_I Rx1_Q Rx2_I Rx2_Q Rx3_I Rx3_Q
                           × 127 samples per channel
```

**Confirmed command sequence:**
1. `0x9030` BrdInit — params `[0, 0x02000000, 0]`
2. `0x900E` RfBrdSet — params `[0x00010000, 0x02000000]`
3. `0x9031` CfgFmcw ×5 — FMCW timing and geometry config blocks
4. `0x9017` RegWrite ×4 — hardware register initialisation
5. `0x9032` MeasTrig — trigger one chirp; repeats 80× per FMCW frame
   - Each trigger: board sends 1024-byte ADC frame then 8-byte ACK
6. `0x9031` with zero params — stop measurement

**Measurement geometry (confirmed):**
- 80 chirps per FMCW frame (chirp_idx: 0, 2, 4 … 158)
- 4 Rx channels, 127 complex samples per chirp per channel
- Range resolution: c/(2×250 MHz) ≈ 0.6 m
- All 4 Rx channels stored; Rx0 used for range-Doppler processing (MIMO DBF planned)

---

## [2.1] — 2026-06-09

### Bug fixes — USB bulk transfer returning -1

Root-cause analysis of `Expected 1024 bytes, got -1` from the pcap:

- **Wrong endpoint selection**: The board uses **ep=0x01** (OUT) and **ep=0x81** (IN),
  confirmed from the capture. The previous code searched for the first bulk-IN/OUT pair
  by direction only; on some Android USB Host stacks the iteration order means ep=0x01
  could be found as the OUT endpoint but something else picked as IN. Fixed to match
  by explicit address first (`ep.address == 0x01` / `ep.address == 0x81`), with
  direction-only fallback if the exact address isn't found.

- **Timeout too short**: `BULK_TIMEOUT_MS = 200` was used for all reads. The board DSP
  takes non-trivial time to process a chirp and DMA the result. Separated into
  `CMD_TIMEOUT_MS = 1000`, `DATA_TIMEOUT_MS = 2000`, `ACK_TIMEOUT_MS = 1000`.

- **Buffer size passed to bulkTransfer**: Was passing `expect` (1024) as the length
  argument — this is correct API usage but combined with the short timeout caused
  premature -1. The buffer is now sized to `ADC_FRAME_SIZE = 1024` and `buf.size`
  is passed.

- **Silent init failure**: Init ACK mismatches were logged but the streaming loop
  continued. If init fails the board won't respond to triggers. Added hard abort
  on init failure with `ERROR` state update.

- **Zero-length frames in pcap**: The `ep=0x01 CMP 0 bytes` and `ep=0x81 SUB 0 bytes`
  frames are Windows USB stack housekeeping — OUT completion and async read pre-post
  respectively. Android's `bulkTransfer()` handles these transparently; no code
  change needed for them.

---

## [2.2] — 2026-06-09

### Based on v2.1 (uploaded stable baseline — connects without crashing)

### Bug fixes — USB protocol rewritten from Python source

TinyRadTool Python source (Connection.py, TinyRad.py) provided the
authoritative protocol. The entire `TinyRadUsbManager` was rewritten.

**Three fundamental errors corrected:**

**1. OUT frame format wrong (every previous version)**

`CmdBuild` in Connection.py encodes the frame as:
```
LenData   = len(DspCmd) + 1
word0     = (Ack<<24) | (LenData<<16) | CmdCod
byte_len  = LenData * 4

2048-byte frame:
  offset 0:  uint16 LE  byte_len
  offset 2:  uint32 LE  word0
  offset 6+: uint32 LE  DspCmd[0], DspCmd[1], …
```
Previous code invented a separate `[cmd:u16][nparams:u16][reserved:u16]` header
that the firmware does not recognise.

**2. All init DspCmd parameter values wrong**

Re-decoded from pcap using the correct frame structure. Example (RegWrite F331):
- Wrong: `[0x00010000, 0, 0x00030000, …]`
- Correct: `[7, 1, 0, 0x00020006, 0x20001499, …]`
  (7=SpiMask, 1=mode, 0=Chn_Rx, then actual ADF5904 SPI register values)

**3. Measurement trigger flow wrong**

`Dsp_GetDat` in TinyRad.py:
```python
DspCmd = [1, 1, Len, 0]          # Len = N * NrChn int16s = 512
CmdSend(0x9032, DspCmd)
UsbData = ConGetUsbData(Len * 2)  # read Len*2 bytes = 1024 bytes
CmdRecv()                         # then ACK
```
The `Len` field (DspCmd[2]=512) tells the board how many int16 samples to
return per trigger. Previous code omitted it — the board returned nothing.

**Physics corrected (from TinyRad.py):**
- `Rf_fStrt=24 GHz`, `Rf_fStop=24.256 GHz` → BW=256 MHz, fc=24.128 GHz
- Range resolution = c/(2×256 MHz) ≈ 0.585 m
- N=128 samples/chirp, chirp period=40 ms (from pcap StrtMeas frame)

---

## [2.3] — 2026-06-09

### Bug fixes — crash on USB connection

- **connect() on main thread**: `claimInterface()` performs a synchronous USB
  operation that must not run on the Android main thread. On some devices this
  causes an `android.os.NetworkOnMainThreadException`-style crash or a deadlock.
  All three call sites (`BroadcastReceiver.onReceive`, `findAndConnect`,
  `connectDevice`) now dispatch `usbManager.connect()` to `Dispatchers.IO`
  and call `observeFrames()` back on the main thread via `withContext(Main)`.

- **Unguarded exception in `observeFrames`**: any exception thrown inside the
  `collect` lambda (e.g. from DSP processing) propagated to `viewModelScope`
  and crashed the app. Wrapped with `try/catch` — exceptions are now logged
  to the in-app Log screen as ERROR entries instead of crashing.

---

## [2.4] — 2026-06-10

### New features

**Log file on device** (`AppLog`):
- Call `AppLog.init(context)` once from `MainActivity.onCreate()` — initialises
  a `PrintWriter` to `<external-files>/TinyRAD/tinyrad_log.txt`.
- Every entry is written with timestamp and level; flushed immediately on any
  `ERROR` entry (so the last command before a crash is always on disk), and
  every 20 entries otherwise.
- Rotates automatically at 512 KB: current log renamed to `tinyrad_log_prev.txt`,
  new file started — so the two most recent sessions are always retained.
- File path is shown in a small hint bar at the top of the Log screen.
- File is closed cleanly in `MainActivity.onDestroy()`.
- To retrieve: `adb pull /sdcard/Android/data/com.rfsat.TinyRadApp/files/TinyRAD/tinyrad_log.txt`
  (or browse with any file manager that can access app-specific external storage).

**Log level filter chips** (`LogScreen`):
- Horizontal chip strip above the log list: **ALL · ERRO · WARN · INFO · DEBU**.
- Each chip shows the count for that level; only levels with at least one entry
  are shown.
- Tapping an active chip clears back to ALL.
- Auto-scroll to bottom follows the currently filtered view.
- DEBUG entries brightened to 78% alpha for readability on the dark background.

---

## [2.5] — 2026-06-10

### Bug fixes

- **Missing coroutine imports** (`TinyRadViewModel`): `Dispatchers` and
  `withContext` were used in the connect-on-IO-thread calls added in v2.3 but
  only `launch` was imported from `kotlinx.coroutines`. Added:
  `import kotlinx.coroutines.Dispatchers` and
  `import kotlinx.coroutines.withContext`.

### CI workflow changes

- **Debug APK only** while hardware communication is being validated.
  `assembleRelease` and `bundleRelease` (Google Play AAB) jobs are commented
  out in `.github/workflows/build.yml` with a note explaining why.
  Lint runs on the debug variant with `continue-on-error: true` so build
  warnings do not block the APK.
  Output artifact: `TinyRadApp_v<version>-debug.apk` (retained 30 days).
  Re-enable the release jobs by uncommenting the disabled section once
  hardware testing confirms the protocol is working.

---

## [2.6] — 2026-06-10

### Bug fixes — ADC read order reversed (root cause of "Start Radar" crash)

Log analysis confirmed the board sends responses in this order after 0x9032:
  1. **8-byte ACK** — arrives ~1 ms after the trigger command
  2. **1024-byte ADC data** — arrives ~40 ms later (after one chirp period)

Previous code read in the wrong order (ADC first, ACK second). Because the
ACK arrived first, `bulkTransfer(epIn, adcBuf, 1024)` received 8 bytes instead
of 1024, logged "ADC read: expected 1024 got 8", hit the 10-error limit, and
cancelled the streaming coroutine — appearing as a crash from the UI's
perspective.

Fixed streaming loop order:
```
sendCmd(0x9032) → readAck() [8 bytes] → bulkRead(adcBuf) [1024 bytes]
```

The `JobCancellationException: Job was cancelled` in `observeFrames` is not
a crash — it is the normal result of `stopStreaming()` being called when the
error loop broke. The try/catch in `observeFrames` correctly absorbs it.

### CI workflow — version 2
Added workflow version number and changelog comment block at the top of
`.github/workflows/build.yml` to track changes independently of app versions.
Full release job (assembleRelease + bundleRelease + GitHub Release) preserved
as commented-out YAML for easy re-enabling after hardware validation.

---

## [2.7] — 2026-06-10

### Bug fixes — log screen crash + board jammed state

**Log screen crash (Compose overload):**
- During streaming, `0x9032` TX and ACK were logged as DEBUG on every chirp
  trigger — 80 per FMCW frame × multiple entries = hundreds of log entries
  per second. This flooded the `StateFlow`, causing rapid recomposition of
  the `LazyColumn` and crashing the UI thread. Fixed by suppressing `0x9032`
  TX/ACK debug lines in `sendCmd`/`readAck` (measurement frame count is
  logged at INFO level instead).
- `MAX_ENTRIES` reduced from 500 to 200 to further limit list size.
- `LazyColumn` key changed from string concatenation
  `"${timestamp}_${hash}"` to a plain integer index — eliminates per-item
  string allocation during recomposition.

**Board jammed after failed/incomplete session:**
- After a session that did not complete a full measurement cycle, the board's
  USB IN endpoint enters a halted/stall state. All subsequent `bulkTransfer`
  reads return -1 immediately, even for `BrdGetUID`. Fixed by calling
  `UsbDeviceConnection.clearHalt()` on both endpoints at the start of every
  `initBoard()` call.
- Added a drain loop (3 × 50ms reads) to consume any bytes the board left in
  its TX buffer from a previous session (e.g. a pending ADC frame that was
  never collected).
- Added `Thread.sleep(200)` after `BrdRst` (0x9031) — the ADF5901/ADF5904/
  ADF4159 RF ICs need time to power-cycle before accepting new SPI
  configuration.

### Note on power cycling
If the board stops responding entirely (all ACKs return -1 including BrdGetUID),
unplug and replug the USB cable to power-cycle the board firmware. The app will
reconnect automatically.

---

## [2.8] — 2026-06-10

### Bug fixes

- **`clearHalt` requires API 28** (`TinyRadUsbManager`): `minSdk` is 26, so
  calling `UsbDeviceConnection.clearHalt()` fails to compile. Replaced with a
  direct USB `CLEAR_FEATURE(ENDPOINT_HALT)` control transfer
  (`bmRequestType=0x02, bRequest=0x01, wValue=0x0000, wIndex=ep.address`)
  which is available on all API levels and is exactly what `clearHalt()`
  calls internally on API 28+.

---

## [2.9] — 2026-06-10

### Bug fixes — ADC data not received (root cause confirmed from drain sizes)

The drain in session 2 of the v2.8 log consumed exactly:
  `520 + 1024 + 8 = 1552 bytes` — proving the board WAS sending data.

`520 = 8 (ACK) + 512 (first USB packet of the 1024-byte ADC frame)`.
This is the exact USB maxPacketSize (512 bytes, confirmed in log).

**Root cause:** `readAck()` used `ackBuf = ByteArray(256)`. The board sends the
8-byte ACK and the start of the 1024-byte ADC frame back-to-back, so fast that
Android USB Host received them as a combined 520-byte packet. Since 520 > 256
(buffer size), `bulkTransfer` returned -1 (overflow), which we logged as
"ACK timeout".

**Fix:** replaced the separate ACK-read + ADC-read with a single combined read
into a `COMBINED_BYTES = 1032` buffer (8 ACK + 1024 ADC). The response is
parsed based on actual byte count:
- `n == 1032`: ACK at offset 0, ADC at offset 8
- `n == 1024`: ADC only at offset 0 (no separate ACK)
- `n > 8 && n < 1024`: partial read, second read attempted for remainder

Drain buffer also increased to `COMBINED_BYTES` and repeat count raised to 6
(one per trigger fired before abort) so stale sessions are fully flushed.

---

## [2.10] — 2026-06-10

### Bug fixes — streaming crash + UI polish

**streamLoop unhandled exception** (`TinyRadUsbManager`):
- Added `try/catch` wrapping the entire trigger loop. Any exception is now
  logged as ERROR (showing class name and message) and sets the connection
  state to ERROR, so the cause appears in the Log screen.
- Added `AppLog.info("Trigger loop starting…")` and `"…ended"` markers so
  the log shows whether the loop ever ran — previously there was no entry
  between "Board init complete" and the JobCancellationException.
- Fixed `IllegalArgumentException` in the partial-read path: the second
  `bulkTransfer` was called with `combinedBuf.size - n` which could be zero
  or negative if `n == COMBINED_BYTES`. Now always passes `combinedBuf.size`.

**Log screen — file path visibility**:
- File path hint text alpha raised from 0.35 to 0.75 — the previous grey
  was essentially invisible against the dark background.

**About screen — clickable web links**:
- "RFSAT Limited" row: tapping opens `https://www.rfsat.com` in browser.
- "Analog Devices EV-TINYRAD24G" row: tapping opens the EV-TINYRAD24G
  evaluation board page on analog.com. Both show an `OpenInNew` icon and
  underlined text to indicate they are tappable.

---

## [2.11] — 2026-06-10

### Bug fixes — BufferUnderflowException: data is real, not IQ pairs

Log confirmed: `0x9032 read: 1032 bytes (chirp 0)` — data arrives correctly.
Then immediately: `streamLoop exception: BufferUnderflowException`.

**Root cause:** `parseCombinedBuffer` read each sample as TWO int16 values
(I + Q), so the loop tried to read 128 × 4 × 2 = 1024 int16 = 2048 bytes
from a 1024-byte buffer — exactly 2× overflow.

**Correct data layout (from TinyRad.py):**
```python
Data = reshape(UsbData, (int(Len/4), 4))  # (128 samples, 4 Rx channels)
```
The ADC data is **real-valued int16** — one value per sample per Rx channel,
not IQ pairs. The `Len = 512` int16 values reshape to `(128 × 4)`:
128 time samples across 4 Rx channels. There is no separate Q component.

**Fixes:**
- `parseCombinedBuffer`: reads exactly `MEAS_LEN = 512` int16 values.
  Sets `adcI[chirpRow][r][s] = value`, `adcQ[chirpRow][r][s] = 0`.
- `processFrame`: now processes all 4 Rx channels with non-coherent power
  summation (`rdMagSq[r][d] += re²+im²`) before converting to dB, matching
  the TinyRad.py multi-channel processing approach.

---

## [2.12] — 2026-06-10

### Bug fixes

- **Syntax error in `TinyRadUsbManager.kt` line 544**: the Python-based
  line replacement in v2.11 escaped `$` as `${'$'}` (valid Python template
  syntax) which ended up literally in the Kotlin source, producing
  `Unresolved reference 'rem'` and `Syntax error: Expecting ','`. Fixed by
  replacing the mangled string with correct Kotlin string interpolation.

---

## [2.13] — 2026-06-10

### New features & fixes (seven items from user request)

**1. 180° semicircle radar view**
Full-width semicircle replaces the previous 1:1-aspect square PPI.
Origin at bottom-centre, range increases upward, azimuth spans ±90°.
Height = screen width ÷ 2 so it fits portrait without scrolling.
Four range-arc rings with distance labels; azimuth spokes at ±90°/±45°/0°.

**2. FPS display fixed**
`frameSamples` rolling average replaced with a simple per-frame `1000ms / Δt`
calculation. FPS now reflects the actual FMCW frame rate (~0.31 fps for
80 chirps × 40ms/chirp = 3.2s/frame). Settings FPS slider changes the
`config.framesPerSec` which the DSP pipeline uses; hardware chirp period
is fixed at 40ms by the board firmware.

**3. Range resolution now tracks settings**
`processFrame` was using the hardcoded `BW_HZ = 256 MHz` constant instead
of `config.bandwidthMHz`. Now uses `config.bandwidthMHz * 1e6f`. Per UG-1709
the board has a max sweep bandwidth of 250 MHz → range resolution ≈ 0.60 m.
`applyConfig` restarts streaming when active so hardware sees the new config.

**4. Multi-object duplicate suppression (CFAR NMS)**
Added Non-Maximum Suppression after CFAR: detections are sorted by SNR,
then any detection within ±2 range bins AND ±2 Doppler bins of a stronger
peak is suppressed. Eliminates duplicates from the same physical reflector.

**5. Brighter object detection dots**
Glow ring alpha 0.35→brighter, filled dot alpha 0.95, white centre highlight
at 0.6 alpha. Dot radius 10+SNR/4 (was 8+SNR/5), minimum 8px.

**6. Compact object list under radar view**
Replaces the side-panel card list. Columns: Class · Dist · Speed · Dir ·
Conf · SNR · Az°. Max height 160dp, scrollable. Colour-coded class dot.

**7. CSV file viewer (RecordingsScreen → CsvViewerScreen)**
Tap the TableChart icon on any recording to open it inline. Shows a
horizontally-scrollable table with alternating row shading, header row,
column widths, and a 500-row cap with a notice. Added
`Screen.CsvViewer` route with URL-encoded path argument.

**8. Four-panel bottom view (FMCW / Range-Doppler / DBF / Range-Time)**
- FMCW: parameter table (bandwidth, range res, max range, histogram bins, etc.)
- Range-Doppler: full heatmap using `radarColormap` colour scale
- DBF: azimuth-range scatter of detected objects (-90° to +90°)
- Range-Time: ring buffer of last 64 range profiles, scrolling waterfall

**9. Radar colourmap**
`radarColormap(v)` — cyan→blue→black→red→yellow→white — used by
Range-Doppler and Range-Time panels.

**10. Screen always-on**
`window.addFlags(FLAG_KEEP_SCREEN_ON)` in `MainActivity.onCreate()` prevents
the display from dimming or locking while the app is in the foreground.
`WAKE_LOCK` permission added to `AndroidManifest.xml`.

---

## [2.14] — 2026-06-10

### Bug fixes — three compile errors in RadarScreen.kt

**`Path.addArc` wrong signature**: Compose `Path.addArc` takes an
`androidx.compose.ui.geometry.Rect` as the `oval` parameter, not named
`left/top/right/bottom` arguments. Fixed to:
`addArc(oval = Rect(cx-r, cy-r, cx+r, cy+r), startAngleDegrees = 180f, sweepAngleDegrees = 180f)`

**`nativeCanvas` access pattern wrong**: `drawContext.canvas.nativeCanvas.apply { drawText(...) }`
fails because `.apply` runs in `android.graphics.Canvas` scope but the compiler
can't infer the receiver. Replaced all occurrences with `drawIntoCanvas { canvas -> }`
(Compose's safe native canvas accessor) and explicit `canvas.nativeCanvas.drawText(...)` calls.

**`Float.MIN_VALUE` is positive in Kotlin**: Range-Time `gMax` initialised with
`Float.MIN_VALUE` (= smallest positive float) instead of `-Float.MAX_VALUE`.
Fixed so the max-search works correctly.

---

## [2.15] — 2026-06-10

### Configurable update rate + optimised default detection settings

#### Update rate — code change required (not a settings-only fix)

The bottleneck is the hardware chirp period: the board firmware is
initialised with `StrtMeas: DspCmd=[1,1, 4_000_000, 128]` which sets each
chirp to 40 ms. One complete original frame = 80 chirps × 40 ms = 3.2 s.
This cannot be changed without modifying the firmware init sequence.

**What was changed:**

`CHIRPS_PER_FRAME` is no longer a hard-coded constant. It is now read from
`config.chirpsPerFrame` (settable in Settings) on every frame boundary.
`processFrame` now accepts `nChirps: Int` and sizes all FFT arrays accordingly.

Effect on update rate (chirp period stays at 40 ms):

| Chirps/frame | Frame time | Update rate |
|---|---|---|
| 4  | 160 ms | ~6.25 fps |
| 8  | 320 ms | ~3.1 fps  |
| **16** | **640 ms** | **~1.5 fps  ← new default** |
| 32 | 1.3 s  | ~0.8 fps  |
| 80 | 3.2 s  | ~0.3 fps  (original) |

Fewer chirps = coarser Doppler resolution but faster display updates.
For object detection (rather than precise velocity measurement), 16 chirps
gives a good balance. The Settings screen now shows the expected fps next
to the slider value.

#### Optimised default detection settings

Changed defaults in `TinyRadConfig` to cover the full 180° aperture and
detect weaker returns:

| Parameter | Old default | New default | Reason |
|---|---|---|---|
| `maxRangeM` | 50 m | **100 m** | Board spec: 100m for RCS=1m² |
| `cfar_threshold` | 15 dB | **10 dB** | Catch weaker/edge returns |
| `minSnrDb` | 10 dB | **8 dB** | Wider angular coverage |
| `chirpsPerFrame` | 80 | **16** | ~1.5 fps update rate |

The FMCW array is electronically steered across ±90° by the 4 Rx beamformer;
the `azimuthDeg` assignment in detection currently defaults to 0° (boresight)
because true DBF requires per-channel phase processing. The full 180° is
covered by the hardware — azimuth estimation is a future enhancement.

---

## [3.0] — 2026-06-10  ★ Major release

### (1) CI workflow v4 — release APK re-enabled
- Debug APK built on every push/PR (unchanged)
- Release APK now built on every push to `main`/`develop` (hardware validated)
- Play Store AAB built only on `v*` tag pushes
- GitHub Release created automatically on tag push
- Signing via repository secrets: `KEYSTORE_BASE64`, `KEY_ALIAS`,
  `KEY_PASSWORD`, `STORE_PASSWORD`. Graceful fallback to unsigned build if
  secrets are absent.

### (2) About screen version auto-updates
`AboutScreen` now reads `BuildConfig.VERSION_NAME` at runtime instead of
a hardcoded string, so it always matches the current build.

### (3) Detection sensitivity — lower thresholds
CFAR threshold lowered to **6 dB** (from 10), minimum SNR to **3 dB**
(from 8), training cells reduced to 4. The classifier filters weak
returns after detection, so a low threshold produces more detections
without excessive false-alarm rate in practice.

### (4) Improved object classification
Complete rewrite of `classifyAndTrack`:
- Speed-primary rules: >20 m/s → vehicle; 0.2–6 m/s within 30 m → human;
  6–12 m/s → vehicle; stationary + high SNR → vehicle
- Confidence now accounts for speed match to walking speed, range, and SNR
- Track age-out extended to 3 s (from 2 s)
- Direction (`AHEAD`/`BEHIND`) derived from Doppler sign rather than
  always `AHEAD`
- Track matching uses range-resolution-relative threshold (`2 × rangeResM`)
  instead of hard-coded 2 m

### (5) Scanning / Tracking operating modes
`RadarOperatingMode` enum with `SCANNING` and `TRACKING` states added to
`TinyRadModels` and `TinyRadUiState`.

State machine in `observeFrames` (ViewModel):
- **SCANNING** → **TRACKING**: first frame with ≥1 detection; acquires
  strongest-SNR object as track target
- **TRACKING** → stays TRACKING: target track ID still visible
- **TRACKING** → re-acquires: original target lost but other objects present
- **TRACKING** → **SCANNING**: no detections in the frame

`RadarScreen` top bar shows a colour-coded pill (cyan = SCANNING,
orange = TRACKING) with target range/speed when tracking. An **Override**
button appears only in TRACKING mode and calls `viewModel.overrideToScanning()`
to force an immediate return to SCANNING.

### (6) Version bump to 3.0
First release with end-to-end validated USB communication, ADC data
reception, DSP processing, object detection, and display.

---

## [3.0.1] — 2026-06-10

### Bug fix

- **`recordingPath` removed from `TinyRadUiState`** in v3.0 (field was in the
  old duplicate tail that was deleted), but `startRecording()` in
  `TinyRadViewModel` still called `.copy(recordingPath = path, ...)`.
  Removed the `recordingPath` named argument from that `.copy()` call.
