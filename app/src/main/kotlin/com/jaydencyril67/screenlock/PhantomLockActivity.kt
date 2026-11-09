package com.jaydencyril67.screenlock

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen lock surface. The 72dp fingerprint artwork remains the same
 * touch target and retains its existing one-use arm and biometric behavior.
 */
class PhantomLockActivity : Activity() {

    companion object {
        const val ACTION_PREPARE = "com.jaydencyril67.screenlock.action.PREPARE_LOCK"
        const val ACTION_WAKE = "com.jaydencyril67.screenlock.action.WAKE_LOCK"

        @Volatile
        var isAlive: Boolean = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var armed = false
    private var authenticating = false
    private var unlockPending = false
    private var receiverRegistered = false
    private var armRunnable: Runnable? = null

    private lateinit var clockLabel: TextView
    private lateinit var dateLabel: TextView
    private lateinit var batteryLabel: TextView
    private lateinit var fingerprintHint: TextView
    private lateinit var notificationHeading: TextView
    private lateinit var notificationContainer: LinearLayout
    private lateinit var mediaContainer: LinearLayout
    private lateinit var mediaTitle: TextView
    private lateinit var mediaSubtitle: TextView
    private lateinit var mediaPlayPause: TextView
    private var fingerprintView: ImageView? = null
    private var mediaController: MediaController? = null
    private var activeMediaToken: android.media.session.MediaSession.Token? = null

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMediaContent()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateMediaContent()
        }

        override fun onSessionDestroyed() {
            mediaController = null
            activeMediaToken = null
            updateMediaContent()
        }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            if (::clockLabel.isInitialized) {
                val now = Date()
                clockLabel.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                dateLabel.text = SimpleDateFormat(
                    "EEEE  ·  d MMMM",
                    Locale.getDefault()
                ).format(now).uppercase(Locale.getDefault())
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    private val clearMismatch = Runnable {
        if (::fingerprintHint.isInitialized && !unlockPending) {
            fingerprintHint.text = "TOUCH TO UNLOCK"
            fingerprintHint.setTextColor(0xC9DCE9F8.toInt())
            fingerprintHint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Keep this Activity alive underneath the sleeping display.
                    // Its showWhenLocked window is already prepared for the next wake.
                    armed = false
                    unlockPending = false
                    armRunnable?.let(handler::removeCallbacks)
                    armRunnable = null
                    handler.removeCallbacks(clearMismatch)
                    if (::fingerprintHint.isInitialized) {
                        fingerprintHint.text = "TOUCH TO UNLOCK"
                        fingerprintHint.setTextColor(0xC9DCE9F8.toInt())
                        fingerprintHint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                    }
                }

                PhantomNotificationListenerService.ACTION_DATA_CHANGED -> {
                    if (::notificationContainer.isInitialized) refreshDynamicContent()
                }

                PhantomOverlayService.ACTION_CLOSE_LOCK -> {
                    armed = false
                    unlockPending = false
                    armRunnable?.let(handler::removeCallbacks)
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isAlive = true

        // Prepare mode is launched as the device goes to sleep. It must NOT wake
        // the display; it only places this show-when-locked Activity in front so
        // its already-created window is ready when the user wakes the phone.
        val prepareOnly = intent?.action == ACTION_PREPARE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(!prepareOnly)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            if (!prepareOnly) {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        applyImmersiveMode()
        registerScreenOffReceiver()
        buildLockScreen()
        handler.post(clockTick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val prepareOnly = intent.action == ACTION_PREPARE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(!prepareOnly)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            @Suppress("DEPRECATION")
            if (prepareOnly) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
        armed = false
        unlockPending = false
        authenticating = false
        armRunnable?.let(handler::removeCallbacks)
        handler.removeCallbacks(clearMismatch)
        if (::fingerprintHint.isInitialized) {
            fingerprintHint.text = "TOUCH TO UNLOCK"
            fingerprintHint.setTextColor(0xC9DCE9F8.toInt())
        }
        fingerprintView?.animate()?.cancel()
        fingerprintView?.alpha = 1f
        fingerprintView?.scaleX = 1f
        fingerprintView?.scaleY = 1f
    }

    private fun buildLockScreen() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 8, 12))
            isFocusableInTouchMode = true
        }

        root.addView(
            LockscreenWallpaperView(this),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Plain system information sits directly on the wallpaper.
        batteryLabel = TextView(this).apply {
            text = readBatteryPercent()
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(0xDDE8E7E4.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        root.addView(
            batteryLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(28),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(29)
                marginEnd = dp(67)
            }
        )

        val padlock = ImageButton(this).apply {
            setImageResource(R.drawable.ic_phantom_padlock)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(0xFFECEAE6.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Arm fingerprint unlock"
            setPadding(0, 0, 0, 0)
            setOnClickListener { armFingerprint() }
        }
        root.addView(
            padlock,
            FrameLayout.LayoutParams(dp(25), dp(25), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(29)
                marginEnd = dp(25)
            }
        )

        clockLabel = TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            textSize = 88f
            setTextColor(0xFFF5F1EA.toInt())
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            letterSpacing = -0.075f
            setShadowLayer(dp(18).toFloat(), 0f, dp(4).toFloat(), 0x775D3925)
        }
        root.addView(
            clockLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(104),
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.155f).toInt()
                marginStart = dp(27)
                marginEnd = dp(20)
            }
        )

        dateLabel = TextView(this).apply {
            textSize = 12f
            letterSpacing = 0.11f
            setTextColor(0xD9D6CFC5.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.START
            includeFontPadding = false
        }
        root.addView(
            dateLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(28),
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.285f).toInt()
                marginStart = dp(31)
                marginEnd = dp(20)
            }
        )

        val contentStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }

        notificationHeading = TextView(this).apply {
            text = "NOTIFICATIONS"
            textSize = 9f
            letterSpacing = 0.2f
            setTextColor(0xAFC7BEB0.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            visibility = View.GONE
        }
        contentStack.addView(
            notificationHeading,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(20)
            )
        )

        notificationContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = null
        }
        contentStack.addView(
            notificationContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        mediaContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = null
            visibility = View.GONE
            setPadding(0, dp(13), 0, 0)
        }
        val mediaHeading = TextView(this).apply {
            text = "NOW PLAYING"
            textSize = 9f
            letterSpacing = 0.2f
            setTextColor(0xAFC7BEB0.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        }
        mediaContainer.addView(
            mediaHeading,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(19)
            )
        )

        mediaTitle = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFF3EFE8.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        mediaContainer.addView(
            mediaTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(24)
            )
        )

        mediaSubtitle = TextView(this).apply {
            textSize = 11f
            setTextColor(0xBFCAC1B7.toInt())
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        mediaContainer.addView(
            mediaSubtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(20)
            )
        )

        val mediaControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val previous = mediaControl("‹", "Previous track") {
            mediaController?.transportControls?.skipToPrevious()
        }
        mediaPlayPause = mediaControl("Ⅱ", "Play or pause") {
            val controller = mediaController ?: return@mediaControl
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
        }
        val next = mediaControl("›", "Next track") {
            mediaController?.transportControls?.skipToNext()
        }
        mediaControls.addView(previous, LinearLayout.LayoutParams(dp(48), dp(38)))
        mediaControls.addView(mediaPlayPause, LinearLayout.LayoutParams(dp(48), dp(38)))
        mediaControls.addView(next, LinearLayout.LayoutParams(dp(48), dp(38)))
        mediaContainer.addView(
            mediaControls,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(42)
            )
        )
        contentStack.addView(
            mediaContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            contentStack,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.385f).toInt()
                marginStart = dp(31)
                marginEnd = dp(31)
            }
        )

        // The fingerprint is the only object in its touch area: exactly 72dp,
        // with no ring, tile, plate, shadow or background behind it.
        val sensorStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val fingerprint = ImageView(this).apply {
            setImageResource(R.drawable.ic_s20_fingerprint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(0xFFF5F2ED.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Fingerprint"
            isClickable = true
            isFocusable = true
            setOnClickListener { onFingerprintPressed() }
        }
        fingerprintView = fingerprint
        sensorStack.addView(
            fingerprint,
            LinearLayout.LayoutParams(dp(72), dp(72))
        )

        fingerprintHint = TextView(this).apply {
            text = "TOUCH TO UNLOCK"
            textSize = 9f
            letterSpacing = 0.19f
            setTextColor(0xC9E3DCD1.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, dp(13), 0, 0)
        }
        sensorStack.addView(
            fingerprintHint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(31)
            )
        )
        root.addView(
            sensorStack,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(49)
            }
        )

        setContentView(root)
        updateClock()
        refreshDynamicContent()
    }

    private fun mediaControl(
        glyph: String,
        description: String,
        action: () -> Unit
    ): TextView = TextView(this).apply {
        text = glyph
        textSize = 27f
        setTextColor(0xFFECE5DA.toInt())
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        gravity = Gravity.CENTER
        includeFontPadding = false
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun refreshDynamicContent() {
        if (!::notificationContainer.isInitialized) return
        renderNotifications()
        connectMediaSession()
        updateMediaContent()
    }

    private fun renderNotifications() {
        notificationContainer.removeAllViews()
        val items = PhantomNotificationListenerService.notifications().take(3)
        notificationHeading.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        notificationContainer.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        items.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(8))
                isClickable = item.contentIntent != null
                isFocusable = item.contentIntent != null
                if (item.contentIntent != null) {
                    setOnClickListener {
                        try {
                            item.contentIntent.send()
                        } catch (_: Exception) {
                            // The originating app may have cancelled its PendingIntent.
                        }
                    }
                }
            }

            val app = TextView(this).apply {
                text = item.appName.uppercase(Locale.getDefault())
                textSize = 8f
                letterSpacing = 0.13f
                setTextColor(0xAFC7BEB0.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                includeFontPadding = false
                maxLines = 1
            }
            row.addView(
                app,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(15)
                )
            )

            val title = TextView(this).apply {
                text = item.title
                textSize = 14f
                setTextColor(0xFFF2EEE7.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            row.addView(
                title,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(21)
                )
            )

            if (item.message.isNotBlank()) {
                val message = TextView(this).apply {
                    text = item.message
                    textSize = 11f
                    setTextColor(0xC6D0C8BE.toInt())
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    includeFontPadding = false
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                row.addView(
                    message,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            notificationContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            if (index < items.lastIndex) {
                val divider = View(this).apply {
                    setBackgroundColor(0x28E7D9C8)
                }
                notificationContainer.addView(
                    divider,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)
                    ).apply {
                        topMargin = dp(3)
                    }
                )
            }
        }
    }

    private fun connectMediaSession() {
        val token = PhantomNotificationListenerService.mediaToken()
        if (token == activeMediaToken) return

        try {
            mediaController?.unregisterCallback(mediaCallback)
        } catch (_: Exception) {
            // The session may already have disconnected.
        }
        mediaController = null
        activeMediaToken = token

        if (token != null) {
            try {
                mediaController = MediaController(this, token).also {
                    it.registerCallback(mediaCallback, handler)
                }
            } catch (_: Exception) {
                mediaController = null
                activeMediaToken = null
            }
        }
    }

    private fun updateMediaContent() {
        if (!::mediaContainer.isInitialized) return
        val controller = mediaController
        val metadata = controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() }
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?.takeIf { it.isNotBlank() }

        if (controller == null || title == null) {
            mediaContainer.visibility = View.GONE
            return
        }

        mediaContainer.visibility = View.VISIBLE
        mediaTitle.text = title
        mediaSubtitle.text = artist.orEmpty()
        mediaPlayPause.text =
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) "Ⅱ" else "▶"
    }

    private fun armFingerprint() {
        // The padlock remains visually and audibly silent.
        armed = false
        armRunnable?.let(handler::removeCallbacks)
        val pendingArm = Runnable { armed = true }
        armRunnable = pendingArm
        handler.postDelayed(pendingArm, 180L)
    }

    private fun onFingerprintPressed() {
        if (authenticating || unlockPending) return

        if (!armed) {
            showMismatch()
            return
        }

        // One-use arming. A short verification beat prevents an instant dismissal.
        armed = false
        armRunnable?.let(handler::removeCallbacks)
        armRunnable = null
        handler.removeCallbacks(clearMismatch)
        unlockPending = true
        fingerprintHint.text = "VERIFYING ACCESS"
        fingerprintHint.setTextColor(0xFFE1F4FF.toInt())

        fingerprintView?.animate()
            ?.alpha(0.72f)
            ?.scaleX(0.94f)
            ?.scaleY(0.94f)
            ?.setDuration(150L)
            ?.withEndAction {
                fingerprintView?.animate()
                    ?.alpha(1f)
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.setDuration(180L)
                    ?.start()
            }
            ?.start()

        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            unlockPending = false
            fingerprintHint.text = "TOUCH TO UNLOCK"
            fingerprintHint.setTextColor(0xC9DCE9F8.toInt())

            val biometricManager = getSystemService(BIOMETRIC_SERVICE) as BiometricManager
            val availability = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            )

            if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
                // The Galaxy A02 has no fingerprint reader. Keep its agreed visual
                // unlock fallback rather than requiring a system PIN or password.
                finish()
                return@postDelayed
            }

            startBiometricPrompt()
        }, 470L)
    }

    private fun startBiometricPrompt() {
        authenticating = true

        try {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("Verify fingerprint")
                .setSubtitle("Touch the fingerprint sensor")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton(
                    "Cancel",
                    mainExecutor,
                    DialogInterface.OnClickListener { _, _ ->
                        authenticating = false
                        showMismatch()
                    }
                )
                .build()

            prompt.authenticate(
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        super.onAuthenticationSucceeded(result)
                        authenticating = false
                        finish()
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        showMismatch()
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence
                    ) {
                        super.onAuthenticationError(errorCode, errString)
                        authenticating = false
                        showMismatch()
                    }
                }
            )
        } catch (_: Exception) {
            authenticating = false
            showMismatch()
        }
    }

    private fun showMismatch() {
        if (!::fingerprintHint.isInitialized) return

        fingerprintHint.text = "Fingerprint doesn't match"
        fingerprintHint.setTextColor(0xFFFF667A.toInt())
        fingerprintHint.setShadowLayer(
            dp(12).toFloat(),
            0f,
            0f,
            0x88FF334F.toInt()
        )
        handler.removeCallbacks(clearMismatch)
        handler.postDelayed(clearMismatch, 1_900L)
        vibrateMismatch()
    }

    private fun vibrateMismatch() {
        try {
            val effect = VibrationEffect.createWaveform(
                longArrayOf(0L, 42L, 48L, 42L),
                -1
            )
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(effect)
        } catch (_: Exception) {
            // Some devices have no vibrator or have haptics disabled by the user.
        }
    }

    private fun updateClock() {
        val now = Date()
        clockLabel.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        dateLabel.text = SimpleDateFormat(
            "EEEE  ·  d MMMM",
            Locale.getDefault()
        ).format(now).uppercase(Locale.getDefault())
    }

    private fun readBatteryPercent(): String {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return "BATTERY"
        return (level * 100 / scale).toString() + "%  BATTERY"
    }

    private fun registerScreenOffReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PhantomOverlayService.ACTION_CLOSE_LOCK)
            addAction(PhantomNotificationListenerService.ACTION_DATA_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenOffReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // The lock surface remains active until its armed unlock or screen-off.
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * Procedural, resolution-independent wallpaper art. It stays sharp at every
     * density and avoids depending on a flattened lockscreen screenshot.
     */
    private class LockscreenWallpaperView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density
        private val frame = RectF()
        private val accentPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            // Matte charcoal-to-ink base with a warm copper atmosphere.
            paint.shader = LinearGradient(
                0f, 0f, w * 0.34f, h,
                intArrayOf(
                    Color.rgb(12, 11, 13),
                    Color.rgb(22, 17, 18),
                    Color.rgb(13, 13, 17),
                    Color.rgb(5, 7, 10)
                ),
                floatArrayOf(0f, 0.38f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // A soft amber horizon replaces the previous blue glass lighting.
            paint.shader = RadialGradient(
                w * 0.86f, h * 0.34f, w * 0.78f,
                intArrayOf(
                    0x55B66A3D,
                    0x2C83472F,
                    0x142E2020,
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.32f, 0.66f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            paint.shader = RadialGradient(
                w * 0.08f, h * 0.78f, w * 0.9f,
                intArrayOf(
                    0x302E4653,
                    0x17202A35,
                    Color.TRANSPARENT
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // Fine engraved curves are part of the wallpaper, not UI surfaces.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density * 0.75f
            paint.color = 0x18E7B58C
            canvas.drawCircle(w * 0.91f, h * 0.32f, w * 0.39f, paint)
            paint.color = 0x10E7B58C
            canvas.drawCircle(w * 0.91f, h * 0.32f, w * 0.47f, paint)
            paint.color = 0x0DDFD1C3
            canvas.drawCircle(w * 0.91f, h * 0.32f, w * 0.56f, paint)

            accentPath.reset()
            accentPath.moveTo(-w * 0.12f, h * 0.58f)
            accentPath.cubicTo(
                w * 0.28f, h * 0.44f,
                w * 0.56f, h * 0.64f,
                w * 1.12f, h * 0.46f
            )
            paint.color = 0x12E5B28B
            paint.strokeWidth = density * 1.15f
            canvas.drawPath(accentPath, paint)
            accentPath.reset()
            accentPath.moveTo(-w * 0.1f, h * 0.63f)
            accentPath.cubicTo(
                w * 0.31f, h * 0.49f,
                w * 0.64f, h * 0.7f,
                w * 1.08f, h * 0.53f
            )
            paint.color = 0x0CE5B28B
            canvas.drawPath(accentPath, paint)
            paint.style = Paint.Style.FILL

            // Keep the lower edge naturally dark behind the standalone sensor.
            paint.shader = LinearGradient(
                0f, h * 0.73f, 0f, h,
                intArrayOf(Color.TRANSPARENT, 0x30030305, 0xCC030406.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
            frame.set(0f, h * 0.73f, w, h)
            canvas.drawRect(frame, paint)
            paint.shader = null
        }
    }

    override fun onDestroy() {
        isAlive = false
        handler.removeCallbacksAndMessages(null)
        armRunnable = null
        try {
            mediaController?.unregisterCallback(mediaCallback)
        } catch (_: Exception) {
            // Session already disconnected.
        }
        mediaController = null
        activeMediaToken = null
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenOffReceiver)
            } catch (_: Exception) {
                // Already unregistered.
            }
        }
        super.onDestroy()
    }
}
