package com.reveng26.xrcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import androidx.appcompat.app.AppCompatActivity
import com.reveng26.xrcam.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), GlassesConnection.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var glasses: GlassesConnection
    private val handler = Handler(Looper.getMainLooper())

    // Listen for USB attach/detach to auto-detect glasses
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    onLog("USB device attached — scanning...")
                    handler.postDelayed({ glasses.scanForGlasses() }, 500)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    onLog("USB device detached")
                    glasses.disconnect()
                    onDisconnected()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.logText.movementMethod = ScrollingMovementMethod()

        glasses = GlassesConnection(this)
        glasses.listener = this

        binding.btnStartCamera.setOnClickListener {
            launchCamera()
        }

        // Register for USB events
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        glasses.start()

        // If launched by USB intent, handle it
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handler.postDelayed({ glasses.scanForGlasses() }, 300)
        }
    }

    override fun onPause() {
        super.onPause()
        // Don't disconnect — keep connection alive while camera activity is open
    }

    override fun onDestroy() {
        super.onDestroy()
        glasses.stop()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }

    private fun launchCamera() {
        onLog("Enabling Eye camera...")
        glasses.enableCamera(GlassesConnection.CameraType.GREYSCALE)
    }

    // --- GlassesConnection.Listener ---

    override fun onConnected(deviceName: String) {
        handler.post {
            binding.statusText.text = "Connected: $deviceName"
            binding.statusText.setTextColor(0xFF00E676.toInt())
            binding.btnStartCamera.isEnabled = true
        }
    }

    override fun onDisconnected() {
        handler.post {
            binding.statusText.text = "Disconnected"
            binding.statusText.setTextColor(0xFFFF5252.toInt())
            binding.btnStartCamera.isEnabled = false
        }
    }

    override fun onError(message: String) {
        handler.post {
            appendLog("ERROR: $message")
        }
    }

    override fun onLog(message: String) {
        handler.post {
            appendLog(message)
        }
    }

    override fun onCameraEnabled(cameraType: GlassesConnection.CameraType) {
        handler.post {
            appendLog("Camera enable command sent!")
            appendLog("Starting camera view...")

            val intent = Intent(this, CameraActivity::class.java).apply {
                putExtra(CameraActivity.EXTRA_CAMERA_TYPE, cameraType.name)
            }
            startActivity(intent)
        }
    }

    private fun appendLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        binding.logText.append("[$time] $msg\n")

        // Auto-scroll to bottom
        val scrollAmount = binding.logText.layout?.let {
            it.getLineTop(binding.logText.lineCount) - binding.logText.height
        } ?: 0
        if (scrollAmount > 0) {
            binding.logText.scrollTo(0, scrollAmount)
        }
    }
}
