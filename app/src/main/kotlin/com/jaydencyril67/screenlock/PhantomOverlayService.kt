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
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast

/**
 * Owns Phantom protection and its lock window.
 *
 * The lock surface is attached directly to WindowManager. It no longer depends
 * on Android repeatedly bringing PhantomLockActivity to the foreground when
 * Home, Recents or the display state changes.
 */
class PhantomOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.jaydencyril67.screenlock.action.START"
        const val ACTION_STOP = "com.jaydencyril67.screenlock.action.STOP"
        const val ACTION_CLOSE_LOCK = "com.jaydencyril67.screenlock.action.CLOSE_LOCK"
        const val ACTION_RESTORE_LOCK = "com.jaydencyril67.screenlock.action.RESTORE_LOCK"
        const val ACTION_SHOW_LOCK = "com.jaydencyril67.screenlock.action.SHOW_LOCK"
        const val PREFS = "phantom_lock_settings"
        const val PREF_ENABLED = "protection_enabled"
        const val PREF_SKIP_HOME_RESTORE_ONCE = "skip_home_restore_once"
        const val PREF_UNLOCKED_UNTIL_SCREEN_OFF = "unlocked_until_screen_off"

        private const val TAG = "PhantomLock"
        private const val CHANNEL_ID = "phantom_lock_service"
        private const val NOTIFICATION_ID = 7314
    }

    private var screenReceiverRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var lockSurface: PhantomLockSurface? = null
    private var torchEnabled = false
    private var torchCameraId: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (isProtectionEnabled()) {
                        // Never leave the custom surface attached to a sleeping display.
                        // The next SCREEN_ON event is the only normal path that shows it.
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean(PREF_UNLOCKED_UNTIL_SCREEN_OFF, false)
                            .apply()
                        removeLockSurface()
                        Log.d(TAG, "Display off: lock surface hidden; next wake will be protected")
                    }
                }

                Intent.ACTION_SCREEN_ON -> {
                    if (isProtectionEnabled() && !isUnlockedUntilScreenOff()) {
                        mainHandler.postDelayed({
                            if (isProtectionEnabled() && !isUnlockedUntilScreenOff()) {
                                showLockSurface()
                            }
                        }, 180L)
                    }
                }

                LocalMusicPlaybackService.ACTION_STATE_CHANGED -> {
                    lockSurface?.refreshMedia()
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

            ACTION_START -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PREF_ENABLED, true)
                    .putBoolean(PREF_UNLOCKED_UNTIL_SCREEN_OFF, false)
                    .commit()
            }

            ACTION_RESTORE_LOCK, ACTION_SHOW_LOCK -> {
                // Kept as a compatibility command for older Phantom components.
                mainHandler.post { showLockSurface() }
            }
        }

        if (!isProtectionEnabled()) {
            removeLockSurface()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_ENABLED, false)
                .commit()
            removeLockSurface()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        // START_STICKY can recreate this service without its original intent.
        // Restore the surface only when the user has not already unlocked this
        // display session.
        if (intent == null && !isUnlockedUntilScreenOff()) {
            mainHandler.post { showLockSurface() }
        }

        return START_STICKY
    }

    private fun isProtectionEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, false)

    private fun isUnlockedUntilScreenOff(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(PREF_UNLOCKED_UNTIL_SCREEN_OFF, false)

    private fun showLockSurface() {
        if (!isProtectionEnabled() || isUnlockedUntilScreenOff()) return

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission was revoked; disabling Phantom protection")
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_ENABLED, false)
                .commit()
            removeLockSurface()
            stopSelf()
            return
        }

        if (lockSurface != null) return

        val surface = PhantomLockSurface(
            context = ContextThemeWrapper(this, R.style.AppTheme),
            onUnlock = { completeUnlock() },
            onFlashlight = { toggleFlashlight() },
            onCamera = { openCameraShortcut() },
            onChooseMusic = { openMusicPicker() }
        )

        val flags = (
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 1f
            dimAmount = 0f
            setTitle("Phantom Lock Surface")
        }

        try {
            windowManager?.addView(surface, params)
            lockSurface = surface
            Log.i(TAG, "Service-owned Phantom lock surface attached")
        } catch (error: Exception) {
            surface.dispose()
            Log.e(TAG, "Unable to attach Phantom lock surface", error)
            Toast.makeText(
                this,
                "Phantom could not display its lock surface. Check overlay access.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun completeUnlock() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_UNLOCKED_UNTIL_SCREEN_OFF, true)
            .putBoolean(PREF_SKIP_HOME_RESTORE_ONCE, true)
            .commit()
        removeLockSurface()
        Log.i(TAG, "Phantom unlocked for the current display session")
    }

    private fun removeLockSurface() {
        val surface = lockSurface ?: return
        lockSurface = null
        surface.dispose()
        try {
            windowManager?.removeView(surface)
        } catch (_: Exception) {
            // The window may already have been detached by the system.
        }
    }

    private fun toggleFlashlight() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(
                this,
                "Allow camera access in Phantom settings to use the flashlight.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = torchCameraId ?: manager.cameraIdList.firstOrNull { cameraId ->
                manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }?.also { torchCameraId = it }

            if (id == null) {
                Toast.makeText(this, "Flashlight is not available on this device.", Toast.LENGTH_SHORT).show()
                return
            }

            torchEnabled = !torchEnabled
            manager.setTorchMode(id, torchEnabled)
            lockSurface?.setFlashlightOn(torchEnabled)
        } catch (error: Exception) {
            torchEnabled = false
            lockSurface?.setFlashlightOn(false)
            Log.e(TAG, "Unable to change flashlight state", error)
            Toast.makeText(this, "Could not switch the flashlight.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCameraShortcut() {
        removeLockSurface()
        try {
            startActivity(
                Intent(this, PhantomCameraShortcutActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unable to open camera shortcut", error)
            showLockSurface()
        }
    }

    private fun openMusicPicker() {
        removeLockSurface()
        try {
            startActivity(
                Intent(this, PhantomMusicPickerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unable to open music picker", error)
            showLockSurface()
        }
    }

    private fun disableProtection() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_ENABLED, false)
            .putBoolean(PREF_UNLOCKED_UNTIL_SCREEN_OFF, false)
            .commit()

        removeLockSurface()
        turnTorchOff()
        sendBroadcast(Intent(ACTION_CLOSE_LOCK).setPackage(packageName))
        stopForeground(true)
        stopSelf()
    }

    private fun turnTorchOff() {
        if (!torchEnabled) return
        try {
            torchCameraId?.let {
                (getSystemService(CAMERA_SERVICE) as CameraManager).setTorchMode(it, false)
            }
        } catch (_: Exception) {
            // The camera may already be unavailable.
        }
        torchEnabled = false
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(LocalMusicPlaybackService.ACTION_STATE_CHANGED)
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
            .setContentText("Protection is running. Open Phantom to manage it.")
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

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        removeLockSurface()
        turnTorchOff()
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