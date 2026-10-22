package app.lararium.hearth

import app.lararium.ui.FailedSend

/**
 * Pure JVM connection management and backoff logic.
 * Contains ZERO android.* imports so it can be tested without Robolectric or framework stubs.
 */

enum class OfflineReason {
    NoNetwork,
    Unreachable,
    Unauthorized
}

sealed interface ConnectionState {
    val needsRepair: Boolean get() = false

    fun messageFor(): String

    object Unknown : ConnectionState {
        override fun messageFor(): String = "Unknown connection state"
    }

    object Live : ConnectionState {
        override fun messageFor(): String = "Connected to hearth"
    }

    data class Reconnecting(val attempt: Int = 1) : ConnectionState {
        override fun messageFor(): String = "Reconnecting to hearth (attempt $attempt)…"
    }

    data class Offline(val reason: OfflineReason) : ConnectionState {
        override val needsRepair: Boolean
            get() = reason == OfflineReason.Unauthorized

        override fun messageFor(): String = when (reason) {
            OfflineReason.NoNetwork -> "No network connection"
            OfflineReason.Unreachable -> "Server unreachable"
            OfflineReason.Unauthorized -> "Hearth token unauthorized or expired — re-pairing required"
        }
    }
}

sealed interface ConnectionEvent {
    object ProbeSuccess : ConnectionEvent
    data class ProbeFailure(val statusCode: Int? = null) : ConnectionEvent
    object NetworkLost : ConnectionEvent
    object NetworkAvailable : ConnectionEvent
    object TurnEvent : ConnectionEvent
    object ManualRetry : ConnectionEvent
}

data class ConnectionTransition(
    val state: ConnectionState,
    val nextProbeDelayMs: Long?
)

val BACKOFF_SCHEDULE_MS = listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)

fun backoffDelayForAttempt(attempt: Int): Long {
    val idx = (attempt - 1).coerceAtLeast(0)
    return if (idx >= BACKOFF_SCHEDULE_MS.size) {
        BACKOFF_SCHEDULE_MS.last()
    } else {
        BACKOFF_SCHEDULE_MS[idx]
    }
}

/**
 * Checks whether [epochAtSend] is stale compared to [current].
 * Epoch is strictly monotonically increasing and only bumps when transitioning away from Live.
 */
fun isStale(epochAtSend: Long, current: Long): Boolean = epochAtSend < current

/**
 * State machine managing connection transitions, backoff scheduling, and connection epoch.
 */
class ConnectionMachine(
    initialState: ConnectionState = ConnectionState.Unknown,
    initialEpoch: Long = 0L
) {
    var state: ConnectionState = initialState
        private set

    @Volatile
    var epoch: Long = initialEpoch
        private set

    private var consecutiveFailures = 0

    fun isStale(epochAtSend: Long): Boolean = isStale(epochAtSend, epoch)

    // Driven from several threads (IO probes, turn callbacks, main-thread
    // network callback) — transitions must be atomic.
    @Synchronized
    fun process(event: ConnectionEvent): ConnectionTransition {
        val oldState = state
        val (newState, nextDelay) = when (event) {
            is ConnectionEvent.ProbeSuccess -> {
                consecutiveFailures = 0
                ConnectionState.Live to null
            }
            is ConnectionEvent.TurnEvent -> {
                consecutiveFailures = 0
                ConnectionState.Live to null
            }
            is ConnectionEvent.NetworkLost -> {
                ConnectionState.Offline(OfflineReason.NoNetwork) to null
            }
            is ConnectionEvent.NetworkAvailable -> {
                if (state is ConnectionState.Offline && (state as ConnectionState.Offline).reason == OfflineReason.Unauthorized) {
                    state to null
                } else {
                    consecutiveFailures = 0
                    ConnectionState.Reconnecting(1) to 0L
                }
            }
            is ConnectionEvent.ManualRetry -> {
                consecutiveFailures = 0
                ConnectionState.Reconnecting(1) to 0L
            }
            is ConnectionEvent.ProbeFailure -> {
                if (event.statusCode == 401 || event.statusCode == 403) {
                    ConnectionState.Offline(OfflineReason.Unauthorized) to null
                } else {
                    consecutiveFailures++
                    val attempt = consecutiveFailures
                    val delay = backoffDelayForAttempt(attempt)
                    ConnectionState.Reconnecting(attempt) to delay
                }
            }
        }

        // Epoch bumps on every transition away from Live
        if (oldState is ConnectionState.Live && newState !is ConnectionState.Live) {
            epoch++
        }

        state = newState
        return ConnectionTransition(newState, nextDelay)
    }
}

/**
 * Tiny pure helper tracking failed sends and retry semantics.
 */
class SendGate {
    var failedSend: FailedSend? = null
        private set

    private var recordedQuery: String? = null

    fun record(query: String) {
        recordedQuery = query
    }

    fun succeed(): FailedSend? {
        failedSend = null
        recordedQuery = null
        return null
    }

    fun fail(query: String = recordedQuery ?: ""): FailedSend {
        val failure = FailedSend(query)
        failedSend = failure
        return failure
    }

    fun retry(query: String? = null): String? {
        val toRetry = query ?: failedSend?.query ?: recordedQuery
        failedSend = null
        recordedQuery = null
        return toRetry
    }

    fun clear() {
        failedSend = null
        recordedQuery = null
    }
}
