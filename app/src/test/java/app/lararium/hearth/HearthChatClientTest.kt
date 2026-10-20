package app.lararium.hearth

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for HearthChatClient:
 * - Session-id persistence & recreate-once on 404 / session errors
 * - Never-loop guarantee
 * - Event-to-delta mapping (Delta, TurnDone, Error)
 * - ToolStart/ToolDone display suppression
 * - ApprovalRequest notice injection
 * - Malformed event tolerance
 * - Friendly error message mapping (no raw exceptions or hostnames)
 * - Cancellation
 */
class HearthChatClientTest {

    private val endpoint = Endpoint("https://127.0.0.1:8443", "lar1_test_token", "hearth")

    @Test
    fun sessionId_persistedAndReused() = runBlocking {
        val prefs = FakeSharedPreferences()
        var createCount = 0

        val fakeClient = object : LarariumClient(endpoint) {
            override suspend fun createSession(title: String?): JSONObject {
                createCount++
                return JSONObject().put("id", "sess-$createCount")
            }

            override suspend fun streamTurn(
                id: String,
                text: String,
                approval: JSONObject?,
                onEvent: (HearthEvent) -> Unit
            ) {
                onEvent(HearthEvent.Delta("Echo: $text", 1))
                onEvent(HearthEvent.TurnDone(2, JSONObject()))
            }
        }

        val chatClient = HearthChatClient(
            endpointProvider = { endpoint },
            prefs = prefs,
            clientFactory = { fakeClient }
        )

        // Turn 1: No session exists -> should create session and persist
        var result1 = ""
        chatClient.streamChat(
            message = "first turn",
            onDelta = {},
            onDone = { result1 = it },
            onError = { fail("Unexpected error: $it") }
        )

        assertEquals("Echo: first turn", result1)
        assertEquals(1, createCount)
        assertEquals("sess-1", prefs.getString("hearth_session_id", null))

        // Turn 2: Session exists -> should reuse session without calling createSession
        var result2 = ""
        chatClient.streamChat(
            message = "second turn",
            onDelta = {},
            onDone = { result2 = it },
            onError = { fail("Unexpected error: $it") }
        )

        assertEquals("Echo: second turn", result2)
        assertEquals(1, createCount) // Still 1, not recreated
        assertEquals("sess-1", prefs.getString("hearth_session_id", null))
    }

    @Test
    fun sessionId_recreateOnceOn404Exception() = runBlocking {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("hearth_session_id", "stale-session").apply()

        var createCount = 0
        var turnsAttempted = 0
        val usedSessionIds = mutableListOf<String>()

        val fakeClient = object : LarariumClient(endpoint) {
            override suspend fun createSession(title: String?): JSONObject {
                createCount++
                return JSONObject().put("id", "fresh-session-$createCount")
            }

            override suspend fun streamTurn(
                id: String,
                text: String,
                approval: JSONObject?,
                onEvent: (HearthEvent) -> Unit
            ) {
                turnsAttempted++
                usedSessionIds.add(id)
                if (id == "stale-session") {
                    throw IOException("turns returned 404")
                }
                onEvent(HearthEvent.Delta("Recovered: $text", 1))
                onEvent(HearthEvent.TurnDone(2, JSONObject()))
            }
        }

        val chatClient = HearthChatClient(
            endpointProvider = { endpoint },
            prefs = prefs,
            clientFactory = { fakeClient }
        )

        var completedText = ""
        chatClient.streamChat(
            message = "hello",
            onDelta = {},
            onDone = { completedText = it },
            onError = { fail("Should have recovered from 404 but got error: $it") }
        )

        assertEquals("Recovered: hello", completedText)
        assertEquals(2, turnsAttempted)
        assertEquals(listOf("stale-session", "fresh-session-1"), usedSessionIds)
        assertEquals(1, createCount)
        assertEquals("fresh-session-1", prefs.getString("hearth_session_id", null))
    }

    @Test
    fun sessionId_recreateOnceOnSessionErrorEvent() = runBlocking {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("hearth_session_id", "expired-session").apply()

        var createCount = 0
        var turnsAttempted = 0
        val usedSessionIds = mutableListOf<String>()

        val fakeClient = object : LarariumClient(endpoint) {
            override suspend fun createSession(title: String?): JSONObject {
                createCount++
                return JSONObject().put("id", "fresh-session-$createCount")
            }

            override suspend fun streamTurn(
                id: String,
                text: String,
                approval: JSONObject?,
                onEvent: (HearthEvent) -> Unit
            ) {
                turnsAttempted++
                usedSessionIds.add(id)
                if (id == "expired-session") {
                    onEvent(HearthEvent.Error("session expired-session not found", 1))
                } else {
                    onEvent(HearthEvent.Delta("OK: $text", 1))
                    onEvent(HearthEvent.TurnDone(2, JSONObject()))
                }
            }
        }

        val chatClient = HearthChatClient(
            endpointProvider = { endpoint },
            prefs = prefs,
            clientFactory = { fakeClient }
        )

        var completedText = ""
        chatClient.streamChat(
            message = "hello",
            onDelta = {},
            onDone = { completedText = it },
            onError = { fail("Should have recovered from session error event: $it") }
        )

        assertEquals("OK: hello", completedText)
        assertEquals(2, turnsAttempted)
        assertEquals(listOf("expired-session", "fresh-session-1"), usedSessionIds)
        assertEquals(1, createCount)
        assertEquals("fresh-session-1", prefs.getString("hearth_session_id", null))
    }

    @Test
    fun sessionId_neverLoopsIfRetryAlsoFails() = runBlocking {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("hearth_session_id", "bad-session").apply()

        var createCount = 0
        var turnsAttempted = 0

        val fakeClient = object : LarariumClient(endpoint) {
            override suspend fun createSession(title: String?): JSONObject {
                createCount++
                return JSONObject().put("id", "another-bad-session-$createCount")
            }

            override suspend fun streamTurn(
                id: String,
                text: String,
                approval: JSONObject?,
                onEvent: (HearthEvent) -> Unit
            ) {
                turnsAttempted++
                // Both attempts fail with 404
                throw IOException("turns returned 404")
            }
        }

        val chatClient = HearthChatClient(
            endpointProvider = { endpoint },
            prefs = prefs,
            clientFactory = { fakeClient }
        )

        var errorReceived: String? = null
        chatClient.streamChat(
            message = "hello",
            onDelta = {},
            onDone = { fail("Turn should not succeed") },
            onError = { errorReceived = it }
        )

        assertEquals(2, turnsAttempted) // Attempt 0 + Attempt 1 retry, then STOP
        assertEquals(1, createCount)
        assertNotNull(errorReceived)
        assertEquals("Hearth session not found.", errorReceived)
    }

    @Test
    fun eventMapping_deltaAndTurnDone() = runBlocking {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("hearth_session_id", "s1").apply()

        val fakeClient = object : LarariumClient(endpoint) {
            override suspend fun streamTurn(
                id: String,
                text: String,
                approval: JSONObject?,
                onEvent: (HearthEvent) -> Unit
            ) {
                onEvent(HearthEvent.Delta("The ", 1))
                onEvent(HearthEvent.Delta("quick ", 2))
                onEvent(HearthEvent.Delta("brown fox", 3))
                onEvent(HearthEvent.TurnDone(4, JSONObject()))
            }
        }

        val chatClient = HearthChatClient(
            endpointProvider = { endpoint },
            prefs = prefs,
            clientFactory = { fakeClient }
        )

        val deltas = mutableListOf<String>()
        var finalAccumulated = ""

        chatClient.streamChat(
            message = "run",
            onDelta = { deltas.add(it) },
            onDone = { finalAccumulated = it },
            onError = { fail("Unexpected error: $it") }
        )

        assertEquals(listOf("The ", "quick ", "brown fox"), deltas)
        assertEquals("The quick brown fox", finalAccumulated)
    }

    @Test
    fun eventMapping_toolsIgnoredForDisplay() = runBlocking {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("hearth_session_id", "s1").apply()

        val fakeClient = object : LarariumClient(endpoint) {
            override suspend fun streamTurn(
                id: String,
                text: String,
                approval: JSONObject?,
                onEvent: (HearthEvent) -> Unit
            ) {
                onEvent(HearthEvent.ToolStart("search", 1, JSONObject()))
                onEvent(HearthEvent.ToolDone("search", 2, JSONObject()))
                onEvent(HearthEvent.Delta("Answer", 3))
                onEvent(HearthEvent.TurnDone(4, JSONObject()))
            }
        }

        val chatClient = HearthChatClient(
            endpointProvider = { endpoint },
            prefs = prefs,
            clientFactory = { fakeClient }
        )

        val deltas = mutableListOf<String>()
        var doneText = ""

        chatClient.streamChat(
            message = "query",
            onDelta = { deltas.add(it) },
            onDone = { doneText = it },
            onError = { fail("Unexpected error") }
        )

        assertEquals(listOf("Answer"), deltas)
        assertEquals("Answer", doneText)
    }

    @Test
    fun eventMapping_approvalRequestMidTurn() = runBlocking {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("hearth_session_id", "s1").apply()

        val fakeClient = object : LarariumClient(endpoint) {
            override suspend fun streamTurn(
                id: String,
                text: String,
                approval: JSONObject?,
                onEvent: (HearthEvent) -> Unit
            ) {
                onEvent(HearthEvent.Delta("Working on it...", 1))
                onEvent(HearthEvent.ApprovalRequest(2, JSONObject()))
                onEvent(HearthEvent.Delta("Completed action.", 3))
                onEvent(HearthEvent.TurnDone(4, JSONObject()))
            }
        }

        val chatClient = HearthChatClient(
            endpointProvider = { endpoint },
            prefs = prefs,
            clientFactory = { fakeClient }
        )

        val deltas = mutableListOf<String>()
        var finalTurn = ""

        chatClient.streamChat(
            message = "do action",
            onDelta = { deltas.add(it) },
            onDone = { finalTurn = it },
            onError = { fail("Unexpected error") }
        )

        assertEquals(3, deltas.size)
        assertEquals("Working on it...", deltas[0])
        assertTrue("Approval notice should mention other device", deltas[1].contains("Your hearth is asking for approval on another device"))
        assertEquals("Completed action.", deltas[2])
        assertTrue(finalTurn.contains("Working on it..."))
        assertTrue(finalTurn.contains("Your hearth is asking for approval on another device"))
        assertTrue(finalTurn.contains("Completed action."))
    }

    @Test
    fun friendlyErrorMessage_sanitizesRawErrors() {
        assertEquals(
            "Hearth session not found.",
            HearthChatClient.friendlyErrorMessage("turns returned 404")
        )
        assertEquals(
            "Hearth session not found.",
            HearthChatClient.friendlyErrorMessage("""{"error":"session not found"}""")
        )
        assertEquals(
            "Hearth authentication failed. Please check your token in Settings.",
            HearthChatClient.friendlyErrorMessage("""{"error":"unauthorized"}""")
        )
        assertEquals(
            "Hearth server error. Please try again later.",
            HearthChatClient.friendlyErrorMessage("turns returned 500")
        )
        assertEquals(
            "Connection to hearth timed out.",
            HearthChatClient.friendlyErrorMessage("java.net.SocketTimeoutException: connect timed out")
        )
        assertEquals(
            "Could not connect to hearth. Please ensure the server is reachable.",
            HearthChatClient.friendlyErrorMessage("java.net.ConnectException: Connection refused")
        )
        assertEquals(
            "Could not resolve hearth host on the network.",
            HearthChatClient.friendlyErrorMessage("dns failed for zimaboard.example.tailscale.net")
        )
        // Ensure no private hostnames or IPs leak
        val mapped = HearthChatClient.friendlyErrorMessage("dns failed for zimaboard.example.tailscale.net")
        assertFalse(mapped.contains("zimaboard"))
        assertFalse(mapped.contains("tailscale"))
    }

    @Test
    fun isSessionError_detection() {
        assertTrue(HearthChatClient.isSessionError("turns returned 404", "s1"))
        assertTrue(HearthChatClient.isSessionError("no_such_session", "s1"))
        assertTrue(HearthChatClient.isSessionError("session s1 not found", "s1"))
        assertTrue(HearthChatClient.isSessionError("unknown session", "s1"))
        assertTrue(HearthChatClient.isSessionError("Session expired", "s1"))
        assertFalse(HearthChatClient.isSessionError("rate limit exceeded", "s1"))
        assertFalse(HearthChatClient.isSessionError("internal server error", "s1"))
    }

    @Test
    fun cancellation_triggersCancel() = runBlocking {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("hearth_session_id", "session-to-cancel").apply()

        var cancelledSessionId: String? = null
        val fakeClient = object : LarariumClient(endpoint) {
            override suspend fun cancel(id: String): JSONObject {
                cancelledSessionId = id
                return JSONObject().put("cancelled", true)
            }
        }

        val chatClient = HearthChatClient(
            endpointProvider = { endpoint },
            prefs = prefs,
            clientFactory = { fakeClient }
        )

        chatClient.cancel()
        // Wait a moment for coroutine
        kotlinx.coroutines.delay(100)
        assertEquals("session-to-cancel", cancelledSessionId)
    }

    @Test
    fun notPaired_returnsFriendlyError() = runBlocking {
        val prefs = FakeSharedPreferences()
        val chatClient = HearthChatClient(
            endpointProvider = { null }, // Not paired
            prefs = prefs
        )

        var errorMsg: String? = null
        chatClient.streamChat(
            message = "test",
            onDelta = {},
            onDone = {},
            onError = { errorMsg = it }
        )

        assertEquals("Not paired with your hearth yet — use Pair hearth in Settings", errorMsg)
    }

    @Test
    fun malformedEvents_tolerance() {
        // Test SSE parser tolerance via LarariumClient
        val client = LarariumClient(endpoint)
        val m = client.javaClass.getDeclaredMethod(
            "parseSseStream",
            java.io.InputStream::class.java,
            Function1::class.java,
        )
        m.isAccessible = true

        fun parse(inputStr: String): List<HearthEvent> {
            val list = mutableListOf<HearthEvent>()
            m.invoke(client, inputStr.byteInputStream(Charsets.UTF_8), { evt: HearthEvent -> list.add(evt) })
            return list
        }

        // 1. Unknown event type -> ignored
        val unknown = "event: ping\ndata: {\"val\":1}\n\nevent: turn_done\ndata: {}\n\n"
        val eventsUnknown = parse(unknown)
        assertEquals(1, eventsUnknown.size)
        assertTrue(eventsUnknown[0] is HearthEvent.TurnDone)

        // 2. Malformed JSON -> does not crash, fallback empty JSON
        val malformedJson = "event: delta\ndata: not-valid-json\n\nevent: turn_done\ndata: {}\n\n"
        val eventsMalformed = parse(malformedJson)
        assertEquals(2, eventsMalformed.size)
        assertTrue(eventsMalformed[0] is HearthEvent.Delta)
        assertEquals("", (eventsMalformed[0] as HearthEvent.Delta).text)

        // 3. Delta missing text field
        val missingText = "event: delta\ndata: {\"seq\":1}\n\nevent: turn_done\ndata: {}\n\n"
        val eventsMissingText = parse(missingText)
        assertEquals(2, eventsMissingText.size)
        assertEquals("", (eventsMissingText[0] as HearthEvent.Delta).text)

        // 4. Error event missing error field -> falls back to "unknown error"
        val emptyError = "event: error\ndata: {\"seq\":1}\n\n"
        val eventsEmptyError = parse(emptyError)
        assertEquals(1, eventsEmptyError.size)
        assertEquals("unknown error", (eventsEmptyError[0] as HearthEvent.Error).message)

        // 5. FriendlyErrorMessage handles malformed and empty strings safely
        assertEquals("Hearth request failed.", HearthChatClient.friendlyErrorMessage(""))
        assertEquals("Hearth request failed.", HearthChatClient.friendlyErrorMessage("   "))
        assertEquals("Hearth request failed. Please check your connection and pairing.", HearthChatClient.friendlyErrorMessage("{not a valid json"))
    }

    // -----------------------------------------------------------------------
    // Fake SharedPreferences implementation for pure JVM tests
    // -----------------------------------------------------------------------

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? =
            map[key]?.toString() ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int =
            (map[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long =
            (map[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float =
            (map[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = EditorImpl()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class EditorImpl : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                key?.let { temp[it] = value }
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                key?.let { temp[it] = values }
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                key?.let { temp[it] = value }
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                key?.let { temp[it] = value }
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                key?.let { temp[it] = value }
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                key?.let { temp[it] = value }
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                key?.let { removed.add(it) }
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clear = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clear) map.clear()
                removed.forEach { map.remove(it) }
                map.putAll(temp)
                temp.clear()
                removed.clear()
                clear = false
            }
        }
    }

    @Test
    fun clearSession_dropsStoredSessionId_soNewChatIsFreshOnHearth() = runBlocking {
        val prefs = FakeSharedPreferences()
        var createCount = 0

        val fakeClient = object : LarariumClient(endpoint) {
            override suspend fun createSession(title: String?): JSONObject {
                createCount++
                return JSONObject().put("id", "sess-$createCount")
            }

            override suspend fun streamTurn(
                id: String,
                text: String,
                approval: JSONObject?,
                onEvent: (HearthEvent) -> Unit
            ) {
                onEvent(HearthEvent.Delta("ok", 1))
                onEvent(HearthEvent.TurnDone(2, JSONObject()))
            }
        }

        val chatClient = HearthChatClient(
            endpointProvider = { endpoint },
            prefs = prefs,
            clientFactory = { fakeClient }
        )

        chatClient.streamChat(
            message = "first",
            onDelta = {},
            onDone = {},
            onError = { fail("Unexpected error: $it") }
        )
        assertEquals("sess-1", prefs.getString("hearth_session_id", null))

        // "New chat": the stored hearth session must not survive the clear,
        // or the model keeps remembering the cleared conversation.
        chatClient.clearSession()
        assertNull(prefs.getString("hearth_session_id", null))

        chatClient.streamChat(
            message = "second",
            onDelta = {},
            onDone = {},
            onError = { fail("Unexpected error: $it") }
        )
        assertEquals(2, createCount)
        assertEquals("sess-2", prefs.getString("hearth_session_id", null))
    }
}
