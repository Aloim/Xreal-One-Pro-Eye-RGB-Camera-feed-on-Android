package com.reveng26.xrcam

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import ai.nreal.glasses.control.UsbConfigList
import ai.nreal.glasses.control.XrealGlasses

/**
 * USB communication with XREAL One Pro ("Gina") glasses via native library.
 *
 * Uses libota-lib.so from the official Glasses Control APK to send HID commands.
 * The native library handles the exact protocol format, USB I/O, and handshaking.
 */
class GlassesConnection(private val context: Context) {

    companion object {
        private const val TAG = "GlassesConnection"
        const val ACTION_USB_PERMISSION = "com.reveng26.xrcam.USB_PERMISSION"
        const val XREAL_VID = 13080  // 0x3318
    }

    interface Listener {
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onError(message: String)
        fun onLog(message: String)
        fun onCameraEnabled(cameraType: CameraType)
    }

    enum class CameraType { GREYSCALE, RGB }

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var device: UsbDevice? = null
    var listener: Listener? = null
    var isConnected = false
        private set

    private var nativeGlasses: XrealGlasses? = null
    private var nativeInitialized = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    listener?.onLog("USB permission granted")
                    device?.let { onDeviceReady(it) }
                } else {
                    listener?.onError("USB permission denied")
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
        }

        // Initialize native library (just loads .so and copies assets — no USB I/O)
        initNativeLibrary()

        scanForGlasses()
    }

    fun stop() {
        try { context.unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        disconnect()
    }

    private fun initNativeLibrary() {
        try {
            listener?.onLog("Loading native library...")

            val permission = object : XrealGlasses.IPermission {
                override fun getPermission(vid: Int, pid: Int, key: String?): Int {
                    Log.d(TAG, "Native getPermission: VID=$vid PID=$pid key=$key")
                    // Check if we already have USB permission for this device
                    for (dev in usbManager.deviceList.values) {
                        if (dev.vendorId == vid && dev.productId == pid) {
                            val has = usbManager.hasPermission(dev)
                            Log.d(TAG, "  hasPermission=$has")
                            return if (has) 1 else 0
                        }
                    }
                    return 0
                }
            }

            nativeGlasses = XrealGlasses(context, permission)
            nativeInitialized = true
            listener?.onLog("Native library loaded OK")

        } catch (e: UnsatisfiedLinkError) {
            listener?.onError("Native lib load failed: ${e.message}")
            Log.e(TAG, "Native library load failed", e)
        } catch (e: Exception) {
            listener?.onError("Native init failed: ${e.message}")
            Log.e(TAG, "Native init failed", e)
        }
    }

    fun scanForGlasses() {
        listener?.onLog("Scanning for XREAL glasses...")
        for (dev in usbManager.deviceList.values) {
            if (dev.vendorId == XREAL_VID) {
                listener?.onLog("Found XREAL: VID=${dev.vendorId} PID=${dev.productId}")
                device = dev
                if (usbManager.hasPermission(dev)) {
                    onDeviceReady(dev)
                } else {
                    listener?.onLog("Requesting USB permission...")
                    val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) }
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    usbManager.requestPermission(dev, PendingIntent.getBroadcast(context, 0, intent, flags))
                }
                return
            }
        }
        listener?.onLog("No XREAL glasses found.")
    }

    /**
     * Called once we have USB permission. Just marks connected — no USB I/O here.
     * All USB I/O is deferred to the native library when the user clicks a button.
     */
    private fun onDeviceReady(dev: UsbDevice) {
        // Log interfaces for debugging
        Log.d(TAG, "Device ready: VID=${dev.vendorId} PID=${dev.productId} ifaces=${dev.interfaceCount}")
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            val cls = when (iface.interfaceClass) {
                1 -> "Audio"; 2 -> "CDC"; 3 -> "HID"; 8 -> "Storage"
                10 -> "CDC-Data"; 14 -> "Video"; else -> "Class${iface.interfaceClass}"
            }
            Log.d(TAG, "  IF#${iface.id}: $cls sub=${iface.interfaceSubclass} eps=${iface.endpointCount}")
        }

        isConnected = true
        listener?.onConnected("XREAL Gina (PID=${dev.productId})")
        listener?.onLog("Ready — tap a camera button to enable")
    }

    fun disconnect() {
        device = null
        isConnected = false
    }

    fun enableCamera(type: CameraType) {
        if (!isConnected) {
            listener?.onError("Not connected")
            return
        }
        if (!nativeInitialized) {
            listener?.onError("Native library not loaded")
            return
        }

        listener?.onLog("=== Enable ${type.name} Camera ===")
        enableCameraNative(type)
    }

    /**
     * Enable camera using native library on a background thread.
     * The native code handles: getFd → open device → claim HID → send commands.
     */
    private fun enableCameraNative(type: CameraType) {
        Thread({
            try {
                val glasses = nativeGlasses!!

                // Step 1: Wait for pilot ready (handshake)
                listener?.onLog("Waiting for pilot ready...")
                val pilotReady = glasses.NRBSPWaitPilotReady(10000, null)
                listener?.onLog("Pilot ready: $pilotReady")

                // Step 2: Get current config
                listener?.onLog("Getting current USB config...")
                val curConfig = glasses.NRBSPGetUsbConfigAll(null)
                if (curConfig != null) {
                    listener?.onLog("Current: ncm=${curConfig.ncm} ecm=${curConfig.ecm} " +
                        "hid=${curConfig.hid_ctrl} uvc0=${curConfig.uvc0} uvc1=${curConfig.uvc1}")
                }

                // Step 3: Set new config with camera enabled
                val config = UsbConfigList()
                config.ncm = 1
                config.ecm = 1
                config.hid_ctrl = 1
                config.enable = 1

                when (type) {
                    CameraType.GREYSCALE -> {
                        config.uvc0 = 1
                        listener?.onLog("Enabling UVC0 (greyscale)")
                    }
                    CameraType.RGB -> {
                        config.uvc1 = 1
                        listener?.onLog("Enabling UVC1 (RGB)")
                    }
                }

                listener?.onLog("Calling NRBSPSetUsbConfigAll...")
                val result = glasses.NRBSPSetUsbConfigAll(config, null)
                listener?.onLog("Result: $result")

                if (result == 0) {
                    listener?.onLog("USB config set OK, waiting for re-enumeration...")
                } else {
                    listener?.onLog("SetUsbConfigAll error: $result")
                }

                // Wait for USB re-enumeration
                Thread.sleep(3000)

                // Check camera status after
                try {
                    val camStatus = glasses.NRBSPGetCameraStatus(null)
                    listener?.onLog("Camera status: $camStatus")
                } catch (e: Exception) {
                    listener?.onLog("Camera status check: ${e.message}")
                }

                listener?.onCameraEnabled(type)

            } catch (e: Exception) {
                listener?.onError("Enable camera failed: ${e.message}")
                Log.e(TAG, "enableCameraNative failed", e)
            }
        }, "NativeEnableCamera").start()
    }

    fun queryUsbConfig() {
        if (!nativeInitialized) return
        Thread({
            try {
                val config = nativeGlasses?.NRBSPGetUsbConfigAll(null)
                if (config != null) {
                    listener?.onLog("USB Config: ncm=${config.ncm} ecm=${config.ecm} " +
                        "uac=${config.uac} hid=${config.hid_ctrl} mtp=${config.mtp} " +
                        "storage=${config.mass_storage} uvc0=${config.uvc0} uvc1=${config.uvc1} " +
                        "enable=${config.enable}")
                }
            } catch (e: Exception) {
                listener?.onLog("Query config: ${e.message}")
            }
        }, "QueryConfig").start()
    }
}
