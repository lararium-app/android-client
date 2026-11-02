package app.lararium.hearth.rails.service

import app.lararium.hearth.rails.DeviceStreamState
import app.lararium.hearth.rails.DeviceStreamStateMachine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests for DeviceStreamClient (F1, spec A2).
 *
 * Verifies:
 * - Successful line-read loop feeding bytes to state machine
 * - SSE comment line (e.g., ": ping") counts as bytes
 * - HTTP 401 / 403 transitions state machine to RepairRequired
 * - pair_required frame transitions state machine to RepairRequired
 * - Obsolete connection epoch causes callbacks to be dropped
 */
class DeviceStreamClientTest {

    private val endpoint = DeviceEndpoint("https://example.com:7717", "lar1_test_token", "dev-1")

    @Test
    fun streamParsesEventsAndFeedsBytes() = runBlocking {
        val sseStream = buildString {
            append(": ping\n\n")
            append("event: action_dispatch\n")
            append("data: {\"action_id\":\"act-1\",\"tool\":\"device.dialer.call\",\"args\":{\"phone\":\"555-0100\"}}\n\n")
        }

        var currentTime = 1000L
        val sm = DeviceStreamStateMachine(clock = { currentTime })
        val receivedFrames = mutableListOf<Pair<String?, JSONObject>>()

        val client = DeviceStreamClient(
            endpoint = endpoint,
            stateMachine = sm,
            streamOpener = { _, _, onStream ->
                onStream(200, ByteArrayInputStream(sseStream.toByteArray(Charsets.UTF_8)))
            }
        )

        client.openStream { event, data ->
            receivedFrames.add(event to data)
        }

        // Both ': ping' and action_dispatch feed bytes
        assertFalse(sm.isStale(1000L))
        assertEquals(1, receivedFrames.size)

        val (event, data) = receivedFrames.first()
        assertEquals("action_dispatch", event)
        assertEquals("act-1", data.getString("action_id"))
        assertEquals("device.dialer.call", data.getString("tool"))

        // Reconnection backoff scheduled on stream close
        assertTrue(sm.state is DeviceStreamState.Reconnecting)
    }

    @Test
    fun http401_triggersRepairRequired() = runBlocking {
        val sm = DeviceStreamStateMachine()
        val client = DeviceStreamClient(
            endpoint = endpoint,
            stateMachine = sm,
            streamOpener = { _, _, onStream ->
                onStream(401, ByteArrayInputStream(ByteArray(0)))
            }
        )

        client.openStream { _, _ -> }

        assertEquals(DeviceStreamState.RepairRequired, sm.state)
    }

    @Test
    fun http403_triggersRepairRequired() = runBlocking {
        val sm = DeviceStreamStateMachine()
        val client = DeviceStreamClient(
            endpoint = endpoint,
            stateMachine = sm,
            streamOpener = { _, _, onStream ->
                onStream(403, ByteArrayInputStream(ByteArray(0)))
            }
        )

        client.openStream { _, _ -> }

        assertEquals(DeviceStreamState.RepairRequired, sm.state)
    }

    @Test
    fun pairRequiredFrame_triggersRepairRequired() = runBlocking {
        val sseStream = "event: pair_required\ndata: {}\n\n"
        val sm = DeviceStreamStateMachine()

        val client = DeviceStreamClient(
            endpoint = endpoint,
            stateMachine = sm,
            streamOpener = { _, _, onStream ->
                onStream(200, ByteArrayInputStream(sseStream.toByteArray(Charsets.UTF_8)))
            }
        )

        client.openStream { _, _ -> }

        assertEquals(DeviceStreamState.RepairRequired, sm.state)
    }

    @Test
    fun staleEpoch_dropsCallbacks() = runBlocking {
        val sseStream = "event: action_dispatch\ndata: {\"action_id\":\"stale\"}\n\n"
        val sm = DeviceStreamStateMachine()
        val received = mutableListOf<String>()

        val client = DeviceStreamClient(
            endpoint = endpoint,
            stateMachine = sm,
            streamOpener = { _, _, onStream ->
                // Advance state machine epoch before connection completes, simulating a race/superseded socket
                sm.nextEpoch()
                onStream(200, ByteArrayInputStream(sseStream.toByteArray(Charsets.UTF_8)))
            }
        )

        client.openStream { _, data ->
            received.add(data.optString("action_id"))
        }

        assertEquals("Frame from superseded epoch must be dropped", 0, received.size)
    }
}
