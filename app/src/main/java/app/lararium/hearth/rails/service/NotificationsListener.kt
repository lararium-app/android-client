package app.lararium.hearth.rails.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.lararium.hearth.rails.Row
import org.json.JSONObject

/**
 * Platform notification listener service capturing incoming notifications (F5).
 * Maintains a synchronized snapshot ring of up to 50 non-mutable notifications.
 */
class NotificationsListener : NotificationListenerService() {

    companion object {
        val ring = NotificationSnapshotRing(capacity = 50)

        @Volatile
        var observer: Runnable? = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) {
            // Filter out Lararium's own notifications
            return
        }

        val flags = sbn.notification?.flags ?: 0
        val isOngoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isForeground = (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0

        val id = sbn.key ?: "${sbn.packageName}/${sbn.id}"
        val extras = sbn.notification?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        val data = JSONObject().apply {
            put("id", id)
            put("package", sbn.packageName)
            put("title", title)
            put("text", text)
            put("post_time", sbn.postTime)
        }

        val added = ring.add(id, Row(id, data), isOngoing = isOngoing, isForeground = isForeground)
        if (added) {
            observer?.run()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val id = sbn.key ?: "${sbn.packageName}/${sbn.id}"
        val removed = ring.remove(id)
        if (removed) {
            observer?.run()
        }
    }
}
