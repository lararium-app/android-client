package app.lararium.hearth.watch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.lararium.MainActivity

/**
 * Manages notification channels and posts local notifications for custody cards,
 * assistant replies, and auth issues.
 */
object HearthNotifier {

    const val CHANNEL_CARDS = "hearth_cards"
    const val CHANNEL_REPLIES = "hearth_replies"

    const val GROUP_CARDS = "app.lararium.cards"
    const val GROUP_REPLIES = "app.lararium.replies"

    /**
     * Idempotently ensures the required notification channels exist on API 26+.
     */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val cardsChannel = NotificationChannel(
                CHANNEL_CARDS,
                "Approvals",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Lararium custody card approvals waiting for review"
            }
            val repliesChannel = NotificationChannel(
                CHANNEL_REPLIES,
                "Replies",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Lararium assistant replies"
            }
            nm.createNotificationChannel(cardsChannel)
            nm.createNotificationChannel(repliesChannel)
        }
    }

    /**
     * Posts a high-importance notification alerting the user to a pending custody card approval.
     */
    fun notifyCard(context: Context, card: CardInfo) {
        ensureChannels(context)
        val lane = when {
            card.tool.isNotBlank() -> card.tool
            card.dest.isNotBlank() -> card.dest
            else -> "credential in use"
        }
        val rawText = if (card.cell.isNotBlank()) "$lane · ${card.cell}" else lane
        val contentText = WatchLogic.truncate(rawText, 120)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("lararium_open", "approvals")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            card.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CARDS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Lararium: approval waiting")
            .setContentText(contentText)
            .setGroup(GROUP_CARDS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(card.id.hashCode(), notification)
        }
    }

    /**
     * Posts a notification for an assistant reply with session ID for stable notification deduplication.
     */
    fun notifyReply(
        context: Context,
        sessionId: String,
        sessionTitle: String,
        reply: ReplyPreview,
    ) {
        ensureChannels(context)
        val title = if (sessionTitle.isNotBlank()) {
            "Lararium: ${WatchLogic.truncate(sessionTitle, 40)}"
        } else {
            "Lararium: new reply"
        }
        val contentText = WatchLogic.truncate(reply.text)

        val notifId = (sessionId + reply.seq).hashCode()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REPLIES)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(contentText)
            .setGroup(GROUP_REPLIES)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        }
    }

    /**
     * Posts a rate-limited notification when the user's hearth rejects the auth token.
     */
    fun notifyAuthError(context: Context) {
        ensureChannels(context)
        val notifId = "hearth_auth_error".hashCode()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REPLIES)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Lararium: pairing rejected")
            .setContentText("Your hearth refused this token. Re-pair from the terminal.")
            .setGroup(GROUP_REPLIES)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        }
    }
}
