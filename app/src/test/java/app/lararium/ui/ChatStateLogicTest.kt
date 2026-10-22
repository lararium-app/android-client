package app.lararium.ui

import app.lararium.llm.ChatSession
import app.lararium.service.AssistantState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for [ChatStateLogic].
 *
 * Tests the re-attachment decision and state recovery across Activity recreation (e.g. rotation)
 * as well as process-death rehydration logic.
 */
class ChatStateLogicTest {

    private val sampleHistory = listOf(
        ChatSession.Message(role = "user", content = "Earlier question", seq = 1),
        ChatSession.Message(role = "assistant", content = "Earlier answer", seq = 2)
    )

    /**
     * Mid-turn state present: the turn counts as in flight and cold-start
     * rehydration must be suppressed so a live turn is never stomped.
     */
    @Test
    fun midTurnState_inFlightAndRehydrationSuppressed() {
        val inFlightUserMsg = ChatSession.Message(
            role = "user",
            content = "What is the capital of France?",
            seq = null
        )
        val messagesWithUser = sampleHistory + inFlightUserMsg
        val partialReply = "The capital of France is"
        val activeState = AssistantState.Responding

        assertTrue(ChatStateLogic.isInFlight(activeState, partialReply))
        assertFalse(ChatStateLogic.shouldRehydrate(messagesWithUser, activeState, partialReply))
    }

    /**
     * Idle with empty streaming text is not an open turn.
     */
    @Test
    fun noInFlightTurn_idleIsNotOpen() {
        assertFalse(ChatStateLogic.isInFlight(AssistantState.Idle, ""))
    }

    /**
     * Process-death path unchanged (regression): clean memory and idle state
     * must allow restoring from the durable hearth event log.
     */
    @Test
    fun processDeathPathUnchanged_regression() {
        assertTrue(
            ChatStateLogic.shouldRehydrate(
                inMemoryMessages = emptyList(),
                state = AssistantState.Idle,
                streamingResponse = ""
            )
        )
    }

    @Test
    fun shouldRehydrate_suppressedWhenInFlightTurnEvenIfMessagesEmpty() {
        // Edge case: query has been accepted by service (Processing/Searching) before user msg is added to list
        assertFalse(ChatStateLogic.shouldRehydrate(emptyList(), AssistantState.Processing, ""))
        assertFalse(ChatStateLogic.shouldRehydrate(emptyList(), AssistantState.Searching, ""))
        assertFalse(ChatStateLogic.shouldRehydrate(emptyList(), AssistantState.Responding, "partial"))
    }
}
