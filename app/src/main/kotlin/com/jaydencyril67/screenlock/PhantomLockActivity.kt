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
import android.graphics.drawable.GradientDrawable
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phantom's native glass lock screen.
 *
 * The fingerprint artwork is a visual touch target only. Where strong biometric
 * hardware is available, verification is delegated to Android's BiometricPrompt.
 */
class PhantomLockActivity : Activity() {

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
    private var fingerprintView: ImageView? = null

    private val clockTick = object : Runnable {
        override fun run() {
            if (::clockLabel.isInitialized) {
                val now = Date()
                clockLabel.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
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
            fingerprintHint.text = "TOUCH TO UNLOCK"
            fingerprintHint.setTextColor(0xC9DCE9F8.toInt())
            fingerprintHint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF ||
                intent.action == PhantomOverlayService.ACTION_CLOSE_LOCK
            ) {
                armed = false
                unlockPending = false
                armRunnable?.let(handler::removeCallbacks)
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request the lock surface before inflating it, so the first visible frame
        // has the best chance of appearing above Android's keyguard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
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
            setBackgroundColor(Color.rgb(4, 7, 16))
            isFocusableInTouchMode = true
        }

        root.addView(
            LockscreenWallpaperView(this),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Minimal floating header: brand, active state, battery and silent padlock.
        val brandStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        val brand = TextView(this).apply {
            text = "PHANTOM"
            textSize = 11f
            letterSpacing = 0.28f
            setTextColor(0xFFF1F6FF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        }
        val brandSubline = TextView(this).apply {
            text = "PERSONAL LOCKSCREEN"
            textSize = 7f
            letterSpacing = 0.19f
            setTextColor(0x8FAFC1DE.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            setPadding(0, dp(5), 0, 0)
        }
        brandStack.addView(brand)
        brandStack.addView(brandSubline)
        root.addView(
            brandStack,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = dp(31)
                marginStart = dp(27)
            }
        )

        batteryLabel = TextView(this).apply {
            text = readBatteryPercent()
            textSize = 9f
            letterSpacing = 0.04f
            setTextColor(0xFFE6EEF9.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = glassSurface(22, 0x1BFFFFFF, 0x10FFFFFF)
        }
        root.addView(
            batteryLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(38),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(19)
                marginEnd = dp(72)
            }
        )

        val padlock = ImageButton(this).apply {
            setImageResource(R.drawable.ic_phantom_padlock)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(0xFFE5EFFC.toInt())
            background = glassSurface(16, 0x20FFFFFF, 0x0DFFFFFF)
            contentDescription = "Arm fingerprint unlock"
            setPadding(dp(11), dp(11), dp(11), dp(11))
            elevation = dp(5).toFloat()
            setOnClickListener { armFingerprint() }
        }
        root.addView(
            padlock,
            FrameLayout.LayoutParams(
                dp(44),
                dp(44),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(16)
                marginEnd = dp(17)
            }
        )

        // Main glass time panel with a quiet cyan highlight and restrained typography.
        val clockCard = FrameLayout(this).apply {
            background = glassSurface(30, 0x24FFFFFF, 0x0BFFFFFF)
            elevation = dp(18).toFloat()
            clipToOutline = true
        }
        val clockStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        clockLabel = TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            textSize = 72f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            letterSpacing = -0.045f
            setShadowLayer(dp(22).toFloat(), 0f, dp(3).toFloat(), 0x664A91D5)
        }
        dateLabel = TextView(this).apply {
            textSize = 9f
            letterSpacing = 0.2f
            setTextColor(0xFFD6E2F2.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, dp(12), 0, dp(12))
        }

        val accentLine = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x008AE8FF, 0xFF8AE8FF.toInt(), 0x008AE8FF)
            ).apply {
                cornerRadius = dp(2).toFloat()
            }
        }

        val activeStatus = TextView(this).apply {
            text = "●  PHANTOM PROTECTION ACTIVE"
            textSize = 7f
            letterSpacing = 0.14f
            setTextColor(0xFFB7DCEB.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(13), dp(8), dp(13), dp(8))
            background = glassSurface(18, 0x1B72C7D8, 0x0CFFFFFF)
        }

        clockStack.addView(
            clockLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(82)
            )
        )
        clockStack.addView(
            dateLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            )
        )
        clockStack.addView(
            accentLine,
            LinearLayout.LayoutParams(dp(36), dp(2)).apply {
                bottomMargin = dp(12)
            }
        )
        clockStack.addView(
            activeStatus,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(28)
            )
        )
        clockCard.addView(
            clockStack,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            clockCard,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(176),
                Gravity.TOP
            ).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.205f).toInt()
                marginStart = dp(26)
                marginEnd = dp(26)
            }
        )

        // Bottom-anchored glass sensor tile. The fingerprint itself remains ringless.
        val sensorStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        val sensorHitArea = FrameLayout(this).apply {
            background = glassSurface(30, 0x27FFFFFF, 0x0EFFFFFF)
            elevation = dp(16).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "Fingerprint"
            setOnClickListener { onFingerprintPressed() }
        }

        val fingerprint = ImageView(this).apply {
            setImageResource(R.drawable.ic_s20_fingerprint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(0xFFF7FAFF.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        fingerprintView = fingerprint
        sensorHitArea.addView(
            fingerprint,
            FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER)
        )
        sensorStack.addView(
            sensorHitArea,
            LinearLayout.LayoutParams(dp(112), dp(112))
        )

        fingerprintHint = TextView(this).apply {
            text = "TOUCH TO UNLOCK"
            textSize = 9f
            letterSpacing = 0.2f
            setTextColor(0xC9DCE9F8.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(8), dp(15), dp(8), 0)
        }
        sensorStack.addView(
            fingerprintHint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(34)
            )
        )

        root.addView(
            sensorStack,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(83)
            }
        )

        val footer = TextView(this).apply {
            text = "DESIGNED TO STAY YOURS"
            textSize = 7f
            letterSpacing = 0.2f
            setTextColor(0x667F9AB8)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        root.addView(
            footer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(24),
                Gravity.BOTTOM
            ).apply { bottomMargin = dp(21) }
        )

        setContentView(root)
        updateClock()
    }

    private fun glassSurface(
        radiusDp: Int,
        upperColor: Int,
        lowerColor: Int
    ): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(upperColor, lowerColor)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), 0x36FFFFFF)
        }
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
                .setTitle("Unlock Phantom Lock")
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

            paint.shader = LinearGradient(
                0f, 0f, w * 0.82f, h,
                intArrayOf(
                    Color.rgb(3, 7, 17),
                    Color.rgb(8, 17, 32),
                    Color.rgb(5, 9, 21),
                    Color.rgb(2, 5, 13)
                ),
                floatArrayOf(0f, 0.42f, 0.76f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // Deep blue glass-like light blooming behind the clock.
            paint.shader = RadialGradient(
                w * 0.88f, h * 0.35f, w * 1.05f,
                intArrayOf(
                    0x523B8FC0,
                    0x272C6595,
                    0x10203B70,
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.34f, 0.67f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // A cooler lower glow gives the fingerprint zone a soft visual anchor.
            paint.shader = RadialGradient(
                w * 0.16f, h * 0.82f, w * 0.82f,
                intArrayOf(
                    0x3A315F92,
                    0x1E253E73,
                    0x10202758,
                    Color.TRANSPARENT
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // Very subtle oversized glass geometry in the wallpaper only.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = 0x16C3E8FF
            canvas.drawCircle(w * 1.05f, h * 0.39f, w * 0.66f, paint)
            paint.color = 0x0FFFFFFF
            canvas.drawCircle(w * 1.05f, h * 0.39f, w * 0.77f, paint)
            paint.color = 0x0C8DBBDB
            canvas.drawCircle(-w * 0.46f, h * 0.88f, w * 0.8f, paint)
            paint.style = Paint.Style.FILL

            // A fine diagonal light ribbon adds depth without distracting from text.
            accentPath.reset()
            accentPath.moveTo(w * 0.73f, 0f)
            accentPath.lineTo(w, 0f)
            accentPath.lineTo(w, h * 0.22f)
            accentPath.close()
            paint.shader = LinearGradient(
                w * 0.72f, 0f, w, h * 0.22f,
                intArrayOf(0x00B8E8FF, 0x153F9ACB, 0x003F9ACB),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(accentPath, paint)
            paint.shader = null

            // Deepen the bottom edge so the lower controls remain legible.
            paint.shader = LinearGradient(
                0f, h * 0.68f, 0f, h,
                intArrayOf(
                    Color.TRANSPARENT,
                    0x3510192D,
                    0xAA02050D.toInt()
                ),
                null,
                Shader.TileMode.CLAMP
            )
            frame.set(0f, h * 0.68f, w, h)
            canvas.drawRect(frame, paint)
            paint.shader = null
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        armRunnable = null
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
