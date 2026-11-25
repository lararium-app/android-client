package app.lararium.ui.approvals

import app.lararium.hearth.watch.CardInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for ApprovalsLogic (pure-Kotlin failure mapping and card ordering).
 */
class ApprovalsLogicTest {

    @Test
    fun describeResolveFailure_noSuchCard() {
        val msg = describeResolveFailure(404, """{"error":"no_such_card"}""")
        assertEquals("This card is gone — pull to refresh.", msg)
    }

    @Test
    fun describeResolveFailure_alreadyAnswered() {
        val msg = describeResolveFailure(409, """{"error":"already_answered"}""")
        assertEquals("Already answered somewhere else.", msg)
    }

    @Test
    fun describeResolveFailure_staleVerdict() {
        val msg = describeResolveFailure(409, """{"error":"stale_verdict"}""")
        assertEquals("That answer no longer applies.", msg)
    }

    @Test
    fun describeResolveFailure_locked() {
        val msg = describeResolveFailure(423, """{"error":"locked"}""")
        assertEquals("Being decided on another device right now.", msg)
    }

    @Test
    fun describeResolveFailure_ipAskOnly() {
        val msg = describeResolveFailure(400, """{"error":"ip_ask_only"}""")
        assertEquals("This card can only be answered from its own network.", msg)
    }

    @Test
    fun describeResolveFailure_unknownCodeWithBody() {
        val msg = describeResolveFailure(400, """{"error":"invalid verdict"}""")
        assertEquals("Hearth said: invalid verdict (HTTP 400)", msg)
    }

    @Test
    fun describeResolveFailure_customUnknownCode() {
        val msg = describeResolveFailure(503, """{"error":"overloaded"}""")
        assertEquals("Hearth said: overloaded (HTTP 503)", msg)
    }

    @Test
    fun describeResolveFailure_garbageBody() {
        val msg = describeResolveFailure(502, "<html><body>Bad Gateway</body></html>")
        assertEquals("Hearth refused (HTTP 502).", msg)
    }

    // -----------------------------------------------------------------------
    // classifyResolve (issue #169)
    // -----------------------------------------------------------------------

    @Test
    fun classifyResolve_acceptedIsSettled() {
        assertSame(
            ResolveOutcome.Settled,
            classifyResolve(200, """{"state":"allowed_once"}"""),
        )
    }

    @Test
    fun classifyResolve_alreadyAnsweredIsGone() {
        val outcome = classifyResolve(409, """{"error":"already_answered"}""")
        assertEquals(ResolveOutcome.Gone("Already answered somewhere else."), outcome)
    }

    @Test
    fun classifyResolve_staleVerdictIsGone() {
        val outcome = classifyResolve(409, """{"error":"stale_verdict"}""")
        assertEquals(ResolveOutcome.Gone("That answer no longer applies."), outcome)
    }

    @Test
    fun classifyResolve_noSuchCardIsGone() {
        val outcome = classifyResolve(404, """{"error":"no_such_card"}""")
        assertEquals(ResolveOutcome.Gone("This card is gone — pull to refresh."), outcome)
    }

    @Test
    fun classifyResolve_bareNotFoundIsGone() {
        // 404s can carry a non-JSON body (e.g. gateway "not found"); the card is
        // unresolvable from here either way.
        val outcome = classifyResolve(404, "not found")
        assertEquals(ResolveOutcome.Gone("Hearth refused (HTTP 404)."), outcome)
    }

    @Test
    fun classifyResolve_lockedIsRetryable() {
        val outcome = classifyResolve(423, """{"error":"locked"}""")
        assertEquals(ResolveOutcome.Retryable("Being decided on another device right now."), outcome)
    }

    @Test
    fun classifyResolve_ipAskOnlyIsRetryable() {
        val outcome = classifyResolve(400, """{"error":"ip_ask_only"}""")
        assertEquals(ResolveOutcome.Retryable("This card can only be answered from its own network."), outcome)
    }

    @Test
    fun classifyResolve_serverErrorIsRetryable() {
        val outcome = classifyResolve(502, "<html><body>Bad Gateway</body></html>")
        assertEquals(ResolveOutcome.Retryable("Hearth refused (HTTP 502)."), outcome)
    }

    @Test
    fun classifyResolve_transportStatusZeroIsRetryable() {
        val outcome = classifyResolve(0, "")
        assertEquals(ResolveOutcome.Retryable("Hearth refused (HTTP 0)."), outcome)
    }

    @Test
    fun describeResolveFailure_emptyBody() {
        val msg = describeResolveFailure(500, "")
        assertEquals("Hearth refused (HTTP 500).", msg)
    }

    @Test
    fun describeResolveFailure_jsonWithoutErrorField() {
        val msg = describeResolveFailure(400, """{"success":false,"code":123}""")
        assertEquals("Hearth refused (HTTP 400).", msg)
    }

    @Test
    fun describeResolveFailure_jsonWithBlankErrorField() {
        val msg = describeResolveFailure(400, """{"error":"   "}""")
        assertEquals("Hearth refused (HTTP 400).", msg)
    }

    @Test
    fun sortCards_preservesOrder() {
        val card1 = CardInfo("1", "cell1", "cred1", "dest1", "tool1", "review1")
        val card2 = CardInfo("2", "cell2", "cred2", "dest2", "tool2", "review2")
        val card3 = CardInfo("3", "cell3", "cred3", "dest3", "tool3", "review3")
        val input = listOf(card1, card2, card3)

        val output = sortCards(input)
        assertEquals(input, output)
        assertSame(input, output)
    }

    @Test
    fun objectDelegationMatches() {
        val msg1 = ApprovalsLogic.describeResolveFailure(404, """{"error":"no_such_card"}""")
        assertEquals("This card is gone — pull to refresh.", msg1)

        val list = listOf(CardInfo("c1", "a", "b", "c", "d", "e"))
        assertEquals(list, ApprovalsLogic.sortCards(list))
    }
}
