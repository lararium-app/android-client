package app.lararium.hearth.rails.ui

import app.lararium.hearth.rails.ActionSheetLogic
import app.lararium.hearth.rails.ActionState
import app.lararium.hearth.rails.DispatchedAction
import app.lararium.hearth.rails.RAIL_TIMEOUT_MS
import app.lararium.hearth.rails.RailsWire
import app.lararium.hearth.rails.ReceiptQueue
import app.lararium.hearth.rails.service.RailToolExecutor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Pure JVM presenter driving action confirmation sheets (G3).
 * Bridges ActionSheetLogic lifecycle, countdown calculation, tool executors,
 * and ReceiptQueue enqueue guarantee.
 * Contains ZERO android.* imports so it can be tested without Robolectric or framework stubs.
 */
class SheetPresenter(
    val action: DispatchedAction,
    val logic: ActionSheetLogic = ActionSheetLogic(),
    val executors: List<RailToolExecutor> = emptyList(),
    val receiptQueue: ReceiptQueue = ReceiptQueue(),
    val inFlightActions: InFlightActions = InFlightActions,
    val receiptEnqueuer: (String, JSONObject) -> Unit = { id, body -> receiptQueue.enqueue(id, body) },
    val onFinish: () -> Unit = {}
) {
    init {
        logic.present(action)
    }

    private var terminalResolved = false

    val actionId: String get() = action.actionId

    fun currentState(): ActionState? = logic.state(action.actionId)

    fun isTerminal(): Boolean = logic.isTerminal(action.actionId)

    fun msRemaining(): Long {
        val state = logic.state(action.actionId)
        return if (state is ActionState.Presented) {
            val elapsed = logic.clock() - state.presentedAtMs
            (RAIL_TIMEOUT_MS - elapsed).coerceAtLeast(0L)
        } else {
            0L
        }
    }

    fun countdownLabel(): String = ActionFormatting.countdownLabel(msRemaining())

    fun humanLabel(): String = ActionFormatting.humanLabel(action.tool, action.args)

    fun onTick(): Boolean {
        logic.tick()
        val state = logic.state(action.actionId)
        if (state != null && logic.isTerminal(action.actionId)) {
            resolveTerminal(state)
            return true
        }
        return false
    }

    fun onApprove() {
        logic.approve(action.actionId)
        val state = logic.state(action.actionId)
        if (state != null) {
            resolveTerminal(state)
        }
    }

    fun onDeny(reason: String = "denied_local") {
        logic.deny(action.actionId, reason)
        val state = logic.state(action.actionId)
        if (state != null) {
            resolveTerminal(state)
        }
    }

    fun onBackPressed() {
        // Non-cancelable-by-back-press (back = deny)
        onDeny("denied_local")
    }

    @Synchronized
    fun resolveTerminal(state: ActionState) {
        if (terminalResolved) return
        if (!logic.isTerminal(action.actionId)) return
        terminalResolved = true

        when (state) {
            is ActionState.Approved -> {
                val executor = executors.firstOrNull { it.canHandle(action.tool) }
                val (ok, resultOrError) = if (executor != null) {
                    runBlocking {
                        executor.execute(action)
                    }
                } else {
                    Pair(false, "unsupported_tool")
                }

                val receiptBody = if (ok) {
                    RailsWire.buildReceiptBody(
                        actionId = action.actionId,
                        ok = true,
                        result = resultOrError,
                        error = null
                    )
                } else {
                    RailsWire.buildReceiptBody(
                        actionId = action.actionId,
                        ok = false,
                        result = null,
                        error = resultOrError
                    )
                }
                receiptEnqueuer(action.actionId, receiptBody)
            }
            is ActionState.Denied -> {
                val receiptBody = RailsWire.buildReceiptBody(
                    actionId = action.actionId,
                    ok = false,
                    result = null,
                    error = state.reason
                )
                receiptEnqueuer(action.actionId, receiptBody)
            }
            is ActionState.TimedOut -> {
                val receiptBody = RailsWire.buildReceiptBody(
                    actionId = action.actionId,
                    ok = false,
                    result = null,
                    error = state.reason
                )
                receiptEnqueuer(action.actionId, receiptBody)
            }
            is ActionState.Presented -> {}
        }

        inFlightActions.remove(action.actionId)
        onFinish()
    }
}
