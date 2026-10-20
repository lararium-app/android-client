package app.lararium.hearth.badge

import app.lararium.hearth.watch.WatchLogic
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure Kotlin logic for the pending-approvals badge. Zero android.* dependencies.
 */
object BadgeLogic {

    const val NO_BADGE = -1

    /**
     * Counts pending cards in a /v1/custos/cards body by reusing [WatchLogic.parseCards].
     * Returns null when the body is not a recognisable cards payload, so callers can
     * keep the last-known count instead of showing 0.
     */
    fun countPending(raw: String): Int? {
        val trimmed = raw.trim()
        val recognisable = try {
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
                true
            } else {
                JSONObject(trimmed).optJSONArray("cards") != null
            }
        } catch (_: Exception) {
            false
        }
        return if (recognisable) WatchLogic.parseCards(trimmed).size else null
    }

    /** Badge label for the shield icon: "1".."9", "9+" beyond; null when no badge should show. */
    fun badgeText(count: Int): String? = when {
        count <= 0 -> null
        count > 9 -> "9+"
        else -> count.toString()
    }

    /** Plural category used by plurals.xml for the home banner. */
    fun pluralCategory(count: Int): String = if (count == 1) "one" else "other"
}
