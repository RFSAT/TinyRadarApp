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

### Bug fixes — three errors in init command encoding found by re-analysing pcap

All three errors caused the board to silently reject the init sequence, leaving
it in an uninitialised state where every MeasTrig returned nothing (-1).

- **`payload_len` field wrong** (`sendCmd`): the formula was `4 + params.size * 4`,
  adding 4 extra bytes. Confirmed from pcap: `payload_len` counts **only** the
  parameter bytes (`params.size * 4`), not the cmd/npar/rsrv header fields.
  Verified against every frame: F319 plen=12=3×4 ✓, F331 plen=48=12×4 ✓,
  F439 plen=20=5×4 ✓. This was the most likely cause of the board rejecting
  every command silently.

- **RegWrite #2 param[8] wrong**: `0x0006001F` → `0x0006011F` (bit 8 missing).
  From pcap frame 335 byte-exact decode.

- **CfgFmcw #5 param[2] byte-swapped**: `0x003D0080` → `0x0080003D`.
  The previous decode read the hex display as big-endian; the actual uint32
  little-endian value is `0x0080003D`. From pcap frame 367 byte-exact decode.

---

## [2.3] — 2026-06-09

### New features

- **Log level filter chips** (`LogScreen`): a horizontal chip strip above the log
  list lets you filter by ALL / ERRO / WARN / INFO / DEBU.  Each chip shows the
  count for that level.  Selecting a chip that is already active clears the
  filter back to ALL.  Auto-scroll to bottom follows the filtered view.

- **USB cable-removal auto-disconnect** (`TinyRadViewModel`): a
  `BroadcastReceiver` for `ACTION_USB_DEVICE_DETACHED` is now registered at
  ViewModel init time.  When the cable is unplugged the receiver calls
  `usbManager.disconnect()`, stops any in-progress recording, clears streaming
  state, and sets the error message "Cable disconnected" so the Home screen
  shows the error state immediately.  The receiver is registered with
  `RECEIVER_EXPORTED` (required for system broadcasts) and unregistered in
  `onCleared`.

- **About screen updated**:
  - ENACT project reference and EU grant language removed
  - Hardware row updated to "Analog Devices EV-TINYRAD24G" with confirmed
    VID/PID
  - Interface row updated to "USB Host — vendor bulk (OTG)"
  - Added two tappable link rows that open the system browser:
    - RFSAT Limited → https://www.rfsat.com
    - TinyRAD Evaluation Board → https://www.analog.com/en/resources/
      evaluation-hardware-and-software/evaluation-boards-kits/eval-tinyrad.html

- **DEBUG log brightness increased** (`LogRow`): DEBUG message alpha raised from
  0.45 to 0.78 so messages are clearly readable against the dark background
  while remaining visually distinct from INFO level.

---

## [2.4] — 2026-06-09

### Bug fixes

- **Reverted USB detach auto-disconnect** (`TinyRadViewModel`): the
  `ACTION_USB_DEVICE_DETACHED` `BroadcastReceiver` introduced in v2.3 caused
  the application to crash on connection.  The receiver declaration,
  `init`-block registration, and `onCleared` unregistration have all been
  removed.  The remaining features from v2.3 (log filter chips, About screen
  update, DEBUG brightness) are unaffected.
