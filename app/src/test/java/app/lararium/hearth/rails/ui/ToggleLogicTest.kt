package app.lararium.hearth.rails.ui

import app.lararium.hearth.rails.HEALTH_CONNECT
import app.lararium.hearth.rails.NOTIFICATION_ACCESS
import app.lararium.hearth.rails.PERM_CALL_PHONE
import app.lararium.hearth.rails.PERM_READ_CALENDAR
import app.lararium.hearth.rails.PERM_READ_CALL_LOG
import app.lararium.hearth.rails.PERM_READ_CONTACTS
import app.lararium.hearth.rails.PERM_SEND_SMS
import app.lararium.hearth.rails.PERM_WRITE_CALENDAR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToggleLogicTest {

    @Test
    fun testGrantSetSwitchEnablement() {
        val granted = setOf(PERM_CALL_PHONE)

        // When permissions are granted and user has it enabled in store -> switch is checked
        val rowEnabled = ToggleLogic.buildRowDisplay(
            rail = "dialer",
            storeEnabled = true,
            granted = granted
        )
        assertTrue(rowEnabled.isChecked)
        assertFalse(rowEnabled.isDegraded)
        assertNull(rowEnabled.hint)

        // When permissions are missing -> switch is NOT checked even if store is true
        val rowMissing = ToggleLogic.buildRowDisplay(
            rail = "dialer",
            storeEnabled = true,
            granted = emptySet()
        )
        assertFalse("Switch must not be checked before grants", rowMissing.isChecked)
        assertTrue("Revoked permissions cause degraded state", rowMissing.isDegraded)
        assertNotNull(rowMissing.hint)
    }

    @Test
    fun testDegradedRows() {
        // Calendar requires READ_CALENDAR and WRITE_CALENDAR
        val partialGrants = setOf(PERM_READ_CALENDAR)

        // User had enabled it, but WRITE_CALENDAR is missing -> degraded = true
        val degradedRow = ToggleLogic.buildRowDisplay(
            rail = "calendar",
            storeEnabled = true,
            granted = partialGrants
        )
        assertFalse(degradedRow.isChecked)
        assertTrue(degradedRow.isDegraded)
        assertTrue(degradedRow.hint?.contains("WRITE_CALENDAR") == true)

        // User had it off in store -> degraded = false, isChecked = false
        val offRow = ToggleLogic.buildRowDisplay(
            rail = "calendar",
            storeEnabled = false,
            granted = partialGrants
        )
        assertFalse(offRow.isChecked)
        assertFalse(offRow.isDegraded)
        assertNotNull(offRow.hint)
    }

    @Test
    fun testNotificationAccessSentinelPath() {
        // Without notification access -> OpenNotificationAccessSettings action
        val planMissing = ToggleLogic.planToggleOn(
            rail = "notifications",
            granted = emptySet(),
            accessState = emptyMap()
        )
        assertEquals(ToggleLogic.TogglePlanAction.OpenNotificationAccessSettings, planMissing)

        // With notification access in accessState -> ReadyToEnable
        val planGranted = ToggleLogic.planToggleOn(
            rail = "notifications",
            granted = emptySet(),
            accessState = mapOf(NOTIFICATION_ACCESS to true)
        )
        assertEquals(ToggleLogic.TogglePlanAction.ReadyToEnable, planGranted)

        // In granted set directly -> ReadyToEnable
        val planGrantedInSet = ToggleLogic.planToggleOn(
            rail = "notifications",
            granted = setOf(NOTIFICATION_ACCESS),
            accessState = emptyMap()
        )
        assertEquals(ToggleLogic.TogglePlanAction.ReadyToEnable, planGrantedInSet)
    }

    @Test
    fun testHealthPlaceholderPath() {
        // Without Health Connect -> ShowHealthConnectPlaceholder
        val planMissing = ToggleLogic.planToggleOn(
            rail = "health",
            granted = emptySet(),
            accessState = emptyMap()
        )
        assertEquals(ToggleLogic.TogglePlanAction.ShowHealthConnectPlaceholder, planMissing)

        // With Health Connect granted -> ReadyToEnable
        val planGranted = ToggleLogic.planToggleOn(
            rail = "health",
            granted = setOf(HEALTH_CONNECT),
            accessState = emptyMap()
        )
        assertEquals(ToggleLogic.TogglePlanAction.ReadyToEnable, planGranted)
    }

    @Test
    fun testRuntimePermissionPlans() {
        // Contacts
        val contactsPlan = ToggleLogic.planToggleOn("contacts", emptySet())
        assertTrue(contactsPlan is ToggleLogic.TogglePlanAction.RequestRuntimePermissions)
        assertEquals(
            setOf(PERM_READ_CONTACTS),
            (contactsPlan as ToggleLogic.TogglePlanAction.RequestRuntimePermissions).permissions
        )

        // Call log
        val callLogPlan = ToggleLogic.planToggleOn("calllog", emptySet())
        assertTrue(callLogPlan is ToggleLogic.TogglePlanAction.RequestRuntimePermissions)
        assertEquals(
            setOf(PERM_READ_CALL_LOG),
            (callLogPlan as ToggleLogic.TogglePlanAction.RequestRuntimePermissions).permissions
        )

        // SMS (only PERM_SEND_SMS)
        val smsPlan = ToggleLogic.planToggleOn("sms", emptySet())
        assertTrue(smsPlan is ToggleLogic.TogglePlanAction.RequestRuntimePermissions)
        assertEquals(
            setOf(PERM_SEND_SMS),
            (smsPlan as ToggleLogic.TogglePlanAction.RequestRuntimePermissions).permissions
        )
    }

    @Test
    fun testBuildAllRows() {
        val rows = ToggleLogic.buildAllRows(
            storeEnabledMap = mapOf("calendar" to true, "sms" to true),
            granted = setOf(PERM_READ_CALENDAR, PERM_WRITE_CALENDAR, PERM_SEND_SMS)
        )
        assertEquals(7, rows.size)

        val calendarRow = rows.first { it.name == "calendar" }
        assertTrue(calendarRow.isChecked)
        assertTrue(calendarRow.isReadOnly)

        val smsRow = rows.first { it.name == "sms" }
        assertTrue(smsRow.isChecked)
        assertFalse(smsRow.isReadOnly)

        val contactsRow = rows.first { it.name == "contacts" }
        assertFalse(contactsRow.isChecked)
        assertFalse(contactsRow.isDegraded)
    }
}
