package app.lararium.assistant

import app.lararium.assistant.AssistantRolePolicy.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantRolePolicyTest {

    @Test
    fun notHeld_showsRequestRow() {
        assertEquals(Row.RequestRole, AssistantRolePolicy.rowFor(sdkInt = 29, roleAvailable = true, roleHeld = false))
        assertEquals(Row.RequestRole, AssistantRolePolicy.rowFor(sdkInt = 36, roleAvailable = true, roleHeld = false))
    }

    @Test
    fun held_showsHeldRow() {
        assertEquals(Row.Held, AssistantRolePolicy.rowFor(sdkInt = 34, roleAvailable = true, roleHeld = true))
    }

    @Test
    fun revoked_revertsToRequestRow() {
        // Same device, role taken away: UI goes back to the request row.
        assertEquals(Row.Held, AssistantRolePolicy.rowFor(33, true, true))
        assertEquals(Row.RequestRole, AssistantRolePolicy.rowFor(33, true, false))
    }

    @Test
    fun api26to28_alwaysFallback() {
        for (sdk in 26..28) {
            assertEquals(Row.Fallback, AssistantRolePolicy.rowFor(sdk, roleAvailable = true, roleHeld = false))
            // Even a (nonsensical) "held" never reaches the role UI below API 29.
            assertEquals(Row.Fallback, AssistantRolePolicy.rowFor(sdk, roleAvailable = true, roleHeld = true))
            assertFalse(AssistantRolePolicy.canRequestRole(sdk, true))
        }
    }

    @Test
    fun roleUnavailable_fallsBackEvenOnModernApi() {
        assertEquals(Row.Fallback, AssistantRolePolicy.rowFor(34, roleAvailable = false, roleHeld = false))
        assertFalse(AssistantRolePolicy.canRequestRole(34, false))
    }

    @Test
    fun boundaryIsApi29() {
        assertFalse(AssistantRolePolicy.canRequestRole(28, true))
        assertTrue(AssistantRolePolicy.canRequestRole(29, true))
    }
}
