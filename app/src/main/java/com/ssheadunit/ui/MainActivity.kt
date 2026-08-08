package com.ssheadunit.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
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
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ssheadunit.R
import com.ssheadunit.session.HeadUnitController
import com.ssheadunit.session.HeadUnitCredentials
import com.ssheadunit.transport.Aoap
import com.ssheadunit.util.HeadUnitLog

/**
 * Full screen projection surface. The tablet behaves like the display of a factory head unit:
 * it shows the phone's Android Auto UI and forwards touch input back to the phone.
 */
class MainActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView
    private lateinit var settingsButton: Button
    private lateinit var usbManager: UsbManager

    @Volatile
    private var switching = false

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
                        val other = Aoap.pickCandidate(
                            usbManager.deviceList.values.filter { it.deviceId != device?.deviceId }
                        )
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
        applyVideoDpi()
        settingsButton.setOnClickListener { showSettings() }
        enterImmersiveMode()
        HeadUnitLog.load(applicationContext)
        runCatching { HeadUnitCredentials.ensureCredentials(applicationContext) }
            .onFailure { HeadUnitLog.e(TAG, "Unable to create head unit credentials", it) }

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
            val attachedCount = usbManager.deviceList.size
            showStatus(
                if (attachedCount == 0) getString(R.string.status_waiting) else getString(R.string.status_no_candidate)
            )
            return
        }
        connect(device)
    }

    /**
     * Picks the most likely phone or wireless adapter out of every attached USB device. Devices
     * are ranked (accessory mode ids, then an AOAP accessory interface, then a known adapter id,
     * then any usable bulk interface) and a device that qualifies for none of those is never
     * picked, so an unrelated peripheral behind a hub cannot silently take the session.
     */
    private fun pickCandidateDevice(): UsbDevice? {
        val devices = usbManager.deviceList.values
        devices.forEach { Aoap.logDevice(it, "Attached") }
        return Aoap.pickCandidate(devices)
    }

    private fun connect(device: UsbDevice) {
        if (!usbManager.hasPermission(device)) {
            requestPermission(device)
            return
        }
        Aoap.logDevice(device, "Connecting to")
        if (Aoap.isSessionReady(device)) {
            startSession(device)
            return
        }
        if (switching) return
        switching = true
        showStatus(getString(R.string.status_switching))
        Thread({ switchToAccessoryMode(device) }, "aa-switch").start()
    }

    /**
     * Switches [device] into accessory mode and waits for it to come back.
     *
     * A wireless adapter reboots when it accepts the accessory strings, so the device that later
     * carries the session is a different USB device: the re-enumeration is awaited explicitly
     * instead of relying on the attach broadcast alone. An adapter that does not answer the AOAP
     * requests at all is not written off either, as long as it exposes a usable bulk interface.
     */
    private fun switchToAccessoryMode(device: UsbDevice) {
        try {
            val result = Aoap.requestAccessoryMode(usbManager, device)
            HeadUnitLog.i(TAG, "Accessory mode requested, result=$result")
            when (result) {
                Aoap.SwitchResult.UNSUPPORTED -> {
                    runOnUiThread { showStatus(getString(R.string.status_not_supported)) }
                    return
                }
                Aoap.SwitchResult.SWITCHED, Aoap.SwitchResult.INCONCLUSIVE -> Unit
            }
            runOnUiThread { showStatus(getString(R.string.status_waiting_accessory)) }
            val ready = awaitAccessoryDevice()
            when {
                ready != null -> runOnUiThread { connect(ready) }
                result == Aoap.SwitchResult.INCONCLUSIVE && Aoap.hasUsableInterface(device) -> {
                    // The device never answered AOAP but still offers a bulk pair: try it anyway.
                    HeadUnitLog.w(TAG, "No accessory device appeared; trying ${device.deviceName} as is")
                    runOnUiThread { startSession(device) }
                }
                else -> runOnUiThread { showStatus(getString(R.string.status_not_supported)) }
            }
        } finally {
            switching = false
        }
    }

    /** Polls the attached devices until one is ready for a session or the wait times out. */
    private fun awaitAccessoryDevice(): UsbDevice? {
        val deadline = System.currentTimeMillis() + RE_ENUMERATION_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            usbManager.deviceList.values.firstOrNull { Aoap.isSessionReady(it) }?.let { return it }
            Thread.sleep(RE_ENUMERATION_POLL_MS)
        }
        return null
    }

    private fun startSession(device: UsbDevice) {
        showStatus(getString(R.string.status_starting))
        ProjectionService.start(this, device)
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

    /** Settings are shown full screen so categories are visible without nesting. */
    private fun showSettings() {
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val currentOrientation = preferences.getInt(PREFERENCE_ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        val currentDpi = preferences.getInt(PREFERENCE_DPI, HeadUnitController.DEFAULT_VIDEO_DPI)
            .coerceIn(HeadUnitController.MIN_VIDEO_DPI, HeadUnitController.MAX_VIDEO_DPI)

        val settingsDialog = Dialog(this).apply settingsDialog@ {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(getColor(android.R.color.black))

                    addView(settingsHeader(this@settingsDialog))
                    addView(
                        ScrollView(this@MainActivity).apply {
                            addView(
                                LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    val padding = dpToPx(16)
                                    setPadding(padding, padding, padding, padding)
                                    addSettingsSection(getString(R.string.settings_display))
                                    ORIENTATIONS.forEach { (orientation, label) ->
                                        val text = getString(R.string.orientation_option, getString(label))
                                        addSettingsButton(
                                            if (orientation == currentOrientation) {
                                                getString(R.string.option_selected, text)
                                            } else {
                                                text
                                            }
                                        ) {
                                            dismiss()
                                            setOrientation(orientation)
                                            showSettings()
                                        }
                                    }
                                    VIDEO_DPI_OPTIONS.forEach { dpi ->
                                        val text = getString(R.string.dpi_option, dpi)
                                        addSettingsButton(
                                            if (dpi == currentDpi) getString(R.string.option_selected, text) else text
                                        ) {
                                            dismiss()
                                            setVideoDpi(dpi)
                                            showSettings()
                                        }
                                    }
                                    val customDpiText = getString(R.string.custom_dpi_option, currentDpi)
                                    addSettingsButton(
                                        if (VIDEO_DPI_OPTIONS.contains(currentDpi)) {
                                            customDpiText
                                        } else {
                                            getString(R.string.option_selected, customDpiText)
                                        }
                                    ) {
                                        dismiss()
                                        showCustomDpiDialog(currentDpi)
                                    }

                                    addSettingsSection(getString(R.string.settings_diagnostics))
                                    addSettingsButton(
                                        getString(
                                            if (HeadUnitLog.enabled) {
                                                R.string.debug_logging_on
                                            } else {
                                                R.string.debug_logging_off
                                            }
                                        )
                                    ) {
                                        dismiss()
                                        HeadUnitLog.setEnabled(applicationContext, !HeadUnitLog.enabled)
                                        showSettings()
                                    }
                                    addSettingsButton(getString(R.string.view_debug_log)) {
                                        dismiss()
                                        showDebugLog()
                                    }
                                }
                            )
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            0,
                            1f
                        )
                    )
                }
            )
        }
        settingsDialog.show()
        settingsDialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        @Suppress("DEPRECATION")
        settingsDialog.window?.decorView?.systemUiVisibility = immersiveModeFlags()
        enterImmersiveMode()
    }

    private fun settingsHeader(dialog: Dialog): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            val padding = dpToPx(16)
            setPadding(padding, padding, padding, padding / 2)
            addView(
                TextView(this@MainActivity).apply {
                    text = getString(R.string.settings)
                    setTextColor(getColor(android.R.color.white))
                    textSize = 24f
                },
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            addView(
                Button(this@MainActivity).apply {
                    text = getString(R.string.close)
                    setOnClickListener { dialog.dismiss() }
                }
            )
        }

    private fun LinearLayout.addSettingsSection(title: String) {
        addView(
            TextView(this@MainActivity).apply {
                text = title
                setTextColor(getColor(android.R.color.white))
                textSize = 18f
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(16)
                bottomMargin = dpToPx(8)
            }
        )
    }

    private fun LinearLayout.addSettingsButton(text: String, onClick: () -> Unit) {
        addView(
            Button(this@MainActivity).apply {
                this.text = text
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
        )
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun setOrientation(orientation: Int) {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREFERENCE_ORIENTATION, orientation)
            .apply()
        requestedOrientation = orientation
    }

    private fun setVideoDpi(dpi: Int) {
        val clampedDpi = dpi.coerceIn(HeadUnitController.MIN_VIDEO_DPI, HeadUnitController.MAX_VIDEO_DPI)
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREFERENCE_DPI, clampedDpi)
            .apply()
        HeadUnitController.setVideoDpi(clampedDpi)
    }

    private fun showCustomDpiDialog(currentDpi: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentDpi.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.custom_dpi_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val enteredDpi = input.text?.toString()?.trim()?.toIntOrNull()
                if (enteredDpi == null) {
                    showCustomDpiDialog(currentDpi)
                } else {
                    setVideoDpi(enteredDpi)
                    showSettings()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> showSettings() }
            .show()
    }

    private fun showDebugLog() {
        Thread({
            val log = HeadUnitLog.read(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val padding = resources.getDimensionPixelSize(R.dimen.debug_log_padding)
                val logView = TextView(this).apply {
                    setTextIsSelectable(true)
                    setPadding(padding, padding, padding, padding)
                    text = log.ifBlank { getString(R.string.debug_log_empty) }
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.debug_log)
                    .setView(ScrollView(this).apply { addView(logView) })
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }, "debug-log-reader").start()
    }

    private fun applyOrientation() {
        requestedOrientation = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getInt(PREFERENCE_ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    }

    private fun applyVideoDpi() {
        val dpi = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getInt(PREFERENCE_DPI, HeadUnitController.DEFAULT_VIDEO_DPI)
        HeadUnitController.setVideoDpi(dpi)
    }

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = immersiveModeFlags()
    }

    private fun immersiveModeFlags(): Int =
        (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

    private companion object {
        const val TAG = "MainActivity"
        const val ACTION_USB_PERMISSION = "com.ssheadunit.USB_PERMISSION"
        const val PREFERENCES_NAME = "settings"
        const val PREFERENCE_ORIENTATION = "orientation"
        const val PREFERENCE_DPI = "video_dpi"

        val ORIENTATIONS = listOf(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to R.string.orientation_landscape,
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to R.string.orientation_portrait,
        )
        val VIDEO_DPI_OPTIONS = listOf(
            HeadUnitController.MIN_VIDEO_DPI,
            HeadUnitController.DEFAULT_VIDEO_DPI,
            200,
            240,
            HeadUnitController.MAX_VIDEO_DPI
        )

        /** How long a device is given to re-enumerate after an accessory mode switch. */
        const val RE_ENUMERATION_TIMEOUT_MS = 20_000L
        const val RE_ENUMERATION_POLL_MS = 500L
    }
}
