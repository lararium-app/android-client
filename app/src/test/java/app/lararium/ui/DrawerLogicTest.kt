package app.lararium.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals("New chat", parsed[1].title)
    }

    @Test
    fun resolveSessionTitle_headerTitleWhenPresent() {
        assertEquals("Custom Title", DrawerLogic.resolveSessionTitle("Custom Title"))
        assertEquals("Custom Title", DrawerLogic.resolveSessionTitle("  Custom Title  "))
    }

    @Test
    fun resolveSessionTitle_fallbackToFirstUserMessageSnippet() {
        val events = JSONArray().apply {
            put(JSONObject().apply {
                put("t", "tool_call"); put("role", "assistant")
            })
            put(JSONObject().apply {
                put("t", "msg"); put("role", "user"); put("text", "What is the capital of France?")
            })
            put(JSONObject().apply {
                put("t", "msg"); put("role", "assistant"); put("text", "Paris")
            })
        }
        assertEquals("What is the capital of France?", DrawerLogic.resolveSessionTitle(null, events))
        assertEquals("What is the capital of France?", DrawerLogic.resolveSessionTitle("", events))
    }

    @Test
    fun resolveSessionTitle_fallbackToNewChatWhenNoUserMessageOrEvents() {
        val eventsWithoutUser = JSONArray().apply {
            put(JSONObject().apply {
                put("t", "msg"); put("role", "assistant"); put("text", "Hello")
            })
        }
        assertEquals("New chat", DrawerLogic.resolveSessionTitle(null, eventsWithoutUser))
        assertEquals("New chat", DrawerLogic.resolveSessionTitle(null, null))
        assertEquals("New chat", DrawerLogic.resolveSessionTitle("", JSONArray()))
    }

    @Test
    fun parseSessionMessages_parsesSeqWhenPresent() {
        val eventsJson = JSONObject().apply {
            put("events", JSONArray().apply {
                put(JSONObject().apply {
                    put("t", "msg"); put("role", "user"); put("text", "Hi"); put("seq", 1)
                })
                put(JSONObject().apply {
                    put("t", "msg"); put("role", "assistant"); put("text", "Hello"); put("seq", 2)
                })
            })
        }.toString()

        val messages = DrawerLogic.parseSessionMessages(eventsJson)
        assertEquals(2, messages.size)
        assertEquals(1, messages[0].seq)
        assertEquals(2, messages[1].seq)
    }

    @Test
    fun mergeMessages_dedupesBySeqAndReplacesOptimistic() {
        val current = listOf(
            app.lararium.llm.ChatSession.Message(role = "user", content = "Hello", seq = null),
            app.lararium.llm.ChatSession.Message(role = "assistant", content = "Hi there!", seq = null)
        )
        val incoming = listOf(
            app.lararium.llm.ChatSession.Message(role = "user", content = "Hello", seq = 1),
            app.lararium.llm.ChatSession.Message(role = "assistant", content = "Hi there!", seq = 2)
        )

        val merged = DrawerLogic.mergeMessages(current, incoming)
        assertEquals(2, merged.size)
        assertEquals(1, merged[0].seq)
        assertEquals("Hello", merged[0].content)
        assertEquals(2, merged[1].seq)
        assertEquals("Hi there!", merged[1].content)
    }

    @Test
    fun mergeMessages_preservesUnpersistedOptimisticUserMessage() {
        val current = listOf(
            app.lararium.llm.ChatSession.Message(role = "user", content = "Hello", seq = 1),
            app.lararium.llm.ChatSession.Message(role = "assistant", content = "Hi", seq = 2),
            app.lararium.llm.ChatSession.Message(role = "user", content = "Pending question", seq = null)
        )
        val incoming = listOf(
            app.lararium.llm.ChatSession.Message(role = "user", content = "Hello", seq = 1),
            app.lararium.llm.ChatSession.Message(role = "assistant", content = "Hi", seq = 2)
        )

        val merged = DrawerLogic.mergeMessages(current, incoming)
        assertEquals(3, merged.size)
        assertEquals(1, merged[0].seq)
        assertEquals(2, merged[1].seq)
        assertNull(merged[2].seq)
        assertEquals("Pending question", merged[2].content)
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

    @Test
    fun parseSessionMessages_withTs_setsEpochMillisTimestamp() {
        val json = """{"events":[
            {"seq":1,"t":"msg","role":"user","text":"hi","ts":"2026-10-15T14:05:00Z"},
            {"seq":2,"t":"msg","role":"assistant","text":"yo","ts":"2026-10-15T14:05:01.123456789Z"}
        ]}"""
        val msgs = DrawerLogic.parseSessionMessages(json)
        assertEquals(1_792_073_100_000L, msgs[0].timestamp)
        assertEquals(1_792_073_101_123L, msgs[1].timestamp)
    }

    @Test
    fun parseSessionMessages_absentOrMalformedTs_yieldsZero() {
        val json = """{"events":[
            {"seq":1,"t":"msg","role":"user","text":"a"},
            {"seq":2,"t":"msg","role":"user","text":"b","ts":"not-a-date"},
            {"seq":3,"t":"msg","role":"user","text":"c","ts":""}
        ]}"""
        val msgs = DrawerLogic.parseSessionMessages(json)
        assertEquals(3, msgs.size)
        msgs.forEach { assertEquals(0L, it.timestamp) }
    }

    @Test
    fun parseSessionReplay_inFlightTrueAndAbsentFlag() {
        val inFlightJson = JSONObject().apply {
            put("in_flight", true)
            put("events", JSONArray().apply {
                put(JSONObject().apply {
                    put("t", "msg"); put("role", "user"); put("text", "Still running?"); put("seq", 1)
                })
            })
        }.toString()

        val inFlightReplay = DrawerLogic.parseSessionReplay(inFlightJson)
        assertTrue(inFlightReplay.inFlight)
        assertEquals(1, inFlightReplay.messages.size)
        assertEquals("user", inFlightReplay.messages[0].role)
        assertEquals("Still running?", inFlightReplay.messages[0].content)
        assertEquals(1, inFlightReplay.messages[0].seq)

        val absentFlagJson = JSONObject().apply {
            put("events", JSONArray().apply {
                put(JSONObject().apply {
                    put("t", "msg"); put("role", "user"); put("text", "Hello"); put("seq", 1)
                })
                put(JSONObject().apply {
                    put("t", "msg"); put("role", "assistant"); put("text", "Hi!"); put("seq", 2)
                })
            })
        }.toString()

        val absentFlagReplay = DrawerLogic.parseSessionReplay(absentFlagJson)
        assertFalse(absentFlagReplay.inFlight)
        assertEquals(2, absentFlagReplay.messages.size)
        assertEquals("Hi!", absentFlagReplay.messages[1].content)

        val emptyReplay = DrawerLogic.parseSessionReplay(null)
        assertFalse(emptyReplay.inFlight)
        assertTrue(emptyReplay.messages.isEmpty())
    }
}
