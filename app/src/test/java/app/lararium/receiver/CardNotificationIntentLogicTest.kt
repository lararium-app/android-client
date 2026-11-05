package app.lararium.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for [CardNotificationIntentLogic] (R4, R7).
 * Tests routing to review for unresolved cards, no-op for resolved cards (double-tap safety),
 * and ignore on malformed intents.
 * ZERO Android dependencies.
 */
class CardNotificationIntentLogicTest {

    @Test
    fun unresolvedCardOpensReview() {
        val decision = CardNotificationIntentLogic.decideAction(
            cardId = "card_alpha_1",
            suggestedVerdict = "allow_once",
            lane = "custody",
            isCardResolved = { false }
        )

        assertTrue("Unresolved card must result in LaunchReview decision", decision is CardNotificationIntentLogic.ActionDecision.LaunchReview)
        val review = decision as CardNotificationIntentLogic.ActionDecision.LaunchReview
        assertEquals("card_alpha_1", review.cardId)
        assertEquals("allow_once", review.suggestedVerdict)
        assertEquals("custody", review.lane)
    }

    @Test
    fun unresolvedCardWithBlankVerdict_opensReviewWithNullVerdict() {
        val decision = CardNotificationIntentLogic.decideAction(
            cardId = "card_beta_2",
            suggestedVerdict = "   ",
            lane = null,
            isCardResolved = { false }
        )

        assertTrue(decision is CardNotificationIntentLogic.ActionDecision.LaunchReview)
        val review = decision as CardNotificationIntentLogic.ActionDecision.LaunchReview
        assertEquals("card_beta_2", review.cardId)
        assertEquals(null, review.suggestedVerdict)
        assertEquals(null, review.lane)
    }

    @Test
    fun resolvedCardIsNoOp() {
        val decision = CardNotificationIntentLogic.decideAction(
            cardId = "card_alpha_1",
            suggestedVerdict = "allow_once",
            lane = "custody",
            isCardResolved = { id -> id == "card_alpha_1" }
        )

        assertEquals(
            "Already resolved card must be ignored (double-tap safety)",
            CardNotificationIntentLogic.ActionDecision.IgnoreAlreadyResolved,
            decision
        )
    }

    @Test
    fun blankOrNullCardIsIgnored() {
        val decisionNull = CardNotificationIntentLogic.decideAction(
            cardId = null,
            suggestedVerdict = "deny",
            lane = null,
            isCardResolved = { false }
        )
        assertEquals(CardNotificationIntentLogic.ActionDecision.IgnoreInvalid, decisionNull)

        val decisionBlank = CardNotificationIntentLogic.decideAction(
            cardId = "   ",
            suggestedVerdict = "deny",
            lane = null,
            isCardResolved = { false }
        )
        assertEquals(CardNotificationIntentLogic.ActionDecision.IgnoreInvalid, decisionBlank)
    }
}
