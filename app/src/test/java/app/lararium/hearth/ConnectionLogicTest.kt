package app.lararium.hearth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLogicTest {

    @Test
    fun probeFailBackoffScheduleAndCapping() {
        val machine = ConnectionMachine(initialState = ConnectionState.Live)

        // 1st failure: 2s (2000ms)
        var transition = machine.process(ConnectionEvent.ProbeFailure())
        assertEquals(ConnectionState.Reconnecting(1), transition.state)
        assertEquals(2_000L, transition.nextProbeDelayMs)

        // 2nd failure: 5s (5000ms)
        transition = machine.process(ConnectionEvent.ProbeFailure())
        assertEquals(ConnectionState.Reconnecting(2), transition.state)
        assertEquals(5_000L, transition.nextProbeDelayMs)

        // 3rd failure: 15s (15000ms)
        transition = machine.process(ConnectionEvent.ProbeFailure())
        assertEquals(ConnectionState.Reconnecting(3), transition.state)
        assertEquals(15_000L, transition.nextProbeDelayMs)

        // 4th failure: 30s (30000ms)
        transition = machine.process(ConnectionEvent.ProbeFailure())
        assertEquals(ConnectionState.Reconnecting(4), transition.state)
        assertEquals(30_000L, transition.nextProbeDelayMs)

        // 5th failure: 60s (60000ms)
        transition = machine.process(ConnectionEvent.ProbeFailure())
        assertEquals(ConnectionState.Reconnecting(5), transition.state)
        assertEquals(60_000L, transition.nextProbeDelayMs)

        // 6th failure: capped at 60s
        transition = machine.process(ConnectionEvent.ProbeFailure())
        assertEquals(ConnectionState.Reconnecting(6), transition.state)
        assertEquals(60_000L, transition.nextProbeDelayMs)
    }

    @Test
    fun successTransitionsToLiveAndResets() {
        val machine = ConnectionMachine(initialState = ConnectionState.Live)
        machine.process(ConnectionEvent.ProbeFailure())
        machine.process(ConnectionEvent.ProbeFailure())
        assertEquals(ConnectionState.Reconnecting(2), machine.state)

        // Success transitions to Live and pauses/stops backoff
        val transition = machine.process(ConnectionEvent.ProbeSuccess)
        assertEquals(ConnectionState.Live, transition.state)
        assertNull(transition.nextProbeDelayMs)

        // Next failure starts over at attempt 1 with 2s
        val nextFail = machine.process(ConnectionEvent.ProbeFailure())
        assertEquals(ConnectionState.Reconnecting(1), nextFail.state)
        assertEquals(2_000L, nextFail.nextProbeDelayMs)
    }

    @Test
    fun unauthorized401StopsBackoff() {
        val machine = ConnectionMachine(initialState = ConnectionState.Live)

        val transition401 = machine.process(ConnectionEvent.ProbeFailure(401))
        assertEquals(ConnectionState.Offline(OfflineReason.Unauthorized), transition401.state)
        assertNull(transition401.nextProbeDelayMs)
        assertTrue(machine.state.needsRepair)

        // NetworkAvailable does not restart backoff when unauthorized
        val networkTransition = machine.process(ConnectionEvent.NetworkAvailable)
        assertEquals(ConnectionState.Offline(OfflineReason.Unauthorized), networkTransition.state)
        assertNull(networkTransition.nextProbeDelayMs)

        // Manual retry restarts
        val retryTransition = machine.process(ConnectionEvent.ManualRetry)
        assertEquals(ConnectionState.Reconnecting(1), retryTransition.state)
        assertEquals(0L, retryTransition.nextProbeDelayMs)
    }

    @Test
    fun unauthorized403StopsBackoff() {
        val machine = ConnectionMachine(initialState = ConnectionState.Live)

        val transition403 = machine.process(ConnectionEvent.ProbeFailure(403))
        assertEquals(ConnectionState.Offline(OfflineReason.Unauthorized), transition403.state)
        assertNull(transition403.nextProbeDelayMs)
        assertTrue(machine.state.needsRepair)
    }

    @Test
    fun networkLostPausesProbes() {
        val machine = ConnectionMachine(initialState = ConnectionState.Live)

        val transition = machine.process(ConnectionEvent.NetworkLost)
        assertEquals(ConnectionState.Offline(OfflineReason.NoNetwork), transition.state)
        assertNull(transition.nextProbeDelayMs)
        assertFalse(machine.state.needsRepair)
    }

    @Test
    fun networkAvailableTransitionsToReconnectingAndImmediateProbe() {
        val machine = ConnectionMachine(initialState = ConnectionState.Live)
        machine.process(ConnectionEvent.NetworkLost)
        assertEquals(ConnectionState.Offline(OfflineReason.NoNetwork), machine.state)

        val transition = machine.process(ConnectionEvent.NetworkAvailable)
        assertEquals(ConnectionState.Reconnecting(1), transition.state)
        assertEquals(0L, transition.nextProbeDelayMs)

        // If that immediate probe fails, backoff starts from step 1 (2s)
        val failTransition = machine.process(ConnectionEvent.ProbeFailure())
        assertEquals(ConnectionState.Reconnecting(1), failTransition.state)
        assertEquals(2_000L, failTransition.nextProbeDelayMs)
    }

    @Test
    fun epochBumpsOnLeavingLiveAndNeverRewinds() {
        val machine = ConnectionMachine(initialState = ConnectionState.Live, initialEpoch = 0L)
        assertEquals(0L, machine.epoch)

        // Leaving Live -> epoch bumps
        machine.process(ConnectionEvent.ProbeFailure())
        assertEquals(1L, machine.epoch)

        // Subsequent failure while already Reconnecting -> epoch does not bump
        machine.process(ConnectionEvent.ProbeFailure())
        assertEquals(1L, machine.epoch)

        // Network lost while Reconnecting -> epoch does not bump
        machine.process(ConnectionEvent.NetworkLost)
        assertEquals(1L, machine.epoch)

        // Network available -> Reconnecting -> epoch does not bump
        machine.process(ConnectionEvent.NetworkAvailable)
        assertEquals(1L, machine.epoch)

        // Back to Live -> epoch does not bump
        machine.process(ConnectionEvent.ProbeSuccess)
        assertEquals(1L, machine.epoch)

        // Leaving Live again -> epoch bumps to 2
        machine.process(ConnectionEvent.NetworkLost)
        assertEquals(2L, machine.epoch)
    }

    @Test
    fun isStaleSemantics() {
        val machine = ConnectionMachine(initialState = ConnectionState.Live, initialEpoch = 1L)

        // Same epoch is not stale
        assertFalse(isStale(epochAtSend = 1L, current = 1L))
        assertFalse(machine.isStale(1L))

        // Leaving Live bumps epoch
        machine.process(ConnectionEvent.ProbeFailure())
        assertEquals(2L, machine.epoch)

        // Previous epoch is now stale
        assertTrue(isStale(epochAtSend = 1L, current = 2L))
        assertTrue(machine.isStale(1L))
        assertFalse(machine.isStale(2L))
    }

    @Test
    fun turnEventWhileOfflineResetsToLive() {
        val machine = ConnectionMachine(initialState = ConnectionState.Live)
        machine.process(ConnectionEvent.NetworkLost)
        assertEquals(ConnectionState.Offline(OfflineReason.NoNetwork), machine.state)

        // Turn event while Offline(NoNetwork) -> Live + reset
        val transition = machine.process(ConnectionEvent.TurnEvent)
        assertEquals(ConnectionState.Live, transition.state)
        assertNull(transition.nextProbeDelayMs)

        // Subsequent failure starts at first backoff step
        val fail = machine.process(ConnectionEvent.ProbeFailure())
        assertEquals(ConnectionState.Reconnecting(1), fail.state)
        assertEquals(2_000L, fail.nextProbeDelayMs)
    }

    @Test
    fun sendGateClearSemantics() {
        val gate = SendGate()

        gate.record("hello hearth")
        val failed = gate.fail("hello hearth")
        assertEquals("hello hearth", failed.query)
        assertEquals("hello hearth", gate.failedSend?.query)

        // retry returns query once and clears failedSend
        val query = gate.retry("hello hearth")
        assertEquals("hello hearth", query)
        assertNull(gate.failedSend)

        // second retry returns null
        val second = gate.retry()
        assertNull(second)

        // succeed clears failedSend and returns null
        gate.record("another query")
        gate.fail("another query")
        val result = gate.succeed()
        assertNull(result)
        assertNull(gate.failedSend)
    }

    @Test
    fun sendGateMarkUndeliverableWithValidFallback() {
        val gate = SendGate()
        val failure = gate.markUndeliverable(
            query = "what is the weather?",
            fallbackEndpoint = "https://backup.example.com:7717"
        )
        assertEquals("what is the weather?", failure.query)
        assertEquals("https://backup.example.com:7717", failure.fallbackEndpoint)
        assertEquals(failure, gate.failedSend)
    }

    @Test
    fun sendGateMarkUndeliverableRejectsInsecureCleartextFallbackWithLog() {
        val gate = SendGate()
        var loggedWarning: String? = null
        val failure = gate.markUndeliverable(
            query = "send token",
            fallbackEndpoint = "http://insecure.example.com:7717",
            logWarning = { loggedWarning = it }
        )
        assertEquals("send token", failure.query)
        assertNull("insecure fallback endpoint must be rejected", failure.fallbackEndpoint)
        assertNotNull("rejection reason must be logged", loggedWarning)
        assertTrue(loggedWarning!!.contains("Rejected fallback endpoint"))
    }
}
