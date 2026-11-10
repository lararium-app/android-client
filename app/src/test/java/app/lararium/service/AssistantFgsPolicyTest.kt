package app.lararium.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantFgsPolicyTest {

    @Test
    fun neitherConfiguredNorActivated_shieldDoesNotStayUp() {
        assertFalse(
            AssistantFgsPolicy.shouldKeepShieldUp(
                roleActivated = false,
                endpointConfigured = false,
                userOptOut = false
            )
        )
    }

    @Test
    fun roleActivatedOnly_shieldStaysUp() {
        assertTrue(
            AssistantFgsPolicy.shouldKeepShieldUp(
                roleActivated = true,
                endpointConfigured = false,
                userOptOut = false
            )
        )
    }

    @Test
    fun endpointConfiguredOnly_shieldStaysUp() {
        assertTrue(
            AssistantFgsPolicy.shouldKeepShieldUp(
                roleActivated = false,
                endpointConfigured = true,
                userOptOut = false
            )
        )
    }

    @Test
    fun bothRoleActivatedAndEndpointConfigured_shieldStaysUp() {
        assertTrue(
            AssistantFgsPolicy.shouldKeepShieldUp(
                roleActivated = true,
                endpointConfigured = true,
                userOptOut = false
            )
        )
    }

    @Test
    fun userOptOut_overridesBothAndDropsShield() {
        assertFalse(
            AssistantFgsPolicy.shouldKeepShieldUp(
                roleActivated = true,
                endpointConfigured = true,
                userOptOut = true
            )
        )
    }

    @Test
    fun userOptOut_overridesRoleOnly() {
        assertFalse(
            AssistantFgsPolicy.shouldKeepShieldUp(
                roleActivated = true,
                endpointConfigured = false,
                userOptOut = true
            )
        )
    }

    @Test
    fun userOptOut_overridesEndpointOnly() {
        assertFalse(
            AssistantFgsPolicy.shouldKeepShieldUp(
                roleActivated = false,
                endpointConfigured = true,
                userOptOut = true
            )
        )
    }
}
