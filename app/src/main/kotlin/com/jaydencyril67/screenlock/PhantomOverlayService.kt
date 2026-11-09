package com.jaydencyril67.screenlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

class PhantomOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.jaydencyril67.screenlock.action.START"
        const val ACTION_STOP = "com.jaydencyril67.screenlock.action.STOP"
        const val ACTION_CLOSE_LOCK = "com.jaydencyril67.screenlock.action.CLOSE_LOCK"
        const val PREFS = "phantom_lock_settings"
        const val PREF_ENABLED = "protection_enabled"

        private const val CHANNEL_ID = "phantom_lock_service"
        private const val NOTIFICATION_ID = 7314
    }

    private val handler = Handler(Looper.getMainLooper())
    private var screenReceiverRegistered = false

    private val showLockRunnable = Runnable {
        if (!isProtectionEnabled() || !Settings.canDrawOverlays(this)) return@Runnable

        try {
            val intent = Intent(this, PhantomLockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            startActivity(intent)
        } catch (_: Exception) {
            // Keep the service alive; the next screen wake can retry.
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(showLockRunnable)
                    sendBroadcast(Intent(ACTION_CLOSE_LOCK).setPackage(packageName))
                }

                Intent.ACTION_SCREEN_ON -> {
                    handler.removeCallbacks(showLockRunnable)
                    if (isProtectionEnabled()) {
                        // Let Android finish waking the display before placing our screen.
                        handler.postDelayed(showLockRunnable, 280L)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                disableProtection()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PREF_ENABLED, true)
                    .apply()
            }
        }

        if (!isProtectionEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Overlay permission is also the user's explicit authorization for this
        // screen-on utility to launch its lock Activity from the background.
        if (!Settings.canDrawOverlays(this)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_ENABLED, false)
                .apply()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun isProtectionEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, false)

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
        screenReceiverRegistered = true
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopService = PendingIntent.getService(
            this,
            2,
            Intent(this, PhantomOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Phantom Lock is active")
            .setContentText("Your fingerprint lock screen appears whenever the display wakes.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Turn off",
                stopService
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phantom Lock protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Phantom Lock ready for each screen wake."
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun disableProtection() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_ENABLED, false)
            .apply()

        handler.removeCallbacksAndMessages(null)
        sendBroadcast(Intent(ACTION_CLOSE_LOCK).setPackage(packageName))
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
                // Already unregistered.
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
