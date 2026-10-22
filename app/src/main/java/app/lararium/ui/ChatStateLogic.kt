package app.lararium.ui

import app.lararium.llm.ChatSession
import app.lararium.service.AssistantState

/**
 * Pure state and decision logic for chat re-attachment across Activity
 * recreation (e.g. rotation) and cold-start rehydration after process death.
 *
 * Zero android.* dependencies for fast JVM unit testing.
 */
object ChatStateLogic {

    /**
     * UI presentation state representing the chat screen content.
     */
    data class ChatUiState(
        val messages: List<ChatSession.Message> = emptyList(),
        val streamingResponse: String = "",
        val assistantState: AssistantState = AssistantState.Idle,
        val isTurnOpen: Boolean = false,
        val isGenerating: Boolean = false
    )

    /**
     * Determines whether an in-flight streaming turn is currently active.
     */
    fun isInFlight(state: AssistantState, streamingResponse: String): Boolean {
        val isTurnState = state is AssistantState.Processing ||
                state is AssistantState.Searching ||
                state is AssistantState.Responding
        return isTurnState || streamingResponse.isNotEmpty()
    }

    /**
     * Decides whether cold-start rehydration from the remote/durable event log
     * should proceed.
     *
     * In-flight turn state or existing in-memory messages must never be stomped
     * by cold rehydration. Only when memory is clean (process death) and no active
     * turn is open should event-log rehydration run.
     */
    fun shouldRehydrate(
        inMemoryMessages: List<ChatSession.Message>,
        state: AssistantState = AssistantState.Idle,
        streamingResponse: String = ""
    ): Boolean {
        if (inMemoryMessages.isNotEmpty()) return false
        if (isInFlight(state, streamingResponse)) return false
        return true
    }

    /**
     * Reconciles the UI view state when a view attaches (or re-attaches after rotation)
     * to the state holder / service.
     */
    fun resolveUiState(
        messages: List<ChatSession.Message>,
        streamingResponse: String,
        state: AssistantState
    ): ChatUiState {
        val inFlight = isInFlight(state, streamingResponse)
        val isGenerating = inFlight || state is AssistantState.Speaking
        return ChatUiState(
            messages = messages,
            streamingResponse = streamingResponse,
            assistantState = state,
            isTurnOpen = inFlight,
            isGenerating = isGenerating
        )
    }

    /**
     * Updates the UI state when a new streaming token arrives mid-turn.
     */
    fun appendToken(current: ChatUiState, token: String): ChatUiState {
        val updated = current.streamingResponse + token
        return current.copy(
            streamingResponse = updated,
            isTurnOpen = true,
            isGenerating = true
        )
    }

    /**
     * Transitions the UI state when the in-flight turn settles into a completed turn.
     * Appends the completed assistant message to history and clears the partial reply.
     */
    fun settleTurn(
        current: ChatUiState,
        settledAssistantReply: String,
        seq: Int? = null
    ): ChatUiState {
        val newMessages = current.messages + ChatSession.Message(
            role = "assistant",
            content = settledAssistantReply,
            seq = seq
        )
        return current.copy(
            messages = newMessages,
            streamingResponse = "",
            assistantState = AssistantState.Complete,
            isTurnOpen = false,
            isGenerating = false
        )
    }
}
