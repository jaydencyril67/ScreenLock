package com.jaydencyril67.screenlock

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Tiny Activity bridge for the Android document picker. The lock itself stays
 * in PhantomOverlayService; this Activity exists only while the user is choosing
 * files because ACTION_OPEN_DOCUMENT requires an Activity result callback.
 */
class PhantomMusicPickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return

        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

        try {
            startActivityForResult(picker, REQUEST_PICK_MUSIC)
        } catch (_: Exception) {
            Toast.makeText(this, "No audio file picker is available.", Toast.LENGTH_LONG).show()
            restoreLockSurface()
            finish()
        }
    }

    @Deprecated("Uses Activity Result API compatibility with the Android 11 target")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_MUSIC) return

        if (resultCode == RESULT_OK && data != null) {
            val selected = mutableListOf<Uri>()
            data.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) {
                    clip.getItemAt(index).uri?.let(selected::add)
                }
            } ?: data.data?.let(selected::add)

            val uris = selected.distinct()
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

            if (uris.isNotEmpty()) {
                val queue = ArrayList(uris.map { it.toString() })
                try {
                    startService(Intent(this, LocalMusicPlaybackService::class.java).apply {
                        action = LocalMusicPlaybackService.ACTION_SET_QUEUE
                        putStringArrayListExtra(LocalMusicPlaybackService.EXTRA_QUEUE, queue)
                    })
                } catch (_: Exception) {
                    Toast.makeText(this, "Could not start local music playback.", Toast.LENGTH_LONG).show()
                }
            }
        }

        restoreLockSurface()
        finish()
    }

    private fun restoreLockSurface() {
        try {
            startService(Intent(this, PhantomOverlayService::class.java).setAction(PhantomOverlayService.ACTION_SHOW_LOCK))
        } catch (_: Exception) {
            // The service may have been disabled while the picker was open.
        }
    }

    companion object {
        private const val REQUEST_PICK_MUSIC = 4172
    }
}