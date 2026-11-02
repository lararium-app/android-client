package app.lararium.hearth.rails.service

import app.lararium.hearth.rails.DispatchedAction

/**
 * Common interface for executing on-device action tools (F4, spec A6).
 */
interface RailToolExecutor {
    fun canHandle(tool: String): Boolean
    suspend fun execute(action: DispatchedAction): Pair<Boolean, String>
}
