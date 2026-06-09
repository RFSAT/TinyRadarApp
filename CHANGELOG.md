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
