package app.lararium.receiver

/**
 * Pure JVM logic determining routing and double-tap safety checks for custody card notification clicks (R4).
 * Pure Kotlin — zero Android dependencies for pure JVM testability.
 */
object CardNotificationIntentLogic {
    const val ACTION_REVIEW_CARD = "app.lararium.action.REVIEW_CARD"
    const val EXTRA_CARD_ID = "card_id"
    const val EXTRA_SUGGESTED_VERDICT = "suggested_verdict"
    const val EXTRA_LANE = "lane"

    sealed interface ActionDecision {
        data class LaunchReview(
            val cardId: String,
            val suggestedVerdict: String?,
            val lane: String?
        ) : ActionDecision

        object IgnoreAlreadyResolved : ActionDecision
        object IgnoreInvalid : ActionDecision
    }

    /**
     * Decides the action to take when a notification quick-action intent is triggered.
     *
     * @param cardId Identifier of the custody card
     * @param suggestedVerdict Suggested verdict ("allow_once", "deny", etc.)
     * @param lane Optional card lane
     * @param isCardResolved Predicate returning true if the card has already been resolved in-app
     */
    fun decideAction(
        cardId: String?,
        suggestedVerdict: String?,
        lane: String?,
        isCardResolved: (String) -> Boolean
    ): ActionDecision {
        if (cardId.isNullOrBlank()) {
            return ActionDecision.IgnoreInvalid
        }
        val trimmed = cardId.trim()
        if (isCardResolved(trimmed)) {
            return ActionDecision.IgnoreAlreadyResolved
        }
        return ActionDecision.LaunchReview(
            cardId = trimmed,
            suggestedVerdict = suggestedVerdict?.takeIf { it.isNotBlank() },
            lane = lane
        )
    }
}
