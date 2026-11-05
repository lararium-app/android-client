package app.lararium.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for [AppLockPolicy] (R2, R7).
 * Tests start state, unlock transitions, 5-minute background re-lock boundaries, and timer clearing.
 * ZERO Android dependencies.
 */
class AppLockPolicyTest {

    @Test
    fun startsLocked() {
        val policy = AppLockPolicy()
        assertFalse("AppLockPolicy must start in locked state", policy.isUnlocked)
        assertNull("Initial lastBackgroundMs must be null", policy.lastBackgroundMs)
        assertFalse("Initial hasBeenBackgrounded must be false", policy.hasBeenBackgrounded)
    }

    @Test
    fun unlockTransitions() {
        val policy = AppLockPolicy()
        assertFalse(policy.isUnlocked)

        policy.unlock()
        assertTrue("unlock() must transition policy to unlocked", policy.isUnlocked)
        assertNull(policy.lastBackgroundMs)
        assertFalse(policy.hasBeenBackgrounded)

        policy.lock()
        assertFalse("lock() must transition policy to locked", policy.isUnlocked)
        assertNull(policy.lastBackgroundMs)
    }

    @Test
    fun relockBoundary_59_9s_staysUnlocked() {
        val policy = AppLockPolicy(relockTimeoutMs = 60_000L)
        policy.unlock()
        assertTrue(policy.isUnlocked)

        val backgroundTime = 10_000L
        policy.onBackground(backgroundTime)
        assertTrue("App remains unlocked immediately upon entering background", policy.isUnlocked)
        assertEquals(backgroundTime, policy.lastBackgroundMs)
        assertTrue(policy.hasBeenBackgrounded)

        // Return to foreground after 59.9 s (59_900 ms elapsed <= 60_000 ms)
        val foregroundTime = backgroundTime + 59_900L
        policy.onForeground(foregroundTime)

        assertTrue("App must stay unlocked at 59.9s (< 60s boundary)", policy.isUnlocked)
        assertNull("Foreground transition must clear lastBackgroundMs", policy.lastBackgroundMs)
    }

    @Test
    fun relockBoundary_60_1s_relocks() {
        val policy = AppLockPolicy(relockTimeoutMs = 60_000L)
        policy.unlock()
        assertTrue(policy.isUnlocked)

        val backgroundTime = 10_000L
        policy.onBackground(backgroundTime)

        // Return to foreground after 60.1 s (60_100 ms elapsed > 60_000 ms)
        val foregroundTime = backgroundTime + 60_100L
        policy.onForeground(foregroundTime)

        assertFalse("App must re-lock at 60.1s (> 60s boundary)", policy.isUnlocked)
        assertNull("Foreground transition must clear lastBackgroundMs", policy.lastBackgroundMs)
    }

    @Test
    fun foregroundRefreshClearsTimer() {
        val policy = AppLockPolicy(relockTimeoutMs = 60_000L)
        policy.unlock()

        // Background for 30s
        policy.onBackground(nowMs = 10_000L)
        policy.onForeground(nowMs = 40_000L) // 30s elapsed -> stays unlocked
        assertTrue(policy.isUnlocked)
        assertNull(policy.lastBackgroundMs)

        // Background again for 40s (total clock elapsed = 70s, but second session elapsed = 40s <= 60s)
        policy.onBackground(nowMs = 50_000L)
        policy.onForeground(nowMs = 90_000L) // 40s elapsed -> stays unlocked
        assertTrue("Foreground refresh must have reset timer, so 40s in second background does not re-lock", policy.isUnlocked)
        assertNull(policy.lastBackgroundMs)

        // Now background for 61s in a single session
        policy.onBackground(nowMs = 100_000L)
        policy.onForeground(nowMs = 161_000L)
        assertFalse("Single session exceeding 60s re-locks", policy.isUnlocked)
    }

    @Test
    fun onBackgroundWhileLocked_remainsLocked() {
        val policy = AppLockPolicy()
        assertFalse(policy.isUnlocked)

        policy.onBackground(1_000L)
        assertFalse(policy.isUnlocked)
        assertNull("lastBackgroundMs should not be set if already locked", policy.lastBackgroundMs)

        policy.onForeground(100_000L)
        assertFalse(policy.isUnlocked)
    }

    @Test
    fun defaultRelockTimeout_isFiveMinutes() {
        val policy = AppLockPolicy()
        policy.unlock()
        policy.onBackground(0L)
        policy.onForeground(299_900L)
        assertTrue("App stays unlocked at 4:59 backgrounded (default 5 min)", policy.isUnlocked)

        policy.onBackground(300_000L)
        policy.onForeground(600_100L)
        assertFalse("App re-locks past 5 min in background (default)", policy.isUnlocked)
    }
}
