package com.jaydencyril67.screenlock

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.graphics.Typeface
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen lock surface. The S20-scale fingerprint artwork remains the same
 * touch target and retains its existing one-use arm and biometric behavior.
 */
class PhantomLockActivity : Activity() {

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 4171
        private const val REQUEST_LOCAL_MUSIC = 4172
        private const val CAMERA_ACTION_NONE = 0
        private const val CAMERA_ACTION_FLASH = 1
        private const val CAMERA_ACTION_OPEN_CAMERA = 2
        const val ACTION_PREPARE = "com.jaydencyril67.screenlock.action.PREPARE_LOCK"
        const val ACTION_AOD_SHOW = "com.jaydencyril67.screenlock.action.AOD_SHOW"
        const val ACTION_WAKE = "com.jaydencyril67.screenlock.action.WAKE_LOCK"
        const val ACTION_HOME_RESTORE = "com.jaydencyril67.screenlock.action.HOME_RESTORE"

        @Volatile
        var isAlive: Boolean = false
            private set

        @Volatile
        var isAodVisible: Boolean = false
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
    private lateinit var mediaContainer: LinearLayout
    private lateinit var mediaTitle: TextView
    private lateinit var mediaArtwork: ImageView
    private lateinit var mediaSubtitle: TextView
    private lateinit var mediaPlayPause: ImageView
    private lateinit var mediaPrevious: ImageView
    private lateinit var mediaNext: ImageView
    private lateinit var mediaChoose: TextView
    private var fingerprintView: ImageView? = null
    private var externalShortcutLaunched = false
    private var flashEnabled = false
    private var flashCameraId: String? = null
    private var pendingCameraAction = CAMERA_ACTION_NONE
    private lateinit var flashIcon: ImageView

    private val clockTick = object : Runnable {
        override fun run() {
            if (::clockLabel.isInitialized) {
                val now = Date()
                clockLabel.text = formatClockTime(now)
                dateLabel.text = SimpleDateFormat(
                    "EEEE, d MMMM",
                    Locale.getDefault()
                ).format(now).uppercase(Locale.getDefault())
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    private val clearMismatch = Runnable {
        if (::fingerprintHint.isInitialized && !unlockPending) {
            fingerprintHint.text = ""
            fingerprintHint.setTextColor(0xC9DCE9F8.toInt())
            fingerprintHint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Reset any in-progress unlock gesture. The foreground service
                    // owns the AOD session and starts the visible AOD Activity.
                    armed = false
                    unlockPending = false
                    armRunnable?.let(handler::removeCallbacks)
                    armRunnable = null
                    handler.removeCallbacks(clearMismatch)
                    if (::fingerprintHint.isInitialized) {
                        fingerprintHint.text = ""
                        fingerprintHint.setTextColor(0xC9DCE9F8.toInt())
                        fingerprintHint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                    }
                }

                LocalMusicPlaybackService.ACTION_STATE_CHANGED -> refreshDynamicContent()

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

        // AOD starts at screen-off and must turn the display on with its own
        // low-brightness window. Only the legacy prepare action stays passive.
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
        if (intent?.action == ACTION_AOD_SHOW) {
            buildAodScreen()
        } else {
            buildLockScreen()
        }
        handler.post(clockTick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val aodShow = intent.action == ACTION_AOD_SHOW
        val prepareOnly = intent.action == ACTION_PREPARE
        val homeRestore = intent.action == ACTION_HOME_RESTORE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(!prepareOnly && !homeRestore)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            @Suppress("DEPRECATION")            if (prepareOnly || homeRestore) {                window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
        if (homeRestore) {
            // Keep the current lockscreen state intact when returning from Home/Recents.
            applyImmersiveMode()
            return
        }
        if (aodShow) {
            armed = false
            unlockPending = false
            authenticating = false
            armRunnable?.let(handler::removeCallbacks)
            handler.removeCallbacks(clearMismatch)
            buildAodScreen()
            applyImmersiveMode()
            return
        }
        armed = false
        unlockPending = false
        authenticating = false
        armRunnable?.let(handler::removeCallbacks)
        handler.removeCallbacks(clearMismatch)
        if (::fingerprintHint.isInitialized) {
            fingerprintHint.text = ""
            fingerprintHint.setTextColor(0xC9DCE9F8.toInt())
        }
        fingerprintView?.animate()?.cancel()
        fingerprintView?.alpha = 1f
        fingerprintView?.scaleX = 1f
        fingerprintView?.scaleY = 1f
    }

    private fun buildAodScreen() {
        isAodVisible = true
        val prefs = getSharedPreferences(PhantomOverlayService.PREFS, MODE_PRIVATE)
        // Wake AOD is the only supported AOD mode and uses system brightness.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }

        var pendingAodTap: Runnable? = null
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            contentDescription = "Phantom always-on display. Double tap to open the lockscreen."
            setOnClickListener {
                val firstTap = pendingAodTap
                if (firstTap != null) {
                    handler.removeCallbacks(firstTap)
                    pendingAodTap = null
                    buildLockScreen()
                    applyImmersiveMode()
                } else {
                    val waitForSecondTap = Runnable { pendingAodTap = null }
                    pendingAodTap = waitForSecondTap
                    handler.postDelayed(waitForSecondTap, 320L)
                }
            }
        }
        val clockStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = false
        }
        clockLabel = TextView(this).apply {
            textSize = 78f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = -0.035f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(0xFFF4F0E9.toInt())
        }
        dateLabel = TextView(this).apply {
            textSize = 11f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            setTextColor(0xFFC8C0B4.toInt())
        }
        clockStack.addView(
            clockLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(100)
            )
        )
        clockStack.addView(
            dateLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(30)
            )
        )
        root.addView(
            clockStack,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                // Keep the clock/date group in the upper third, not screen-centred.
                topMargin = (resources.displayMetrics.heightPixels * 0.18f).toInt()
                leftMargin = dp(20)
                rightMargin = dp(20)
            }
        )

        val battery = TextView(this).apply {
            text = readBatteryPercent()
            textSize = 10f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFFAAA398.toInt())
            isClickable = true
            isFocusable = true
            contentDescription = "Tap to arm fingerprint unlock"
            setOnClickListener { armFingerprint() }
        }
        root.addView(
            battery,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(28),
                Gravity.BOTTOM
            ).apply { bottomMargin = dp(8) }
        )

        // Use the same fingerprint artwork, half-second hold and biometric
        // authentication path as the full Phantom lockscreen.
        val aodSensorStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        var aodFingerprintTouchActive = false
        var aodFingerprintStartedArmed = false
        var aodFingerprintHold: Runnable? = null
        val aodFingerprint = ImageView(this).apply {
            setImageResource(R.drawable.ic_s20_fingerprint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(
                0xFFE9E3D9.toInt()
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Fingerprint"
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        aodFingerprintTouchActive = true
                        aodFingerprintStartedArmed = armed
                        if (!authenticating && !unlockPending) {
                            val hold = Runnable {
                                if (aodFingerprintTouchActive &&
                                    armed == aodFingerprintStartedArmed &&
                                    !authenticating && !unlockPending
                                ) onFingerprintPressed()
                            }
                            aodFingerprintHold = hold
                            handler.postDelayed(hold, 500L)
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        aodFingerprintTouchActive = false
                        aodFingerprintHold?.let(handler::removeCallbacks)
                        aodFingerprintHold = null
                        view.isPressed = false
                        true
                    }
                    else -> true
                }
            }
        }
        fingerprintView = aodFingerprint
        aodSensorStack.addView(
            aodFingerprint,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )
        fingerprintHint = TextView(this).apply {
            text = ""
            textSize = 8f
            letterSpacing = 0.15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            0xFFB9B0A3.toInt()
            setPadding(0, dp(9), 0, 0)
        }
        aodSensorStack.addView(            fingerprintHint,            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(27)
            )
        )
        root.addView(
            aodSensorStack,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(92) }
        )

        setContentView(root)
        updateClock()
    }

    private fun buildLockScreen() {
        isAodVisible = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 8, 12))
            isFocusableInTouchMode = true
        }

        root.addView(
            PhantomWallpaperView(this),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Match the AOD battery indicator: centred, low on the screen,
        // with identical typography, colour and bottom spacing.
        batteryLabel = TextView(this).apply {
            text = readBatteryPercent()
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(0xFFAAA398.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            contentDescription = "Tap to arm fingerprint unlock"
            setOnClickListener { armFingerprint() }
        }
        root.addView(
            batteryLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(28),
                Gravity.BOTTOM
            ).apply { bottomMargin = dp(8) }
        )

        clockLabel = TextView(this).apply {
            text = formatClockTime(Date())
            textSize = 76f
            setTextColor(0xFFF7F2E9.toInt())
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            letterSpacing = -0.04f
            setShadowLayer(dp(20).toFloat(), 0f, dp(4).toFloat(), 0x885D3925.toInt())
        }
        root.addView(
            clockLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(112),
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.115f).toInt()
                marginStart = dp(25)
                marginEnd = dp(18)
            }
        )

        dateLabel = TextView(this).apply {
            textSize = 11f
            letterSpacing = 0.15f
            setTextColor(0xE7D9C7B5.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.START
            includeFontPadding = false
        }
        root.addView(
            dateLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(26),
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.265f).toInt()
                marginStart = dp(29)
                marginEnd = dp(20)
            }
        )

        val contentStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }

        mediaContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(0xC9141820.toInt())
                setStroke(dp(1), 0x28FFFFFF)
            }
            visibility = View.GONE
            setPadding(dp(13), dp(12), dp(13), dp(10))
            clipToOutline = true
        }

        val mediaMainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mediaArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(0xFF242A35.toInt())
            }
            clipToOutline = true
            contentDescription = "Album artwork"
        }
        mediaMainRow.addView(
            mediaArtwork,
            LinearLayout.LayoutParams(dp(62), dp(62)).apply { marginEnd = dp(13) }
        )
        val mediaTextColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mediaTitle = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFFFF9F0.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        mediaSubtitle = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFC4BBAF.toInt())
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        mediaTextColumn.addView(
            mediaTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(23)
            )
        )
        mediaTextColumn.addView(
            mediaSubtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(19)
            )
        )
        mediaMainRow.addView(
            mediaTextColumn,
            LinearLayout.LayoutParams(0, dp(62), 1f)
        )
        mediaContainer.addView(
            mediaMainRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(66)
            )
        )

        val mediaControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        mediaPrevious = mediaControl("previous", "Previous track") {
            sendMusicCommand(LocalMusicPlaybackService.ACTION_PREVIOUS)
        }
        mediaPlayPause = mediaControl("play", "Play or pause") {
            sendMusicCommand(LocalMusicPlaybackService.ACTION_TOGGLE)
        }
        mediaNext = mediaControl("next", "Next track") {
            sendMusicCommand(LocalMusicPlaybackService.ACTION_NEXT)
        }
        mediaControls.addView(mediaPrevious, LinearLayout.LayoutParams(dp(42), dp(42)))
        mediaControls.addView(mediaPlayPause, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginStart = dp(7)
            marginEnd = dp(7)
        })
        mediaControls.addView(mediaNext, LinearLayout.LayoutParams(dp(42), dp(42)))
        mediaControls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        mediaContainer.addView(
            mediaControls,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(50)
            )
        )

        mediaChoose = TextView(this).apply {
            text = "CHANGE MUSIC"
            textSize = 9f
            letterSpacing = 0.08f
            setTextColor(0xFFE4C49A.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            setPadding(dp(10), dp(7), dp(10), dp(7))
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.TRANSPARENT)
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Choose local music files"
            setOnClickListener { openLocalMusicPicker() }
        }
        mediaContainer.addView(
            mediaChoose,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(30)
            ).apply {
                topMargin = dp(2)
                gravity = Gravity.END
            }
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
                topMargin = (resources.displayMetrics.heightPixels * 0.395f).toInt()
                marginStart = dp(29)
                marginEnd = dp(31)
            }
        )

        // The compact S20-scale fingerprint remains completely standalone, with no ring, tile,
        // plate, shadow or background behind it.
        val sensorStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        var fingerprintTouchStartedArmed = false
        var fingerprintTouchActive = false
        var fingerprintHoldRunnable: Runnable? = null

        val fingerprint = ImageView(this).apply {
            setImageResource(R.drawable.ic_s20_fingerprint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(0xFFF5F2ED.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Fingerprint"
            isClickable = true
            isFocusable = true
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        fingerprintTouchActive = true
                        fingerprintTouchStartedArmed = armed

                        if (!authenticating && !unlockPending) {
                            // Both armed and unarmed touches use the same continuous
                            // half-second hold. The result is evaluated only at the threshold:
                            // armed = unlock; unarmed = mismatch.
                            val hold = Runnable {
                                if (fingerprintTouchActive &&
                                    armed == fingerprintTouchStartedArmed &&
                                    !authenticating && !unlockPending
                                ) {
                                    onFingerprintPressed()
                                }
                            }
                            fingerprintHoldRunnable = hold
                            handler.postDelayed(hold, 500L)
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        fingerprintTouchActive = false
                        fingerprintHoldRunnable?.let(handler::removeCallbacks)
                        fingerprintHoldRunnable = null
                        view.isPressed = false
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        fingerprintTouchActive = false
                        fingerprintHoldRunnable?.let(handler::removeCallbacks)
                        fingerprintHoldRunnable = null
                        view.isPressed = false
                        true
                    }
                    else -> true
                }
            }
        }
        fingerprintView = fingerprint
        sensorStack.addView(
            fingerprint,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        fingerprintHint = TextView(this).apply {
            text = ""
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
                bottomMargin = dp(110)
            }
        )

        val shortcutRow = FrameLayout(this)
        val flashShortcut = shortcutButton(R.drawable.ic_flashlight_shortcut, "Flashlight") {
            toggleFlashlight()
        }
        flashIcon = flashShortcut.second
        shortcutRow.addView(
            flashShortcut.first,
            FrameLayout.LayoutParams(dp(78), dp(64), Gravity.BOTTOM or Gravity.START)
        )
        val cameraShortcut = shortcutButton(R.drawable.ic_camera_shortcut, "Camera") {
            launchCamera()
        }
        shortcutRow.addView(
            cameraShortcut.first,
            FrameLayout.LayoutParams(dp(78), dp(64), Gravity.BOTTOM or Gravity.END)
        )
        root.addView(
            shortcutRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(70),
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(25)
                rightMargin = dp(25)
                bottomMargin = dp(17)
            }
        )

        setContentView(root)
        PhantomTypography.apply(root, this)
        updateClock()
        refreshDynamicContent()
    }

    private fun mediaControl(
        icon: String,
        description: String,
        action: () -> Unit
    ): ImageView = ImageView(this).apply {
        val isMain = description == "Play or pause"
        val tint = if (isMain) 0xFF10131A.toInt() else 0xFFECE5DA.toInt()
        setImageDrawable(MediaControlDrawable(icon, tint))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(11), dp(11), dp(11), dp(11))
        contentDescription = description
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isMain) 0xFFE8D3B4.toInt() else 0x1FFFFFFF)
            if (!isMain) setStroke(dp(1), 0x24FFFFFF)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun refreshDynamicContent() {
        updateMediaContent()
    }

    private fun updateMediaContent() {
        if (!::mediaContainer.isInitialized) return
        val prefs = getSharedPreferences(LocalMusicPlaybackService.PREFS, MODE_PRIVATE)
        val queue = prefs.getString(LocalMusicPlaybackService.KEY_QUEUE, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        val index = prefs.getInt(LocalMusicPlaybackService.KEY_INDEX, 0)
            .coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        val playing = prefs.getBoolean(LocalMusicPlaybackService.KEY_PLAYING, false)

        mediaContainer.visibility = View.VISIBLE
        if (queue.isEmpty()) {
            mediaTitle.text = "Your local music"
            mediaSubtitle.text = "Choose audio stored on this phone"
            mediaArtwork.setImageResource(R.drawable.ic_music_note)
            mediaChoose.text = "CHOOSE MUSIC FROM PHONE"
        } else {
            mediaTitle.text = prefs.getString(
                LocalMusicPlaybackService.KEY_TITLE,
                "Loading selected track…"
            )
            mediaSubtitle.text = prefs.getString(
                LocalMusicPlaybackService.KEY_ARTIST,
                "LOCAL AUDIO"
            )
            mediaChoose.text = "CHANGE LOCAL MUSIC"
            val bitmap = try {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, Uri.parse(queue[index]))
                    val bytes = retriever.embeddedPicture
                    if (bytes != null) {
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else null
                } finally {
                    retriever.release()
                }
            } catch (_: Exception) {
                null
            }
            if (bitmap != null) {
                mediaArtwork.setImageBitmap(bitmap)
            } else {
                mediaArtwork.setImageResource(R.drawable.ic_music_note)
            }
        }

        val hasMusic = queue.isNotEmpty()
        mediaPrevious.isEnabled = hasMusic
        mediaPrevious.alpha = if (hasMusic) 1f else 0.35f
        mediaPlayPause.isEnabled = hasMusic
        mediaPlayPause.alpha = if (hasMusic) 1f else 0.35f
        mediaNext.isEnabled = hasMusic
        mediaNext.alpha = if (hasMusic) 1f else 0.35f
        mediaPlayPause.setImageDrawable(
            MediaControlDrawable(
                if (playing) "pause" else "play",
                0xFF10131A.toInt()
            )
        )
    }

    private fun sendMusicCommand(action: String) {
        try {
            startService(
                Intent(this, LocalMusicPlaybackService::class.java).setAction(action)
            )
        } catch (_: Exception) {
            android.widget.Toast.makeText(
                this,
                "Could not control local music.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun openLocalMusicPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        externalShortcutLaunched = true
        try {
            startActivityForResult(picker, REQUEST_LOCAL_MUSIC)
        } catch (_: Exception) {
            externalShortcutLaunched = false
            android.widget.Toast.makeText(
                this,
                "No audio file picker is available on this device.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_LOCAL_MUSIC) return
        externalShortcutLaunched = false
        if (resultCode != RESULT_OK || data == null) return

        val selected = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let(selected::add)
            }
        } else {
            data.data?.let(selected::add)
        }

        val uris = selected.distinct()
        if (uris.isEmpty()) return

        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers grant temporary access only.
            }
        }

        val uriStrings = ArrayList(uris.map { it.toString() })
        val playerIntent = Intent(this, LocalMusicPlaybackService::class.java).apply {
            action = LocalMusicPlaybackService.ACTION_SET_QUEUE
            putStringArrayListExtra(LocalMusicPlaybackService.EXTRA_QUEUE, uriStrings)
        }
        try {
            startService(playerIntent)
            handler.postDelayed({ updateMediaContent() }, 250L)
        } catch (_: Exception) {
            android.widget.Toast.makeText(
                this,
                "Could not start the local music player.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun shortcutButton(
        iconResource: Int,
        caption: String,
        action: () -> Unit
    ): Pair<View, ImageView> {
        val button = FrameLayout(this).apply {
            // Keep the shortcut directly on the lockscreen: no enclosing
            // glass circle, card fill, or border. Retain only a subtle
            // touch ripple around the icon.
            background = null
            foreground = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                },
                GradientDrawable().apply { shape = GradientDrawable.OVAL }
            )
            isClickable = true
            isFocusable = true
            contentDescription = caption
            setOnClickListener { action() }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconResource)
            imageTintList = ColorStateList.valueOf(0xFFF5F1EA.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = caption
        }
        button.addView(icon, FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER))
        return Pair(button, icon)
    }

    private fun updateFlashIcon() {
        if (::flashIcon.isInitialized) {
            flashIcon.imageTintList = ColorStateList.valueOf(
                if (flashEnabled) 0xFFE5C18F.toInt() else 0xFFF5F1EA.toInt()
            )
        }
    }

    private fun requestCameraPermission(action: Int) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            runCameraAction(action)
            return
        }

        pendingCameraAction = action
        requestPermissions(
            arrayOf(android.Manifest.permission.CAMERA),
            REQUEST_CAMERA_PERMISSION
        )
    }

    private fun runCameraAction(action: Int) {
        when (action) {
            CAMERA_ACTION_FLASH -> toggleFlashlightWithPermission()
            CAMERA_ACTION_OPEN_CAMERA -> openCameraApp()
        }
    }

    private fun toggleFlashlight() {
        requestCameraPermission(CAMERA_ACTION_FLASH)
    }

    private fun toggleFlashlightWithPermission() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = flashCameraId ?: manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(
                    android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE
                ) == true
            }?.also { flashCameraId = it }

            if (cameraId == null) {
                android.widget.Toast.makeText(
                    this, "Flashlight is not available on this device.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return
            }

            flashEnabled = !flashEnabled
            manager.setTorchMode(cameraId, flashEnabled)
            updateFlashIcon()
        } catch (_: SecurityException) {
            flashEnabled = false
            updateFlashIcon()
            android.widget.Toast.makeText(
                this, "Camera permission is required to control the flashlight.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
            flashEnabled = false
            updateFlashIcon()
            android.widget.Toast.makeText(
                this, "Could not switch on the flashlight.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            val action = pendingCameraAction
            pendingCameraAction = CAMERA_ACTION_NONE

            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                runCameraAction(action)
            } else {
                val message = if (action == CAMERA_ACTION_FLASH) {
                    "Camera permission is needed to control the flashlight."
                } else {
                    "Camera permission is needed to use the camera shortcut."
                }
                android.widget.Toast.makeText(
                    this, message, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun launchCamera() {
        requestCameraPermission(CAMERA_ACTION_OPEN_CAMERA)
    }

    private fun openCameraApp() {
        var cameraIntent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (cameraIntent.resolveActivity(packageManager) == null) {
            cameraIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        }
        if (cameraIntent.resolveActivity(packageManager) == null) {
            android.widget.Toast.makeText(
                this, "No camera app is available.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        externalShortcutLaunched = true
        try {
            startActivity(cameraIntent)
        } catch (_: Exception) {
            externalShortcutLaunched = false
            android.widget.Toast.makeText(
                this, "Could not open the camera.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun armFingerprint() {
        // Battery tap arms the fingerprint immediately; the only detection timer
        // is the half-second continuous press-and-hold on the fingerprint.
        armRunnable?.let(handler::removeCallbacks)
        armRunnable = null
        armed = true
        unlockPending = false
        handler.removeCallbacks(clearMismatch)

        if (::fingerprintHint.isInitialized) {
            fingerprintHint.text = ""
            fingerprintHint.setTextColor(0xFFE3DCD1.toInt())
            fingerprintHint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }
    private fun onFingerprintPressed() {
        if (authenticating || unlockPending) return
        if (!armed) {
            showMismatch()
            return
        }

        // This method is called by the half-second hold timer while the finger
        // is still physically down. No ACTION_UP/release is needed.
        armed = false
        armRunnable?.let(handler::removeCallbacks)
        armRunnable = null
        handler.removeCallbacks(clearMismatch)
        unlockPending = true

        if (::fingerprintHint.isInitialized) {
            fingerprintHint.text = "VERIFYING ACCESS"
            fingerprintHint.setTextColor(0xFFE1F4FF.toInt())
        }

        val biometricManager = getSystemService(BIOMETRIC_SERVICE) as BiometricManager
        val availability = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )

        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            // Devices without a supported fingerprint reader use the existing
            // visual-unlock fallback immediately at the half-second threshold.
            unlockPending = false
            finishAfterFingerprintUnlock()
            return
        }

        unlockPending = false
        startBiometricPrompt()
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
                        finishAfterFingerprintUnlock()
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

    private fun finishAfterFingerprintUnlock() {
        finish()
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

    private fun formatClockTime(date: Date): String {
        val pattern = if (android.text.format.DateFormat.is24HourFormat(this)) {
            "HH:mm"
        } else {
            "h:mm a"
        }
        return SimpleDateFormat(pattern, Locale.getDefault())
            .format(date)
            .uppercase(Locale.getDefault())
    }

    private fun updateClock() {
        val now = Date()
        clockLabel.text = formatClockTime(now)
        dateLabel.text = SimpleDateFormat(
            "EEEE, d MMMM",
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
            addAction(LocalMusicPlaybackService.ACTION_STATE_CHANGED)
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
        // Back is navigation, not an authentication event. Keep Phantom visible.
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isFinishing || isDestroyed) return
        if (externalShortcutLaunched) {
            externalShortcutLaunched = false
            return
        }

        // Let the already-running foreground protection service restore the
        // existing singleTask lock Activity. Launching the Activity directly
        // from onUserLeaveHint races Android's transition to the launcher and
        // can silently leave Phantom behind Home on Android 11 / Samsung One UI.
        if (!getSharedPreferences(PhantomOverlayService.PREFS, MODE_PRIVATE)                .getBoolean(PhantomOverlayService.PREF_ENABLED, false)) return

        try {
            startService(Intent(this, PhantomOverlayService::class.java).apply {
                action = PhantomOverlayService.ACTION_RESTORE_LOCK            })
        } catch (_: Exception) {
            // The service may have been stopped while protection was being disabled.
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        isAlive = false
        isAodVisible = false
        handler.removeCallbacksAndMessages(null)
        armRunnable = null
        if (flashEnabled) {
            try {
                flashCameraId?.let {
                    (getSystemService(CAMERA_SERVICE) as CameraManager).setTorchMode(it, false)
                }
            } catch (_: Exception) { }
            flashEnabled = false
        }
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

/**
 * Small Material-style vector controls for the lockscreen media player.
 * Drawn locally so the buttons use real interface icons, never emoji glyphs.
 */
private class MediaControlDrawable(
    private val icon: String,
    private val tint: Int
) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = android.graphics.Paint.Style.FILL
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 24f, b.height() / 24f)
        when (icon) {
            "previous" -> {
                canvas.drawRect(4f, 5f, 6.5f, 19f, paint)
                val p = android.graphics.Path().apply {
                    moveTo(19f, 5f); lineTo(8f, 12f); lineTo(19f, 19f); close()
                }
                canvas.drawPath(p, paint)
            }
            "next" -> {
                canvas.drawRect(17.5f, 5f, 20f, 19f, paint)
                val p = android.graphics.Path().apply {
                    moveTo(5f, 5f); lineTo(16f, 12f); lineTo(5f, 19f); close()
                }
                canvas.drawPath(p, paint)
            }
            "pause" -> {
                canvas.drawRoundRect(6f, 4.5f, 10f, 19.5f, 1f, 1f, paint)
                canvas.drawRoundRect(14f, 4.5f, 18f, 19.5f, 1f, 1f, paint)
            }
            else -> {
                val p = android.graphics.Path().apply {
                    moveTo(7f, 4.5f); lineTo(19f, 12f); lineTo(7f, 19.5f); close()
                }
                canvas.drawPath(p, paint)
            }
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
