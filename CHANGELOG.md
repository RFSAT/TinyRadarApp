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
