package app.lararium.hearth.rails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for RailPermissions mapping, togglePlan, and uiState (A4).
 */
class RailPermissionsTest {

    @Test
    fun requiredPermissions_tableMatchesSpecA4() {
        // calendar -> READ_CALENDAR + WRITE_CALENDAR
        assertEquals(
            setOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
            requiredPermissions("calendar")
        )

        // contacts -> READ_CONTACTS
        assertEquals(
            setOf("android.permission.READ_CONTACTS"),
            requiredPermissions("contacts")
        )

        // notifications -> emptySet (access state comes via listener sentinel)
        assertEquals(emptySet<String>(), requiredPermissions("notifications"))
        assertEquals("notification_listener_enabled", NOTIFICATION_ACCESS)

        // calllog -> READ_CALL_LOG
        assertEquals(
            setOf("android.permission.READ_CALL_LOG"),
            requiredPermissions("calllog")
        )

        // health -> emptySet + sentinel
        assertEquals(emptySet<String>(), requiredPermissions("health"))
        assertEquals("health_connect", HEALTH_CONNECT)

        // dialer -> CALL_PHONE
        assertEquals(
            setOf("android.permission.CALL_PHONE"),
            requiredPermissions("dialer")
        )

        // sms -> SEND_SMS ONLY — READ_SMS is strictly banned by frozen spec
        val smsPerms = requiredPermissions("sms")
        assertEquals(setOf("android.permission.SEND_SMS"), smsPerms)
        assertFalse("READ_SMS is strictly banned by frozen spec", smsPerms.contains("android.permission.READ_SMS"))

        // Unknown rail -> emptySet
        assertEquals(emptySet<String>(), requiredPermissions("unknown_rail"))
    }

    @Test
    fun togglePlan_permissionsAndSentinels() {
        // Calendar: none granted -> request both
        assertEquals(
            setOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
            togglePlan("calendar", emptySet())
        )

        // Calendar: READ granted -> request WRITE
        assertEquals(
            setOf("android.permission.WRITE_CALENDAR"),
            togglePlan("calendar", setOf("android.permission.READ_CALENDAR"))
        )

        // Calendar: both granted -> emptySet (no prompt)
        assertEquals(
            emptySet<String>(),
            togglePlan("calendar", setOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"))
        )

        // Notifications: sentinel absent -> prompt for sentinel
        assertEquals(
            setOf(NOTIFICATION_ACCESS),
            togglePlan("notifications", emptySet())
        )

        // Notifications: sentinel granted -> emptySet
        assertEquals(
            emptySet<String>(),
            togglePlan("notifications", setOf(NOTIFICATION_ACCESS))
        )

        // Health: sentinel absent -> prompt for sentinel
        assertEquals(
            setOf(HEALTH_CONNECT),
            togglePlan("health", emptySet())
        )

        // Health: sentinel granted -> emptySet
        assertEquals(
            emptySet<String>(),
            togglePlan("health", setOf(HEALTH_CONNECT))
        )

        // Unknown rail -> emptySet
        assertEquals(emptySet<String>(), togglePlan("unknown", emptySet()))
    }

    @Test
    fun uiState_healthyEnabledRail() {
        val state = uiState(
            rail = "calendar",
            enabled = true,
            granted = setOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR")
        )
        assertEquals("calendar", state.name)
        assertTrue(state.enabled)
        assertFalse(state.degraded)
        assertTrue(state.missingPermissions.isEmpty())
    }

    @Test
    fun uiState_revokedPermissionDegradesAndNeverEnabled() {
        // User wants rail enabled, but permission was revoked / missing
        val state = uiState(
            rail = "calendar",
            enabled = true,
            granted = setOf("android.permission.READ_CALENDAR") // missing WRITE_CALENDAR
        )
        assertEquals("calendar", state.name)
        assertFalse("Must never be enabled when missing permissions", state.enabled)
        assertTrue("Must be degraded when missing permissions and enabled=true", state.degraded)
        assertEquals(setOf("android.permission.WRITE_CALENDAR"), state.missingPermissions)
    }

    @Test
    fun uiState_disabledRailNotDegraded() {
        // Rail is toggled off; missing permissions don't degrade an intentionally disabled rail
        val state = uiState(
            rail = "contacts",
            enabled = false,
            granted = emptySet()
        )
        assertFalse(state.enabled)
        assertFalse(state.degraded)
        assertEquals(setOf("android.permission.READ_CONTACTS"), state.missingPermissions)
    }

    @Test
    fun uiState_sentinelAccessHandling() {
        // Notifications missing access
        val notifNoAccess = uiState("notifications", enabled = true, granted = emptySet(), accessState = emptyMap())
        assertFalse(notifNoAccess.enabled)
        assertTrue(notifNoAccess.degraded)
        assertEquals(setOf(NOTIFICATION_ACCESS), notifNoAccess.missingPermissions)

        // Notifications with access via map
        val notifWithAccess = uiState("notifications", enabled = true, granted = emptySet(), accessState = mapOf(NOTIFICATION_ACCESS to true))
        assertTrue(notifWithAccess.enabled)
        assertFalse(notifWithAccess.degraded)
        assertTrue(notifWithAccess.missingPermissions.isEmpty())

        // Health Connect missing access
        val healthNoAccess = uiState("health", enabled = true, granted = emptySet(), accessState = emptyMap())
        assertFalse(healthNoAccess.enabled)
        assertTrue(healthNoAccess.degraded)
        assertEquals(setOf(HEALTH_CONNECT), healthNoAccess.missingPermissions)

        // Health Connect with access via granted set
        val healthWithAccess = uiState("health", enabled = true, granted = setOf(HEALTH_CONNECT), accessState = emptyMap())
        assertTrue(healthWithAccess.enabled)
        assertFalse(healthWithAccess.degraded)
    }

    @Test
    fun uiState_unknownRailsAreInert() {
        val unknown = uiState("unknown_rail", enabled = true, granted = emptySet())
        assertEquals("unknown_rail", unknown.name)
        assertFalse("Unknown rails can never be enabled", unknown.enabled)
        assertFalse("Unknown rails can never be degraded", unknown.degraded)
        assertTrue(unknown.missingPermissions.isEmpty())
    }
}
