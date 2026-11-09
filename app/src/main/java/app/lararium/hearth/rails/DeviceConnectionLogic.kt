package app.lararium.hearth.rails

/**
 * Pure JVM stream state machine and backoff calculation (A2).
 * Contains ZERO android.* imports so it can be tested without Robolectric or framework stubs.
 */

sealed interface DeviceStreamState {
    object Idle : DeviceStreamState
    object Connecting : DeviceStreamState
    object Live : DeviceStreamState
    data class Reconnecting(val attempt: Int, val nextDelayMs: Long) : DeviceStreamState
    object RepairRequired : DeviceStreamState
}

enum class OfflineCause {
    NoNetwork,
    Unauthorized,
    StaleToken,
    PairRequired
}

class DeviceStreamStateMachine(
    val clock: () -> Long = { System.currentTimeMillis() }
) {
    var onStateChanged: ((DeviceStreamState) -> Unit)? = null

    var state: DeviceStreamState = DeviceStreamState.Idle
        private set(value) {
            val changed = field != value
            field = value
            if (changed) {
                onStateChanged?.invoke(value)
            }
        }

    private var currentEpoch: Long = 0L
    private var attempt: Int = 0
    private var streamOpenedAt: Long = 0L
    private var lastBytesAt: Long = 0L

    fun currentEpoch(): Long = currentEpoch

    fun nextEpoch(): Long = ++currentEpoch

    fun isCurrentEpoch(e: Long): Boolean = (e == currentEpoch && currentEpoch > 0L)

    fun isStale(nowMs: Long): Boolean {
        return state is DeviceStreamState.Live && (nowMs - lastBytesAt) > 5_000L
    }

    /**
     * Exponential backoff calculation:
     * Initial 1000ms, doubled per attempt, capped at 30_000ms base.
     * Deterministic jitter `+ (attempt * 37L) % 251` chosen to avoid thundering herds
     * while keeping unit tests strict and reproducible.
     */
    fun calculateBackoff(attempt: Int): Long {
        val shift = (attempt - 1).coerceAtLeast(0).coerceAtMost(30)
        val base = minOf(30_000L, 1_000L * (1L shl shift))
        val jitter = (attempt * 37L) % 251L
        return base + jitter
    }

    fun onConnecting() {
        if (state is DeviceStreamState.RepairRequired) {
            // Terminal until explicit repair(token); NO auto-retry past this gate.
            return
        }
        nextEpoch()
        state = DeviceStreamState.Connecting
    }

    fun onStreamOpen() {
        if (state is DeviceStreamState.RepairRequired) {
            return
        }
        val now = clock()
        streamOpenedAt = now
        lastBytesAt = now
        state = DeviceStreamState.Live
    }

    fun onStreamBytes() {
        if (state is DeviceStreamState.RepairRequired) {
            return
        }
        lastBytesAt = clock()
    }

    fun onStreamClosed() {
        if (state is DeviceStreamState.RepairRequired) {
            return
        }
        val now = clock()
        if (state is DeviceStreamState.Live) {
            // A stream that stayed open >= 60s resets attempt counter to 0
            if (now - streamOpenedAt >= 60_000L) {
                attempt = 0
            }
        }
        attempt++
        val nextDelayMs = calculateBackoff(attempt)
        state = DeviceStreamState.Reconnecting(attempt, nextDelayMs)
    }

    fun onFailure(cause: OfflineCause) {
        if (state is DeviceStreamState.RepairRequired) {
            return
        }
        when (cause) {
            OfflineCause.Unauthorized,
            OfflineCause.StaleToken,
            OfflineCause.PairRequired -> {
                state = DeviceStreamState.RepairRequired
            }
            OfflineCause.NoNetwork -> {
                val now = clock()
                if (state is DeviceStreamState.Live) {
                    if (now - streamOpenedAt >= 60_000L) {
                        attempt = 0
                    }
                }
                attempt++
                val nextDelayMs = calculateBackoff(attempt)
                state = DeviceStreamState.Reconnecting(attempt, nextDelayMs)
            }
        }
    }

    fun repair(tokenCleared: Boolean) {
        if (state !is DeviceStreamState.RepairRequired) {
            throw IllegalStateException("repair is only legal from RepairRequired, but current state is $state")
        }
        attempt = 0
        state = DeviceStreamState.Idle
    }
}
