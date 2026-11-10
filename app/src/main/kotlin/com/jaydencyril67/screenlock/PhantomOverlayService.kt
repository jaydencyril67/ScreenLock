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
import android.os.IBinder
import android.provider.Settings
import android.util.Log

class PhantomOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.jaydencyril67.screenlock.action.START"
        const val ACTION_STOP = "com.jaydencyril67.screenlock.action.STOP"
        const val ACTION_CLOSE_LOCK = "com.jaydencyril67.screenlock.action.CLOSE_LOCK"
        const val ACTION_RESTORE_LOCK = "com.jaydencyril67.screenlock.action.RESTORE_LOCK"
        const val PREFS = "phantom_lock_settings"
        const val PREF_ENABLED = "protection_enabled"

        private const val TAG = "PhantomLock"
        private const val CHANNEL_ID = "phantom_lock_service"
        private const val NOTIFICATION_ID = 7314
    }

    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (isProtectionEnabled()) {
                        Log.d(TAG, "Screen going non-interactive: preparing lock surface before next wake")
                        prepareLockScreen()
                    }
                }

                Intent.ACTION_SCREEN_ON -> {
                    if (isProtectionEnabled()) {
                        if (PhantomLockActivity.isAlive) {
                            Log.d(TAG, "Screen interactive: pre-prepared Phantom activity is already alive")
                        } else {
                            // Recovery path for process death, missed screen-off events,
                            // or a Samsung power-management restart while the display slept.
                            Log.w(TAG, "No prepared lock activity found; using screen-on fallback")
                            launchLockScreen(PhantomLockActivity.ACTION_WAKE)
                        }
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
                    .commit()
            }

            ACTION_RESTORE_LOCK -> {
                // Restore immediately when Home is pressed without completing unlock.
                // Do not add an app-side delay; Android/Samsung controls the transition.
                if (isProtectionEnabled() && Settings.canDrawOverlays(this)) {
                    Log.i(TAG, "Immediately restoring Phantom after Home")
                    launchLockScreen(PhantomLockActivity.ACTION_HOME_RESTORE)
                }
            }
        }

        if (!isProtectionEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_ENABLED, false)
                .commit()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun isProtectionEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, false)

    /**
     * Start and fully create the lock Activity while the display is non-interactive.
     * The Activity is showWhenLocked but deliberately does not turn the display on.
     * This moves Activity creation/layout work out of the screen-on race.
     */
    private fun prepareLockScreen() {
        if (!isProtectionEnabled() || !Settings.canDrawOverlays(this)) return

        if (PhantomLockActivity.isAlive) {
            Log.d(TAG, "Phantom lock activity already prepared")
            return
        }

        launchLockScreen(PhantomLockActivity.ACTION_PREPARE)
    }

    private fun launchLockScreen(action: String) {
        if (!isProtectionEnabled() || !Settings.canDrawOverlays(this)) return

        try {
            val intent = Intent(this, PhantomLockActivity::class.java).apply {
                this.action = action
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            startActivity(intent)
            Log.i(TAG, "Phantom lock Activity launch requested: $action")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to launch Phantom lock Activity ($action)", error)
        }
    }

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

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Phantom Lock is active")
            .setContentText("Running in the background. Open Phantom Lock to manage protection.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phantom Lock protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows that Phantom Lock protection is running in the background."
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun disableProtection() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_ENABLED, false)
            .commit()

        sendBroadcast(Intent(ACTION_CLOSE_LOCK).setPackage(packageName))
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
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
