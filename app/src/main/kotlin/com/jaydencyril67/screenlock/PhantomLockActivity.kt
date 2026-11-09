package com.jaydencyril67.screenlock

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
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
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A custom full-screen lock surface displayed over Android's keyguard.
 * Fingerprint artwork is visual only; biometric verification is delegated to Android.
 */
class PhantomLockActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var armed = false
    private var authenticating = false
    private var receiverRegistered = false

    private lateinit var clockLabel: TextView
    private lateinit var dateLabel: TextView
    private lateinit var batteryLabel: TextView
    private lateinit var mismatchLabel: TextView

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
        if (::mismatchLabel.isInitialized) mismatchLabel.visibility = View.INVISIBLE
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF ||
                intent.action == PhantomOverlayService.ACTION_CLOSE_LOCK
            ) {
                armed = false
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set these before inflating the UI so Phantom can be placed above keyguard
        // on the very first visible frame after the display wakes.
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
        authenticating = false
        handler.removeCallbacks(clearMismatch)
        if (::mismatchLabel.isInitialized) mismatchLabel.visibility = View.INVISIBLE
    }

    private fun buildLockScreen() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true
        }

        root.addView(
            LockscreenWallpaperView(this),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Small, quiet system-style header.
        val brand = TextView(this).apply {
            text = "PHANTOM"
            textSize = 9f
            letterSpacing = 0.24f
            setTextColor(0xCCDAE5FA.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        root.addView(
            brand,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = dp(34)
                marginStart = dp(28)
            }
        )

        batteryLabel = TextView(this).apply {
            textSize = 9f
            letterSpacing = 0.08f
            setTextColor(0xCCB8C8E4.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.END
            text = readBatteryPercent()
        }
        root.addView(
            batteryLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(34)
                marginEnd = dp(77)
            }
        )

        // The small padlock remains in the top-right corner. It silently arms one tap.
        val padlock = ImageButton(this).apply {
            setImageResource(R.drawable.ic_phantom_padlock)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = android.content.res.ColorStateList.valueOf(
                Color.rgb(211, 224, 245)
            )
            background = null
            contentDescription = "Arm fingerprint unlock"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                armed = true
                // Intentionally silent: no animation, toast, label or vibration.
            }
        }
        root.addView(
            padlock,
            FrameLayout.LayoutParams(
                dp(48),
                dp(48),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(17)
                marginEnd = dp(16)
            }
        )

        clockLabel = TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            textSize = 70f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            letterSpacing = -0.035f
            setShadowLayer(dp(24).toFloat(), 0f, dp(3).toFloat(), 0x554C79D8)
        }
        root.addView(
            clockLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(96),
                Gravity.TOP
            ).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.225f).toInt()
                marginStart = dp(18)
                marginEnd = dp(18)
            }
        )

        dateLabel = TextView(this).apply {
            textSize = 10f
            letterSpacing = 0.18f
            setTextColor(0xFFD0D9E9.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        root.addView(
            dateLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(28),
                Gravity.TOP
            ).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.225f).toInt() +
                    dp(88)
            }
        )

        // Wide invisible hit target; the imported fingerprint itself is large and has no ring.
        val sensorHitArea = FrameLayout(this).apply {
            isClickable = true
            contentDescription = "Fingerprint"
            setOnClickListener { onFingerprintPressed() }
        }

        val fingerprint = ImageView(this).apply {
            setImageResource(R.drawable.ic_s20_fingerprint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(
                Color.rgb(246, 249, 255)
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        sensorHitArea.addView(
            fingerprint,
            FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER)
        )

        root.addView(
            sensorHitArea,
            FrameLayout.LayoutParams(dp(120), dp(112), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply {
                    topMargin = (resources.displayMetrics.heightPixels * 0.655f).toInt()
                }
        )

        mismatchLabel = TextView(this).apply {
            text = "Fingerprint doesn't match"
            textSize = 12f
            letterSpacing = 0.015f
            setTextColor(0xFFE2E8F4.toInt())
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
        }
        root.addView(
            mismatchLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                topMargin = (resources.displayMetrics.heightPixels * 0.655f).toInt() +
                    dp(108)
                marginStart = dp(22)
                marginEnd = dp(22)
            }
        )

        val footer = TextView(this).apply {
            text = "PHANTOM  •  SECURE LOCK SCREEN"
            textSize = 8f
            letterSpacing = 0.2f
            setTextColor(0x777F91AD)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        root.addView(
            footer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(30),
                Gravity.BOTTOM
            ).apply { bottomMargin = dp(24) }
        )

        setContentView(root)
        updateClock()
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
        return "BATTERY  ${(level * 100 / scale)}%"
    }

    private fun onFingerprintPressed() {
        if (authenticating) return

        if (!armed) {
            showMismatch()
            return
        }

        // The padlock arms exactly one fingerprint tap, then immediately resets.
        armed = false
        handler.removeCallbacks(clearMismatch)

        val biometricManager = getSystemService(BIOMETRIC_SERVICE) as BiometricManager
        val availability = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )

        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            // This A02 has no fingerprint reader. Retain its agreed visual-unlock
            // fallback instead of forcing a phone PIN, pattern or password.
            finish()
            return
        }

        startBiometricPrompt()
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
        mismatchLabel.text = "Fingerprint doesn't match"
        mismatchLabel.visibility = View.VISIBLE
        handler.removeCallbacks(clearMismatch)
        handler.postDelayed(clearMismatch, 1_800L)
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
        // Keep the lock surface in place; screen-off or the armed fingerprint dismisses it.
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private class LockscreenWallpaperView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            paint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(
                    Color.rgb(3, 6, 15),
                    Color.rgb(8, 13, 27),
                    Color.rgb(4, 6, 15)
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            paint.shader = RadialGradient(
                w * 0.78f, h * 0.58f, w * 0.92f,
                intArrayOf(0x43305BA0, 0x1D203D78, Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            paint.shader = RadialGradient(
                w * 0.08f, h * 0.82f, w * 0.72f,
                intArrayOf(0x352D205F, 0x18201952, Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = 0x16B5CFFF
            canvas.drawCircle(w * 0.88f, h * 0.45f, w * 0.58f, paint)
            paint.color = 0x0DFFFFFF
            canvas.drawCircle(w * 0.88f, h * 0.45f, w * 0.68f, paint)
            paint.style = Paint.Style.FILL

            paint.shader = LinearGradient(
                0f, h * 0.78f, 0f, h,
                intArrayOf(Color.TRANSPARENT, 0x6602050D, 0x9902050D.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, h * 0.78f, w, h, paint)
            paint.shader = null
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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
