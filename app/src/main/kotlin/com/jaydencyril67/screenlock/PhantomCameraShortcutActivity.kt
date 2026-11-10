package com.jaydencyril67.screenlock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast

/**
 * Activity bridge for the camera app. The service temporarily removes its
 * overlay while the camera is in use, then restores Phantom when the user
 * returns from the camera.
 */
class PhantomCameraShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return

        var cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (cameraIntent.resolveActivity(packageManager) == null) {
            cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        }
        if (cameraIntent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "No camera app is available.", Toast.LENGTH_SHORT).show()
            restoreLockSurface()
            finish()
            return
        }

        try {
            startActivityForResult(cameraIntent, REQUEST_CAMERA)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open the camera.", Toast.LENGTH_SHORT).show()
            restoreLockSurface()
            finish()
        }
    }

    @Deprecated("Uses Activity Result API compatibility with the Android 11 target")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAMERA) return
        restoreLockSurface()
        finish()
    }

    private fun restoreLockSurface() {
        try {
            startService(Intent(this, PhantomOverlayService::class.java).setAction(PhantomOverlayService.ACTION_SHOW_LOCK))
        } catch (_: Exception) {
            // The service may have been disabled while the camera was open.
        }
    }

    companion object {
        private const val REQUEST_CAMERA = 4173
    }
}