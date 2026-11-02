package app.lararium.hearth.rails.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.lararium.R
import app.lararium.hearth.rails.DispatchedAction

/**
 * In-memory RAM registry for actions awaiting user confirmation (G4).
 * Bounded to 100 actions, evicting the oldest on overflow.
 * Posts heads-up notifications for incoming action dispatches.
 */
object InFlightActions {

    const val MAX_CAPACITY = 100
    const val CHANNEL_ID = "action_confirmations"
    const val CHANNEL_NAME = "Action confirmations"

    private val actions = LinkedHashMap<String, DispatchedAction>()

    @Synchronized
    fun put(action: DispatchedAction, context: Context? = null) {
        if (actions.size >= MAX_CAPACITY && !actions.containsKey(action.actionId)) {
            val oldestKey = actions.keys.firstOrNull()
            if (oldestKey != null) {
                actions.remove(oldestKey)
            }
        }
        actions[action.actionId] = action

        if (context != null) {
            postNotification(context, action)
        }
    }

    @Synchronized
    fun get(actionId: String?): DispatchedAction? {
        if (actionId == null) return null
        return actions[actionId]
    }

    @Synchronized
    fun remove(actionId: String?, context: Context? = null): DispatchedAction? {
        if (actionId == null) return null
        val removed = actions.remove(actionId)
        if (context != null) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(actionId.hashCode())
            } catch (_: Exception) {}
        }
        return removed
    }

    @Synchronized
    fun size(): Int = actions.size

    @Synchronized
    fun all(): List<DispatchedAction> = actions.values.toList()

    @Synchronized
    fun clear() {
        actions.clear()
    }

    fun postNotification(context: Context, action: DispatchedAction) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "High-importance alerts for on-device action confirmations"
                }
                nm.createNotificationChannel(channel)
            }

            val intent = Intent(context, ActionConfirmActivity::class.java).apply {
                putExtra("action_id", action.actionId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                action.actionId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val label = ActionFormatting.humanLabel(action.tool, action.args)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_assistant)
                .setContentTitle("Approve action?")
                .setContentText(label)
                .setStyle(NotificationCompat.BigTextStyle().bigText(label))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(action.actionId.hashCode(), notification)
        } catch (_: Exception) {}
    }
}
