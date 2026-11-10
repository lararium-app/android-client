package app.lararium.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BalLaunchPolicyTest {

    @Test
    fun pre34_usesDirectStart() {
        assertFalse(BalLaunch.usesPendingIntentPath(26))
        assertFalse(BalLaunch.usesPendingIntentPath(33))
    }

    @Test
    fun api34Plus_usesPendingIntentOptIn() {
        assertTrue(BalLaunch.usesPendingIntentPath(34))
        assertTrue(BalLaunch.usesPendingIntentPath(35))
        assertTrue(BalLaunch.usesPendingIntentPath(36))
    }
}
