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
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.app.KeyguardManager

class PhantomOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.jaydencyril67.screenlock.action.START"
        const val ACTION_STOP = "com.jaydencyril67.screenlock.action.STOP"
        const val ACTION_AUTHENTICATED = "com.jaydencyril67.screenlock.action.AUTHENTICATED"
        const val ACTION_AUTH_CANCELLED = "com.jaydencyril67.screenlock.action.AUTH_CANCELLED"
        const val PREFS = "phantom_lock_settings"
        const val PREF_ENABLED = "protection_enabled"
        private const val CHANNEL_ID = "phantom_lock_service"
        private const val NOTIFICATION_ID = 7314
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var lockView: PhantomOverlayView? = null
    private var screenReceiverRegistered = false
    private var authInProgress = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    authInProgress = false
                    removeLockOverlay()
                }
                Intent.ACTION_USER_PRESENT -> {
                    handler.postDelayed({
                        if (isProtectionEnabled()) showLockOverlay()
                    }, 350L)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                disableProtection()
                return START_NOT_STICKY
            }
            ACTION_AUTHENTICATED -> {
                authInProgress = false
                removeLockOverlay()
                if (isProtectionEnabled()) startForeground(NOTIFICATION_ID, buildNotification())
                return START_STICKY
            }
            ACTION_AUTH_CANCELLED -> {
                authInProgress = false
                lockView?.showReady()
                if (isProtectionEnabled()) startForeground(NOTIFICATION_ID, buildNotification())
                return START_STICKY
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

        if (!Settings.canDrawOverlays(this)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_ENABLED, false)
                .apply()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val power = getSystemService(POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (power.isInteractive && !keyguard.isKeyguardLocked) {
            showLockOverlay()
        } else {
            removeLockOverlay()
        }

        return START_STICKY
    }

    private fun isProtectionEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_ENABLED, false)

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
        screenReceiverRegistered = true
    }

    private fun showLockOverlay() {
        if (!isProtectionEnabled() || authInProgress || lockView != null) return
        if (!Settings.canDrawOverlays(this)) return

        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (!power.isInteractive || keyguard.isKeyguardLocked) return

        val view = PhantomOverlayView(this) { requestSystemAuthentication() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        try {
            windowManager.addView(view, params)
            lockView = view
        } catch (_: Exception) {
            lockView = null
        }
    }

    private fun removeLockOverlay() {
        val view = lockView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // The system may already have removed the overlay during shutdown.
        }
        lockView = null
    }

    private fun requestSystemAuthentication() {
        if (authInProgress) return
        authInProgress = true
        lockView?.showVerifying()

        try {
            val intent = Intent(this, CredentialAuthActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            authInProgress = false
            lockView?.showReady()
        }
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
            .setContentText("Your Android lock screen is backed by a second lock layer.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn off", stopService)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phantom Lock protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Phantom Lock ready to protect the phone after screen unlock."
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
        authInProgress = false
        removeLockOverlay()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeLockOverlay()
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
