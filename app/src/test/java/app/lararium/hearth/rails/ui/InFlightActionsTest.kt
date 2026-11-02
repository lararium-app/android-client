package app.lararium.hearth.rails.ui

import app.lararium.hearth.rails.DispatchedAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class InFlightActionsTest {

    @Before
    fun setUp() {
        InFlightActions.clear()
    }

    @Test
    fun testPutGetRemove() {
        val action = DispatchedAction(
            actionId = "act-1",
            tool = "device.dialer.call",
            args = JSONObject().apply { put("phone", "555-0100") }
        )

        InFlightActions.put(action)
        assertEquals(1, InFlightActions.size())
        assertEquals(action, InFlightActions.get("act-1"))

        val removed = InFlightActions.remove("act-1")
        assertEquals(action, removed)
        assertEquals(0, InFlightActions.size())
        assertNull(InFlightActions.get("act-1"))
    }

    @Test
    fun testBound100EvictionOrder() {
        // Fill up to capacity (100)
        for (i in 0 until 100) {
            val action = DispatchedAction(
                actionId = "act-$i",
                tool = "device.sms.send",
                args = JSONObject().apply { put("text", "msg $i") }
            )
            InFlightActions.put(action)
        }
        assertEquals(100, InFlightActions.size())
        assertNotNull(InFlightActions.get("act-0"))
        assertNotNull(InFlightActions.get("act-99"))

        // Add 1 more: act-0 (oldest) should be evicted
        val action100 = DispatchedAction(
            actionId = "act-100",
            tool = "device.sms.send",
            args = JSONObject().apply { put("text", "msg 100") }
        )
        InFlightActions.put(action100)

        assertEquals(100, InFlightActions.size())
        assertNull("act-0 should be evicted", InFlightActions.get("act-0"))
        assertNotNull("act-1 should still exist", InFlightActions.get("act-1"))
        assertNotNull("act-100 should exist", InFlightActions.get("act-100"))

        // Add 10 more: act-1 through act-10 should be evicted
        for (i in 101..110) {
            val action = DispatchedAction(
                actionId = "act-$i",
                tool = "device.dialer.call",
                args = JSONObject().apply { put("phone", "555-$i") }
            )
            InFlightActions.put(action)
        }

        assertEquals(100, InFlightActions.size())
        for (i in 0..10) {
            assertNull("act-$i should be evicted", InFlightActions.get("act-$i"))
        }
        for (i in 11..110) {
            assertNotNull("act-$i should still exist", InFlightActions.get("act-$i"))
        }
    }

    @Test
    fun testDuplicateActionIdDoesNotEvict() {
        for (i in 0 until 100) {
            InFlightActions.put(
                DispatchedAction(
                    actionId = "act-$i",
                    tool = "device.sms.send",
                    args = JSONObject()
                )
            )
        }
        assertEquals(100, InFlightActions.size())

        // Re-putting act-0 with updated args should NOT evict act-1
        val updated0 = DispatchedAction(
            actionId = "act-0",
            tool = "device.sms.send",
            args = JSONObject().apply { put("updated", true) }
        )
        InFlightActions.put(updated0)

        assertEquals(100, InFlightActions.size())
        assertNotNull(InFlightActions.get("act-0"))
        assertNotNull(InFlightActions.get("act-1"))
        assertEquals(true, InFlightActions.get("act-0")?.args?.optBoolean("updated"))
    }

    @Test
    fun testRemoveNonExistentReturnsNull() {
        assertNull(InFlightActions.remove("non-existent"))
        assertNull(InFlightActions.get("non-existent"))
    }
}
