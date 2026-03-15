# XREAL One Pro — Camera Access Guide

A step-by-step technical guide to accessing the XREAL One Pro's Eye (RGB) camera on Android without the official SDK.

**Tested on:** Samsung Galaxy S26 Ultra (SM-S948B) with XREAL One Pro glasses (Eye camera model).
May also work on Samsung Galaxy S24/S25 series, but this has not been tested.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Overview](#overview)
3. [Step 1: Load Native Libraries](#step-1-load-native-libraries)
4. [Step 2: Connect to Glasses via HID](#step-2-connect-to-glasses-via-hid)
5. [Step 3: Enable the Camera](#step-3-enable-the-camera)
6. [Step 4: Handle USB Re-enumeration](#step-4-handle-usb-re-enumeration)
7. [Step 5: Find the UVC Camera Interface](#step-5-find-the-uvc-camera-interface)
8. [Step 6: UVC Probe/Commit Negotiation](#step-6-uvc-probecommit-negotiation)
9. [Step 7: Start Bulk Streaming](#step-7-start-bulk-streaming)
10. [Step 8: Decode H.265/HEVC Frames](#step-8-decode-h265hevc-frames)
11. [Key Technical Details](#key-technical-details)
12. [Common Pitfalls](#common-pitfalls)
13. [HID Command Reference](#hid-command-reference)
14. [Troubleshooting: Black Image on Startup](#troubleshooting-black-image-on-startup)

---

## Prerequisites

- Android phone with **USB-C** and **USB host** support
- **XREAL One Pro** glasses with Eye camera hardware
- **XREAL Glasses Control APK** installed on the phone (provides native `.so` libraries)
- Android **minSdk 28** (Android 9.0+)

## Overview

The XREAL One Pro glasses expose a USB composite device with multiple interfaces: HID for control, CDC/NCM and CDC/ECM for networking, Audio, MTP, Mass Storage, and two UVC video interfaces.

The camera is not accessible by default. You must:
1. Load XREAL's native libraries via JNI
2. Send HID commands to enable the camera
3. Wait for USB re-enumeration (device changes from ~13 to 17 interfaces)
4. Open the UVC interface and negotiate stream parameters
5. Read bulk transfers and decode the HEVC video stream

## Step 1: Load Native Libraries

The XREAL One Pro uses two native libraries for USB communication:

- `libota-lib.so` — OTA/firmware utility library
- `libnr_glasses_api.so` — Main glasses API (HID commands, camera control)

These libraries are bundled inside the official **XREAL Glasses Control** APK. At runtime, locate and load them:

```kotlin
// Find the Glasses Control APK
val pkgInfo = packageManager.getPackageInfo("ai.nreal.glasses.control", 0)
val apkPath = pkgInfo.applicationInfo.sourceDir

// Extract and load native libs from the APK
// libnr_glasses_api.so depends on libota-lib.so, so load ota-lib first
System.load(extractedOtaLibPath)
System.load(extractedGlassesApiPath)
```

The JNI bridge class (`XrealGlasses.java`) provides callbacks that the native library uses:
- `getFd()` — returns the USB file descriptor for HID communication
- `hidRawWrite(byte[])` — writes raw HID reports
- `hidRawReadTimeout(int)` — reads HID responses with timeout
- `closeFd()` — closes the USB connection
- `getCurrentGlass()` — returns the glasses model identifier

## Step 2: Connect to Glasses via HID

When the XREAL One Pro is plugged in via USB-C, it appears as a USB device with:
- **Vendor ID**: `0x3318` (13080)
- **Product ID**: `0x0436` (1078)

Open the HID interface (typically interface #0) and establish a USB connection:

```kotlin
val usbManager = getSystemService(UsbManager::class.java)
val device = usbManager.deviceList.values.find {
    it.vendorId == 0x3318 && it.productId == 0x0436
}
val connection = usbManager.openDevice(device)
```

The native library communicates through HID reports. A first USB permission prompt appears here.

## Step 3: Enable the Camera

Use the native API to enable the camera via USB configuration:

```kotlin
// Wait for the glasses to be ready
NRBSPWaitPilotReady()

// Get current USB config
val config = NRBSPGetUsbConfigAll()

// Enable the camera interface
// uvc0 = Eye/RGB camera (interface #10)
config.uvc0 = 1   // Enable UVC interface 0

// Apply the new config — this triggers USB re-enumeration
NRBSPSetUsbConfigAll(config)
```

**Important:** Setting the USB config causes the glasses to **detach and re-attach** on the USB bus.

## Step 4: Handle USB Re-enumeration

After `NRBSPSetUsbConfigAll`, the USB device re-enumerates:
- The device disconnects and reconnects
- Interface count changes from ~13 to **17 interfaces**
- New interfaces include UVC0 and UVC1

You must:
1. **Wait ~3 seconds** for re-enumeration to complete
2. **Re-scan** the USB device list to find the updated device
3. **Request a new USB permission** — the re-enumerated device is treated as a new device by Android

```kotlin
// Sleep to allow re-enumeration
Thread.sleep(3000)

// Retry scanning for the camera device
// The device now has 17 interfaces instead of 13
```

This is why the app shows **two permission prompts** — one for the initial HID connection and one for the camera device after re-enumeration.

## Step 5: Find the UVC Camera Interface

After re-enumeration, scan the USB device's interfaces to find the UVC Video Streaming (VS) interface:

- **UVC interface class**: `0x0E` (14) — Video
- **UVC VS subclass**: `0x02` — Video Streaming
- Look for a **bulk endpoint** (not isochronous)

```kotlin
for (i in 0 until device.interfaceCount) {
    val iface = device.getInterface(i)
    if (iface.interfaceClass == 14 && iface.interfaceSubclass == 2) {
        // Found a UVC Video Streaming interface
        // Check alternate settings for bulk endpoints
    }
}
```

The XREAL One Pro uses **bulk transfer** for video (not isochronous), with `maxPacketSize = 512`.

## Step 6: UVC Probe/Commit Negotiation

Before streaming, negotiate the video format using UVC probe/commit control transfers:

```
Probe request (26 bytes):
  bFormatIndex = 1
  bFrameIndex  = 1
  dwFrameInterval = 333333 (30fps)
  dwMaxVideoFrameSize = 5242880
  dwMaxPayloadTransferSize = 262144

Control transfers:
  SET_CUR (Probe)  → bmRequestType=0x21, bRequest=0x01, wValue=0x0100
  GET_CUR (Probe)  → bmRequestType=0xA1, bRequest=0x81, wValue=0x0100
  SET_CUR (Commit) → bmRequestType=0x21, bRequest=0x01, wValue=0x0200
```

After committing, activate the streaming alternate setting:

```kotlin
// SET_INTERFACE: switch to alt setting 1 to start streaming
connection.setInterface(vsInterface)  // alt setting with bulk endpoint
```

## Step 7: Start Bulk Streaming

Read video data using bulk transfers:

```kotlin
val buffer = ByteArray(65536)  // 64KB — critical size, see pitfalls
while (streaming) {
    val bytesRead = connection.bulkTransfer(bulkEndpoint, buffer, buffer.size, 1000)
    if (bytesRead > 2) {
        // Strip the 2-byte UVC header
        val headerSize = buffer[0].toInt() and 0xFF  // Usually 2
        val payload = buffer.copyOfRange(headerSize, bytesRead)

        // Use FID (Frame ID) bit toggle and EOF bit to detect frame boundaries
        val bmHeaderFlags = buffer[1].toInt() and 0xFF
        val fid = bmHeaderFlags and 0x01      // Frame ID toggles per frame
        val eof = bmHeaderFlags and 0x02      // End of frame marker

        // Accumulate payloads until FID toggles or EOF is set
        frameBuffer.write(payload)

        if (eof != 0 || fidChanged) {
            // Complete frame received — deliver for decoding
            onFrameReceived(frameBuffer.toByteArray())
            frameBuffer.reset()
        }
    }
}
```

**Critical:** Each `bulkTransfer()` call returns exactly **one UVC payload** with a single 2-byte header. Do NOT try to parse multiple headers within one transfer — this causes corruption.

## Step 8: Decode H.265/HEVC Frames

The camera stream is **H.265/HEVC** (not H.264). Each complete frame is an HEVC access unit containing multiple NAL units.

### NAL Unit Parsing

HEVC NAL units use Annex B start codes (`00 00 00 01` or `00 00 01`). The NAL type is extracted differently from H.264:

```kotlin
// HEVC: NAL type = bits 1-6 of first byte after start code
val nalType = (byte_after_start_code.toInt() shr 1) and 0x3F

// Key NAL types:
// 32 = VPS (Video Parameter Set)
// 33 = SPS (Sequence Parameter Set)
// 34 = PPS (Picture Parameter Set)
// 19 = IDR_W_RADL (keyframe)
// 20 = IDR_N_LP (keyframe)
//  0 = TRAIL_N (non-IDR slice)
//  1 = TRAIL_R (non-IDR slice)
```

### Decoder Initialization

The MediaCodec HEVC decoder requires `csd-0` containing **VPS + SPS + PPS** concatenated:

```kotlin
val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080)

// Concatenate VPS + SPS + PPS (all with their start codes)
val csd0 = ByteArrayOutputStream()
csd0.write(vpsNal)  // includes 00 00 00 01 prefix
csd0.write(spsNal)
csd0.write(ppsNal)
format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0.toByteArray()))

val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
decoder.configure(format, surface, null, 0)
decoder.start()
```

### Threading

**Run USB reads and decoding on separate threads.** The USB bulk reads must never be blocked by the decoder, or you'll lose data:

```kotlin
// USB thread: reads bulk transfers, parses NAL units, queues them
val nalQueue = ConcurrentLinkedQueue<ByteArray>()

// Decoder thread: dequeues NALs, feeds to MediaCodec, drains output
val decoderThread = HandlerThread("HEVC-Decoder")
```

The actual decoded resolution is **2048x1512** (reported by the decoder as crop-right=2047, crop-bottom=1511).

---

## Key Technical Details

| Property | Value |
|----------|-------|
| Vendor ID | `0x3318` (13080) |
| Product ID | `0x0436` (1078) |
| SoC | X1 ("ethernet series") |
| Stream codec | H.265/HEVC |
| Resolution | 2048x1512 |
| Frame rate | ~60fps |
| USB transfer | Bulk (not isochronous) |
| Max packet size | 512 bytes |
| UVC max payload | 262144 bytes |
| Optimal read buffer | 64KB (65536 bytes) |
| IDR frame size | ~24KB typical |
| Interface count (pre-camera) | ~13 |
| Interface count (post-camera) | 17 |
| UVC header | 2 bytes per bulkTransfer call |

## Common Pitfalls

### Buffer Size Matters

| Buffer Size | Result |
|-------------|--------|
| 16KB | IDR frames (~24KB) get truncated → black/pixelated bottom of frame |
| **64KB** | **Works perfectly** — fits entire IDR frames |
| 256KB | `bulkTransfer()` returns no data at all |

### Do NOT Parse Multiple UVC Headers Per Transfer

Android's `bulkTransfer()` returns exactly one UVC payload per call. If you try to strip headers every 512 bytes (maxPacketSize), you'll corrupt the HEVC data, see false frame boundaries, and get ~120fps with 80% artifacts.

### Do NOT Change SurfaceTexture Buffer Size During Streaming

Calling `surfaceTexture.setDefaultBufferSize()` while the stream is active breaks rendering.

### HEVC, Not H.264

The stream uses H.265/HEVC despite using the same Annex B start codes as H.264. If you configure an H.264 decoder, you'll get a black screen. Key differences:
- NAL type extraction: `(byte >> 1) & 0x3F` (HEVC) vs `byte & 0x1F` (H.264)
- Three parameter sets: VPS + SPS + PPS (HEVC) vs SPS + PPS (H.264)
- Single `csd-0` buffer (HEVC) vs `csd-0` + `csd-1` (H.264)

### Two USB Permission Prompts Are Normal

The first prompt is for HID control. After enabling the camera, the device re-enumerates and Android treats it as a new device, requiring a second permission prompt.

## HID Command Reference

Commands discovered from the XREAL Glasses Control APK:

| Command | Code | Description |
|---------|------|-------------|
| RGB Enable | `0x68` | Enable RGB camera hardware |
| RGB Stream | `0x69` | Start RGB stream |
| Network Enable | `0x6A` | Enable network interface |
| Start Stream | `0x1101` | Start stream (payload: Interface_ID, Stream_Type, Resolution_Index) |
| IDR Request | `0x1102` | Request IDR keyframe |

## Troubleshooting: Black Image on Startup

Sometimes the stream may show a black image or fail to initialize. If this happens, try the following sequence:

1. **Unplug** the glasses from the phone
2. **Launch the app** while the glasses are disconnected
3. **Plug in** the glasses
4. **Press OK on the USB authorization prompt as fast as possible** — the glasses' USB handshake can time out if you wait too long
5. Tap **"Start Eye Camera"**
6. **Press OK on the second permission prompt as fast as possible**

The speed at which you grant permissions matters — delays can cause the USB handshake or camera initialization to time out, resulting in a black screen.
