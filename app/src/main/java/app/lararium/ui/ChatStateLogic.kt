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
}
