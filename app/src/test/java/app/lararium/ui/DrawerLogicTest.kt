package app.lararium.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerLogicTest {

    @Test
    fun formatConnectionState_unpairedWhenNullOrBlank() {
        assertEquals(DrawerLogic.ConnectionState.Unpaired, DrawerLogic.formatConnectionState(null))
        assertEquals(
            DrawerLogic.ConnectionState.Unpaired,
            DrawerLogic.formatConnectionState(Triple("", "tok", "Hearth"))
        )
    }

    @Test
    fun formatConnectionState_pairedWithHearthName() {
        val state = DrawerLogic.formatConnectionState(Triple("https://hearth.local", "token123", "My Living Room"))
        assertTrue(state is DrawerLogic.ConnectionState.Paired)
        assertEquals("My Living Room", (state as DrawerLogic.ConnectionState.Paired).hearthName)
    }

    @Test
    fun formatConnectionState_pairedWithFallbackNameWhenBlank() {
        val state = DrawerLogic.formatConnectionState(Triple("https://hearth.local", "token123", "   "))
        assertTrue(state is DrawerLogic.ConnectionState.Paired)
        assertEquals("Hearth", (state as DrawerLogic.ConnectionState.Paired).hearthName)
    }

    @Test
    fun formatTopBarTitle_unpairedReturnsLararium() {
        assertEquals("Lararium", DrawerLogic.formatTopBarTitle(null))
        assertEquals("Lararium", DrawerLogic.formatTopBarTitle(Triple("https://hearth.local", "tok", "")))
        assertEquals("Lararium", DrawerLogic.formatTopBarTitle(Triple("https://hearth.local", "tok", "   ")))
    }

    @Test
    fun formatTopBarTitle_pairedReturnsHearthName() {
        assertEquals("Studio Hearth", DrawerLogic.formatTopBarTitle(Triple("https://hearth.local", "tok", "Studio Hearth")))
    }

    @Test
    fun parseSessions_emptyOrMalformedReturnsEmptyList() {
        assertTrue(DrawerLogic.parseSessions(null).isEmpty())
        assertTrue(DrawerLogic.parseSessions("").isEmpty())
        assertTrue(DrawerLogic.parseSessions("not valid json").isEmpty())
        assertTrue(DrawerLogic.parseSessions("{}").isEmpty())
    }

    @Test
    fun parseSessions_parsesValidSessions() {
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "sess-1")
                put("title", "First Discussion")
                put("updated_at", 1700000000L)
            })
            put(JSONObject().apply {
                put("id", "sess-2")
                // Missing title -> fallback
            })
            put(JSONObject().apply {
                // Missing id -> skipped
                put("title", "No ID")
            })
        }.toString()

        val parsed = DrawerLogic.parseSessions(json)
        assertEquals(2, parsed.size)
        assertEquals("sess-1", parsed[0].id)
        assertEquals("First Discussion", parsed[0].title)
        assertEquals(1700000000L, parsed[0].updatedAt)

        assertEquals("sess-2", parsed[1].id)
        assertEquals("Conversation sess-2", parsed[1].title)
    }

    @Test
    fun parseSessionMessages_parsesOnlyUserAndAssistantMsgs() {
        val eventsJson = JSONObject().apply {
            put("events", JSONArray().apply {
                put(JSONObject().apply {
                    put("t", "msg"); put("role", "user"); put("text", "Hello there")
                })
                put(JSONObject().apply {
                    put("t", "tool_call"); put("role", "assistant"); put("name", "search")
                })
                put(JSONObject().apply {
                    put("t", "msg"); put("role", "assistant"); put("text", "General Kenobi!")
                })
            })
        }.toString()

        val messages = DrawerLogic.parseSessionMessages(eventsJson)
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("Hello there", messages[0].content)
        assertEquals("assistant", messages[1].role)
        assertEquals("General Kenobi!", messages[1].content)
    }

    @Test
    fun parseSessionMessages_emptyOrMalformedReturnsEmptyList() {
        assertTrue(DrawerLogic.parseSessionMessages(null).isEmpty())
        assertTrue(DrawerLogic.parseSessionMessages("").isEmpty())
        assertTrue(DrawerLogic.parseSessionMessages("bad json").isEmpty())
        assertTrue(DrawerLogic.parseSessionMessages("{}").isEmpty())
    }
}
