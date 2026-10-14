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
     * Sorts custody cards. The server order is authoritative; returns the input list unchanged.
     */
    fun sortCards(cards: List<CardInfo>): List<CardInfo> =
        app.lararium.ui.approvals.sortCards(cards)
}

/**
 * Maps a failed card resolve response (HTTP status + raw response body)
 * into a human-readable failure description.
 */
fun describeResolveFailure(status: Int, body: String): String {
    val errorCode = try {
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
 * Sorts custody cards. The server order is authoritative; returns the input list unchanged.
 */
fun sortCards(cards: List<CardInfo>): List<CardInfo> = cards
