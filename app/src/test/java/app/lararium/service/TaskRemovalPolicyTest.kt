package app.lararium.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for [TaskRemovalPolicy] (issue #124).
 * Tests reschedule decisions based on hearth endpoint configuration and deliberate unpair timing.
 * ZERO Android dependencies.
 */
class TaskRemovalPolicyTest {

    @Test
    fun configuredTrue_reschedules() {
        val policy = TaskRemovalPolicy()
        assertTrue("Configured hearth endpoint must trigger reschedule", policy.shouldReschedule(true))
        assertTrue("Configured hearth endpoint with null unpair timestamp must trigger reschedule", policy.shouldReschedule(true, null))
    }

    @Test
    fun configuredFalse_neverReschedules() {
        val policy = TaskRemovalPolicy()
        assertFalse("Unconfigured hearth must never trigger reschedule", policy.shouldReschedule(false))
        assertFalse("Unconfigured hearth with null unpair timestamp must never trigger reschedule", policy.shouldReschedule(false, null))
        assertFalse("Unconfigured hearth with old unpair timestamp must never trigger reschedule", policy.shouldReschedule(false, 1000L))
    }

    @Test
    fun suppressionVariant_justUnpaired_isSuppressed() {
        val nowMs = 100_000L
        val policy = TaskRemovalPolicy(nowMs = nowMs)

        // 30 seconds ago (< 60_000 ms)
        val recentlyUnpairedMs = nowMs - 30_000L
        assertFalse(
            "Recent deliberate unpair must suppress resurrection",
            policy.shouldReschedule(hearthConfigured = true, deliberatelyUnpairedAt = recentlyUnpairedMs)
        )

        // Exactly at boundary: elapsed == 60_000 ms (not strictly > 60_000 ms)
        val boundaryUnpairedMs = nowMs - TaskRemovalPolicy.RESURRECT_SUPPRESSION_MS
        assertFalse(
            "Deliberate unpair at exact boundary (60s) must suppress resurrection",
            policy.shouldReschedule(hearthConfigured = true, deliberatelyUnpairedAt = boundaryUnpairedMs)
        )
    }

    @Test
    fun suppressionVariant_olderUnpaired_allowed() {
        val nowMs = 100_000L
        val policy = TaskRemovalPolicy(nowMs = nowMs)

        // 60.001 seconds ago (> 60_000 ms)
        val olderUnpairedMs = nowMs - 60_001L
        assertTrue(
            "Unpair older than suppression window allows resurrection",
            policy.shouldReschedule(hearthConfigured = true, deliberatelyUnpairedAt = olderUnpairedMs)
        )
    }

    @Test
    fun constants_matchSpecification() {
        assertEquals(3_000L, TaskRemovalPolicy.RESCHEDULE_DELAY_MS)
        assertEquals(60_000L, TaskRemovalPolicy.RESURRECT_SUPPRESSION_MS)
    }
}
