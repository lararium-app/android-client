package app.lararium.hearth.rails

import org.json.JSONObject

/**
 * Pure JVM double-gated action sheet lifecycle logic (A6).
 * Contains ZERO android.* imports so it can be tested without Robolectric or framework stubs.
 */

const val RAIL_TIMEOUT_MS = 60_000L

sealed interface ActionState {
    data class Presented(val action: DispatchedAction, val presentedAtMs: Long) : ActionState
    object Approved : ActionState
    data class Denied(val reason: String = "denied_local") : ActionState
    data class TimedOut(val reason: String = "denied_local") : ActionState
}

class ActionSheetLogic(
    val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val actionStates = mutableMapOf<String, ActionState>()

    @Synchronized
    fun present(action: DispatchedAction) {
        if (actionStates.containsKey(action.actionId)) {
            // Already presented or terminal, ignore duplicate present
            return
        }
        actionStates[action.actionId] = ActionState.Presented(action, clock())
    }

    @Synchronized
    fun approve(actionId: String) {
        val current = actionStates[actionId]
        if (current is ActionState.Presented) {
            actionStates[actionId] = ActionState.Approved
        }
        // Post-terminal user input is strictly ignored
    }

    @Synchronized
    fun deny(actionId: String, reason: String = "denied_local") {
        val current = actionStates[actionId]
        if (current is ActionState.Presented) {
            actionStates[actionId] = ActionState.Denied(reason)
        }
        // Post-terminal user input is strictly ignored
    }

    @Synchronized
    fun tick() {
        val now = clock()
        for ((id, state) in actionStates.entries.toList()) {
            if (state is ActionState.Presented) {
                if (now - state.presentedAtMs >= RAIL_TIMEOUT_MS) {
                    actionStates[id] = ActionState.TimedOut("denied_local")
                }
            }
        }
    }

    @Synchronized
    fun state(actionId: String): ActionState? = actionStates[actionId]

    @Synchronized
    fun isTerminal(actionId: String): Boolean {
        return when (actionStates[actionId]) {
            is ActionState.Approved,
            is ActionState.Denied,
            is ActionState.TimedOut -> true
            else -> false
        }
    }

    @Synchronized
    fun receiptBody(actionId: String): JSONObject {
        val state = actionStates[actionId]
            ?: throw IllegalArgumentException("Action '$actionId' not found in ActionSheetLogic")
        return when (state) {
            is ActionState.Approved -> JSONObject().apply {
                put("ok", true)
                put("result", "approved_local")
            }
            is ActionState.Denied -> JSONObject().apply {
                put("ok", false)
                put("error", state.reason)
            }
            is ActionState.TimedOut -> JSONObject().apply {
                put("ok", false)
                put("error", state.reason)
            }
            is ActionState.Presented -> throw IllegalStateException("Action '$actionId' is not in a terminal state")
        }
    }
}
