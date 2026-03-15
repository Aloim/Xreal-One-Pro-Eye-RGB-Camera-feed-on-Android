# XREAL One Pro — Eye/RGB Camera on Android (No SDK Required)

A lightweight Android app that accesses the **XREAL One Pro** glasses' Eye (RGB) camera directly via USB — **without** the official XREAL Glasses Control app or Unity SDK.

## What This Does

This app reverse-engineers the XREAL One Pro's USB protocol to:

1. Load the native XREAL libraries (`libota-lib.so`, `libnr_glasses_api.so`) from the official Glasses Control APK
2. Send HID commands to enable the Eye camera
3. Open a UVC bulk-transfer stream
4. Decode the **H.265/HEVC** video feed at **2048x1512 @ 60fps** in real-time

## Quick Start

1. Download the pre-built APK from [`apk/XRCam-debug.apk`](apk/XRCam-debug.apk)
2. Install it on your Android phone
3. Plug in your XREAL One Pro glasses via USB-C
4. Grant USB permissions when prompted (two prompts — one for HID control, one for camera)
5. Tap **"Start Eye Camera"**

> For a detailed step-by-step guide, see [`docs/camera-access-guide.md`](docs/camera-access-guide.md)

## Tested Hardware

| Device | Glasses | Status |
|--------|---------|--------|
| Samsung Galaxy S26 Ultra (SM-S948B) | XREAL One Pro (with Eye camera) | **Fully working** |
| Samsung Galaxy S24 / S25 series | XREAL One Pro | Untested — may work |

## Technical Details

- **Stream codec**: H.265/HEVC (NOT H.264)
- **Resolution**: 2048x1512
- **Frame rate**: ~60fps
- **USB transfer**: UVC Bulk (not isochronous), 64KB read buffer
- **XREAL One Pro identifiers**: VID=0x3318, PID=0x0436, X1 SoC
- **USB Composite**: 17 interfaces after camera enable (HID, CDC/NCM, CDC/ECM, Audio, UVC0, UVC1, etc.)

## Project Structure

```
├── apk/                    # Pre-built debug APK
├── app/src/main/java/      # Kotlin source code
│   └── com/reveng26/xrcam/
│       ├── MainActivity.kt         # Main UI, glasses connection
│       ├── GlassesConnection.kt    # JNI bridge to native XREAL libs
│       ├── CameraActivity.kt       # HEVC decoder + video display
│       └── UvcCameraHelper.kt      # UVC protocol (probe/commit/stream)
├── docs/                   # Documentation
│   └── camera-access-guide.md
├── build.gradle.kts        # Gradle build config
└── LICENSE                 # MIT License
```

## Prerequisites

- Android phone with USB-C and USB host support
- XREAL One Pro glasses (with Eye camera hardware)
- **XREAL Glasses Control APK** must be installed on the phone (the app loads native libraries from it at runtime)

## Troubleshooting

If you get a **black image** or the stream doesn't start on the first try:

1. **Unplug** the glasses
2. **Launch the app** first
3. **Plug in** the glasses
4. **Press OK on the USB authorization prompt as fast as possible**
5. Tap "Start Eye Camera" and again **press OK on the second permission prompt as fast as possible**

The timing of the permission grants matters — the glasses' USB handshake can time out if you wait too long.

## Known Limitations

- **Two USB permission prompts** are required (device re-enumerates after camera enable)
- The app requires the official Glasses Control APK to be installed for its native `.so` libraries

## How It Works

See the [Camera Access Guide](docs/camera-access-guide.md) for a full technical breakdown of the USB protocol, HID commands, UVC negotiation, and HEVC decoding pipeline.

## License

MIT — see [LICENSE](LICENSE)

## Contributing

This is a research/reverse-engineering project for the XREAL developer community. PRs, issues, and findings about other XREAL models are welcome.
