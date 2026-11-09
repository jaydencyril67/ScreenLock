package com.jaydencyril67.screenlock

import android.app.PendingIntent
import android.media.session.MediaSession

data class PhantomLockNotification(
    val key: String,
    val appName: String,
    val title: String,
    val message: String,
    val postedAt: Long,
    val contentIntent: PendingIntent?
)

/**
 * Shared in-process snapshot consumed by the lockscreen.
 * Only the Play-distribution flavor contains a service that can populate it.
 */
object PhantomNotificationData {
    const val ACTION_DATA_CHANGED =
        "com.jaydencyril67.screenlock.action.LOCKSCREEN_DATA_CHANGED"

    @Volatile
    private var cachedNotifications: List<PhantomLockNotification> = emptyList()

    @Volatile
    private var cachedMediaToken: MediaSession.Token? = null

    fun notifications(): List<PhantomLockNotification> = cachedNotifications

    fun mediaToken(): MediaSession.Token? = cachedMediaToken

    fun update(
        notifications: List<PhantomLockNotification>,
        mediaToken: MediaSession.Token?
    ) {
        cachedNotifications = notifications
        cachedMediaToken = mediaToken
    }

    fun clear() {
        cachedNotifications = emptyList()
        cachedMediaToken = null
    }
}
