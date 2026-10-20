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
                val title = if (rawTitle.isNotEmpty()) rawTitle else "Conversation ${id.take(8)}"
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
                    if (text.isNotEmpty() && (role == "user" || role == "assistant")) {
                        messages.add(ChatSession.Message(role = role, content = text))
                    }
                }
            }
            messages
        } catch (_: Exception) {
            emptyList()
        }
    }
}
