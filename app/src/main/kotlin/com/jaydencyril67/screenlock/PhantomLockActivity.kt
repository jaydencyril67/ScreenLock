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
import android.hardware.camera2.CameraManager
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
 * Full-screen lock surface. The S20-scale fingerprint artwork remains the same
 * touch target and retains its existing one-use arm and biometric behavior.
 */
class PhantomLockActivity : Activity() {

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 4171
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
    private lateinit var mediaContainer: LinearLayout
    private lateinit var mediaTitle: TextView
    private lateinit var mediaArtwork: ImageView
    private lateinit var mediaSubtitle: TextView
    private lateinit var mediaPlayPause: TextView
    private var fingerprintView: ImageView? = null
    private var mediaController: MediaController? = null
    private var activeMediaToken: android.media.session.MediaSession.Token? = null
    private var externalShortcutLaunched = false
    private var flashEnabled = false
    private var flashCameraId: String? = null
    private lateinit var flashLabel: TextView

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
                clockLabel.text = SimpleDateFormat("HH  :  mm", Locale.getDefault()).format(now)
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

                PhantomNotificationData.ACTION_DATA_CHANGED -> refreshDynamicContent()

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
            PhantomWallpaperView(this),
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
            text = SimpleDateFormat("HH  :  mm", Locale.getDefault()).format(Date())
            textSize = 73f
            setTextColor(0xFFF5F1EA.toInt())
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            letterSpacing = -0.045f
            setShadowLayer(dp(18).toFloat(), 0f, dp(4).toFloat(), 0x775D3925)
        }
        root.addView(
            clockLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(104),
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.165f).toInt()
                marginStart = dp(25)
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
                topMargin = (resources.displayMetrics.heightPixels * 0.265f).toInt()
                marginStart = dp(29)
                marginEnd = dp(20)
            }
        )

        val dateRule = View(this).apply {
            setBackgroundColor(0x70D1B38A)
        }
        root.addView(
            dateRule,
            FrameLayout.LayoutParams(dp(54), dp(1), Gravity.TOP or Gravity.START).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.315f).toInt()
                marginStart = dp(30)
            }
        )

        val dateCaption = TextView(this).apply {
            text = "YOUR DAY  ·  YOUR DEVICE"
            textSize = 8f
            letterSpacing = 0.19f
            setTextColor(0xAFCFC6B8.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        }
        root.addView(
            dateCaption,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(20),
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.325f).toInt()
                marginStart = dp(30)
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

        val mediaMainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mediaArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x332F3540)
        }
        mediaMainRow.addView(
            mediaArtwork,
            LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginEnd = dp(13) }
        )
        val mediaTextColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mediaTitle = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFF3EFE8.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        mediaSubtitle = TextView(this).apply {
            textSize = 11f
            setTextColor(0xBFCAC1B7.toInt())
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        mediaTextColumn.addView(
            mediaTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(25)
            )
        )
        mediaTextColumn.addView(
            mediaSubtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(22)
            )
        )
        mediaMainRow.addView(
            mediaTextColumn,
            LinearLayout.LayoutParams(0, dp(58), 1f)
        )
        mediaContainer.addView(
            mediaMainRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(62)
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
                topMargin = (resources.displayMetrics.heightPixels * 0.405f).toInt()
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
        var fingerprintHoldTriggered = false
        var fingerprintTouchActive = false
        var fingerprintHoldRunnable: Runnable? = null

        val fingerprint = ImageView(this).apply {
            setImageResource(R.drawable.ic_s20_fingerprint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(0xFFF5F2ED.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Fingerprint — press and hold after arming"
            isClickable = true
            isFocusable = true
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        fingerprintTouchActive = true
                        fingerprintTouchStartedArmed = armed
                        fingerprintHoldTriggered = false
                        if (fingerprintTouchStartedArmed && !authenticating && !unlockPending) {
                            val hold = Runnable {
                                if (fingerprintTouchActive && fingerprintTouchStartedArmed &&
                                    armed && !authenticating && !unlockPending
                                ) {
                                    fingerprintHoldTriggered = true
                                    onFingerprintPressed()
                                }
                            }
                            fingerprintHoldRunnable = hold
                            handler.postDelayed(hold, 350L)
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        fingerprintTouchActive = false
                        fingerprintHoldRunnable?.let(handler::removeCallbacks)
                        fingerprintHoldRunnable = null
                        // An unarmed tap keeps the existing mismatch feedback.
                        // Once armed, a short tap does nothing; a 350ms hold unlocks.
                        if (!fingerprintTouchStartedArmed) {
                            onFingerprintPressed()
                        }
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
            LinearLayout.LayoutParams(dp(38), dp(38))
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
                bottomMargin = dp(110)
            }
        )

        val shortcutRow = FrameLayout(this)
        val flashShortcut = shortcutButton(R.drawable.ic_flashlight_shortcut, "FLASHLIGHT") {
            toggleFlashlight()
        }
        flashLabel = flashShortcut.second
        shortcutRow.addView(
            flashShortcut.first,
            FrameLayout.LayoutParams(dp(78), dp(64), Gravity.BOTTOM or Gravity.START)
        )
        val cameraShortcut = shortcutButton(R.drawable.ic_camera_shortcut, "CAMERA") {
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
        if (!::mediaContainer.isInitialized) return
        connectMediaSession()
        updateMediaContent()
    }

    private fun connectMediaSession() {
        val token = PhantomNotificationData.mediaToken()
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
        val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (artwork != null) {
            mediaArtwork.setImageBitmap(artwork)
        } else {
            mediaArtwork.setImageResource(R.drawable.ic_music_note)
        }
        mediaPlayPause.text =
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) "Ⅱ" else "▶"
    }

    private fun shortcutButton(
        iconResource: Int,
        caption: String,
        action: () -> Unit
    ): Pair<View, TextView> {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconResource)
            imageTintList = ColorStateList.valueOf(0xFFF4F0E9.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = caption
        }
        val label = TextView(this).apply {
            text = caption
            textSize = 8f
            letterSpacing = 0.14f
            setTextColor(0xC9D8D0C5.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        column.addView(icon, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(39)
        ))
        column.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(17)
        ))
        return Pair(column, label)
    }

    private fun toggleFlashlight() {
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

            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.CAMERA),
                    REQUEST_CAMERA_PERMISSION
                )
                return
            }

            flashEnabled = !flashEnabled
            manager.setTorchMode(cameraId, flashEnabled)
            flashLabel.text = if (flashEnabled) "FLASH ON" else "FLASHLIGHT"
        } catch (_: SecurityException) {
            flashEnabled = false
            flashLabel.text = "FLASHLIGHT"
            android.widget.Toast.makeText(
                this, "Allow camera permission to use the flashlight.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
            flashEnabled = false
            flashLabel.text = "FLASHLIGHT"
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
        if (requestCode == REQUEST_CAMERA_PERMISSION &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            toggleFlashlight()
        }
    }

    private fun launchCamera() {
        val cameraIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
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
        // The padlock remains visually and audibly silent.
        armed = false
        armRunnable?.let(handler::removeCallbacks)
        val pendingArm = Runnable {
            armed = true
            if (::fingerprintHint.isInitialized) {
                fingerprintHint.text = "PRESS & HOLD TO UNLOCK"
                fingerprintHint.setTextColor(0xFFE3DCD1.toInt())
            }
        }
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
            addAction(PhantomNotificationData.ACTION_DATA_CHANGED)
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

        // Home and Recents background Activities. Ask the foreground service to
        // restore Phantom; overlay permission is already required by setup.
        try {
            startService(Intent(this, PhantomOverlayService::class.java).apply {
                action = PhantomOverlayService.ACTION_RESTORE_LOCK
            })
        } catch (_: Exception) {
            // Protection may have been disabled while navigation was occurring.
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
