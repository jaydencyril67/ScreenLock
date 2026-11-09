package com.jaydencyril67.screenlock

import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Android requires an enabled notification listener to enumerate active media
 * sessions. Phantom uses that access only to obtain media controls; it never
 * reads, stores, or displays notification content.
 */
class PhantomNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        publishMediaSession()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        publishMediaSession()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        publishMediaSession()
    }

    override fun onListenerDisconnected() {
        PhantomNotificationData.clear()
        broadcastChanged()
        super.onListenerDisconnected()
    }

    private fun publishMediaSession() {
        var selectedToken: MediaSession.Token? = null
        try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listener = ComponentName(this, PhantomNotificationListenerService::class.java)
            val sessions = manager.getActiveSessions(listener)
            val activeSession = sessions.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: sessions.firstOrNull {
                it.metadata != null
            }
            selectedToken = activeSession?.sessionToken
        } catch (_: SecurityException) {
            // Access can be revoked while Phantom is running.
        } catch (_: Exception) {
            // A media app may be starting or stopping its session.
        }

        PhantomNotificationData.update(selectedToken)
        broadcastChanged()
    }

    private fun broadcastChanged() {
        sendBroadcast(Intent(PhantomNotificationData.ACTION_DATA_CHANGED).setPackage(packageName))
    }
}
