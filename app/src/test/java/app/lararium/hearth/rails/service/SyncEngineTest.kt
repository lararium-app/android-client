package app.lararium.hearth.rails.service

import app.lararium.hearth.HttpResponse
import app.lararium.hearth.rails.Row
import app.lararium.hearth.rails.SeqTracker
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SyncEngine (F2, spec A5).
 *
 * Verifies:
 * - First push sends full snapshot with seq = 1
 * - Change triggers incremental delta push with seq = acceptedSeq + 1
 * - No change triggers zero network pushes
 * - 409 stale_seq triggers SeqTracker reset and immediate snapshot recovery re-push
 * - Two rails operate completely independently
 */
class SyncEngineTest {

    private val endpoint = DeviceEndpoint("https://example.com:7717", "lar1_test_token", "test-device")

    private fun makeRow(id: String, title: String): Row {
        return Row(id, JSONObject().apply {
            put("id", id)
            put("title", title)
        })
    }

    private data class PostedPayload(
        val url: String,
        val body: JSONObject,
        val headers: Map<String, String>
    )

    @Test
    fun firstPush_sendsSnapshotSeq1() = runBlocking {
        val rows = mutableListOf(makeRow("r1", "Event 1"), makeRow("r2", "Event 2"))
        val seqTracker = SeqTracker()
        val posted = mutableListOf<PostedPayload>()

        val engine = SyncEngine(
            rail = "calendar",
            snapshotProvider = { rows },
            seqTracker = seqTracker,
            endpointProvider = { endpoint },
            httpPoster = { url, body, headers ->
                posted.add(PostedPayload(url, JSONObject(body), headers))
                HttpResponse(200, "{\"ok\":true}")
            }
        )

        val ok = engine.sync()
        assertTrue(ok)
        assertEquals(1, posted.size)

        val envelope = posted.first().body
        assertEquals("calendar", envelope.getString("rail"))
        assertEquals(1L, envelope.getLong("seq"))
        assertTrue(envelope.has("snapshot"))
        assertFalse(envelope.has("deltas"))
        assertEquals(2, envelope.getJSONArray("snapshot").length())
        assertEquals(1L, seqTracker.acceptedSeq("calendar"))
    }

    @Test
    fun change_triggersDeltaPushSeqPlus1() = runBlocking {
        val rows = mutableListOf(makeRow("r1", "Event 1"))
        val seqTracker = SeqTracker()
        val posted = mutableListOf<PostedPayload>()

        val engine = SyncEngine(
            rail = "calendar",
            snapshotProvider = { rows },
            seqTracker = seqTracker,
            endpointProvider = { endpoint },
            httpPoster = { url, body, headers ->
                posted.add(PostedPayload(url, JSONObject(body), headers))
                HttpResponse(200, "{\"ok\":true}")
            }
        )

        // Pass 1: Initial snapshot
        engine.sync()
        assertEquals(1, posted.size)
        assertEquals(1L, seqTracker.acceptedSeq("calendar"))

        // Pass 2: Add a row
        rows.add(makeRow("r2", "Event 2"))
        val ok = engine.sync()
        assertTrue(ok)
        assertEquals(2, posted.size)

        val secondEnvelope = posted[1].body
        assertEquals("calendar", secondEnvelope.getString("rail"))
        assertEquals(2L, secondEnvelope.getLong("seq"))
        assertFalse("Deltas push must not carry full snapshot", secondEnvelope.has("snapshot"))
        assertTrue(secondEnvelope.has("deltas"))

        val deltas = secondEnvelope.getJSONArray("deltas")
        assertEquals(1, deltas.length())
        val op = deltas.getJSONObject(0)
        assertEquals("add", op.getString("op"))
        assertEquals("r2", op.getString("id"))
        assertEquals(2L, seqTracker.acceptedSeq("calendar"))
    }

    @Test
    fun noChange_triggersZeroPushes() = runBlocking {
        val rows = mutableListOf(makeRow("r1", "Event 1"))
        val seqTracker = SeqTracker()
        val posted = mutableListOf<PostedPayload>()

        val engine = SyncEngine(
            rail = "calendar",
            snapshotProvider = { rows },
            seqTracker = seqTracker,
            endpointProvider = { endpoint },
            httpPoster = { url, body, headers ->
                posted.add(PostedPayload(url, JSONObject(body), headers))
                HttpResponse(200, "{\"ok\":true}")
            }
        )

        // Pass 1: Initial push
        engine.sync()
        assertEquals(1, posted.size)

        // Pass 2: No modifications
        val ok = engine.sync()
        assertTrue(ok)
        assertEquals("Zero network calls when snapshot is unchanged", 1, posted.size)
    }

    @Test
    fun staleSeq409_resetsAndRepushesFullSnapshot() = runBlocking {
        val rows = mutableListOf(makeRow("r1", "Initial"))
        val seqTracker = SeqTracker()
        val posted = mutableListOf<PostedPayload>()
        var return409Once = false

        val engine = SyncEngine(
            rail = "calendar",
            snapshotProvider = { rows },
            seqTracker = seqTracker,
            endpointProvider = { endpoint },
            httpPoster = { url, body, headers ->
                posted.add(PostedPayload(url, JSONObject(body), headers))
                if (return409Once) {
                    return409Once = false
                    HttpResponse(409, "{\"error\":\"stale_seq\"}")
                } else {
                    HttpResponse(200, "{\"ok\":true}")
                }
            }
        )

        // Pass 1: First sync succeeds (seq=1)
        engine.sync()
        assertEquals(1, posted.size)
        assertEquals(1L, seqTracker.acceptedSeq("calendar"))

        // Now mutate and simulate server returning 409 stale_seq on delta push
        rows.add(makeRow("r2", "Second"))
        return409Once = true

        val ok = engine.sync()
        assertTrue(ok)

        // There should be 3 posted envelopes total:
        // 1. Initial snapshot (seq=1) -> 200
        // 2. Delta push (seq=2) -> 409
        // 3. Immediate recovery full snapshot (seq=1) -> 200
        assertEquals(3, posted.size)

        val recoveryEnvelope = posted[2].body
        assertEquals("calendar", recoveryEnvelope.getString("rail"))
        assertEquals(1L, recoveryEnvelope.getLong("seq"))
        assertTrue("Recovery push must carry full snapshot", recoveryEnvelope.has("snapshot"))
        assertEquals(2, recoveryEnvelope.getJSONArray("snapshot").length())
        assertEquals(1L, seqTracker.acceptedSeq("calendar"))
    }

    @Test
    fun twoRails_areIndependent() = runBlocking {
        val calRows = mutableListOf(makeRow("c1", "Meeting"))
        val contactRows = mutableListOf(makeRow("p1", "Alice"))
        val seqTracker = SeqTracker()
        val posted = mutableListOf<PostedPayload>()

        val calEngine = SyncEngine(
            rail = "calendar",
            snapshotProvider = { calRows },
            seqTracker = seqTracker,
            endpointProvider = { endpoint },
            httpPoster = { url, body, headers ->
                posted.add(PostedPayload(url, JSONObject(body), headers))
                HttpResponse(200, "{\"ok\":true}")
            }
        )

        val contactEngine = SyncEngine(
            rail = "contacts",
            snapshotProvider = { contactRows },
            seqTracker = seqTracker,
            endpointProvider = { endpoint },
            httpPoster = { url, body, headers ->
                posted.add(PostedPayload(url, JSONObject(body), headers))
                HttpResponse(200, "{\"ok\":true}")
            }
        )

        // Sync both initially
        calEngine.sync()
        contactEngine.sync()

        assertEquals(2, posted.size)
        assertEquals(1L, seqTracker.acceptedSeq("calendar"))
        assertEquals(1L, seqTracker.acceptedSeq("contacts"))

        // Modify only contacts
        contactRows.add(makeRow("p2", "Bob"))
        contactEngine.sync()

        assertEquals(3, posted.size)
        assertEquals("contacts", posted[2].body.getString("rail"))
        assertEquals(2L, posted[2].body.getLong("seq"))

        // Calendar remains unchanged at seq=1
        assertEquals(1L, seqTracker.acceptedSeq("calendar"))
        assertEquals(2L, seqTracker.acceptedSeq("contacts"))
    }
}
