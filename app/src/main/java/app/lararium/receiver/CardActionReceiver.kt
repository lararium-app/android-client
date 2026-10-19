package app.lararium.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.lararium.hearth.Endpoint
import app.lararium.hearth.LarariumClient
import app.lararium.hearth.watch.HearthNotifier
import app.lararium.hearth.watch.WatchLogic
import app.lararium.security.SecureKeyManager
import app.lararium.ui.approvals.ApprovalsLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles action button clicks on custody card notifications ("Allow once" and "Deny").
 * Resolves the custody card via the hearthd API off the main thread, cancels the card
 * notification, and displays a confirmation or error notification.
 */
class CardActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cardId = intent.getStringExtra(EXTRA_CARD_ID)
        if (cardId.isNullOrBlank()) {
            return
        }
        val trimmedCardId = cardId.trim()

        val rawAction = intent.getStringExtra(EXTRA_ACTION) ?: intent.action
        val verdict = WatchLogic.actionToVerdict(rawAction) ?: return
        val lane = intent.getStringExtra(EXTRA_LANE) ?: ""

        // Double-tap safety: check if already resolved or in-flight
        if (resolvedCards.contains(trimmedCardId)) {
            runCatching {
                NotificationManagerCompat.from(context).cancel(WatchLogic.cardNotificationId(trimmedCardId))
            }
            return
        }

        if (!inFlightCards.add(trimmedCardId)) {
            return
        }

        val pendingResult = goAsync()
        val notifId = WatchLogic.cardNotificationId(trimmedCardId)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val epTriple = SecureKeyManager(context).getHearthEndpoint()
                if (epTriple == null) {
                    NotificationManagerCompat.from(context).cancel(notifId)
                    HearthNotifier.notifyCardError(context, trimmedCardId, "Not paired with a hearth yet.")
                    return@launch
                }

                val endpoint = Endpoint(epTriple.first, epTriple.second, epTriple.third)
                val client = LarariumClient(endpoint)
                val (status, body) = client.resolveCard(trimmedCardId, verdict)

                NotificationManagerCompat.from(context).cancel(notifId)

                if (status == 200) {
                    resolvedCards.add(trimmedCardId)
                    HearthNotifier.notifyCardConfirmation(context, trimmedCardId, verdict, lane)
                } else {
                    val failureMessage = ApprovalsLogic.describeResolveFailure(status, body)
                    HearthNotifier.notifyCardError(context, trimmedCardId, failureMessage)
                }
            } catch (e: Exception) {
                val failureMessage = if (e is IOException) {
                    "Can't reach your hearth."
                } else {
                    ApprovalsLogic.describeResolveFailure(0, e.message ?: "")
                }
                NotificationManagerCompat.from(context).cancel(notifId)
                HearthNotifier.notifyCardError(context, trimmedCardId, failureMessage)
            } finally {
                inFlightCards.remove(trimmedCardId)
                pendingResult.finish()
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

        fun isCardInFlight(cardId: String): Boolean = inFlightCards.contains(cardId)
        fun isCardResolved(cardId: String): Boolean = resolvedCards.contains(cardId)
        fun resetStateForTesting() {
            inFlightCards.clear()
            resolvedCards.clear()
        }
    }
}
