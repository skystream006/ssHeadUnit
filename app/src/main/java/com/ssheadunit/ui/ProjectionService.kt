package com.ssheadunit.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.TimeUnit
import com.ssheadunit.R
import com.ssheadunit.session.HeadUnitController

/**
 * Foreground service that keeps the projection session alive while the phone is plugged in,
 * mirroring the always-on behaviour of a factory head unit.
 */
class ProjectionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val device: UsbDevice? = intent?.let {
            @Suppress("DEPRECATION")
            it.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        if (device == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        HeadUnitController.start(this, device)
        return START_STICKY
    }

    override fun onDestroy() {
        HeadUnitController.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            legacyNotificationBuilder()
        }
        val notification: Notification = notificationBuilder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyNotificationBuilder(): Notification.Builder = Notification.Builder(this)

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ssHeadUnit:session")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    companion object {
        /** Safety net so a leaked session can never keep the tablet awake for ever. */
        private val WAKE_LOCK_TIMEOUT_MS = TimeUnit.HOURS.toMillis(8)
        private const val CHANNEL_ID = "ssheadunit-projection"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context, device: UsbDevice) {
            val intent = Intent(context, ProjectionService::class.java).putExtra(UsbManager.EXTRA_DEVICE, device)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProjectionService::class.java))
        }
    }
}
