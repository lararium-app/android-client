package app.lararium.hearth.rails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for DeviceConnectionLogic and DeviceStreamStateMachine (A2).
 */
class DeviceConnectionLogicTest {

    // -----------------------------------------------------------------------
    // Transition matrix & happy path
    // -----------------------------------------------------------------------

    @Test
    fun transitionMatrix_happyPath() {
        var currentTime = 1_000_000L
        val sm = DeviceStreamStateMachine(clock = { currentTime })

        // Initial state is Idle
        assertEquals(DeviceStreamState.Idle, sm.state)
        assertEquals(0L, sm.currentEpoch())

        // onConnecting bumps epoch and enters Connecting
        sm.onConnecting()
        assertEquals(DeviceStreamState.Connecting, sm.state)
        assertEquals(1L, sm.currentEpoch())
        assertTrue(sm.isCurrentEpoch(1L))
        assertFalse(sm.isCurrentEpoch(0L))

        // onStreamOpen enters Live
        sm.onStreamOpen()
        assertEquals(DeviceStreamState.Live, sm.state)
        assertFalse(sm.isStale(currentTime))

        // onStreamBytes updates lastBytesAt
        currentTime += 3_000L
        sm.onStreamBytes()
        assertFalse(sm.isStale(currentTime))

        // onStreamClosed transitions to Reconnecting with attempt 1
        currentTime += 10_000L // 13s open (<60s)
        sm.onStreamClosed()
        assertTrue(sm.state is DeviceStreamState.Reconnecting)
        val recon1 = sm.state as DeviceStreamState.Reconnecting
        assertEquals(1, recon1.attempt)
        assertEquals(1037L, recon1.nextDelayMs) // 1000 + (1 * 37) % 251 = 1037

        // Second failure transitions to attempt 2
        sm.onFailure(OfflineCause.NoNetwork)
        val recon2 = sm.state as DeviceStreamState.Reconnecting
        assertEquals(2, recon2.attempt)
        assertEquals(2074L, recon2.nextDelayMs) // 2000 + (2 * 37) % 251 = 2074
    }

    // -----------------------------------------------------------------------
    // Terminal 401/403-class failures & RepairRequired gate
    // -----------------------------------------------------------------------

    @Test
    fun terminalFailure_unauthorizedBlocksFurtherAutoTransitions() {
        var currentTime = 1_000_000L
        val sm = DeviceStreamStateMachine(clock = { currentTime })

        sm.onConnecting()
        sm.onStreamOpen()
        assertEquals(DeviceStreamState.Live, sm.state)

        // 401 Unauthorized causes RepairRequired
        sm.onFailure(OfflineCause.Unauthorized)
        assertEquals(DeviceStreamState.RepairRequired, sm.state)

        // Verify NO further auto transitions are permitted
        sm.onConnecting()
        assertEquals(DeviceStreamState.RepairRequired, sm.state)

        sm.onStreamOpen()
        assertEquals(DeviceStreamState.RepairRequired, sm.state)

        sm.onStreamBytes()
        assertEquals(DeviceStreamState.RepairRequired, sm.state)

        sm.onStreamClosed()
        assertEquals(DeviceStreamState.RepairRequired, sm.state)

        sm.onFailure(OfflineCause.NoNetwork)
        assertEquals(DeviceStreamState.RepairRequired, sm.state)

        sm.onFailure(OfflineCause.StaleToken)
        assertEquals(DeviceStreamState.RepairRequired, sm.state)

        // Explicit repair clears RepairRequired
        sm.repair(tokenCleared = true)
        assertEquals(DeviceStreamState.Idle, sm.state)

        // Now onConnecting is allowed again
        sm.onConnecting()
        assertEquals(DeviceStreamState.Connecting, sm.state)
    }

    @Test
    fun terminalFailure_staleTokenAndPairRequired() {
        val sm1 = DeviceStreamStateMachine()
        sm1.onConnecting()
        sm1.onFailure(OfflineCause.StaleToken)
        assertEquals(DeviceStreamState.RepairRequired, sm1.state)

        val sm2 = DeviceStreamStateMachine()
        sm2.onConnecting()
        sm2.onFailure(OfflineCause.PairRequired)
        assertEquals(DeviceStreamState.RepairRequired, sm2.state)
    }

    @Test
    fun repair_onlyLegalFromRepairRequired() {
        val sm = DeviceStreamStateMachine()
        assertEquals(DeviceStreamState.Idle, sm.state)

        try {
            sm.repair(tokenCleared = true)
            fail("Expected IllegalStateException when repairing from Idle")
        } catch (e: IllegalStateException) {
            // Success
        }

        sm.onConnecting()
        try {
            sm.repair(tokenCleared = true)
            fail("Expected IllegalStateException when repairing from Connecting")
        } catch (e: IllegalStateException) {
            // Success
        }
    }

    // -----------------------------------------------------------------------
    // Epoch invalidation
    // -----------------------------------------------------------------------

    @Test
    fun epoch_monotonicAndInvalidatesStale() {
        val sm = DeviceStreamStateMachine()

        sm.onConnecting()
        val epoch1 = sm.currentEpoch()
        assertEquals(1L, epoch1)
        assertTrue(sm.isCurrentEpoch(epoch1))

        sm.onConnecting()
        val epoch2 = sm.currentEpoch()
        assertEquals(2L, epoch2)
        assertTrue(sm.isCurrentEpoch(epoch2))
        assertFalse("Old epoch must be rejected", sm.isCurrentEpoch(epoch1))

        sm.onConnecting()
        val epoch3 = sm.currentEpoch()
        assertEquals(3L, epoch3)
        assertTrue(sm.isCurrentEpoch(epoch3))
        assertFalse(sm.isCurrentEpoch(epoch2))
        assertFalse(sm.isCurrentEpoch(epoch1))
    }

    // -----------------------------------------------------------------------
    // isStale boundary testing
    // -----------------------------------------------------------------------

    @Test
    fun isStale_boundaryTesting() {
        var currentTime = 1_000_000L
        val sm = DeviceStreamStateMachine(clock = { currentTime })

        // Not Live -> isStale is always false
        assertFalse(sm.isStale(currentTime + 10_000L))

        sm.onConnecting()
        assertFalse(sm.isStale(currentTime + 10_000L))

        sm.onStreamOpen()
        // Exactly at start: 0ms elapsed -> false
        assertFalse(sm.isStale(currentTime))

        // 4999ms elapsed -> false (<= 5000)
        assertFalse(sm.isStale(currentTime + 4_999L))

        // 5000ms elapsed -> false (<= 5000)
        assertFalse(sm.isStale(currentTime + 5_000L))

        // 5001ms elapsed -> true (> 5000, dead stream)
        assertTrue(sm.isStale(currentTime + 5_001L))

        // Receiving stream bytes resets the timer
        currentTime += 4_000L
        sm.onStreamBytes()
        assertFalse(sm.isStale(currentTime + 4_999L))
        assertTrue(sm.isStale(currentTime + 5_001L))
    }

    // -----------------------------------------------------------------------
    // Backoff ladder & deterministic jitter
    // -----------------------------------------------------------------------

    @Test
    fun backoffLadder_deterministicCalculation() {
        val sm = DeviceStreamStateMachine()

        // Formula: min(30000, 1000 * 2^(attempt-1)) + (attempt * 37) % 251
        // Attempt 1: 1000 + 37 = 1037
        assertEquals(1037L, sm.calculateBackoff(1))

        // Attempt 2: 2000 + 74 = 2074
        assertEquals(2074L, sm.calculateBackoff(2))

        // Attempt 3: 4000 + 111 = 4111
        assertEquals(4111L, sm.calculateBackoff(3))

        // Attempt 4: 8000 + 148 = 8148
        assertEquals(8148L, sm.calculateBackoff(4))

        // Attempt 5: 16000 + 185 = 16185
        assertEquals(16185L, sm.calculateBackoff(5))

        // Attempt 6: 30000 + 222 = 30222 (base capped at 30000)
        assertEquals(30222L, sm.calculateBackoff(6))

        // Attempt 7: 30000 + (7*37)%251 = 30000 + 259%251 = 30008
        assertEquals(30008L, sm.calculateBackoff(7))
    }

    // -----------------------------------------------------------------------
    // 60s open reset rule
    // -----------------------------------------------------------------------

    @Test
    fun streamOpenResetRule_60sResetsAttempt() {
        var currentTime = 1_000_000L
        val sm = DeviceStreamStateMachine(clock = { currentTime })

        // Connect and fail quickly twice
        sm.onConnecting()
        sm.onStreamOpen()
        currentTime += 10_000L // 10s open
        sm.onStreamClosed()
        assertEquals(1, (sm.state as DeviceStreamState.Reconnecting).attempt)

        sm.onConnecting()
        sm.onStreamOpen()
        currentTime += 5_000L // 5s open
        sm.onStreamClosed()
        assertEquals(2, (sm.state as DeviceStreamState.Reconnecting).attempt)

        // Now connect and stay open for >= 60s (e.g. 60_000ms exactly)
        sm.onConnecting()
        sm.onStreamOpen()
        currentTime += 60_000L // 60s open!
        sm.onStreamClosed()

        // Attempt was reset to 0 before incrementing, so the next reconnection is attempt 1
        val recon = sm.state as DeviceStreamState.Reconnecting
        assertEquals(1, recon.attempt)
        assertEquals(1037L, recon.nextDelayMs)
    }

    @Test
    fun streamOpenResetRule_lessThan60sDoesNotReset() {
        var currentTime = 1_000_000L
        val sm = DeviceStreamStateMachine(clock = { currentTime })

        sm.onConnecting()
        sm.onStreamOpen()
        currentTime += 59_999L // 1ms short of 60s
        sm.onStreamClosed()

        // Attempt was NOT reset
        assertEquals(1, (sm.state as DeviceStreamState.Reconnecting).attempt)

        sm.onConnecting()
        sm.onStreamOpen()
        currentTime += 1_000L
        sm.onStreamClosed()
        assertEquals(2, (sm.state as DeviceStreamState.Reconnecting).attempt)
    }
}
