package com.jarvis.watchface

import android.app.Notification
import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Optional. Makes Jarvis react when a notification arrives. Inert until
 * notification access is granted (adb command in the README). The system
 * calls us only when a notification is posted, so there is no polling.
 * Only the category is read, never the content.
 *
 * The screen is never woken from here: on Wear OS, lighting the screen with a
 * fresh notification makes the system show its full-screen card over the face.
 */
class JarvisNotificationListener : NotificationListenerService() {

    // Media: being a notification listener is what lets us see active media sessions
    // (including the one Wear uses to mirror the phone's player). Callbacks only, no polling.
    private val controllers = mutableListOf<MediaController>()
    private val playbackCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = updateMusic()
        override fun onSessionDestroyed() = updateMusic()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { watch(it) }

    override fun onListenerConnected() {
        recount()
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            val me = ComponentName(this, JarvisNotificationListener::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsListener, me)
            watch(msm.getActiveSessions(me))
        } catch (e: SecurityException) {
            Log.w(TAG, "media sessions unavailable: $e")
        }
    }

    override fun onListenerDisconnected() {
        AvatarBus.setUnread(0)
        runCatching { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsListener) }
        watch(null)
    }

    private fun watch(sessions: List<MediaController>?) {
        controllers.forEach { it.unregisterCallback(playbackCallback) }
        controllers.clear()
        sessions?.forEach { it.registerCallback(playbackCallback); controllers += it }
        updateMusic()
    }

    /** Wear's ongoing media chip is showing (it is removed when playback pauses). */
    private var mediaNotification = false

    private fun updateMusic() {
        // Wear's mirror of the phone player can sit in BUFFERING while music plays,
        // so the media notification itself is the main signal; a session reporting
        // PLAYING (e.g. a player on the watch) also counts.
        val playing = mediaNotification ||
            controllers.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (playing != AvatarBus.music) Log.i(TAG, "music ${if (playing) "playing" else "stopped"}")
        AvatarBus.setMusic(playing)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = recount()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        recount()
        val n = sbn.notification
        if (!counts(sbn)) return
        if ((n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0 && n.`when` < System.currentTimeMillis() - 5_000) return
        val kind = when (n.category) {
            Notification.CATEGORY_REMINDER, Notification.CATEGORY_ALARM, Notification.CATEGORY_EVENT -> "remind"
            Notification.CATEGORY_MESSAGE, Notification.CATEGORY_EMAIL, Notification.CATEGORY_CALL,
            Notification.CATEGORY_SOCIAL, "missed_call" -> "message"
            else -> "other"
        }
        Log.i(TAG, "posted ${sbn.packageName} category=${n.category} → $kind")
        AvatarBus.post("notify:$kind:${SystemClock.uptimeMillis()}")
    }

    /** Notifications you can read and swipe away: not ongoing, not un-clearable (media players), not media. */
    private fun counts(sbn: StatusBarNotification): Boolean =
        sbn.packageName != packageName && sbn.isClearable && !isMedia(sbn) &&
            (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0

    private fun isMedia(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        return n.category == Notification.CATEGORY_TRANSPORT ||
            n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            n.extras.getString(Notification.EXTRA_TEMPLATE)?.endsWith("MediaStyle") == true
    }

    private fun recount() {
        val active = try { activeNotifications } catch (e: Exception) { null } ?: return
        AvatarBus.setUnread(active.count(::counts))
        // Only the ongoing media chip: Wear removes it on pause. The player's foreground-service
        // notification (NO_CLEAR, not ongoing) lingers after music stops, so it doesn't count.
        mediaNotification = active.any { it.isOngoing && isMedia(it) }
        updateMusic()
    }

    private companion object {
        const val TAG = "JarvisNotify"
    }
}
