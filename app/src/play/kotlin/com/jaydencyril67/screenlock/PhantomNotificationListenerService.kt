package com.jaydencyril67.screenlock

import android.app.Notification
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads notifications only after the user explicitly grants Android's
 * Notification access permission. Private notification content is redacted
 * while the system keyguard is locked.
 */
class PhantomNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        publishSnapshot()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        publishSnapshot()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        publishSnapshot()
    }

    override fun onListenerDisconnected() {
        PhantomNotificationData.clear()
        broadcastChanged()
        super.onListenerDisconnected()
    }

    private fun publishSnapshot() {
        val active = try {
            activeNotifications?.toList().orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val keyguardLocked = try {
            (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        } catch (_: Exception) {
            true
        }

        val mediaItems = active.sortedByDescending { it.postTime }
        var selectedToken: MediaSession.Token? = null
        // Prefer Android's active media sessions so apps without a visible
        // MediaStyle notification can still expose working playback controls.
        try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listener = ComponentName(this, PhantomNotificationListenerService::class.java)
            val sessions = manager.getActiveSessions(listener)
            val activeSession = sessions.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: sessions.firstOrNull { it.metadata != null }
            selectedToken = activeSession?.sessionToken
        } catch (_: Exception) {
            // Fall back to the media-session token carried by notifications.
        }

        if (selectedToken == null) {
            for (sbn in mediaItems) {
                val extras = sbn.notification.extras ?: continue
                @Suppress("DEPRECATION")
                val token = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
                if (token != null) {
                    selectedToken = token
                    break
                }
            }
        }
        val result = ArrayList<PhantomLockNotification>()
        for (sbn in mediaItems) {
            if (result.size >= 4) break
            if (sbn.packageName == packageName) continue

            val notification = sbn.notification
            if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            if (notification.visibility == Notification.VISIBILITY_SECRET) continue

            val appName = try {
                val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                sbn.packageName.substringAfterLast('.')
            }

            val publicNotification = notification.publicVersion
            val showPrivateContent = !keyguardLocked ||
                notification.visibility == Notification.VISIBILITY_PUBLIC

            val visibleNotification = when {
                showPrivateContent -> notification
                publicNotification != null -> publicNotification
                else -> null
            }

            val title: String
            val message: String
            if (visibleNotification == null) {
                title = appName
                message = "New notification"
            } else {
                val extras = visibleNotification.extras
                title = extras?.getCharSequence(Notification.EXTRA_TITLE)
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?: appName
                message = (
                    extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: extras?.getCharSequence(Notification.EXTRA_TEXT)
                        ?: extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)
                    )?.toString()?.take(180).orEmpty()
            }

            if (title.isBlank() && message.isBlank()) continue
            result.add(
                PhantomLockNotification(
                    key = sbn.key,
                    appName = appName,
                    title = title,
                    message = message,
                    postedAt = sbn.postTime,
                    contentIntent = if (
                        !keyguardLocked || notification.visibility == Notification.VISIBILITY_PUBLIC
                    ) notification.contentIntent else null
                )
            )
        }

        PhantomNotificationData.update(result, selectedToken)
        broadcastChanged()
    }

    private fun broadcastChanged() {
        sendBroadcast(Intent(PhantomNotificationData.ACTION_DATA_CHANGED).setPackage(packageName))
    }

}
