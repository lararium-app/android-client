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
 * Pushes full snapshot (seq=1) upon first connect or 409 stale_seq recovery.
 * In steady state, diffs snapshots via DeltaBuilder and pushes deltas (seq = acceptedSeq + 1).
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
    private val coroutineScope: CoroutineScope? = null
) {
    private var previousSnapshot: List<Row>? = null
    private var debounceJob: Job? = null

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
     * Executes a sync pass immediately.
     */
    suspend fun sync(): Boolean {
        val endpoint = endpointProvider() ?: return false
        val currentRows = snapshotProvider()
        val acceptedSeq = seqTracker.acceptedSeq(rail)

        val url = "${endpoint.baseUrl.trimEnd('/')}/local/device/v1/sync"
        val headers = mapOf(
            "Authorization" to "Bearer ${endpoint.token}",
            "Content-Type" to "application/json"
        )

        if (previousSnapshot == null || acceptedSeq == 0L) {
            // Initial full snapshot push (seq=1)
            val snapshotArray = buildSnapshotArray(currentRows)
            val envelope = RailsWire.buildSyncEnvelope(
                rail = rail,
                seq = 1L,
                snapshot = snapshotArray,
                deltas = null
            )

            val response = try {
                httpPoster(url, envelope.toString(), headers)
            } catch (_: Exception) {
                return false
            }

            return handleSyncResponse(response, 1L, currentRows, url, headers)
        } else {
            // Steady state: diff previous and current snapshots
            val deltas = DeltaBuilder.diff(previousSnapshot!!, currentRows)
            if (DeltaBuilder.isEmpty(deltas)) {
                // No changes, zero HTTP pushes
                return true
            }

            val nextSeq = acceptedSeq + 1L
            val envelope = RailsWire.buildSyncEnvelope(
                rail = rail,
                seq = nextSeq,
                snapshot = null,
                deltas = deltas
            )

            val response = try {
                httpPoster(url, envelope.toString(), headers)
            } catch (_: Exception) {
                return false
            }

            return handleSyncResponse(response, nextSeq, currentRows, url, headers)
        }
    }

    private suspend fun handleSyncResponse(
        response: HttpResponse,
        seqPushed: Long,
        currentRows: List<Row>,
        url: String,
        headers: Map<String, String>
    ): Boolean {
        return when {
            response.status in 200..299 -> {
                seqTracker.accept(rail, seqPushed)
                previousSnapshot = currentRows.toList()
                true
            }
            response.status == 409 -> {
                // 409 stale_seq: reset cursor and immediately re-snapshot + full-snapshot push
                seqTracker.reset(rail)
                previousSnapshot = null

                val recoveryRows = snapshotProvider()
                val recoveryArray = buildSnapshotArray(recoveryRows)
                val recoveryEnvelope = RailsWire.buildSyncEnvelope(
                    rail = rail,
                    seq = 1L,
                    snapshot = recoveryArray,
                    deltas = null
                )

                val recoveryResponse = try {
                    httpPoster(url, recoveryEnvelope.toString(), headers)
                } catch (_: Exception) {
                    return false
                }

                if (recoveryResponse.status in 200..299) {
                    seqTracker.accept(rail, 1L)
                    previousSnapshot = recoveryRows.toList()
                    true
                } else {
                    false
                }
            }
            else -> {
                // Surface error, retry on next tick
                false
            }
        }
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
}
