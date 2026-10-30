package app.lararium.hearth

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for [TurnRetryPolicy] (issues #45, #63).
 */
class TurnRetryPolicyTest {

    @Test
    fun happyPath_neverRetries() {
        val policy = TurnRetryPolicy()

        assertEquals(TurnRetryPolicy.Phase.SENDING_INITIAL, policy.onSendStart())
        assertEquals(TurnRetryPolicy.Action.NoOp, policy.onFirstToken())
        assertTrue(policy.receivedFirstToken)

        // Watchdog tick after first token is a no-op
        assertEquals(TurnRetryPolicy.Action.NoOp, policy.onWatchdogTick(45_000L))

        val doneAction = policy.onTurnDone("Hello from hearth")
        assertEquals(TurnRetryPolicy.Action.Complete("Hello from hearth"), doneAction)
        assertEquals(0, policy.retriesUsed)
        assertEquals(TurnRetryPolicy.Phase.COMPLETED, policy.phase)
    }

    @Test
    fun transientFailure_triggersExactlyOneRetry_thenSucceedsOrFailsIfRetryAlsoFails() {
        // Case 1: Transient failure on attempt 0 -> 1 retry -> retry succeeds (no failure surfaced)
        val policySuccessOnRetry = TurnRetryPolicy(retryDelayMs = 1_500L)
        policySuccessOnRetry.onSendStart()

        val firstFailAction = policySuccessOnRetry.onFailure("Connection reset")
        assertEquals(
            TurnRetryPolicy.Action.CheckEventLogBeforeRetry(1_500L),
            firstFailAction
        )
        assertEquals(1, policySuccessOnRetry.retriesUsed)

        val emptyEventsJson = JSONObject().put("events", JSONArray()).put("in_flight", false).toString()
        val retryDecision = policySuccessOnRetry.onEventLogFetched(emptyEventsJson, "first message")
        assertEquals(TurnRetryPolicy.Action.Resend, retryDecision)

        assertEquals(TurnRetryPolicy.Phase.SENDING_RETRY, policySuccessOnRetry.onSendStart())
        policySuccessOnRetry.onFirstToken()
        val doneOnRetry = policySuccessOnRetry.onTurnDone("Answered on retry")
        assertEquals(TurnRetryPolicy.Action.Complete("Answered on retry"), doneOnRetry)
        assertEquals(1, policySuccessOnRetry.retriesUsed)
        assertEquals(TurnRetryPolicy.Phase.COMPLETED, policySuccessOnRetry.phase)

        // Case 2: Transient failure on attempt 0 -> 1 retry -> retry ALSO fails -> Fail surfaced
        val policyFailBoth = TurnRetryPolicy(retryDelayMs = 1_500L)
        policyFailBoth.onSendStart()

        val step1 = policyFailBoth.onZeroTokenClose()
        assertEquals(TurnRetryPolicy.Action.CheckEventLogBeforeRetry(1_500L), step1)
        assertEquals(1, policyFailBoth.retriesUsed)

        val step2 = policyFailBoth.onEventLogFetched(emptyEventsJson, "first message")
        assertEquals(TurnRetryPolicy.Action.Resend, step2)

        policyFailBoth.onSendStart()
        val step3 = policyFailBoth.onFailure("Hearth request failed.")
        assertEquals(TurnRetryPolicy.Action.Fail("Hearth request failed."), step3)
        assertEquals(1, policyFailBoth.retriesUsed)
        assertEquals(TurnRetryPolicy.Phase.FAILED, policyFailBoth.phase)
    }

    @Test
    fun messageAlreadyPresentInEventLog_yieldsWaitForCommitNotResend() {
        val policy = TurnRetryPolicy(retryDelayMs = 1_000L, commitWaitDeadlineMs = 150_000L)
        policy.onSendStart()

        val failAction = policy.onZeroTokenClose()
        assertEquals(TurnRetryPolicy.Action.CheckEventLogBeforeRetry(1_000L), failAction)

        val eventsWithUserMessage = JSONObject().apply {
            put("in_flight", true)
            put(
                "events",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("t", "msg")
                            put("role", "user")
                            put("text", "tell me a story")
                            put("seq", 1)
                        }
                    )
                }
            )
        }.toString()

        assertTrue(TurnRetryPolicy.hasUserMessageInLog(eventsWithUserMessage, "tell me a story"))
        assertFalse(TurnRetryPolicy.hasUserMessageInLog(eventsWithUserMessage, "different message"))

        val decision = policy.onEventLogFetched(eventsWithUserMessage, "tell me a story")
        assertEquals(TurnRetryPolicy.Action.WaitForCommit(150_000L), decision)
        assertEquals(TurnRetryPolicy.Phase.WAITING_FOR_COMMIT, policy.phase)

        // Commit arrives -> Complete
        val complete = policy.onCommitWaitResult("Once upon a time...")
        assertEquals(TurnRetryPolicy.Action.Complete("Once upon a time..."), complete)
        assertEquals(TurnRetryPolicy.Phase.COMPLETED, policy.phase)

        // If wait-for-commit times out with null -> Fail
        val timeoutPolicy = TurnRetryPolicy()
        timeoutPolicy.onSendStart()
        timeoutPolicy.onFailure("dropped")
        assertEquals(
            TurnRetryPolicy.Action.WaitForCommit(150_000L),
            timeoutPolicy.onEventLogFetched(eventsWithUserMessage, "tell me a story")
        )
        assertEquals(
            TurnRetryPolicy.Action.Fail("still answering"),
            timeoutPolicy.onCommitWaitResult(null, "still answering")
        )
        assertEquals(TurnRetryPolicy.Phase.FAILED, timeoutPolicy.phase)
    }

    @Test
    fun watchdog45s_withNoTokensAndNoCommit_yieldsRetryThenFailSequence() {
        val policy = TurnRetryPolicy(watchdogTimeoutMs = 45_000L, retryDelayMs = 1_500L)
        val emptyEvents = JSONObject().put("events", JSONArray()).put("in_flight", false).toString()

        // Initial send starts
        policy.onSendStart()

        // Before 45 s -> NoOp
        assertEquals(TurnRetryPolicy.Action.NoOp, policy.onWatchdogTick(44_999L, emptyEvents, "ping"))

        // At 45 s with 0 tokens and no commit -> triggers single retry check
        val firstTimeout = policy.onWatchdogTick(45_000L, emptyEvents, "ping")
        assertEquals(TurnRetryPolicy.Action.CheckEventLogBeforeRetry(1_500L), firstTimeout)
        assertEquals(1, policy.retriesUsed)

        // Event log has no message -> Resend
        val retryAction = policy.onEventLogFetched(emptyEvents, "ping")
        assertEquals(TurnRetryPolicy.Action.Resend, retryAction)

        // Retry send starts; 45 s elapses again with 0 tokens and no commit -> Fail
        policy.onSendStart()
        val secondTimeout = policy.onWatchdogTick(45_000L, emptyEvents, "ping")
        assertTrue(secondTimeout is TurnRetryPolicy.Action.Fail)
        assertEquals(1, policy.retriesUsed)
        assertEquals(TurnRetryPolicy.Phase.FAILED, policy.phase)
    }
}
