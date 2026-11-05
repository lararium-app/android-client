package app.lararium.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for [AuthGateLogic] (R3, R7).
 * Tests fake Authenticator integration, success runs block once, deny never runs block,
 * and destructive always authenticates even when unlocked.
 * ZERO Android dependencies.
 */
class AuthGateLogicTest {

    private class FakeAuthenticator(
        var shouldSucceed: Boolean = true
    ) : Authenticator {
        var callCount = 0
        var lastTitle: String? = null
        var lastText: String? = null

        override fun authenticate(
            activity: Any?,
            reasonTitle: String,
            reasonText: String,
            onResult: (Boolean) -> Unit
        ) {
            callCount++
            lastTitle = reasonTitle
            lastText = reasonText
            onResult(shouldSucceed)
        }
    }

    @Test
    fun successRunsBlockOnce() = runTest {
        val policy = AppLockPolicy() // starts locked
        val fakeAuth = FakeAuthenticator(shouldSucceed = true)
        val gate = AuthGateLogic(policy, fakeAuth)

        var blockExecutions = 0
        val result = gate.execute(
            activity = null,
            reasonTitle = "Test Action",
            reasonText = "Please confirm",
            destructive = false
        ) {
            blockExecutions++
        }

        assertTrue("Gate must report success when authenticator succeeds", result)
        assertEquals("Authenticator must have been invoked once", 1, fakeAuth.callCount)
        assertEquals("Block must be executed exactly once on success", 1, blockExecutions)
        assertTrue("Successful auth must unlock the policy", policy.isUnlocked)
    }

    @Test
    fun denyNeverRunsBlock() = runTest {
        val policy = AppLockPolicy() // starts locked
        val fakeAuth = FakeAuthenticator(shouldSucceed = false)
        val gate = AuthGateLogic(policy, fakeAuth)

        var blockExecutions = 0
        val result = gate.execute(
            activity = null,
            reasonTitle = "Denied Action",
            reasonText = "Please confirm",
            destructive = false
        ) {
            blockExecutions++
        }

        assertFalse("Gate must report failure when authenticator denies", result)
        assertEquals("Authenticator must have been invoked once", 1, fakeAuth.callCount)
        assertEquals("Block must NEVER be executed when auth is denied", 0, blockExecutions)
        assertFalse("Policy must remain locked after failed auth", policy.isUnlocked)
    }

    @Test
    fun destructiveAlwaysAuthenticatesEvenWhenUnlocked() = runTest {
        val policy = AppLockPolicy()
        policy.unlock() // Already unlocked session!
        assertTrue(policy.isUnlocked)

        val fakeAuth = FakeAuthenticator(shouldSucceed = true)
        val gate = AuthGateLogic(policy, fakeAuth)

        var blockExecutions = 0
        val result = gate.execute(
            activity = null,
            reasonTitle = "Delete Connector",
            reasonText = "Destructive verb",
            destructive = true
        ) {
            blockExecutions++
        }

        assertTrue(result)
        assertEquals("Destructive action must ALWAYS trigger authenticator even when unlocked", 1, fakeAuth.callCount)
        assertEquals(1, blockExecutions)
    }

    @Test
    fun nonDestructiveWhenUnlockedDoesNotPromptAuthenticator() = runTest {
        val policy = AppLockPolicy()
        policy.unlock() // Unlocked
        assertTrue(policy.isUnlocked)

        val fakeAuth = FakeAuthenticator(shouldSucceed = true)
        val gate = AuthGateLogic(policy, fakeAuth)

        var blockExecutions = 0
        val result = gate.execute(
            activity = null,
            reasonTitle = "Save Safe Action",
            destructive = false
        ) {
            blockExecutions++
        }

        assertTrue(result)
        assertEquals("Non-destructive action when unlocked and not backgrounded must NOT prompt authenticator", 0, fakeAuth.callCount)
        assertEquals(1, blockExecutions)
    }

    @Test
    fun backgroundedRequiresReauthEvenIfUnlockedAndNonDestructive() = runTest {
        val policy = AppLockPolicy()
        policy.unlock()
        policy.onBackground(1_000L) // Backgrounded for 5s (< 60s, remains unlocked)
        policy.onForeground(6_000L)
        assertTrue(policy.isUnlocked)
        assertTrue(policy.hasBeenBackgrounded)

        val fakeAuth = FakeAuthenticator(shouldSucceed = true)
        val gate = AuthGateLogic(policy, fakeAuth)

        var blockExecutions = 0
        val result = gate.execute(
            activity = null,
            reasonTitle = "Action After Background",
            destructive = false
        ) {
            blockExecutions++
        }

        assertTrue(result)
        assertEquals("Action after backgrounding must require fresh authentication", 1, fakeAuth.callCount)
        assertEquals(1, blockExecutions)
        assertFalse("Successful auth must clear hasBeenBackgrounded", policy.hasBeenBackgrounded)
    }
}
