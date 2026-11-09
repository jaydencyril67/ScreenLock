package com.jaydencyril67.screenlock

import android.media.session.MediaSession

/**
 * The lockscreen consumes only the active media-session token.
 * Notification content is never stored or rendered by Phantom.
 */
object PhantomNotificationData {
    const val ACTION_DATA_CHANGED =
        "com.jaydencyril67.screenlock.action.MEDIA_SESSION_CHANGED"

    @Volatile
    private var cachedMediaToken: MediaSession.Token? = null

    fun mediaToken(): MediaSession.Token? = cachedMediaToken

    fun update(mediaToken: MediaSession.Token?) {
        cachedMediaToken = mediaToken
    }

    fun clear() {
        cachedMediaToken = null
    }
}
