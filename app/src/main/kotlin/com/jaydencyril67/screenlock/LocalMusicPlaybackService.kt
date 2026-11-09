package com.jaydencyril67.screenlock

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns

/**
 * Plays only audio files the user explicitly selects through Android's local
 * document picker. It is independent of notification-listener/media-session
 * access so the sideload build can use the same player.
 */
class LocalMusicPlaybackService : Service() {

    companion object {
        const val PREFS = "phantom_local_music"
        const val KEY_QUEUE = "queue"
        const val KEY_INDEX = "index"
        const val KEY_PLAYING = "playing"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"

        const val EXTRA_QUEUE = "queue_uris"
        const val ACTION_SET_QUEUE = "com.jaydencyril67.screenlock.music.SET_QUEUE"
        const val ACTION_PLAY = "com.jaydencyril67.screenlock.music.PLAY"
        const val ACTION_PAUSE = "com.jaydencyril67.screenlock.music.PAUSE"
        const val ACTION_TOGGLE = "com.jaydencyril67.screenlock.music.TOGGLE"
        const val ACTION_NEXT = "com.jaydencyril67.screenlock.music.NEXT"
        const val ACTION_PREVIOUS = "com.jaydencyril67.screenlock.music.PREVIOUS"
        const val ACTION_STATE_CHANGED = "com.jaydencyril67.screenlock.music.STATE_CHANGED"
    }

    private var player: MediaPlayer? = null
    private var queue = mutableListOf<String>()
    private var index = 0
    private var prepared = false
    private var playWhenPrepared = true

    private val preferences by lazy {
        getSharedPreferences(PREFS, MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_QUEUE -> {
                queue = intent.getStringArrayListExtra(EXTRA_QUEUE)
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    ?.toMutableList()
                    ?: mutableListOf()
                index = 0
                preferences.edit()
                    .putString(KEY_QUEUE, queue.joinToString("\n"))
                    .putInt(KEY_INDEX, index)
                    .apply()
                playWhenPrepared = true
                playCurrentTrack()
            }

            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> {
                if (preferences.getBoolean(KEY_PLAYING, false)) pause() else play()
            }
            ACTION_NEXT -> moveNext(automatic = false)
            ACTION_PREVIOUS -> movePrevious()
            null -> {
                restoreQueue()
                if (preferences.getBoolean(KEY_PLAYING, false)) {
                    playWhenPrepared = true
                    playCurrentTrack()
                }
            }
        }
        return START_STICKY
    }

    private fun restoreQueue() {
        queue = preferences.getString(KEY_QUEUE, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toMutableList()
        index = preferences.getInt(KEY_INDEX, 0)
            .coerceIn(0, (queue.size - 1).coerceAtLeast(0))
    }

    private fun play() {
        if (queue.isEmpty()) restoreQueue()
        if (queue.isEmpty()) {
            publishState(false)
            return
        }

        playWhenPrepared = true
        val current = player
        if (current != null && prepared) {
            try {
                current.start()
                publishState(true)
            } catch (_: IllegalStateException) {
                playCurrentTrack()
            }
        } else if (current == null) {
            playCurrentTrack()
        }
    }

    private fun pause() {
        playWhenPrepared = false
        try {
            if (prepared) player?.pause()
        } catch (_: IllegalStateException) {
            // The decoder may be changing tracks.
        }
        publishState(false)
    }

    private fun moveNext(automatic: Boolean) {
        if (queue.isEmpty()) restoreQueue()
        if (queue.isEmpty()) return

        if (automatic && index >= queue.lastIndex) {
            releasePlayer()
            publishState(false)
            return
        }

        index = if (index >= queue.lastIndex) 0 else index + 1
        preferences.edit().putInt(KEY_INDEX, index).apply()
        playWhenPrepared = true
        playCurrentTrack()
    }

    private fun movePrevious() {
        if (queue.isEmpty()) restoreQueue()
        if (queue.isEmpty()) return

        try {
            if (prepared && (player?.currentPosition ?: 0) > 3_000) {
                player?.seekTo(0)
                publishState(player?.isPlaying == true)
                return
            }
        } catch (_: Exception) {
            // Fall through to the previous item.
        }

        index = if (index <= 0) queue.lastIndex else index - 1
        preferences.edit().putInt(KEY_INDEX, index).apply()
        playWhenPrepared = true
        playCurrentTrack()
    }

    private fun playCurrentTrack() {
        if (queue.isEmpty()) {
            publishState(false)
            return
        }

        index = index.coerceIn(0, queue.lastIndex)
        releasePlayer()
        val uri = Uri.parse(queue[index])
        val metadata = readMetadata(uri)
        preferences.edit()
            .putInt(KEY_INDEX, index)
            .putString(KEY_TITLE, metadata.first)
            .putString(KEY_ARTIST, metadata.second)
            .putBoolean(KEY_PLAYING, false)
            .apply()
        broadcastStateChanged()

        val nextPlayer = MediaPlayer()
        player = nextPlayer
        prepared = false
        try {
            nextPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            nextPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            nextPlayer.setOnPreparedListener { ready ->
                if (player !== ready) return@setOnPreparedListener
                prepared = true
                if (playWhenPrepared) {
                    try {
                        ready.start()
                        publishState(true)
                    } catch (_: IllegalStateException) {
                        publishState(false)
                    }
                } else {
                    publishState(false)
                }
            }
            nextPlayer.setOnCompletionListener {
                moveNext(automatic = true)
            }
            nextPlayer.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    releasePlayer()
                    publishState(false)
                }
                true
            }
            nextPlayer.setDataSource(this, uri)
            nextPlayer.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
            preferences.edit()
                .putString(KEY_TITLE, "Unable to play this audio file")
                .putString(KEY_ARTIST, "Choose another local audio file")
                .putBoolean(KEY_PLAYING, false)
                .apply()
            broadcastStateChanged()
        }
    }

    private fun readMetadata(uri: Uri): Pair<String, String> {
        var title: String? = null
        var artist: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
        } catch (_: Exception) {
            // Document providers do not all expose embedded audio metadata.
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) { }
        }

        if (title.isNullOrBlank()) {
            title = try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (column >= 0) cursor.getString(column) else null
                        } else null
                    }
            } catch (_: Exception) {
                null
            }
        }
        val cleanTitle = title?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: "Local audio"
        return cleanTitle to (artist?.takeIf { it.isNotBlank() } ?: "LOCAL AUDIO")
    }

    private fun publishState(playing: Boolean) {
        preferences.edit().putBoolean(KEY_PLAYING, playing).apply()
        broadcastStateChanged()
    }

    private fun broadcastStateChanged() {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED).setPackage(packageName)
        )
    }

    private fun releasePlayer() {
        prepared = false
        val old = player
        player = null
        try {
            old?.setOnPreparedListener(null)
            old?.setOnCompletionListener(null)
            old?.setOnErrorListener(null)
            old?.reset()
            old?.release()
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
