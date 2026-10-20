package com.jarvis.watchface

import android.app.Notification
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

    override fun onListenerConnected() = recount()

    override fun onListenerDisconnected() = AvatarBus.setUnread(0)

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

    /** Notifications that stay in the stream until you clear them. */
    private fun counts(sbn: StatusBarNotification): Boolean =
        sbn.packageName != packageName && !sbn.isOngoing &&
            (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0

    private fun recount() {
        val active = try { activeNotifications } catch (e: Exception) { null } ?: return
        AvatarBus.setUnread(active.count(::counts))
    }

    private companion object {
        const val TAG = "JarvisNotify"
    }
}
