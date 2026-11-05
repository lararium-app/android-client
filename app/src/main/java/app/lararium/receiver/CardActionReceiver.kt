package app.lararium.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.lararium.MainActivity
import app.lararium.hearth.watch.WatchLogic
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles action button clicks on custody card notifications (R4).
 *
 * Notification actions route into [MainActivity] with review extras instead of resolving
 * cards in the background. Enforces double-tap safety via [CardNotificationIntentLogic].
 */
class CardActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val rawCardId = intent.getStringExtra(EXTRA_CARD_ID)
        val rawAction = intent.getStringExtra(EXTRA_ACTION) ?: intent.action
        val verdict = WatchLogic.actionToVerdict(rawAction)
        val lane = intent.getStringExtra(EXTRA_LANE)

        val decision = CardNotificationIntentLogic.decideAction(
            cardId = rawCardId,
            suggestedVerdict = verdict,
            lane = lane,
            isCardResolved = { isCardResolved(it) }
        )

        when (decision) {
            is CardNotificationIntentLogic.ActionDecision.LaunchReview -> {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    action = CardNotificationIntentLogic.ACTION_REVIEW_CARD
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("lararium_open", "approvals")
                    putExtra(CardNotificationIntentLogic.EXTRA_CARD_ID, decision.cardId)
                    putExtra(CardNotificationIntentLogic.EXTRA_SUGGESTED_VERDICT, decision.suggestedVerdict)
                    putExtra(CardNotificationIntentLogic.EXTRA_LANE, decision.lane)
                }
                context.startActivity(launchIntent)
            }
            is CardNotificationIntentLogic.ActionDecision.IgnoreAlreadyResolved -> {
                rawCardId?.trim()?.let { id ->
                    runCatching {
                        NotificationManagerCompat.from(context).cancel(WatchLogic.cardNotificationId(id))
                    }
                }
            }
            is CardNotificationIntentLogic.ActionDecision.IgnoreInvalid -> {
                // Ignore malformed intents
            }
        }
    }

    companion object {
        const val ACTION_CARD_ACTION = "app.lararium.action.CARD_ACTION"
        const val ACTION_ALLOW_ONCE = "app.lararium.action.CARD_ALLOW_ONCE"
        const val ACTION_DENY = "app.lararium.action.CARD_DENY"

        const val EXTRA_CARD_ID = "card_id"
        const val EXTRA_ACTION = "action"
        const val EXTRA_LANE = "lane"

        private val inFlightCards = ConcurrentHashMap.newKeySet<String>()
        private val resolvedCards = ConcurrentHashMap.newKeySet<String>()

        fun isCardInFlight(cardId: String): Boolean = inFlightCards.contains(cardId.trim())
        fun isCardResolved(cardId: String): Boolean = resolvedCards.contains(cardId.trim())

        fun markCardResolved(cardId: String) {
            resolvedCards.add(cardId.trim())
        }

        fun resetStateForTesting() {
            inFlightCards.clear()
            resolvedCards.clear()
        }
    }
}
