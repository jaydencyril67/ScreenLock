package com.jaydencyril67.screenlock

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phantom's service-owned lock window content.
 *
 * It deliberately contains no Activity or lifecycle dependency. The foreground
 * service owns the WindowManager attachment and keeps this surface above Home.
 */
class PhantomLockSurface(
    context: Context,
    private val onUnlock: () -> Unit,
    private val onFlashlight: () -> Unit,
    private val onCamera: () -> Unit,
    private val onChooseMusic: () -> Unit
) : FrameLayout(context) {

    private val uiHandler = Handler(Looper.getMainLooper())
    private var armed = false
    private var fingerIsDown = false
    private var holdRunnable: Runnable? = null
    private var flashLabel: TextView? = null

    private lateinit var clockLabel: TextView
    private lateinit var dateLabel: TextView
    private lateinit var batteryLabel: TextView
    private lateinit var fingerprintHint: TextView
    private lateinit var mediaContainer: LinearLayout
    private lateinit var mediaTitle: TextView
    private lateinit var mediaSubtitle: TextView
    private lateinit var mediaArtwork: ImageView
    private lateinit var previousButton: TextView
    private lateinit var playPauseButton: TextView
    private lateinit var nextButton: TextView
    private lateinit var chooseMusicButton: TextView

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            batteryLabel.text = readBatteryPercent()
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    private val clearMismatch = Runnable {
        if (::fingerprintHint.isInitialized) {
            fingerprintHint.text = if (armed) "PRESS & HOLD FOR 1 SECOND" else "TOUCH TO UNLOCK"
            fingerprintHint.setTextColor(0xC9DCE9F8.toInt())
            fingerprintHint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    init {
        setBackgroundColor(Color.rgb(7, 8, 12))
        isFocusable = false
        isFocusableInTouchMode = false
        @Suppress("DEPRECATION")
        systemUiVisibility = (
            SYSTEM_UI_FLAG_FULLSCREEN or
                SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                SYSTEM_UI_FLAG_LAYOUT_STABLE or
                SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        addView(
            PhantomWallpaperView(context),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        batteryLabel = label(readBatteryPercent(), 11f, 0xDDE8E7E4.toInt(), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            isClickable = true
            isFocusable = true
            contentDescription = "Battery status — tap to arm Phantom unlock"
            setOnClickListener { armUnlock() }
        }
        addView(
            batteryLabel,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(28), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(29)
                marginEnd = dp(25)
            }
        )

        clockLabel = label("00:00", 78f, 0xFFF7F2E9.toInt(), false).apply {
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            letterSpacing = -0.055f
            setShadowLayer(dp(20).toFloat(), 0f, dp(4).toFloat(), 0x885D3925.toInt())
        }
        addView(
            clockLabel,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(112), Gravity.TOP or Gravity.START).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.115f).toInt()
                marginStart = dp(25)
                marginEnd = dp(18)
            }
        )

        dateLabel = label("", 11f, 0xE7D9C7B5.toInt(), true).apply {
            letterSpacing = 0.15f
        }
        addView(
            dateLabel,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(26), Gravity.TOP or Gravity.START).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.265f).toInt()
                marginStart = dp(29)
                marginEnd = dp(20)
            }
        )

        val rule = View(context).apply { setBackgroundColor(0x90D1B38A.toInt()) }
        addView(
            rule,
            LayoutParams(dp(62), dp(1), Gravity.TOP or Gravity.START).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.305f).toInt()
                marginStart = dp(30)
            }
        )

        val dateCaption = label("LOCAL TIME  ·  24-HOUR FORMAT", 8f, 0xAFCFC6B8.toInt(), true).apply {
            letterSpacing = 0.18f
        }
        addView(
            dateCaption,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(20), Gravity.TOP or Gravity.START).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.322f).toInt()
                marginStart = dp(30)
            }
        )

        buildMediaCard()

        val sensorStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        val fingerprint = ImageView(context).apply {
            setImageResource(R.drawable.ic_s20_fingerprint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(0xFFF5F2ED.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Press and hold for one second to unlock"
            isClickable = true
            isFocusable = true
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        fingerIsDown = true
                        val armedAtTouchStart = armed
                        holdRunnable?.let(uiHandler::removeCallbacks)
                        val hold = Runnable {
                            if (fingerIsDown && armed == armedAtTouchStart) {
                                if (armedAtTouchStart) {
                                    armed = false
                                    fingerprintHint.text = "UNLOCKING"
                                    fingerprintHint.setTextColor(0xFFE1F4FF.toInt())
                                    onUnlock()
                                } else {
                                    showMismatch()
                                }
                            }
                        }
                        holdRunnable = hold
                        uiHandler.postDelayed(hold, 1_000L)
                        view.isPressed = true
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        fingerIsDown = false
                        holdRunnable?.let(uiHandler::removeCallbacks)
                        holdRunnable = null
                        view.isPressed = false
                        true
                    }
                    else -> true
                }
            }
        }
        sensorStack.addView(fingerprint, LinearLayout.LayoutParams(dp(38), dp(38)))

        fingerprintHint = label("TOUCH TO UNLOCK", 9f, 0xC9E3DCD1.toInt(), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.19f
            setPadding(0, dp(13), 0, 0)
        }
        sensorStack.addView(
            fingerprintHint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(31)
            )
        )
        addView(
            sensorStack,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(110)
            }
        )

        val shortcutRow = FrameLayout(context)
        shortcutRow.addView(
            shortcut(R.drawable.ic_flashlight_shortcut, "FLASHLIGHT", onFlashlight).also {
                flashLabel = it.second
            }.first,
            FrameLayout.LayoutParams(dp(78), dp(64), Gravity.BOTTOM or Gravity.START)
        )
        shortcutRow.addView(
            shortcut(R.drawable.ic_camera_shortcut, "CAMERA", onCamera).first,
            FrameLayout.LayoutParams(dp(78), dp(64), Gravity.BOTTOM or Gravity.END)
        )
        addView(
            shortcutRow,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(70), Gravity.BOTTOM).apply {
                leftMargin = dp(25)
                rightMargin = dp(25)
                bottomMargin = dp(17)
            }
        )

        updateClock()
        refreshMedia()
        uiHandler.post(clockTick)
    }

    private fun buildMediaCard() {
        mediaContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(0xA9101218.toInt())
                setStroke(dp(1), 0x35FFFFFF)
            }
            setPadding(dp(14), dp(12), dp(14), dp(11))
            clipToOutline = true
        }

        mediaContainer.addView(
            label("NOW PLAYING", 8f, 0xB8D8C9B7.toInt(), true).apply {
                letterSpacing = 0.19f
            },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(19))
        )

        val mainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mediaArtwork = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedSurface(0xFF20232B.toInt(), 12)
            clipToOutline = true
            contentDescription = "Album artwork"
            setImageResource(R.drawable.ic_music_note)
        }
        mainRow.addView(mediaArtwork, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
            marginEnd = dp(13)
        })

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mediaTitle = label("Your local music", 14f, 0xFFF5F1EA.toInt(), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        mediaSubtitle = label("Choose audio stored on this phone", 11f, 0xBFCAC1B7.toInt(), false).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textColumn.addView(mediaTitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(25)))
        textColumn.addView(mediaSubtitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(22)))
        mainRow.addView(textColumn, LinearLayout.LayoutParams(0, dp(58), 1f))
        mediaContainer.addView(mainRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(68)).apply {
            topMargin = dp(5)
        })

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, 0)
        }
        previousButton = mediaControl("‹", false) { sendMusicCommand(LocalMusicPlaybackService.ACTION_PREVIOUS) }
        playPauseButton = mediaControl("▶", true) { sendMusicCommand(LocalMusicPlaybackService.ACTION_TOGGLE) }
        nextButton = mediaControl("›", false) { sendMusicCommand(LocalMusicPlaybackService.ACTION_NEXT) }
        controls.addView(previousButton, LinearLayout.LayoutParams(dp(42), dp(40)))
        controls.addView(playPauseButton, LinearLayout.LayoutParams(dp(46), dp(40)))
        controls.addView(nextButton, LinearLayout.LayoutParams(dp(42), dp(40)))
        controls.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        mediaContainer.addView(controls, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(42)))

        chooseMusicButton = label("CHANGE LOCAL MUSIC", 8f, 0xFFE4C49A.toInt(), true).apply {
            letterSpacing = 0.12f
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(10), dp(9), 0, dp(9))
            isClickable = true
            isFocusable = true
            contentDescription = "Choose local music files"
            setOnClickListener { onChooseMusic() }
        }
        mediaContainer.addView(
            chooseMusicButton,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(27))
        )

        addView(
            mediaContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.395f).toInt()
                marginStart = dp(29)
                marginEnd = dp(31)
            }
        )
    }

    private fun mediaControl(glyph: String, main: Boolean, action: () -> Unit): TextView =
        label(glyph, if (main) 17f else 23f, if (main) 0xFF10131A.toInt() else 0xFFECE5DA.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (main) 0xFFE8D3B4.toInt() else 0x1FFFFFFF)
                if (!main) setStroke(dp(1), 0x24FFFFFF)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    fun refreshMedia() {
        if (!::mediaContainer.isInitialized) return
        val prefs = context.getSharedPreferences(LocalMusicPlaybackService.PREFS, Context.MODE_PRIVATE)
        val queue = prefs.getString(LocalMusicPlaybackService.KEY_QUEUE, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        val playing = prefs.getBoolean(LocalMusicPlaybackService.KEY_PLAYING, false)
        if (queue.isEmpty()) {
            mediaTitle.text = "Your local music"
            mediaSubtitle.text = "Choose audio stored on this phone"
            mediaArtwork.setImageResource(R.drawable.ic_music_note)
        } else {
            mediaTitle.text = prefs.getString(LocalMusicPlaybackService.KEY_TITLE, "Loading selected track…")
            mediaSubtitle.text = prefs.getString(LocalMusicPlaybackService.KEY_ARTIST, "LOCAL AUDIO")
        }
        val hasMusic = queue.isNotEmpty()
        listOf(previousButton, playPauseButton, nextButton).forEach {
            it.isEnabled = hasMusic
            it.alpha = if (hasMusic) 1f else 0.35f
        }
        playPauseButton.text = if (playing) "Ⅱ" else "▶"
    }

    fun setFlashlightOn(enabled: Boolean) {
        flashLabel?.text = if (enabled) "FLASH ON" else "FLASHLIGHT"
    }

    private fun sendMusicCommand(action: String) {
        try {
            context.startService(Intent(context, LocalMusicPlaybackService::class.java).setAction(action))
        } catch (_: Exception) {
            android.widget.Toast.makeText(context, "Could not control local music.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun armUnlock() {
        armed = true
        uiHandler.removeCallbacks(clearMismatch)
        fingerprintHint.text = "PRESS & HOLD FOR 1 SECOND"
        fingerprintHint.setTextColor(0xFFE3DCD1.toInt())
        fingerprintHint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }

    private fun showMismatch() {
        fingerprintHint.text = "Fingerprint doesn't match"
        fingerprintHint.setTextColor(0xFFFF667A.toInt())
        fingerprintHint.setShadowLayer(dp(12).toFloat(), 0f, 0f, 0x88FF334F.toInt())
        uiHandler.removeCallbacks(clearMismatch)
        uiHandler.postDelayed(clearMismatch, 1_900L)
        try {
            val effect = VibrationEffect.createWaveform(longArrayOf(0L, 42L, 48L, 42L), -1)
            context.getSystemService(Vibrator::class.java)?.vibrate(effect)
        } catch (_: Exception) {
            // Haptics are optional.
        }
    }

    private fun updateClock() {
        if (!::clockLabel.isInitialized || !::dateLabel.isInitialized) return
        val now = Date()
        clockLabel.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        dateLabel.text = SimpleDateFormat("EEEE  ·  d MMMM yyyy", Locale.getDefault())
            .format(now).uppercase(Locale.getDefault())
    }

    private fun readBatteryPercent(): String {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return "BATTERY"
        return (level * 100 / scale).toString() + "%  BATTERY"
    }

    private fun shortcut(
        iconResource: Int,
        caption: String,
        action: () -> Unit
    ): Pair<View, TextView> {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        val icon = ImageView(context).apply {
            setImageResource(iconResource)
            imageTintList = ColorStateList.valueOf(0xFFF4F0E9.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = caption
        }
        val captionView = label(caption, 8f, 0xC9D8D0C5.toInt(), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.14f
        }
        column.addView(icon, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(39)))
        column.addView(captionView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(17)))
        return Pair(column, captionView)
    }

    private fun label(value: String, size: Float, color: Int, medium: Boolean): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            typeface = Typeface.create(
                "sans-serif",
                if (medium) Typeface.BOLD else Typeface.NORMAL
            )
        }

    private fun roundedSurface(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    fun dispose() {
        fingerIsDown = false
        holdRunnable?.let(uiHandler::removeCallbacks)
        holdRunnable = null
        uiHandler.removeCallbacksAndMessages(null)
    }
}