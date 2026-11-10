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
    private lateinit var mediaPrevious: TextView
    private lateinit var mediaNext: TextView
    private lateinit var mediaChoose: TextView
    private var fingerprintView: ImageView? = null
    private var externalShortcutLaunched = false
    private var flashEnabled = false
    private var flashCameraId: String? = null
    private var pendingCameraAction = CAMERA_ACTION_NONE
    private lateinit var flashLabel: TextView

    private val clockTick = object : Runnable {
        override fun run() {
            if (::clockLabel.isInitialized) {
                val now = Date()
                clockLabel.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                dateLabel.text = SimpleDateFormat(
                    "EEEE  ·  d MMMM yyyy",
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
            isClickable = true
            isFocusable = true
            contentDescription = "Battery status"
            setOnClickListener { armFingerprint() }
        }
        root.addView(
            batteryLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(28),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(29)
                marginEnd = dp(25)
            }
        )

        clockLabel = TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            textSize = 78f
            setTextColor(0xFFF7F2E9.toInt())
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            letterSpacing = -0.055f
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

        val dateRule = View(this).apply {
            setBackgroundColor(0x90D1B38A.toInt())
        }
        root.addView(
            dateRule,
            FrameLayout.LayoutParams(dp(62), dp(1), Gravity.TOP or Gravity.START).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.305f).toInt()
                marginStart = dp(30)
            }
        )

        val dateCaption = TextView(this).apply {
            text = "LOCAL TIME  ·  24-HOUR FORMAT"
            textSize = 8f
            letterSpacing = 0.18f
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
                topMargin = (resources.displayMetrics.heightPixels * 0.322f).toInt()
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
            text = "LOCAL MUSIC"
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
        mediaPrevious = mediaControl("‹", "Previous track") {
            sendMusicCommand(LocalMusicPlaybackService.ACTION_PREVIOUS)
        }
        mediaPlayPause = mediaControl("▶", "Play or pause") {
            sendMusicCommand(LocalMusicPlaybackService.ACTION_TOGGLE)
        }
        mediaNext = mediaControl("›", "Next track") {
            sendMusicCommand(LocalMusicPlaybackService.ACTION_NEXT)
        }
        mediaControls.addView(mediaPrevious, LinearLayout.LayoutParams(dp(48), dp(38)))
        mediaControls.addView(mediaPlayPause, LinearLayout.LayoutParams(dp(48), dp(38)))
        mediaControls.addView(mediaNext, LinearLayout.LayoutParams(dp(48), dp(38)))
        mediaContainer.addView(
            mediaControls,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(42)
            )
        )

        mediaChoose = TextView(this).apply {
            text = "CHOOSE MUSIC FROM PHONE"
            textSize = 9f
            letterSpacing = 0.16f
            setTextColor(0xFFE0BE91.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            setPadding(0, dp(8), 0, dp(2))
            isClickable = true
            isFocusable = true
            contentDescription = "Choose local music files"
            setOnClickListener { openLocalMusicPicker() }
        }
        mediaContainer.addView(
            mediaChoose,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(27)
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
            contentDescription = "Fingerprint — press and hold after arming"
            isClickable = true
            isFocusable = true
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        fingerprintTouchActive = true
                        fingerprintTouchStartedArmed = armed

                        if (!fingerprintTouchStartedArmed) {
                            // Show the mismatch as soon as the finger touches down.
                            // ACTION_UP deliberately does not trigger a second response.
                            onFingerprintPressed()
                        } else if (!authenticating && !unlockPending) {
                            // Unlock while the finger is still held. No release is needed.
                            val hold = Runnable {
                                if (fingerprintTouchActive && fingerprintTouchStartedArmed &&
                                    armed && !authenticating && !unlockPending
                                ) {
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
        mediaPlayPause.text = if (playing) "Ⅱ" else "▶"
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
            flashLabel.text = if (flashEnabled) "FLASH ON" else "FLASHLIGHT"
        } catch (_: SecurityException) {
            flashEnabled = false
            flashLabel.text = "FLASHLIGHT"
            android.widget.Toast.makeText(
                this, "Camera permission is required to control the flashlight.",
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
        // Battery percentage is the discreet arming target; no lock icon is shown.
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
            "EEEE  ·  d MMMM yyyy",
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
