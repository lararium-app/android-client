package app.lararium.hearth.badge

import android.content.Context
import app.lararium.hearth.Endpoint
import app.lararium.hearth.LarariumClient
import app.lararium.security.SecureKeyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the pending-approval count shown on the home screen.
 * [BadgeLogic.NO_BADGE] (-1) means unknown / not paired; no badge is displayed.
 */
object HearthBadgeState {

    private val _pending = MutableStateFlow(BadgeLogic.NO_BADGE)
    val pending: StateFlow<Int> = _pending.asStateFlow()

    /**
     * Fetches the current card count. Network or parse failures leave the
     * last-known count untouched; being unpaired clears the badge.
     */
    suspend fun refresh(context: Context) {
        val ep = SecureKeyManager(context.applicationContext).getHearthEndpoint()
        if (ep == null) {
            _pending.value = BadgeLogic.NO_BADGE
            return
        }
        try {
            val raw = LarariumClient(Endpoint(ep.first, ep.second, ep.third)).custosCards()
            BadgeLogic.countPending(raw)?.let { _pending.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // keep last-known count
        }
    }
}
