package com.jaydencyril67.screenlock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent

/**
 * Mirrors eligible local-audio MediaSessions from other apps.
 * Phantom never creates a player or opens an audio file itself.
 */
class PhantomMediaListenerService : NotificationListenerService() {

    companion object {
        const val ACTION_STATE_CHANGED = "com.jaydencyril67.screenlock.media.STATE_CHANGED"
        const val COMMAND_PREVIOUS = "previous"
        const val COMMAND_PLAY_PAUSE = "play_pause"
        const val COMMAND_NEXT = "next"

        @Volatile var activeController: MediaController? = null
            private set
        @Volatile var currentMetadata: MediaMetadata? = null
            private set
        @Volatile var isPlaying: Boolean = false
            private set

        fun hasNotificationAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            val component = ComponentName(context, PhantomMediaListenerService::class.java)
            return enabled.split(':').any { it.equals(component.flattenToString(), true) ||
                it.equals(component.flattenToShortString(), true) }
        }

        fun refreshSessions(context: Context) {
            if (!hasNotificationAccess(context)) {
                publish(null)
                return
            }
            try {
                val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                    as MediaSessionManager
                val sessions = manager.getActiveSessions(
                    ComponentName(context, PhantomMediaListenerService::class.java)
                )
                val eligible = sessions.filter { isLocalAudioSession(it) }
                val selected = eligible.firstOrNull {
                    it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                } ?: eligible.firstOrNull()
                publish(selected)
            } catch (_: SecurityException) {
                publish(null)
            } catch (_: Exception) {
                publish(null)
            }
        }

        private fun isLocalAudioSession(controller: MediaController): Boolean {
            val metadata = controller.metadata ?: return false
            val mediaUri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
                ?: controller.metadata?.description?.mediaUri?.toString()
                ?: metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                ?: return false
            val scheme = try { android.net.Uri.parse(mediaUri).scheme?.lowercase() }
                catch (_: Exception) { null }
            // Only content:// and file:// sources qualify. Network URLs and
            // sessions without a verifiable local source are deliberately ignored.
            return scheme == "content" || scheme == "file"
        }

        private fun publish(controller: MediaController?) {
            activeController = controller
            currentMetadata = controller?.metadata
            isPlaying = controller?.playbackState?.state ==
                android.media.session.PlaybackState.STATE_PLAYING
        }

        fun sendCommand(command: String): Boolean {
            val controller = activeController ?: return false
            if (!isLocalAudioSession(controller)) {
                publish(null)
                return false
            }
            return try {
                val controls = controller.transportControls
                when (command) {
                    COMMAND_PREVIOUS -> controls.skipToPrevious()
                    COMMAND_PLAY_PAUSE -> {
                        if (controller.playbackState?.state ==
                            android.media.session.PlaybackState.STATE_PLAYING
                        ) controls.pause() else controls.play()
                    }
                    COMMAND_NEXT -> controls.skipToNext()
                    else -> return false
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private var observedController: MediaController? = null
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (observedController === activeController) {
                currentMetadata = metadata
                sendStateChanged()
            }
        }

        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
            if (observedController === activeController) {
                isPlaying = state?.state ==
                    android.media.session.PlaybackState.STATE_PLAYING
                sendStateChanged()
            }
        }

        override fun onSessionDestroyed() {
            refreshSessions(this@PhantomMediaListenerService)
        }
    }

    private fun refreshFromListener() {
        try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(
                ComponentName(this, PhantomMediaListenerService::class.java)
            )
            val eligible = sessions.filter { isLocalAudioSession(it) }
            val selected = eligible.firstOrNull {
                it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            } ?: eligible.firstOrNull()

            if (observedController !== selected) {
                observedController?.unregisterCallback(controllerCallback)
                observedController = selected
                observedController?.registerCallback(controllerCallback)
            }
            publish(selected)
            sendStateChanged()
        } catch (_: Exception) {
            observedController?.unregisterCallback(controllerCallback)
            observedController = null
            publish(null)
            sendStateChanged()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshFromListener()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshFromListener()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshFromListener()
    }

    override fun onListenerDisconnected() {
        observedController?.unregisterCallback(controllerCallback)
        observedController = null
        publish(null)
        sendStateChanged()
        super.onListenerDisconnected()
    }

    private fun sendStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    override fun onDestroy() {
        observedController?.unregisterCallback(controllerCallback)
        observedController = null
        publish(null)
        super.onDestroy()
    }
}
