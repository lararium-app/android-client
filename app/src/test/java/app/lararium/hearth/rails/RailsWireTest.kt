package app.lararium.hearth.rails

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for RailsWire wire formats, serializers, and parsers (A1).
 */
class RailsWireTest {

    // -----------------------------------------------------------------------
    // RAIL_NAMES & vocabulary
    // -----------------------------------------------------------------------

    @Test
    fun railNames_exactClosedSet() {
        val expected = setOf("calendar", "contacts", "notifications", "calllog", "health", "dialer", "sms")
        assertEquals(expected, RailsWire.RAIL_NAMES)
        assertEquals(5, RailsWire.READ_RAILS.size)
        assertEquals(2, RailsWire.ACTION_RAILS.size)
        assertTrue(RailsWire.READ_RAILS.containsAll(listOf("calendar", "contacts", "notifications", "calllog", "health")))
        assertTrue(RailsWire.ACTION_RAILS.containsAll(listOf("dialer", "sms")))
    }

    // -----------------------------------------------------------------------
    // buildRegisterBody
    // -----------------------------------------------------------------------

    @Test
    fun buildRegisterBody_happyPathExactShape() {
        val rails = listOf(
            DeclaredRail(name = "calendar", readOnly = true, enabled = true),
            DeclaredRail(name = "dialer", readOnly = false, enabled = true)
        )
        val body = RailsWire.buildRegisterBody(
            deviceId = "phone-dev-1",
            displayName = "Pixel 8 Pro",
            railsDeclared = rails
        )

        assertEquals("phone-dev-1", body.getString("device_id"))
        assertEquals("Pixel 8 Pro", body.getString("display_name"))
        assertFalse("fingerprint must be deliberately absent", body.has("fingerprint"))

        val declaredArray = body.getJSONArray("rails_declared")
        assertEquals(2, declaredArray.length())

        val r0 = declaredArray.getJSONObject(0)
        assertEquals("calendar", r0.getString("name"))
        assertTrue(r0.getBoolean("read_only"))
        assertTrue(r0.getBoolean("enabled"))

        val r1 = declaredArray.getJSONObject(1)
        assertEquals("dialer", r1.getString("name"))
        assertFalse(r1.getBoolean("read_only"))
        assertTrue(r1.getBoolean("enabled"))
    }

    @Test
    fun buildRegisterBody_failsClosedOnReadRailMisflagged() {
        // V-D2: pure-logic unit test asserts client-side registration builder fails closed
        val readRails = listOf("calendar", "contacts", "notifications", "calllog", "health")
        for (railName in readRails) {
            try {
                RailsWire.buildRegisterBody(
                    deviceId = "device-1",
                    displayName = "Test Device",
                    railsDeclared = listOf(DeclaredRail(name = railName, readOnly = false, enabled = true))
                )
                fail("Expected RailsWireException for read rail '$railName' configured with readOnly = false")
            } catch (e: RailsWireException) {
                assertTrue(e.message?.contains(railName) == true)
            }
        }
    }

    @Test
    fun buildRegisterBody_failsClosedOnActionRailMisflagged() {
        // V-D2: pure-logic unit test asserts client-side registration builder fails closed
        val actionRails = listOf("dialer", "sms")
        for (railName in actionRails) {
            try {
                RailsWire.buildRegisterBody(
                    deviceId = "device-1",
                    displayName = "Test Device",
                    railsDeclared = listOf(DeclaredRail(name = railName, readOnly = true, enabled = true))
                )
                fail("Expected RailsWireException for action rail '$railName' configured with readOnly = true")
            } catch (e: RailsWireException) {
                assertTrue(e.message?.contains(railName) == true)
            }
        }
    }

    @Test
    fun buildRegisterBody_blankIdentifiersThrow() {
        try {
            RailsWire.buildRegisterBody("", "Test Device", emptyList())
            fail("Expected exception for blank deviceId")
        } catch (e: RailsWireException) {
            // Success
        }

        try {
            RailsWire.buildRegisterBody("device-1", "   ", emptyList())
            fail("Expected exception for blank displayName")
        } catch (e: RailsWireException) {
            // Success
        }
    }

    // -----------------------------------------------------------------------
    // parseActionFrame
    // -----------------------------------------------------------------------

    @Test
    fun parseActionFrame_happyPath() {
        val args = JSONObject().apply {
            put("phone_number", "+15551234567")
            put("message", "Test text")
        }
        val data = JSONObject().apply {
            put("action_id", "act-abc-123")
            put("tool", "device.sms.send")
            put("args", args)
        }

        val action = RailsWire.parseActionFrame("action_dispatch", data)
        assertNotNull(action)
        assertEquals("act-abc-123", action!!.actionId)
        assertEquals("device.sms.send", action.tool)
        assertEquals("+15551234567", action.args.getString("phone_number"))
        assertEquals("Test text", action.args.getString("message"))
    }

    @Test
    fun parseActionFrame_unknownFrameTypeReturnsNull() {
        val data = JSONObject().apply {
            put("action_id", "act-1")
            put("tool", "device.dialer.call")
            put("args", JSONObject())
        }

        assertNull(RailsWire.parseActionFrame("ping", data))
        assertNull(RailsWire.parseActionFrame("card_review", data))
        assertNull(RailsWire.parseActionFrame("unknown_type", data))
    }

    @Test
    fun parseActionFrame_malformedPayloadsThrow() {
        // Missing action_id
        val noId = JSONObject().apply {
            put("tool", "device.sms.send")
            put("args", JSONObject())
        }
        try {
            RailsWire.parseActionFrame("action_dispatch", noId)
            fail("Expected exception for missing action_id")
        } catch (e: RailsWireException) {
            assertTrue(e.message!!.contains("action_id"))
        }

        // Blank action_id
        val blankId = JSONObject().apply {
            put("action_id", "   ")
            put("tool", "device.sms.send")
            put("args", JSONObject())
        }
        try {
            RailsWire.parseActionFrame("action_dispatch", blankId)
            fail("Expected exception for blank action_id")
        } catch (e: RailsWireException) {
            assertTrue(e.message!!.contains("action_id"))
        }

        // Missing tool
        val noTool = JSONObject().apply {
            put("action_id", "act-1")
            put("args", JSONObject())
        }
        try {
            RailsWire.parseActionFrame("action_dispatch", noTool)
            fail("Expected exception for missing tool")
        } catch (e: RailsWireException) {
            assertTrue(e.message!!.contains("tool"))
        }

        // Invalid tool format (not matching device.[a-z.]+)
        val badTools = listOf("dialer", "device.", "mcp__dialer", "DEVICE.DIALER", "device.dialer.123", "tool")
        for (badTool in badTools) {
            val badToolJson = JSONObject().apply {
                put("action_id", "act-1")
                put("tool", badTool)
                put("args", JSONObject())
            }
            try {
                RailsWire.parseActionFrame("action_dispatch", badToolJson)
                fail("Expected exception for invalid tool: $badTool")
            } catch (e: RailsWireException) {
                assertTrue(e.message!!.contains("tool"))
            }
        }

        // Missing args
        val noArgs = JSONObject().apply {
            put("action_id", "act-1")
            put("tool", "device.sms.send")
        }
        try {
            RailsWire.parseActionFrame("action_dispatch", noArgs)
            fail("Expected exception for missing args")
        } catch (e: RailsWireException) {
            assertTrue(e.message!!.contains("args"))
        }

        // args is not a JSONObject (e.g., string or array)
        val stringArgs = JSONObject().apply {
            put("action_id", "act-1")
            put("tool", "device.sms.send")
            put("args", "invalid_args_string")
        }
        try {
            RailsWire.parseActionFrame("action_dispatch", stringArgs)
            fail("Expected exception for non-object args")
        } catch (e: RailsWireException) {
            assertTrue(e.message!!.contains("args"))
        }
    }

    // -----------------------------------------------------------------------
    // parseStreamEvent
    // -----------------------------------------------------------------------

    @Test
    fun parseStreamEvent_pairRequiredViaRawEventOrData() {
        val event1 = RailsWire.parseStreamEvent("pair_required", JSONObject())
        assertEquals(StreamEvent.PairRequired, event1)

        val dataWithEvent = JSONObject().apply { put("event", "pair_required") }
        val event2 = RailsWire.parseStreamEvent(null, dataWithEvent)
        assertEquals(StreamEvent.PairRequired, event2)

        val dataWithType = JSONObject().apply { put("type", "pair_required") }
        val event3 = RailsWire.parseStreamEvent(null, dataWithType)
        assertEquals(StreamEvent.PairRequired, event3)
    }

    @Test
    fun parseStreamEvent_actionDispatchHappyPath() {
        val data = JSONObject().apply {
            put("action_id", "act-42")
            put("tool", "device.dialer.call")
            put("args", JSONObject().apply { put("phone", "555-0100") })
        }

        val event = RailsWire.parseStreamEvent("action_dispatch", data)
        assertTrue(event is StreamEvent.ActionDispatch)
        val dispatch = (event as StreamEvent.ActionDispatch).action
        assertEquals("act-42", dispatch.actionId)
        assertEquals("device.dialer.call", dispatch.tool)
        assertEquals("555-0100", dispatch.args.getString("phone"))
    }

    @Test
    fun parseStreamEvent_unknownEvent() {
        val unknown1 = RailsWire.parseStreamEvent("keepalive", JSONObject())
        assertEquals(StreamEvent.Unknown, unknown1)

        val unknown2 = RailsWire.parseStreamEvent(null, JSONObject().apply { put("foo", "bar") })
        assertEquals(StreamEvent.Unknown, unknown2)
    }

    // -----------------------------------------------------------------------
    // buildSyncEnvelope
    // -----------------------------------------------------------------------

    @Test
    fun buildSyncEnvelope_allVariants() {
        val snapshot = JSONArray().apply { put(JSONObject().apply { put("id", "c1") }) }
        val deltas = JSONArray().apply { put(JSONObject().apply { put("op", "add") }) }

        // Both present
        val full = RailsWire.buildSyncEnvelope("calendar", 100L, snapshot, deltas)
        assertEquals("calendar", full.getString("rail"))
        assertEquals(100L, full.getLong("seq"))
        assertEquals(1, full.getJSONArray("snapshot").length())
        assertEquals(1, full.getJSONArray("deltas").length())

        // Snapshot only
        val snapOnly = RailsWire.buildSyncEnvelope("calendar", 101L, snapshot, null)
        assertTrue(snapOnly.has("snapshot"))
        assertFalse(snapOnly.has("deltas"))

        // Deltas only
        val deltasOnly = RailsWire.buildSyncEnvelope("calendar", 102L, null, deltas)
        assertFalse(deltasOnly.has("snapshot"))
        assertTrue(deltasOnly.has("deltas"))

        // Blank rail throws
        try {
            RailsWire.buildSyncEnvelope("  ", 1L, null, null)
            fail("Expected exception for blank rail")
        } catch (e: RailsWireException) {
            // Success
        }
    }

    // -----------------------------------------------------------------------
    // buildReceiptBody
    // -----------------------------------------------------------------------

    @Test
    fun buildReceiptBody_okAndErrorShapes() {
        // Success with result
        val okBody = RailsWire.buildReceiptBody("act-1", ok = true, result = "sms_sent", error = null)
        assertTrue(okBody.getBoolean("ok"))
        assertEquals("sms_sent", okBody.getString("result"))
        assertFalse(okBody.has("error"))

        // Failure with error
        val errBody = RailsWire.buildReceiptBody("act-2", ok = false, result = null, error = "denied_local")
        assertFalse(errBody.getBoolean("ok"))
        assertEquals("denied_local", errBody.getString("error"))
        assertFalse(errBody.has("result"))

        // Blank actionId throws
        try {
            RailsWire.buildReceiptBody("", ok = true, result = null, error = null)
            fail("Expected exception for blank actionId")
        } catch (e: RailsWireException) {
            // Success
        }
    }
}
