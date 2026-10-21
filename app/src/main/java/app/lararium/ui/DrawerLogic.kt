package app.lararium.ui

import app.lararium.llm.ChatSession
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure presentation and data transformation logic for navigation drawer,
 * connection status formatting, and session history parsing.
 * Zero android.* dependencies for fast JVM unit testing.
 */
object DrawerLogic {

    sealed class ConnectionState {
        data class Paired(val hearthName: String) : ConnectionState()
        object Unpaired : ConnectionState()
    }

    data class SessionSummary(
        val id: String,
        val title: String,
        val updatedAt: Long? = null
    )

    /**
     * Determines whether the app is currently paired with a hearth and formats the connection state.
     */
    fun formatConnectionState(hearthEndpoint: Triple<String, String, String>?): ConnectionState {
        return if (hearthEndpoint != null && hearthEndpoint.first.isNotBlank()) {
            val name = hearthEndpoint.third.trim()
            ConnectionState.Paired(if (name.isNotEmpty()) name else "Hearth")
        } else {
            ConnectionState.Unpaired
        }
    }

    /**
     * Formats the top bar title: hearth name if paired, otherwise "Lararium".
     */
    fun formatTopBarTitle(hearthEndpoint: Triple<String, String, String>?): String {
        return if (hearthEndpoint != null && hearthEndpoint.third.isNotBlank()) {
            hearthEndpoint.third.trim()
        } else {
            "Lararium"
        }
    }

    /**
     * Resolves session title: header title when present, else snippet of first user message
     * from events if available, else "New chat".
     */
    fun resolveSessionTitle(rawTitle: String?, events: JSONArray? = null): String {
        if (!rawTitle.isNullOrBlank()) return rawTitle.trim()
        val firstUserSnippet = events?.let { arr ->
            (0 until arr.length()).asSequence()
                .mapNotNull { arr.optJSONObject(it) }
                .filter { it.optString("t") == "msg" }
                .map { it.optJSONObject("fields") ?: it }
                .firstOrNull { it.optString("role") == "user" }
                ?.optString("text")?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.take(50)
        }
        return firstUserSnippet ?: "New chat"
    }

    /**
     * Parses a JSON string array of sessions returned by LarariumClient.listSessions().
     */
    fun parseSessions(json: String?): List<SessionSummary> {
        if (json.isNullOrBlank()) return emptyList()
        val result = mutableListOf<SessionSummary>()
        return try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = if (obj.has("id") && !obj.isNull("id")) obj.optString("id", "").trim() else ""
                if (id.isEmpty()) continue
                val rawTitle = if (obj.has("title") && !obj.isNull("title")) obj.optString("title", "").trim() else ""
                val title = resolveSessionTitle(rawTitle, obj.optJSONArray("events"))
                val updatedAt = if (obj.has("updated_at") && !obj.isNull("updated_at")) {
                    obj.optLong("updated_at")
                } else null
                result.add(SessionSummary(id, title, updatedAt))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Parses past messages from a session events JSON payload (POST/GET /v1/sessions/{id}/events).
     */
    fun parseSessionMessages(eventsJson: String?): List<ChatSession.Message> {
        if (eventsJson.isNullOrBlank()) return emptyList()
        val messages = mutableListOf<ChatSession.Message>()
        return try {
            val root = JSONObject(eventsJson)
            val array = root.optJSONArray("events") ?: return emptyList()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val t = obj.optString("t", "")
                if (t == "msg") {
                    val role = obj.optString("role", "")
                    val text = obj.optString("text", "")
                    val seq = if (obj.has("seq") && !obj.isNull("seq")) obj.optInt("seq") else null
                    if (text.isNotEmpty() && (role == "user" || role == "assistant")) {
                        messages.add(ChatSession.Message(role = role, content = text, seq = seq))
                    }
                }
            }
            messages
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Merges incoming persisted messages with current/optimistic session messages.
     * Messages with matching server `seq` are deduplicated (persisted replaces optimistic).
     * Unsequenced optimistic user and assistant messages matching incoming messages are
     * replaced to prevent duplicate bubbles after reload.
     */
    fun mergeMessages(
        current: List<ChatSession.Message>,
        incoming: List<ChatSession.Message>
    ): List<ChatSession.Message> {
        if (incoming.isEmpty()) return current
        if (current.isEmpty()) return incoming

        val incomingSeqMap = incoming.filter { it.seq != null }.associateBy { it.seq!! }
        val incomingUserTexts = incoming.filter { it.role == "user" }.map { it.content.trim() }.toSet()
        val incomingAssistantTexts = incoming.filter { it.role == "assistant" }.map { it.content.trim() }.toSet()

        val result = mutableListOf<ChatSession.Message>()
        val seenSeqs = mutableSetOf<Int>()

        // 1. Add current messages with seq if not superseded by incoming
        for (msg in current) {
            val seq = msg.seq
            if (seq != null) {
                if (incomingSeqMap.containsKey(seq)) {
                    continue
                } else {
                    seenSeqs.add(seq)
                    result.add(msg)
                }
            }
        }

        // 2. Add incoming persisted messages
        for (msg in incoming) {
            val seq = msg.seq
            if (seq != null) {
                if (seenSeqs.add(seq)) {
                    result.add(msg)
                }
            } else {
                result.add(msg)
            }
        }

        val sortedSequenced = result.filter { it.seq != null }.sortedBy { it.seq!! }
        val unsequencedIncoming = result.filter { it.seq == null }
        val finalResult = (sortedSequenced + unsequencedIncoming).toMutableList()

        // 3. Keep optimistic unsequenced messages from current that have not yet been persisted
        for (msg in current) {
            if (msg.seq == null) {
                val alreadyPersisted = when (msg.role) {
                    "user" -> incomingUserTexts.contains(msg.content.trim())
                    "assistant" -> incomingAssistantTexts.contains(msg.content.trim())
                    else -> false
                }
                if (!alreadyPersisted) {
                    finalResult.add(msg)
                }
            }
        }

        return finalResult
    }
}
