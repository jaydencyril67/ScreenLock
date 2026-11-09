package com.jaydencyril67.screenlock

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {

    private lateinit var lockView: FingerprintLockView
    private val handler = Handler(Looper.getMainLooper())
    private var scanArmed = false

    private val clearArmedScan = Runnable {
        scanArmed = false
    }

    private val resetMessage = Runnable {
        if (::lockView.isInitialized && !lockView.isAuthenticated) {
            lockView.showStatus("TOUCH TO SCAN", "Place your finger on the glowing scanner")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.BLACK
        applyImmersiveMode()

        lockView = FingerprintLockView(
            context = this,
            onKeypadTap = { armNextFingerprintTouch() },
            onFingerprintTap = { handleFingerprintTouch() },
            onCloseTap = { finish() }
        )
        setContentView(lockView)
    }

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun armNextFingerprintTouch() {
        handler.removeCallbacks(clearArmedScan)
        scanArmed = true
        // The keypad is intentionally silent and visually unchanged.
        // Arming expires after 10 seconds and is consumed by one scan only.
        handler.postDelayed(clearArmedScan, 10_000L)
    }

    private fun handleFingerprintTouch() {
        if (!scanArmed) {
            vibrate(55L)
            lockView.showStatus(
                "FINGERPRINT NOT MATCHED",
                "Tap the small locked keypad to arm a scan",
                isError = true
            )
            handler.removeCallbacks(resetMessage)
            handler.postDelayed(resetMessage, 2_100L)
            return
        }

        // Consume the hidden one-time arm before showing system authentication.
        scanArmed = false
        handler.removeCallbacks(clearArmedScan)
        handler.removeCallbacks(resetMessage)
        vibrate(28L)
        lockView.showStatus(
            "VERIFYING DEVICE OWNER",
            "Complete the Android screen-lock check"
        )
        requestSystemCredential()
    }

    private fun requestSystemCredential() {
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            lockView.showStatus(
                "SCREEN LOCK NOT SET",
                "Set a PIN, pattern or password in Android Settings",
                isError = true
            )
            return
        }

        try {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("Confirm it's you")
                .setSubtitle("Use your phone's screen lock")
                .setDescription("Enter your PIN, pattern or password to continue.")
                .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()

            prompt.authenticate(
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        vibrate(90L)
                        lockView.showAuthenticated()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        val message = when (errorCode) {
                            BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
                            BiometricPrompt.BIOMETRIC_ERROR_CANCELED ->
                                "Verification cancelled. Arm another scan to try again"
                            else ->
                                "System verification unavailable. Try again"
                        }
                        lockView.showStatus("VERIFICATION NOT COMPLETED", message, isError = true)
                    }
                }
            )
        } catch (_: Exception) {
            lockView.showStatus(
                "SYSTEM CHECK UNAVAILABLE",
                "Try again or check your Android screen-lock settings",
                isError = true
            )
        }
    }

    private fun vibrate(durationMs: Long) {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        durationMs,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            }
        } catch (_: Exception) {
            // Haptics are optional; the UI remains usable if unavailable.
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
