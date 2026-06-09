# TinyRadApp

**Native Android application for real-time FMCW radar object detection and classification using the Analog Devices TinyRAD evaluation board.**

Developed by **[RFSAT Limited](https://www.rfsat.com)** as part of the **ENACT** project, funded by the European Union under the Horizon Europe programme (Grant Agreement No. 101157101).

[![Build APK](https://github.com/rfsat/TinyRadApp/actions/workflows/build.yml/badge.svg)](https://github.com/rfsat/TinyRadApp/actions/workflows/build.yml)
![Version](https://img.shields.io/badge/version-1.7-brightgreen)
![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin)
![License](https://img.shields.io/badge/License-MIT-blue)

---

## Features

| Feature | Details |
|---|---|
| **USB CDC-ACM** | Direct USB Host connection to TinyRAD via OTG adapter — no BLE, no network |
| **Object detection** | Real-time CFAR detection on range-Doppler maps |
| **Object classification** | Distinguishes Human · Animal · Ground Vehicle · Aerial Vehicle |
| **Kinematics** | Per-object distance (m), speed (m/s & km/h), and approach direction |
| **PPI radar display** | Plan-position indicator canvas with colour-coded object blips and velocity vectors |
| **Range-Doppler map** | Live heatmap of the 2D range-Doppler spectrum |
| **CSV recording** | Timestamped ISO 8601 UTC CSV with all detection columns |
| **File sharing** | Share or export CSV via Android share sheet |
| **Dark theme** | Navy/cyan radar brand palette throughout |
| **Auto-launch** | Activity launches automatically when TinyRAD USB device is attached |

---

## Detected Object Classes

| Class | Colour | Detection criteria |
|---|---|---|
| **Human** | Teal | 0.3–4 m/s, range < 20 m, moderate SNR |
| **Animal** | Amber | 0.1–8 m/s, lower SNR |
| **Ground Vehicle** | Sky blue | > 5 m/s or high-SNR stationary RCS |
| **Aerial Vehicle** | Lavender | > 15 m/s, range > 10 m |
| **Unknown** | Grey | Does not match above criteria |

> Classification uses rule-based heuristics on speed, range, and SNR. The `classifyAndTrack()` method in `TinyRadUsbManager` is designed to be replaceable with an on-device ML model when training data is available.

---

## Requirements

- Android **8.0 (API 26)** or higher
- Device with **USB Host** support (most Android phones via USB-OTG adapter)
- Analog Devices **TinyRAD** evaluation board, connected via OTG USB cable
- Android 9 (API 28) and below: `WRITE_EXTERNAL_STORAGE` required for recording

---

## TinyRAD Hardware

The [Analog Devices TinyRAD](https://www.analog.com/en/design-center/evaluation-hardware-and-software/evaluation-boards-kits/EVAL-TINYRAD.html) is a compact 24 GHz FMCW radar evaluation platform. It presents to the host as a USB CDC-ACM (virtual serial) device.

Default USB identifiers matched by the app:

| Vendor | VID | PID | Notes |
|---|---|---|---|
| Analog Devices Dev Tools | 0x064B | 0x7823 | **EV-TINYRAD24G fw R 3.0.3 — confirmed** |
| Analog Devices | 0x0456 | 0xB60F | Older ADSP-BF70x firmware |
| Analog Devices | 0x0456 | 0xB671 | Alternate older firmware |
| STMicroelectronics | 0x0483 | 0x5740 | STM32 CDC-ACM fallback |

The board appears on the USB bus as **"BF707 Bulk Device"** — this is expected and correct per UG-1709 (page 6, Figure 7). VID `0x064B` is ADI's second vendor ID used exclusively for development/evaluation board firmware.

---

## Getting Started

### Download pre-built APK

Download the latest APK from the [**Releases**](https://github.com/rfsat/TinyRadApp/releases) page.
Enable *Install from unknown sources* and install. All packages are named `TinyRadApp_v<major>.<minor>.apk`.

### Build from source

```bash
git clone https://github.com/rfsat/TinyRadApp.git
cd TinyRadApp
./gradlew assembleRelease
# APK → app/build/outputs/apk/release/TinyRadApp_v1.0.apk
```

---

## Versioning scheme

`<major>.<minor>`

| Increment | When |
|---|---|
| **major** | New features added |
| **minor** | Bug fixes only |

Artifact filenames always include the version: `TinyRadApp_v<major>.<minor>.apk`

---

## Architecture

```
com.rfsat.tinyrad
├── MainActivity.kt              ← Compose NavHost, USB intent handling
├── ui/
│   ├── Screen.kt               ← Route definitions
│   ├── theme/                  ← Material3 dark theme, radar colour palette
│   └── screens/
│       ├── HomeScreen.kt       ← USB connect/disconnect, status
│       ├── RadarScreen.kt      ← Live PPI display, object list, range-Doppler map
│       ├── RecordingsScreen.kt ← CSV file list, share, delete
│       ├── SettingsScreen.kt   ← Radar configuration sliders
│       └── LogAndAboutScreens.kt ← Event log + credits
├── viewmodel/
│   └── TinyRadViewModel.kt     ← State management, USB permission flow, recording
└── data/
    ├── models/
    │   └── TinyRadModels.kt    ← DetectedObject, RadarFrame, TinyRadConfig, UiState
    ├── usb/
    │   └── TinyRadUsbManager.kt ← USB Host, CDC-ACM framing, DSP pipeline
    └── repository/
        ├── RecordingRepository.kt  ← CSV file writing
        ├── PreferencesRepository.kt ← DataStore config persistence
        └── AppLog.kt              ← In-memory event log
```

### USB protocol

TinyRAD communicates over USB CDC-ACM (virtual serial):

1. **Enumerate** — app finds device by VID/PID or prompts user
2. **Permission** — Android USB permission dialog (once per device)
3. **Open** — claim CDC data interface, identify bulk IN/OUT endpoints
4. **Commands** — ASCII text, newline-terminated (`START\n`, `STOP\n`, `CONFIG key=val\n`)
5. **Frames** — binary, sync word `0xA5 0x5A` + 4-byte header + payload:
   - Type `0x01`: raw ADC IQ samples → host-side DSP pipeline
   - Type `0x02`: device-processed detections (range bin + Doppler bin + SNR)
   - Type `0x03`: config acknowledgement
   - Type `0x04`: device info string

### DSP pipeline (host-side, type 0x01 frames)

```
ADC IQ samples
   ↓ Range FFT (fast-time, per chirp)
Range spectrum [chirps × range bins]
   ↓ Doppler FFT (slow-time, per range bin)
Range-Doppler magnitude [range bins × Doppler bins]
   ↓ 2D CA-CFAR threshold
Raw detections (range bin, Doppler bin, SNR)
   ↓ Physical units (m, m/s)
   ↓ Rule-based classifier
   ↓ Nearest-neighbour tracker
DetectedObject list
```

### CSV recording format

```csv
# RFSAT Limited — ENACT Project (Horizon Europe Grant 101157101)
# TinyRAD FMCW Radar Recording
# Session start: 2026-06-09T10:00:00.000Z
# Generated by TinyRadApp v1.0
timestamp_iso,timestamp_ms,frame_index,track_id,object_class,distance_m,azimuth_deg,elevation_deg,speed_mps,speed_kmh,direction,snr_db,confidence
2026-06-09T10:00:00.100Z,1749462000100,1,1,Human,8.50,0.0,0.0,1.200,4.32,Ahead,18.3,0.87
```

---

## Release signing

Add these GitHub repository secrets for signed release builds:

| Secret | Description |
|---|---|
| `KEYSTORE_BASE64` | Base64-encoded `.jks` keystore |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |
| `STORE_PASSWORD` | Keystore password |

---

## EU Acknowledgement

This project has received funding from the European Union under the Horizon Europe programme.

**Project:** ENACT — Environmental monitoring and health outcomes
**Grant:** 101157101
**CORDIS:** [cordis.europa.eu/project/id/101157101](https://cordis.europa.eu/project/id/101157101)

*Views and opinions expressed are those of the author(s) only and do not necessarily reflect those of the European Union.*

---

## Licence

MIT © 2026 RFSAT Limited
