package com.reveng26.xrcam

import android.content.Context
import android.hardware.usb.*
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * UVC camera access helper with proper header stripping and HEVC decoding support.
 *
 * XREAL "Gina" UVC bulk transfers contain:
 * - UVC Payload Header (2 or 12 bytes) at the start of each bulk transfer
 * - HEVC Annex B data (00 00 00 01 + NAL units) as the payload
 *
 * The camera sends SPS/PPS only once at stream start, then IDR + P-frames.
 */
class UvcCameraHelper(private val context: Context) {

    companion object {
        private const val TAG = "UvcCameraHelper"

        const val OV580_VID = 1449
        const val OV580_PID = 1664
        const val OV580_PID_ALT = 62848
        const val RGB_VID1 = 2071
        const val RGB_VID2 = 13080
        const val RGB_PID = 2313
        const val RGB_PID2 = 2320

        const val USB_CLASS_VIDEO = 14
        const val UVC_SC_VIDEOCONTROL = 1
        const val UVC_SC_VIDEOSTREAMING = 2

        // UVC request codes
        const val UVC_SET_CUR = 0x01
        const val UVC_GET_CUR = 0x81
        const val UVC_GET_DEF = 0x84

        const val VS_PROBE_CONTROL = 0x01
        const val VS_COMMIT_CONTROL = 0x02

        const val USB_RT_CLASS_IFACE_SET = 0x21
        const val USB_RT_CLASS_IFACE_GET = 0xA1

        // UVC payload header bits
        const val UVC_HEADER_FID = 0x01
        const val UVC_HEADER_EOF = 0x02
        const val UVC_HEADER_ERR = 0x40
    }

    interface Listener {
        fun onCameraFound(description: String)
        fun onStreamStarted()
        fun onStreamStopped()
        fun onError(message: String)
        fun onLog(message: String)
        fun onFrameReceived(width: Int, height: Int, data: ByteArray)
    }

    var listener: Listener? = null
    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var streamingDevice: UsbDevice? = null
    private var streamingConnection: UsbDeviceConnection? = null
    private var streamingEndpoint: UsbEndpoint? = null
    private var streamingInterface: UsbInterface? = null
    private var videoControlInterface: UsbInterface? = null
    @Volatile
    private var isStreaming = false
    private var streamThread: Thread? = null

    fun findCamera(type: GlassesConnection.CameraType): UsbDevice? {
        val deviceList = usbManager.deviceList
        Log.d(TAG, "findCamera(${type.name}): ${deviceList.size} USB devices")

        // Check for dedicated camera devices
        for (dev in deviceList.values) {
            when (type) {
                GlassesConnection.CameraType.GREYSCALE -> {
                    if (dev.vendorId == OV580_VID && (dev.productId == OV580_PID || dev.productId == OV580_PID_ALT)) {
                        listener?.onCameraFound("OV580 Greyscale")
                        return dev
                    }
                }
                GlassesConnection.CameraType.RGB -> {
                    if ((dev.vendorId == RGB_VID1 || dev.vendorId == RGB_VID2) && (dev.productId == RGB_PID || dev.productId == RGB_PID2)) {
                        listener?.onCameraFound("RGB Camera")
                        return dev
                    }
                }
            }
        }

        // Fallback: XREAL composite device with UVC interfaces
        for (dev in deviceList.values) {
            if (dev.vendorId == GlassesConnection.XREAL_VID) {
                for (i in 0 until dev.interfaceCount) {
                    val iface = dev.getInterface(i)
                    if (iface.interfaceClass == USB_CLASS_VIDEO && iface.interfaceSubclass == UVC_SC_VIDEOSTREAMING) {
                        listener?.onCameraFound("UVC ${type.name} on composite (IF#${iface.id})")
                        return dev
                    }
                }
            }
        }

        listener?.onLog("Camera not found yet")
        return null
    }

    fun startStream(device: UsbDevice) {
        streamingDevice = device
        listener?.onLog("Opening camera device...")

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            listener?.onError("Failed to open USB device")
            return
        }
        streamingConnection = conn

        // Find Video Control and Video Streaming interfaces
        var vcIface: UsbInterface? = null
        var vsIface: UsbInterface? = null
        var videoEndpoint: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_VIDEO) {
                when (iface.interfaceSubclass) {
                    UVC_SC_VIDEOCONTROL -> {
                        if (vcIface == null) vcIface = iface
                    }
                    UVC_SC_VIDEOSTREAMING -> {
                        if (vsIface == null && iface.endpointCount > 0) {
                            vsIface = iface
                            for (e in 0 until iface.endpointCount) {
                                val ep = iface.getEndpoint(e)
                                if (ep.direction == UsbConstants.USB_DIR_IN) {
                                    videoEndpoint = ep
                                    val epType = when (ep.type) {
                                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                                        else -> "type=${ep.type}"
                                    }
                                    listener?.onLog("Stream EP: $epType maxPkt=${ep.maxPacketSize} addr=0x${ep.address.toString(16)}")
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }

        if (vsIface == null || videoEndpoint == null) {
            listener?.onError("No UVC streaming endpoint found")
            conn.close()
            return
        }

        videoControlInterface = vcIface
        streamingInterface = vsIface
        streamingEndpoint = videoEndpoint

        // Claim interfaces
        if (vcIface != null) {
            conn.claimInterface(vcIface, true)
            listener?.onLog("Claimed VC IF#${vcIface.id}")
        }
        if (!conn.claimInterface(vsIface, true)) {
            listener?.onError("Failed to claim VS IF#${vsIface.id}")
            conn.close()
            return
        }
        listener?.onLog("Claimed VS IF#${vsIface.id}")

        isStreaming = true
        streamThread = Thread({
            try {
                negotiateStream(conn, vsIface, videoEndpoint)
                listener?.onStreamStarted()
                readAndDeliverFrames(conn, videoEndpoint)
            } catch (e: Exception) {
                listener?.onError("Stream error: ${e.message}")
                Log.e(TAG, "Stream error", e)
            }
        }, "UVC-Stream").also { it.start() }
    }

    private fun negotiateStream(conn: UsbDeviceConnection, vsIface: UsbInterface, endpoint: UsbEndpoint) {
        val ifaceId = vsIface.id

        // Step 1: Try SET_INTERFACE to alternate setting 1 to start the encoder
        listener?.onLog("SET_INTERFACE alt=1 for IF#$ifaceId...")
        val setIfResult = conn.controlTransfer(
            0x01,  // USB_RECIP_INTERFACE, USB_TYPE_STANDARD, HOST_TO_DEVICE
            0x0B,  // SET_INTERFACE
            1,     // alternate setting 1
            ifaceId,
            null, 0, 2000
        )
        listener?.onLog("SET_INTERFACE result: $setIfResult")

        // Step 2: Try UVC probe/commit
        val defProbe = uvcGetControl(conn, UVC_GET_DEF, VS_PROBE_CONTROL, ifaceId, 34)
        if (defProbe != null) {
            listener?.onLog("Default probe: ${formatProbe(defProbe)}")
        }

        var probeSize = 34
        var probe = buildProbeData(probeSize, formatIndex = 1, frameIndex = 1, fps = 30)

        listener?.onLog("SET_CUR probe (fmt=1 frm=1 30fps)...")
        var result = uvcSetControl(conn, VS_PROBE_CONTROL, ifaceId, probe)
        if (result < 0) {
            probeSize = 26
            probe = buildProbeData(probeSize, formatIndex = 1, frameIndex = 1, fps = 30)
            result = uvcSetControl(conn, VS_PROBE_CONTROL, ifaceId, probe)
        }

        if (result >= 0) {
            listener?.onLog("Probe OK ($result)")
            val curProbe = uvcGetControl(conn, UVC_GET_CUR, VS_PROBE_CONTROL, ifaceId, probeSize)
            if (curProbe != null) {
                listener?.onLog("Negotiated: ${formatProbe(curProbe)}")
                probe = curProbe
            }

            result = uvcSetControl(conn, VS_COMMIT_CONTROL, ifaceId, probe)
            listener?.onLog("Commit: $result")
        } else {
            listener?.onLog("Probe failed ($result), reading raw...")
        }
    }

    /**
     * Read bulk transfers, strip UVC payload headers, accumulate HEVC NAL units,
     * and deliver complete frames.
     *
     * UVC Payload Header:
     *   byte[0] = Header Length (typically 0x02 or 0x0C)
     *   byte[1] = Bit Field (FID, EOF, ERR, etc.)
     *   byte[2..] = Optional PTS/SCR if header > 2
     *
     * After stripping: HEVC Annex B data with 00 00 00 01 start codes.
     */
    private fun readAndDeliverFrames(conn: UsbDeviceConnection, endpoint: UsbEndpoint) {
        val bufSize = 65536  // Must be larger than IDR frames (~24KB observed)
        val buffer = ByteArray(bufSize)
        val frameAccum = ByteArrayOutputStream(256 * 1024)

        var bulkReadCount = 0
        var frameCount = 0
        var lastFid = -1
        var timeoutCount = 0
        var totalHevcBytes = 0L

        listener?.onLog("Starting bulk reads (buf=$bufSize maxPkt=${endpoint.maxPacketSize})...")

        while (isStreaming) {
            val read = conn.bulkTransfer(endpoint, buffer, bufSize, 500)

            if (read <= 0) {
                if (read == -1) {
                    timeoutCount++
                    if (timeoutCount == 20) {
                        listener?.onLog("20 timeouts — no data flowing")
                    }
                    if (timeoutCount >= 120) {
                        listener?.onError("No data after 60s — stopping")
                        break
                    }
                }
                continue
            }

            timeoutCount = 0
            bulkReadCount++

            // Log first several raw bulk reads for debugging
            if (bulkReadCount <= 10) {
                val hex = buffer.take(read.coerceAtMost(32)).joinToString(" ") { "%02X".format(it) }
                Log.d(TAG, "Bulk #$bulkReadCount: $read bytes: $hex")
                listener?.onLog("Bulk #$bulkReadCount: $read bytes: $hex")
            }

            // Check for UVC Payload Header
            val headerLen = buffer[0].toInt() and 0xFF
            val headerInfo = buffer[1].toInt() and 0xFF

            // Valid UVC header: length is 2-12 and doesn't exceed the read size
            if (headerLen in 2..12 && headerLen <= read) {
                val fid = headerInfo and UVC_HEADER_FID
                val eof = (headerInfo and UVC_HEADER_EOF) != 0
                val err = (headerInfo and UVC_HEADER_ERR) != 0

                // FID toggle = new frame starting
                if (lastFid >= 0 && fid != lastFid && frameAccum.size() > 0) {
                    deliverHevcFrame(frameAccum.toByteArray(), frameCount)
                    frameCount++
                    frameAccum.reset()
                }
                lastFid = fid

                // Append HEVC payload (after UVC header)
                val payloadStart = headerLen
                val payloadLen = read - payloadStart
                if (payloadLen > 0) {
                    frameAccum.write(buffer, payloadStart, payloadLen)
                    totalHevcBytes += payloadLen
                }

                // EOF = frame complete
                if (eof && frameAccum.size() > 0) {
                    deliverHevcFrame(frameAccum.toByteArray(), frameCount)
                    frameCount++
                    frameAccum.reset()
                }

                if (err && bulkReadCount <= 50) {
                    Log.d(TAG, "UVC error bit at bulk #$bulkReadCount")
                }
            } else {
                // No valid UVC header — continuation data, append as-is
                frameAccum.write(buffer, 0, read)

                if (bulkReadCount <= 5) {
                    Log.d(TAG, "No UVC header (headerLen=$headerLen), appending raw $read bytes")
                }
            }

            // Periodic stats
            if (bulkReadCount % 1000 == 0) {
                listener?.onLog("Stats: $bulkReadCount reads, $frameCount frames, ${totalHevcBytes / 1024}KB HEVC data")
            }
        }

        listener?.onLog("Stream ended: $frameCount frames from $bulkReadCount bulk reads")
    }

    /**
     * Deliver a complete HEVC access unit (one frame's worth of NAL units).
     * Scan for NAL start codes and log the types found.
     */
    private fun deliverHevcFrame(data: ByteArray, frameNum: Int) {
        if (data.isEmpty()) return

        // Log details of first frames and periodically
        if (frameNum < 20 || frameNum % 200 == 0) {
            val nalTypes = findNalTypes(data)
            val hex = data.take(24.coerceAtMost(data.size)).joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "H264 Frame #$frameNum: ${data.size} bytes, NALs=$nalTypes, hex=[$hex]")
            listener?.onLog("Frame #$frameNum: ${data.size}b NALs=$nalTypes")
        }

        listener?.onFrameReceived(0, 0, data)
    }

    /**
     * Find all HEVC NAL unit types in an access unit.
     * HEVC NAL type = (first_byte_after_start_code >> 1) & 0x3F
     * Common types: 0=TRAIL_N, 1=TRAIL_R, 19=IDR_W_RADL, 20=IDR_N_LP, 32=VPS, 33=SPS, 34=PPS
     */
    private fun findNalTypes(data: ByteArray): List<Int> {
        val types = mutableListOf<Int>()
        var i = 0
        while (i < data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() && i + 4 < data.size) {
                    types.add((data[i + 4].toInt() shr 1) and 0x3F)
                    i += 5
                    continue
                }
                if (data[i + 2] == 1.toByte() && i + 3 < data.size) {
                    types.add((data[i + 3].toInt() shr 1) and 0x3F)
                    i += 4
                    continue
                }
            }
            i++
        }
        return types
    }

    private fun buildProbeData(size: Int, formatIndex: Int, frameIndex: Int, fps: Int): ByteArray {
        val data = ByteArray(size)
        data[0] = 0x01; data[1] = 0x00  // bmHint
        data[2] = formatIndex.toByte()
        data[3] = frameIndex.toByte()
        val interval = 10_000_000 / fps
        data[4] = (interval and 0xFF).toByte()
        data[5] = ((interval shr 8) and 0xFF).toByte()
        data[6] = ((interval shr 16) and 0xFF).toByte()
        data[7] = ((interval shr 24) and 0xFF).toByte()
        return data
    }

    private fun formatProbe(data: ByteArray): String {
        if (data.size < 26) return "short(${data.size}b)"
        val fmt = data[2].toInt() and 0xFF
        val frm = data[3].toInt() and 0xFF
        val interval = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8) or
                ((data[6].toInt() and 0xFF) shl 16) or ((data[7].toInt() and 0xFF) shl 24)
        val fps = if (interval > 0) 10_000_000.0 / interval else 0.0
        val maxFrame = (data[18].toInt() and 0xFF) or ((data[19].toInt() and 0xFF) shl 8) or
                ((data[20].toInt() and 0xFF) shl 16) or ((data[21].toInt() and 0xFF) shl 24)
        val maxPayload = (data[22].toInt() and 0xFF) or ((data[23].toInt() and 0xFF) shl 8) or
                ((data[24].toInt() and 0xFF) shl 16) or ((data[25].toInt() and 0xFF) shl 24)
        return "fmt=$fmt frm=$frm ${"%.1f".format(fps)}fps maxFrame=$maxFrame maxPayload=$maxPayload"
    }

    private fun uvcSetControl(conn: UsbDeviceConnection, selector: Int, ifaceId: Int, data: ByteArray): Int {
        return conn.controlTransfer(USB_RT_CLASS_IFACE_SET, UVC_SET_CUR, selector shl 8, ifaceId, data, data.size, 2000)
    }

    private fun uvcGetControl(conn: UsbDeviceConnection, request: Int, selector: Int, ifaceId: Int, size: Int): ByteArray? {
        val data = ByteArray(size)
        val result = conn.controlTransfer(USB_RT_CLASS_IFACE_GET, request, selector shl 8, ifaceId, data, data.size, 2000)
        return if (result >= 0) data else null
    }

    fun stopStream() {
        isStreaming = false
        streamThread?.interrupt()
        streamThread?.join(2000)
        streamThread = null

        streamingInterface?.let { streamingConnection?.releaseInterface(it) }
        videoControlInterface?.let { streamingConnection?.releaseInterface(it) }
        streamingConnection?.close()
        streamingConnection = null
        streamingDevice = null
        streamingEndpoint = null
        streamingInterface = null
        videoControlInterface = null

        listener?.onStreamStopped()
    }
}
