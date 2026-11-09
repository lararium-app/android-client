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
 * - 409 stale_seq rebases SeqTracker onto the server counter and re-pushes a full snapshot
 * - Legacy stale_seq bodies (number only in the error string) rebase via regex
 * - Unparseable 409 and 401/403 back off (injected clock) without rebasing
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

    private fun staleBody(withNumeric: Boolean, current: Long = 7L): String {
        val o = JSONObject()
        o.put("error", "stale seq for test-device/calendar: current $current, proposed 2")
        o.put("reason", "stale_seq")
        if (withNumeric) o.put("current_seq", current)
        return o.toString()
    }

    private fun staleRecovery(body: String) = runBlocking {
        val rows = mutableListOf(makeRow("r1", "Initial"))
        val seqTracker = SeqTracker()
        val posted = mutableListOf<PostedPayload>()
        var respond409Once = false

        val engine = SyncEngine(
            rail = "calendar",
            snapshotProvider = { rows },
            seqTracker = seqTracker,
            endpointProvider = { endpoint },
            httpPoster = { url, b, headers ->
                posted.add(PostedPayload(url, JSONObject(b), headers))
                if (respond409Once) {
                    respond409Once = false
                    HttpResponse(409, body)
                } else {
                    HttpResponse(200, "{\"ok\":true}")
                }
            }
        )

        engine.sync()
        assertEquals(1, posted.size)
        rows.add(makeRow("r2", "Second"))
        respond409Once = true

        assertTrue(engine.sync())
        // initial(1) -> 200, delta(2) -> 409, rebased full snapshot(8) -> 200
        assertEquals(3, posted.size)
        val recovery = posted[2].body
        assertEquals(8L, recovery.getLong("seq"))
        assertTrue("Recovery push must carry full snapshot", recovery.has("snapshot"))
        assertFalse(recovery.has("deltas"))
        assertEquals(2, recovery.getJSONArray("snapshot").length())
        assertEquals(8L, seqTracker.acceptedSeq("calendar"))
    }

    @Test
    fun staleSeq409_withCurrentSeq_rebasesAndPushesFullSnapshot() {
        staleRecovery(staleBody(withNumeric = true))
    }

    @Test
    fun staleSeq409_legacyBody_rebasesViaRegex() {
        staleRecovery(staleBody(withNumeric = false))
    }

    @Test
    fun unparsableStale409_backsOffWithoutTightLoop() = runBlocking {
        var now = 1_000_000L
        val posted = mutableListOf<PostedPayload>()
        val seqTracker = SeqTracker()
        val engine = SyncEngine(
            rail = "calendar",
            snapshotProvider = { listOf(makeRow("r1", "A")) },
            seqTracker = seqTracker,
            endpointProvider = { endpoint },
            httpPoster = { url, b, headers ->
                posted.add(PostedPayload(url, JSONObject(b), headers))
                HttpResponse(409, "{\"error\":\"stale_seq\"}")
            },
            clock = { now }
        )

        assertFalse(engine.sync())
        assertEquals(1, posted.size)
        // Hammering inside the window sends nothing
        repeat(50) { assertFalse(engine.sync()) }
        assertEquals(1, posted.size)

        now += 1_999L
        assertFalse(engine.sync())
        assertEquals(1, posted.size)

        // 2s elapsed: one more attempt, then window doubles to 4s
        now += 1L
        engine.sync()
        assertEquals(2, posted.size)
        now += 3_999L
        engine.sync()
        assertEquals(2, posted.size)
        now += 1L
        engine.sync()
        assertEquals(3, posted.size)

        // Cap at 30s: after many failures the window never exceeds 30s
        repeat(10) { now += 30_000L; engine.sync() }
        val before = posted.size
        now += 29_999L
        engine.sync()
        assertEquals(before, posted.size)
        now += 1L
        engine.sync()
        assertEquals(before + 1, posted.size)
        assertEquals(0L, seqTracker.acceptedSeq("calendar"))
    }

    @Test
    fun backoffResetsAfterSuccess() = runBlocking {
        var now = 0L
        var status = 409
        var calls = 0
        var rev = 0
        val engine = SyncEngine(
            rail = "calendar",
            snapshotProvider = { listOf(makeRow("r1", "A$rev")) },
            seqTracker = SeqTracker(),
            endpointProvider = { endpoint },
            httpPoster = { _, _, _ ->
                calls++
                HttpResponse(status, "{\"ok\":true}")
            },
            clock = { now }
        )
        engine.sync(); now += 2_000L
        engine.sync(); now += 4_000L
        status = 200
        assertTrue(engine.sync())
        // After success the next failure starts again at the 2s base.
        status = 401
        rev++
        engine.sync()
        val c = calls
        now += 1_999L
        engine.sync()
        assertEquals(c, calls)
        now += 1L
        engine.sync()
        assertEquals(c + 1, calls)
    }

    @Test
    fun auth4xx_doNotRebase() = runBlocking {
        for (status in listOf(401, 403)) {
            val seqTracker = SeqTracker()
            seqTracker.accept("calendar", 4L)
            val posted = mutableListOf<PostedPayload>()
            val engine = SyncEngine(
                rail = "calendar",
                snapshotProvider = { listOf(makeRow("r1", "A")) },
                seqTracker = seqTracker,
                endpointProvider = { endpoint },
                httpPoster = { url, b, headers ->
                    posted.add(PostedPayload(url, JSONObject(b), headers))
                    HttpResponse(status, staleBody(withNumeric = true, current = 99L))
                },
                clock = { 0L }
            )
            assertFalse(engine.sync())
            assertEquals(1, posted.size)
            assertEquals("status $status must not rebase", 4L, seqTracker.acceptedSeq("calendar"))
        }
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
