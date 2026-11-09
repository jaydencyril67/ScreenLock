package com.jaydencyril67.screenlock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.widget.Toast

/**
 * This Activity deliberately contains no custom PIN or fingerprint logic.
 * Android's own BiometricPrompt is the sole authentication authority.
 */
class CredentialAuthActivity : Activity() {

    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)

        if (savedInstanceState == null) beginSystemAuthentication()
    }

    private fun beginSystemAuthentication() {
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            Toast.makeText(
                this,
                "Set a PIN, pattern or password in Android Settings first.",
                Toast.LENGTH_LONG
            ).show()
            notifyService(PhantomOverlayService.ACTION_AUTH_CANCELLED)
            finish()
            return
        }

        try {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("Unlock Phantom Lock")
                .setSubtitle("Confirm your identity")
                .setDescription("Use an enrolled fingerprint or your Android screen lock.")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            prompt.authenticate(
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        completed = true
                        notifyService(PhantomOverlayService.ACTION_AUTHENTICATED)
                        finish()
                        overridePendingTransition(0, 0)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        completed = true
                        notifyService(PhantomOverlayService.ACTION_AUTH_CANCELLED)
                        finish()
                        overridePendingTransition(0, 0)
                    }
                }
            )
        } catch (_: Exception) {
            notifyService(PhantomOverlayService.ACTION_AUTH_CANCELLED)
            Toast.makeText(this, "Android authentication could not start.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun notifyService(action: String) {
        startService(Intent(this, PhantomOverlayService::class.java).setAction(action))
    }

    override fun onDestroy() {
        if (!completed && !isChangingConfigurations) {
            notifyService(PhantomOverlayService.ACTION_AUTH_CANCELLED)
        }
        super.onDestroy()
    }
}
