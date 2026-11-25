package app.lararium.ui.approvals

import app.lararium.hearth.watch.CardInfo
import org.json.JSONObject

/**
 * Pure Kotlin presentation and error-mapping logic for custody card approvals.
 * Zero android.* dependencies.
 */
object ApprovalsLogic {

    /**
     * Maps a failed card resolve response (HTTP status + raw response body)
     * into a human-readable failure description.
     */
    fun describeResolveFailure(status: Int, body: String): String =
        app.lararium.ui.approvals.describeResolveFailure(status, body)

    /**
     * Classifies a resolve attempt into a single UI action (issue #169).
     */
    fun classifyResolve(status: Int, body: String): ResolveOutcome =
        app.lararium.ui.approvals.classifyResolve(status, body)

    /**
     * Sorts custody cards. The server order is authoritative; returns the input list unchanged.
     */
    fun sortCards(cards: List<CardInfo>): List<CardInfo> =
        app.lararium.ui.approvals.sortCards(cards)
}

/**
 * What the UI must do after a card resolve attempt (issue #169). One card may be
 * visible on several surfaces; every surface reacts to the same classified outcome
 * so a settled card never stays tappable anywhere.
 */
sealed interface ResolveOutcome {

    /** The verdict was accepted; the card settled. */
    data object Settled : ResolveOutcome

    /**
     * The server can no longer accept an answer for this card (gone, already
     * answered, or stale); drop it from every surface.
     */
    data class Gone(val message: String) : ResolveOutcome

    /** Transient or caller-side failure; keep the card visible for a retry. */
    data class Retryable(val message: String) : ResolveOutcome
}

/**
 * Extracts the hearthd error code from a resolve response body, or null when the
 * body carries none. Shared by [describeResolveFailure] and [classifyResolve] so
 * the two can never disagree about the wire code.
 */
private fun resolveErrorCode(body: String): String? {
    return try {
        val json = JSONObject(body)
        if (json.has("error") && !json.isNull("error")) {
            val err = json.optString("error", "").trim()
            err.ifEmpty { null }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Maps a failed card resolve response (HTTP status + raw response body)
 * into a human-readable failure description.
 */
fun describeResolveFailure(status: Int, body: String): String {
    val errorCode = resolveErrorCode(body)

    return when (errorCode) {
        "no_such_card" -> "This card is gone — pull to refresh."
        "already_answered" -> "Already answered somewhere else."
        "stale_verdict" -> "That answer no longer applies."
        "locked" -> "Being decided on another device right now."
        "ip_ask_only" -> "This card can only be answered from its own network."
        null -> "Hearth refused (HTTP $status)."
        else -> "Hearth said: $errorCode (HTTP $status)"
    }
}

/**
 * Classifies a resolve attempt (HTTP status + raw response body) into a
 * [ResolveOutcome]. Settled on 200; Gone when the card can never be answered
 * from here again (already_answered / stale_verdict / no_such_card, or any 404);
 * everything else — lock contention, caller-side 400s, transport errors, 5xx —
 * stays Retryable so a flaky network never eats a live card.
 */
fun classifyResolve(status: Int, body: String): ResolveOutcome {
    if (status == 200) return ResolveOutcome.Settled
    val message = describeResolveFailure(status, body)
    val errorCode = resolveErrorCode(body)
    return when {
        errorCode == "already_answered" || errorCode == "stale_verdict" ||
            errorCode == "no_such_card" || status == 404 -> ResolveOutcome.Gone(message)
        else -> ResolveOutcome.Retryable(message)
    }
}

/**
 * Sorts custody cards. The server order is authoritative; returns the input list unchanged.
 */
fun sortCards(cards: List<CardInfo>): List<CardInfo> = cards
