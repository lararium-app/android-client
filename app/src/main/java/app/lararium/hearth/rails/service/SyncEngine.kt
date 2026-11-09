package app.lararium.hearth.rails.service

import app.lararium.hearth.HttpResponse
import app.lararium.hearth.Net
import app.lararium.hearth.rails.DeltaBuilder
import app.lararium.hearth.rails.RailsWire
import app.lararium.hearth.rails.Row
import app.lararium.hearth.rails.SeqTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-rail snapshot and incremental delta pusher (F2, spec A5).
 *
 * Pushes full snapshot (seq = acceptedSeq + 1, i.e. 1 on first connect) upon first connect or
 * 409 stale_seq recovery. In steady state, diffs snapshots via DeltaBuilder and pushes deltas
 * (seq = acceptedSeq + 1).
 *
 * On 409 stale_seq the server's counter is adopted via SeqTracker.rebase (numeric current_seq,
 * or parsed from the legacy error string) and the next push is a full snapshot. 4xx failures
 * back off per rail (2s doubling to 30s cap, reset on success) so recovery can never become a
 * resend storm.
 * Does not persist snapshot data on device disk; SeqTracker cursor is RAM-only.
 */
class SyncEngine(
    val rail: String,
    val snapshotProvider: suspend () -> List<Row>,
    val seqTracker: SeqTracker,
    private val endpointProvider: () -> DeviceEndpoint?,
    private val httpPoster: suspend (url: String, body: String, headers: Map<String, String>) -> HttpResponse = { url, body, headers ->
        Net.post(url, body, headers)
    },
    private val coroutineScope: CoroutineScope? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var previousSnapshot: List<Row>? = null
    private var debounceJob: Job? = null
    private var failureCount = 0
    private var nextAttemptAtMs = 0L

    private enum class Outcome { OK, REBASED, FAILED }

    private fun recordSuccess() {
        failureCount = 0
        nextAttemptAtMs = 0L
    }

    private fun recordClientFailure() {
        val delayMs = minOf(BACKOFF_BASE_MS shl minOf(failureCount, 10), BACKOFF_CAP_MS)
        failureCount++
        nextAttemptAtMs = clock() + delayMs
    }

    /**
     * Triggered by ContentObserver or external data change event.
     * Debounces 500ms before pushing deltas.
     */
    fun notifyDataChanged(debounceMs: Long = 500L) {
        val scope = coroutineScope ?: return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            sync()
        }
    }

    /**
     * Executes a sync pass immediately. Returns false without any network call while the
     * rail is inside a 4xx backoff window.
     */
    suspend fun sync(): Boolean {
        if (clock() < nextAttemptAtMs) return false
        val endpoint = endpointProvider() ?: return false

        val first = attempt(endpoint)
        if (first != Outcome.REBASED) return first == Outcome.OK
        // Counter was rebased onto the server's; retry exactly once with a full snapshot.
        val second = attempt(endpoint)
        if (second == Outcome.REBASED) {
            // Server still disagrees right after a rebase: back off rather than chase it.
            recordClientFailure()
        }
        return second == Outcome.OK
    }

    private suspend fun attempt(endpoint: DeviceEndpoint): Outcome {
        val currentRows = snapshotProvider()
        val acceptedSeq = seqTracker.acceptedSeq(rail)
        val nextSeq = acceptedSeq + 1L

        val url = "${endpoint.baseUrl.trimEnd('/')}/v1/devices/${endpoint.deviceId}/sync"
        val headers = mapOf(
            "Authorization" to "Bearer ${endpoint.token}",
            "Content-Type" to "application/json"
        )

        val prev = previousSnapshot
        val envelope = if (prev == null || acceptedSeq == 0L) {
            // Full snapshot push (first connect, or after a stale_seq rebase)
            RailsWire.buildSyncEnvelope(
                rail = rail,
                seq = nextSeq,
                snapshot = buildSnapshotArray(currentRows),
                deltas = null
            )
        } else {
            // Steady state: diff previous and current snapshots
            val deltas = DeltaBuilder.diff(prev, currentRows)
            if (DeltaBuilder.isEmpty(deltas)) {
                // No changes, zero HTTP pushes
                return Outcome.OK
            }
            RailsWire.buildSyncEnvelope(
                rail = rail,
                seq = nextSeq,
                snapshot = null,
                deltas = deltas
            )
        }

        val response = try {
            httpPoster(url, envelope.toString(), headers)
        } catch (_: Exception) {
            return Outcome.FAILED
        }

        return handleSyncResponse(response, nextSeq, currentRows)
    }

    private fun handleSyncResponse(
        response: HttpResponse,
        seqPushed: Long,
        currentRows: List<Row>
    ): Outcome {
        return when {
            response.status in 200..299 -> {
                seqTracker.accept(rail, seqPushed)
                previousSnapshot = currentRows.toList()
                recordSuccess()
                Outcome.OK
            }
            response.status == 409 && isStaleSeq(response.body) -> {
                val serverCurrent = parseServerCurrent(response.body)
                if (serverCurrent != null) {
                    seqTracker.rebase(rail, serverCurrent)
                    // Delta base is untrustworthy after a counter jump: force a full snapshot.
                    previousSnapshot = null
                    Outcome.REBASED
                } else {
                    // Unparseable stale_seq: recoverable, but never resend immediately.
                    previousSnapshot = null
                    recordClientFailure()
                    Outcome.FAILED
                }
            }
            response.status in 400..499 -> {
                recordClientFailure()
                Outcome.FAILED
            }
            else -> {
                // Surface error, retry on next tick
                Outcome.FAILED
            }
        }
    }

    private fun parseBody(body: String?): JSONObject? =
        try { body?.let { JSONObject(it) } } catch (_: Exception) { null }

    private fun isStaleSeq(body: String?): Boolean =
        parseBody(body)?.optString("reason") == "stale_seq"

    private fun parseServerCurrent(body: String?): Long? {
        val json = parseBody(body) ?: return null
        val numeric = json.opt("current_seq")
        if (numeric is Number) return numeric.toLong()
        val match = STALE_CURRENT_REGEX.find(json.optString("error")) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun buildSnapshotArray(rows: List<Row>): JSONArray {
        val array = JSONArray()
        for (row in rows) {
            val item = JSONObject(row.data.toString())
            item.put("id", row.id)
            array.put(item)
        }
        return array
    }

    private companion object {
        const val BACKOFF_BASE_MS = 2_000L
        const val BACKOFF_CAP_MS = 30_000L
        val STALE_CURRENT_REGEX = Regex("""current (\d+)""")
    }
}
