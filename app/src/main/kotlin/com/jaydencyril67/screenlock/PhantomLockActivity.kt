package com.jaydencyril67.screenlock

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
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

/**
 * A small, screen-on lock surface. The fingerprint artwork is visual; only Android's
 * enrolled biometric hardware can verify a real fingerprint.
 */
class PhantomLockActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var armed = false
    private var authenticating = false
    private var receiverRegistered = false

    private lateinit var mismatchLabel: TextView

    private val clearMismatch = Runnable {
        mismatchLabel.visibility = View.INVISIBLE
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF ||
                intent.action == PhantomOverlayService.ACTION_CLOSE_LOCK
            ) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        applyImmersiveMode()
        registerScreenOffReceiver()
        buildLockSurface()
    }

    private fun buildLockSurface() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true
        }

        val sensor = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.rgb(92, 112, 151))
            }
            contentDescription = "Fingerprint sensor"
            isClickable = true
            setOnClickListener { onFingerprintPressed() }
        }

        val fingerprint = ImageView(this).apply {
            setImageResource(R.drawable.ic_s20_fingerprint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(
                Color.rgb(230, 237, 250)
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val iconSize = dp(37)
        val iconParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        sensor.addView(fingerprint, iconParams)

        val sensorSize = dp(58)
        val sensorParams = FrameLayout.LayoutParams(
            sensorSize,
            sensorSize,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = (resources.displayMetrics.heightPixels * 0.635f).toInt()
        }
        root.addView(sensor, sensorParams)

        mismatchLabel = TextView(this).apply {
            text = "Fingerprint doesn't match"
            textSize = 12f
            setTextColor(Color.rgb(215, 221, 235))
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
            typeface = android.graphics.Typeface.create(
                "sans-serif",
                android.graphics.Typeface.NORMAL
            )
            letterSpacing = 0.015f
        }
        val mismatchParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ).apply {
            topMargin = (resources.displayMetrics.heightPixels * 0.635f).toInt() +
                dp(72)
            marginStart = dp(24)
            marginEnd = dp(24)
        }
        root.addView(mismatchLabel, mismatchParams)

        // Tiny padlock at the top edge. Tapping it silently arms exactly one scan.
        val padlock = ImageButton(this).apply {
            setImageResource(R.drawable.ic_phantom_padlock)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = android.content.res.ColorStateList.valueOf(
                Color.rgb(177, 190, 216)
            )
            background = null
            contentDescription = "Arm fingerprint unlock"
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setOnClickListener {
                armed = true
                // Intentionally no visual change, toast, or haptic feedback.
            }
        }
        val padlockParams = FrameLayout.LayoutParams(
            dp(46),
            dp(46),
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(18)
            marginEnd = dp(17)
        }
        root.addView(padlock, padlockParams)

        setContentView(root)
    }

    private fun onFingerprintPressed() {
        if (authenticating) return

        if (!armed) {
            showMismatch()
            return
        }

        // Consume the one-time silent arm before attempting any authentication.
        armed = false
        handler.removeCallbacks(clearMismatch)

        val biometricManager = getSystemService(BIOMETRIC_SERVICE) as BiometricManager
        val availability = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )

        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            // The Galaxy A02 has no fingerprint reader. Do not force a phone PIN:
            // on unsupported devices, the armed touch simply dismisses this visual lock.
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
