package app.lararium.ui

import app.lararium.llm.ChatSession
import app.lararium.service.AssistantState
import org.junit.Assert.assertEquals
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
     * (a) mid-turn state present → recreated view shows partial + completes
     */
    @Test
    fun midTurnStatePresent_recreatedViewShowsPartialAndCompletes() {
        val inFlightUserMsg = ChatSession.Message(
            role = "user",
            content = "What is the capital of France?",
            seq = null
        )
        val messagesWithUser = sampleHistory + inFlightUserMsg
        val partialReply = "The capital of France is"
        val activeState = AssistantState.Responding

        // 1. Verify in-flight detection
        assertTrue(ChatStateLogic.isInFlight(activeState, partialReply))

        // 2. Cold-start rehydration must be suppressed while a mid-turn turn is in flight
        assertFalse(ChatStateLogic.shouldRehydrate(messagesWithUser, activeState, partialReply))

        // 3. Activity recreation: View attaches to the state holder mid-turn
        val recreatedViewState = ChatStateLogic.resolveUiState(
            messages = messagesWithUser,
            streamingResponse = partialReply,
            state = activeState
        )

        // (a) Shows prior history
        assertEquals(3, recreatedViewState.messages.size)
        assertEquals("Earlier question", recreatedViewState.messages[0].content)
        assertEquals("Earlier answer", recreatedViewState.messages[1].content)

        // (b) Shows in-flight user message plus partial reply accumulated so far
        assertEquals("What is the capital of France?", recreatedViewState.messages[2].content)
        assertEquals("The capital of France is", recreatedViewState.streamingResponse)
        assertTrue(recreatedViewState.isTurnOpen)
        assertTrue(recreatedViewState.isGenerating)

        // (c) Keeps appending live tokens as they arrive
        val withNextToken = ChatStateLogic.appendToken(recreatedViewState, " Paris.")
        assertEquals("The capital of France is Paris.", withNextToken.streamingResponse)
        assertTrue(withNextToken.isTurnOpen)
        assertTrue(withNextToken.isGenerating)

        // Settles into the final reply
        val settledState = ChatStateLogic.settleTurn(
            current = withNextToken,
            settledAssistantReply = "The capital of France is Paris.",
            seq = 3
        )

        assertEquals(4, settledState.messages.size)
        assertEquals("assistant", settledState.messages[3].role)
        assertEquals("The capital of France is Paris.", settledState.messages[3].content)
        assertEquals("", settledState.streamingResponse)
        assertFalse(settledState.isTurnOpen)
        assertFalse(settledState.isGenerating)
    }

    /**
     * (b) no in-flight turn → history only
     */
    @Test
    fun noInFlightTurn_historyOnly() {
        val idleState = AssistantState.Idle
        assertFalse(ChatStateLogic.isInFlight(idleState, ""))

        val viewState = ChatStateLogic.resolveUiState(
            messages = sampleHistory,
            streamingResponse = "",
            state = idleState
        )

        // View shows history only, with no open turn and no streaming text
        assertEquals(2, viewState.messages.size)
        assertEquals("Earlier question", viewState.messages[0].content)
        assertEquals("Earlier answer", viewState.messages[1].content)
        assertEquals("", viewState.streamingResponse)
        assertFalse(viewState.isTurnOpen)
        assertFalse(viewState.isGenerating)
    }

    /**
     * (c) process-death path unchanged (regression)
     */
    @Test
    fun processDeathPathUnchanged_regression() {
        // After process death, in-memory state is empty
        val inMemoryMessages = emptyList<ChatSession.Message>()
        val state = AssistantState.Idle
        val streamingResponse = ""

        // shouldRehydrate must allow restoring from the durable hearth event log
        assertTrue(ChatStateLogic.shouldRehydrate(inMemoryMessages, state, streamingResponse))

        // Once rehydrated from log, resolved state shows history
        val rehydratedMessages = sampleHistory
        val postRehydrationState = ChatStateLogic.resolveUiState(
            messages = rehydratedMessages,
            streamingResponse = "",
            state = AssistantState.Idle
        )

        assertEquals(2, postRehydrationState.messages.size)
        assertEquals("Earlier question", postRehydrationState.messages[0].content)
        assertEquals("Earlier answer", postRehydrationState.messages[1].content)
        assertEquals("", postRehydrationState.streamingResponse)
        assertFalse(postRehydrationState.isTurnOpen)
    }

    @Test
    fun shouldRehydrate_suppressedWhenInFlightTurnEvenIfMessagesEmpty() {
        // Edge case: query has been accepted by service (Processing/Searching) before user msg is added to list
        assertFalse(ChatStateLogic.shouldRehydrate(emptyList(), AssistantState.Processing, ""))
        assertFalse(ChatStateLogic.shouldRehydrate(emptyList(), AssistantState.Searching, ""))
        assertFalse(ChatStateLogic.shouldRehydrate(emptyList(), AssistantState.Responding, "partial"))
    }
}
