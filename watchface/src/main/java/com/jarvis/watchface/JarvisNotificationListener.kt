package com.jarvis.watchface

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Optional. Makes Jarvis perk up when a notification arrives. Inert until
 * notification access is granted (adb command in the README). The system
 * calls us only when a notification is posted, so there is no polling.
 * Only the category is read, never the content.
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
        AvatarBus.post("notify:$kind")
    }
}
