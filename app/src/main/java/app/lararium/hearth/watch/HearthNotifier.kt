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
import app.lararium.receiver.CardActionReceiver

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
        val lane = WatchLogic.cardLane(card)
        val rawText = if (card.cell.isNotBlank()) "$lane · ${card.cell}" else lane
        val contentText = WatchLogic.truncate(rawText, 120)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("lararium_open", "approvals")
        }
        val notifId = WatchLogic.cardNotificationId(card.id)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val allowOnceIntent = Intent(context, CardActionReceiver::class.java).apply {
            action = CardActionReceiver.ACTION_ALLOW_ONCE
            putExtra(CardActionReceiver.EXTRA_CARD_ID, card.id)
            putExtra(CardActionReceiver.EXTRA_ACTION, "allow_once")
            putExtra(CardActionReceiver.EXTRA_LANE, lane)
        }
        val allowOncePendingIntent = PendingIntent.getBroadcast(
            context,
            (card.id + ":once").hashCode(),
            allowOnceIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val denyIntent = Intent(context, CardActionReceiver::class.java).apply {
            action = CardActionReceiver.ACTION_DENY
            putExtra(CardActionReceiver.EXTRA_CARD_ID, card.id)
            putExtra(CardActionReceiver.EXTRA_ACTION, "deny")
            putExtra(CardActionReceiver.EXTRA_LANE, lane)
        }
        val denyPendingIntent = PendingIntent.getBroadcast(
            context,
            (card.id + ":deny").hashCode(),
            denyIntent,
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
            .addAction(0, "Allow once", allowOncePendingIntent)
            .addAction(0, "Deny", denyPendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        }
    }

    /**
     * Cancels the pending card notification and posts a brief confirmation notification
     * ("Allowed once: <lane>" / "Denied: <lane>").
     */
    fun notifyCardConfirmation(
        context: Context,
        cardId: String,
        verdict: String,
        lane: String,
    ) {
        ensureChannels(context)
        val notifId = WatchLogic.cardNotificationId(cardId)
        NotificationManagerCompat.from(context).cancel(notifId)

        val confirmationText = WatchLogic.confirmationText(verdict, lane)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("lararium_open", "approvals")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CARDS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Lararium: approval resolved")
            .setContentText(confirmationText)
            .setGroup(GROUP_CARDS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        }
    }

    /**
     * Cancels the pending card notification and surfaces a failure notification
     * directing the user to open the app.
     */
    fun notifyCardError(
        context: Context,
        cardId: String,
        failureReason: String? = null,
    ) {
        ensureChannels(context)
        val notifId = WatchLogic.cardNotificationId(cardId)
        NotificationManagerCompat.from(context).cancel(notifId)

        val rawText = if (!failureReason.isNullOrBlank()) {
            "Could not resolve — open app ($failureReason)"
        } else {
            "Could not resolve — open app"
        }
        val contentText = WatchLogic.truncate(rawText, 120)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("lararium_open", "approvals")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CARDS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Lararium: could not resolve")
            .setContentText(contentText)
            .setGroup(GROUP_CARDS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
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
