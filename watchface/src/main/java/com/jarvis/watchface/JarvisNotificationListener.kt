package com.jarvis.watchface

import android.app.Notification
import android.content.Context
import android.os.PowerManager
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
 * If the screen is in ambient, it is woken for a few seconds so the reaction
 * is actually seen (at most once per WAKE_GAP_MS).
 */
class JarvisNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0 && n.`when` < System.currentTimeMillis() - 5_000) return
        val kind = when (n.category) {
            Notification.CATEGORY_REMINDER, Notification.CATEGORY_ALARM, Notification.CATEGORY_EVENT -> "remind"
            Notification.CATEGORY_MESSAGE, Notification.CATEGORY_EMAIL, Notification.CATEGORY_CALL,
            Notification.CATEGORY_SOCIAL, "missed_call" -> "message"
            else -> "other"
        }
        Log.i(TAG, "posted ${sbn.packageName} category=${n.category} → $kind")
        AvatarBus.post("notify:$kind")
        wakeScreen()
    }

    /** Turns the screen on briefly; the watch face replays the reaction on wake. */
    @Suppress("DEPRECATION") // ACQUIRE_CAUSES_WAKEUP is the only way to light the screen from a service
    private fun wakeScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return
        val now = SystemClock.uptimeMillis()
        if (now - lastWake < WAKE_GAP_MS) return
        lastWake = now
        try {
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "jarvis:notify",
            ).acquire(WAKE_MS)
            Log.i(TAG, "woke screen for ${WAKE_MS}ms")
        } catch (e: Exception) {
            Log.w(TAG, "screen wake failed: $e")
        }
    }

    private companion object {
        const val TAG = "JarvisNotify"
        const val WAKE_MS = 3_500L
        const val WAKE_GAP_MS = 30_000L
        var lastWake = -WAKE_GAP_MS
    }
}
