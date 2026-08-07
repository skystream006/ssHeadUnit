package com.ssheadunit.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.ssheadunit.R
import com.ssheadunit.session.HeadUnitController
import com.ssheadunit.transport.Aoap

/**
 * Full screen projection surface. The tablet behaves like the display of a factory head unit:
 * it shows the phone's Android Auto UI and forwards touch input back to the phone.
 */
class MainActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView
    private lateinit var settingsButton: Button
    private lateinit var usbManager: UsbManager

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        connect(device)
                    } else {
                        // The user may have denied permission for the wrong device if several
                        // peripherals are attached at once (e.g. through a USB hub). Try any
                        // other candidate still plugged in before giving up.
                        val other = usbManager.deviceList.values.firstOrNull { it.deviceId != device?.deviceId }
                        if (other != null) {
                            connect(other)
                        } else {
                            showStatus(getString(R.string.status_permission_denied))
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> device?.let { connect(it) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    ProjectionService.stop(this@MainActivity)
                    showStatus(getString(R.string.status_waiting))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        surfaceView = findViewById(R.id.projection_surface)
        statusView = findViewById(R.id.status_text)
        settingsButton = findViewById(R.id.settings_button)
        surfaceView.holder.addCallback(this)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        applyOrientation()
        settingsButton.setOnClickListener { showSettings() }
        enterImmersiveMode()

        HeadUnitController.statusListener = { text, connected ->
            runOnUiThread {
                statusView.text = text
                statusView.visibility = if (connected) View.GONE else View.VISIBLE
                settingsButton.visibility = if (connected) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
        showStatus(HeadUnitController.status.takeIf { HeadUnitController.isConnected } ?: getString(R.string.status_waiting))
        scanForPhone()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(usbReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        HeadUnitController.statusListener = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enterImmersiveMode()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        HeadUnitController.onTouch(event, surfaceView.width, surfaceView.height) || super.onTouchEvent(event)

    // --- surface ------------------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        HeadUnitController.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        HeadUnitController.attachSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        HeadUnitController.attachSurface(null)
    }

    // --- usb ----------------------------------------------------------------------------------

    private fun scanForPhone() {
        val attached = intent?.takeIf { it.action == UsbManager.ACTION_USB_DEVICE_ATTACHED }?.let {
            @Suppress("DEPRECATION")
            it.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        }
        val device = attached ?: pickCandidateDevice()
        if (device == null) {
            showStatus(getString(R.string.status_waiting))
            return
        }
        connect(device)
    }

    /**
     * Picks the most likely phone out of every attached USB device. When several peripherals
     * share the same OTG port (e.g. through a hub) a device already switched into accessory mode
     * is preferred, since that is unambiguously the phone; otherwise the first attached device is
     * used as a best effort.
     */
    private fun pickCandidateDevice(): UsbDevice? {
        val devices = usbManager.deviceList.values
        return devices.firstOrNull { Aoap.isInAccessoryMode(it) } ?: devices.firstOrNull()
    }

    private fun connect(device: UsbDevice) {
        if (!usbManager.hasPermission(device)) {
            requestPermission(device)
            return
        }
        if (Aoap.isInAccessoryMode(device)) {
            showStatus(getString(R.string.status_starting))
            ProjectionService.start(this, device)
            return
        }
        showStatus(getString(R.string.status_switching))
        Thread {
            val switched = Aoap.requestAccessoryMode(usbManager, device)
            Log.i(TAG, "Accessory mode requested, result=$switched")
            if (!switched) {
                runOnUiThread { showStatus(getString(R.string.status_not_supported)) }
            }
        }.start()
    }

    private fun requestPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun showStatus(text: String) {
        statusView.text = text
        statusView.visibility = View.VISIBLE
        settingsButton.visibility = if (HeadUnitController.isConnected) View.GONE else View.VISIBLE
    }

    private fun showSettings() {
        val orientations = intArrayOf(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        )
        val currentOrientation = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getInt(PREFERENCE_ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        val checkedItem = orientations.indexOf(currentOrientation).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.display_orientation)
            .setSingleChoiceItems(
                arrayOf(getString(R.string.orientation_landscape), getString(R.string.orientation_portrait)),
                checkedItem,
            ) { dialog, which ->
                getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(PREFERENCE_ORIENTATION, orientations[which])
                    .apply()
                requestedOrientation = orientations[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun applyOrientation() {
        requestedOrientation = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getInt(PREFERENCE_ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    }

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    private companion object {
        const val TAG = "MainActivity"
        const val ACTION_USB_PERMISSION = "com.ssheadunit.USB_PERMISSION"
        const val PREFERENCES_NAME = "settings"
        const val PREFERENCE_ORIENTATION = "orientation"
    }
}
