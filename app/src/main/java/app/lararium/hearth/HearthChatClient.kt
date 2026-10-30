package app.lararium.hearth

import android.content.Context
import android.content.SharedPreferences
import app.lararium.llm.ChatSession
import app.lararium.security.SecureKeyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Chat client connecting the Assistant pipeline to the paired hearth's sessions API.
 * Uses LarariumClient.streamTurn over POST /v1/sessions/{id}/messages.
 */
class HearthChatClient(
    private val endpointProvider: () -> Endpoint?,
    private val prefs: SharedPreferences,
    private val clientFactory: (Endpoint) -> LarariumClient = { LarariumClient(it) },
    private val recoveryBackoffMs: LongArray = RECOVERY_BACKOFF_MS
) {

    companion object {
        const val PREFS_NAME = "assistant_settings"
        const val KEY_SESSION_ID = "hearth_session_id"
        const val APPROVAL_NOTICE = "Your hearth is asking for approval on another device"
        const val STILL_ANSWERING_MARKER = "still-answering"

        // These two strings go to onError FINAL, they are already user-facing
        const val STILL_ANSWERING_HINT =
            "Hearth received your message and is still answering. Reopen this chat in a moment — do not resend it."
        const val REPLY_LOST_HINT =
            "Connection dropped before your reply finished. Tap retry — hearth did not complete the reply."

        /**
         * Wait schedule for re-probing the session log when a dropped
         * stream finds the turn still in flight: a tool-heavy turn often
         * commits seconds after the wire dies, so we give it a bounded
         * chance (~35s) before falling back to the still-answering hint.
         */
        val RECOVERY_BACKOFF_MS = longArrayOf(3000, 7000, 12000, 13000)

        fun clearStoredSessionId(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_SESSION_ID)
                .apply()
        }

        fun getStoredSessionId(context: Context): String? =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SESSION_ID, null)?.takeIf { it.isNotBlank() }

        /**
         * Checks whether an error message or exception names the session or indicates a 404.
         */
        fun isSessionError(message: String, sessionId: String?): Boolean {
            val lower = message.lowercase()
            if (lower.contains("404")) return true
            if (lower.contains("no_such_session") || lower.contains("session not found") || lower.contains("invalid session")) return true
            if (lower.contains("session") && (lower.contains("not found") || lower.contains("missing") || lower.contains("expired") || lower.contains("unknown") || lower.contains("closed") || lower.contains("drop"))) return true
            if (!sessionId.isNullOrBlank() && lower.contains(sessionId.lowercase())) return true
            return false
        }

        /**
         * Maps raw error strings, HTTP status codes, or exception messages to clean, friendly user text.
         * Guarantees no raw hostnames, IPs, or exception traces are exposed.
         */
        fun friendlyErrorMessage(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "Hearth request failed."

            val errText = if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    val json = JSONObject(trimmed)
                    json.optString("error").ifEmpty { json.optString("message", "") }
                } catch (_: Exception) {
                    trimmed
                }
            } else trimmed

            val lower = errText.lowercase()
            return when {
                lower.contains("404") || lower.contains("no_such_session") || lower.contains("session not found") ->
                    "Hearth session not found."
                lower.contains("401") || lower.contains("403") || lower.contains("unauthorized") || lower.contains("forbidden") ->
                    "Hearth authentication failed. Please check your token in Settings."
                lower.contains("500") || lower.contains("502") || lower.contains("503") || lower.contains("504") || lower.contains("server error") ->
                    "Hearth server error. Please try again later."
                lower.contains("timeout") || lower.contains("timed out") ->
                    "Connection to hearth timed out."
                lower.contains("refused") || lower.contains("connectexception") ->
                    "Could not connect to hearth. Please ensure the server is reachable."
                lower.contains("dns") || lower.contains("unknownhost") ->
                    "Could not resolve hearth host on the network."
                lower.contains("ssl") || lower.contains("tls") || lower.contains("cert") || lower.contains("handshake") ->
                    "Secure connection to hearth failed."
                lower.contains("cleartext") ->
                    "Cleartext connection is only allowed on private networks."
                lower.contains("overloaded") ->
                    "Hearth is currently overloaded. Please try again shortly."
                lower.contains("rate") && lower.contains("limit") ->
                    "Hearth rate limit exceeded. Please wait a moment."
                lower.contains("cancel") ->
                    "Turn cancelled."
                else ->
                    "Hearth request failed. Please check your connection and pairing."
            }
        }
    }

    constructor(context: Context, keyManager: SecureKeyManager) : this(
        endpointProvider = {
            keyManager.getHearthEndpoint()?.let {
                Endpoint(it.first, it.second, it.third)
            }
        },
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()

    @Volatile
    var lastTurnSeq: Int? = null
        private set

    @Volatile
    private var activeSessionId: String? = null

    private var explicitEndpoint: Endpoint? = null

    val chatSession = ChatSession()

    fun setEndpoint(ep: Endpoint?) {
        explicitEndpoint = ep
    }

    private fun currentEndpoint(): Endpoint? = explicitEndpoint ?: endpointProvider()

    fun clearSession() {
        chatSession.clear()
        // A fresh conversation must also be fresh on the hearth: without
        // dropping the stored id the model would keep remembering "cleared"
        // history on the next turn.
        clearStoredSessionId()
    }

    fun getStoredSessionId(): String? =
        prefs.getString(KEY_SESSION_ID, null)?.takeIf { it.isNotBlank() }

    fun setStoredSessionId(id: String) {
        prefs.edit().putString(KEY_SESSION_ID, id).apply()
    }

    fun clearStoredSessionId() {
        prefs.edit().remove(KEY_SESSION_ID).apply()
    }

    private suspend fun getOrCreateSessionId(client: LarariumClient): String = sessionMutex.withLock {
        val existing = getStoredSessionId()
        if (existing != null) return@withLock existing
        return@withLock createNewSession(client)
    }

    private suspend fun createNewSession(client: LarariumClient): String {
        // create is safe to retry; a lost response only orphans an empty session file
        // (cosmetic, deleted with the session); this retry fixes the intermittent
        // first-message failure at session-create.
        val resp = try {
            client.createSession()
        } catch (_: IOException) {
            client.createSession()
        }
        val id = resp.optString("id").ifEmpty { resp.optString("session_id") }
        if (id.isBlank()) throw IOException("Invalid session response from hearth")
        setStoredSessionId(id)
        return id
    }

    private data class RecoveredMsg(
        val role: String,
        val text: String,
        val seq: Int?
    )

    private suspend fun recoverDroppedTurn(
        client: LarariumClient,
        sessionId: String,
        sentText: String,
    ): Pair<String?, Boolean> {
        return try {
            val root = client.events(sessionId, afterSeq = 0)
            val inFlight = root.optBoolean("in_flight", false)
            val array = root.optJSONArray("events") ?: return null to inFlight

            val messages = mutableListOf<RecoveredMsg>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val t = obj.optString("t", "")
                if (t == "msg") {
                    val role = obj.optString("role", "")
                    val text = obj.optString("text", "")
                    val seq = if (obj.has("seq") && !obj.isNull("seq")) obj.optInt("seq") else null
                    if (text.isNotBlank() && (role == "user" || role == "assistant")) {
                        messages.add(RecoveredMsg(role, text, seq))
                    }
                }
            }

            val lastUserIndex = messages.indexOfLast { it.role == "user" && it.text.trim() == sentText.trim() }
            if (lastUserIndex == -1) {
                return null to inFlight
            }

            val assistantEntry = messages.subList(lastUserIndex + 1, messages.size).firstOrNull { it.role == "assistant" }
            if (assistantEntry != null) {
                if (assistantEntry.seq != null) {
                    lastTurnSeq = assistantEntry.seq
                }
                assistantEntry.text to inFlight
            } else {
                null to inFlight
            }
        } catch (_: Exception) {
            null to false
        }
    }

    /**
     * Probes the session log for a dropped turn's committed answer, and if
     * the turn is still in flight, re-probes with bounded backoff instead of
     * giving up at first glance — the hearth commits turns even when the
     * SSE wire to this phone dies mid-stream. Cancellation (user cancel,
     * process death) aborts the wait; a turn that never commits within the
     * schedule degrades to the still-answering hint as before.
     */
    private suspend fun recoverDroppedTurnAwait(
        client: LarariumClient,
        sessionId: String,
        sentText: String,
    ): Pair<String?, Boolean> {
        var inFlight = false
        for (waitMs in longArrayOf(0L) + recoveryBackoffMs) {
            if (waitMs > 0L) delay(waitMs)
            val (recovered, flight) = recoverDroppedTurn(client, sessionId, sentText)
            if (recovered != null) return recovered to flight
            inFlight = flight
            if (!flight) break
        }
        return null to inFlight
    }

    /**
     * Replays the stored session's event log for cold-start restore.
     * Returns the raw JSON object string (with an "events" array) or null when
     * unpaired, no stored session, or the replay fails.
     */
    suspend fun replayStoredSession(): String? {
        val ep = currentEndpoint() ?: return null
        val id = getStoredSessionId() ?: return null
        return try {
            clientFactory(ep).events(id, afterSeq = 0).toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Stop/cancel current turn on the hearth.
     */
    fun cancel() {
        val id = activeSessionId ?: getStoredSessionId() ?: return
        val ep = currentEndpoint() ?: return
        clientScope.launch {
            try {
                clientFactory(ep).cancel(id)
            } catch (_: Exception) {
            }
        }
    }

    /** Session id used by the most recent (or in-flight) turn, if any. */
    fun lastActiveSessionId(): String? = activeSessionId ?: getStoredSessionId()

    /**
     * Streams a chat turn to the paired hearth.
     * Maps: Delta -> onDelta(text); TurnDone -> onDone(accumulated); Error -> onError(friendly message).
     * ApprovalRequest -> emits friendly notice via onDelta and continues streaming.
     * Retries once on 404 or session error event by dropping session id and recreating. Never loops.
     */
    suspend fun streamChat(
        message: String,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
        onTurnEvent: ((HearthEvent) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        lastTurnSeq = null
        val ep = currentEndpoint()
        if (ep == null) {
            onError("Not paired with your hearth yet — use Pair hearth in Settings")
            return@withContext
        }

        val client = clientFactory(ep)
        var attempt = 0
        var terminalDelivered = false
        var currentSessionId: String? = null

        while (attempt <= 1 && !terminalDelivered) {
            val sessionId = currentSessionId ?: try {
                getOrCreateSessionId(client)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onError(friendlyErrorMessage(e.message ?: "Failed to connect to hearth"))
                return@withContext
            }
            activeSessionId = sessionId

            val accumulated = StringBuilder()
            var shouldRetry = false

            try {
                client.streamTurn(sessionId, message) { event ->
                    onTurnEvent?.invoke(event)
                    when (event) {
                        is HearthEvent.Delta -> {
                            if (event.seq != null) {
                                lastTurnSeq = event.seq
                            }
                            if (event.text.isNotEmpty()) {
                                accumulated.append(event.text)
                                onDelta(event.text)
                            }
                        }
                        is HearthEvent.ApprovalRequest -> {
                            val notice = "\n\n[$APPROVAL_NOTICE]\n\n"
                            accumulated.append(notice)
                            onDelta(notice)
                        }
                        is HearthEvent.TurnDone -> {
                            if (event.seq != null) {
                                lastTurnSeq = event.seq
                            }
                            terminalDelivered = true
                            onDone(accumulated.toString())
                        }
                        is HearthEvent.Error -> {
                            if (attempt == 0 && accumulated.isEmpty() && isSessionError(event.message, sessionId)) {
                                shouldRetry = true
                            } else {
                                terminalDelivered = true
                                onError(friendlyErrorMessage(event.message))
                            }
                        }
                        is HearthEvent.ToolStart, is HearthEvent.ToolDone -> {
                            // Ignored for display per requirement 1b
                        }
                    }
                }

                if (!terminalDelivered && !shouldRetry) {
                    terminalDelivered = true
                    val (recovered, inFlight) = recoverDroppedTurnAwait(client, sessionId, message)
                    if (recovered != null) {
                        onDone(recovered)
                    } else if (inFlight) {
                        onError(STILL_ANSWERING_HINT)
                    } else {
                        onError(REPLY_LOST_HINT)
                    }
                }
            } catch (e: CancellationException) {
                try {
                    client.cancel(sessionId)
                } catch (_: Exception) {}
                throw e
            } catch (e: Exception) {
                val errorMsg = e.message ?: ""
                if (attempt == 0 && accumulated.isEmpty() && isSessionError(errorMsg, sessionId)) {
                    shouldRetry = true
                } else {
                    terminalDelivered = true
                    val (recovered, inFlight) = recoverDroppedTurnAwait(client, sessionId, message)
                    if (recovered != null) {
                        onDone(recovered)
                    } else if (inFlight) {
                        onError(STILL_ANSWERING_HINT)
                    } else {
                        onError(friendlyErrorMessage(errorMsg))
                    }
                }
            } finally {
                activeSessionId = null
            }

            if (shouldRetry) {
                attempt++
                clearStoredSessionId()
                currentSessionId = try {
                    sessionMutex.withLock {
                        val existing = getStoredSessionId()
                        existing ?: createNewSession(client)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    terminalDelivered = true
                    onError(friendlyErrorMessage(e.message ?: "Failed to recreate hearth session"))
                    return@withContext
                }
            } else {
                break
            }
        }
    }

    /**
     * Overload accepting message history list.
     */
    suspend fun streamChat(
        messages: List<ChatSession.Message>,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val lastUserMessage = messages.lastOrNull { it.role == "user" }?.content ?: ""
        streamChat(lastUserMessage, onDelta, onDone, onError)
    }

    /**
     * Flow-based streaming completion.
     */
    fun streamCompletion(
        userQuery: String,
        imageBase64: String? = null
    ): Flow<String> = kotlinx.coroutines.flow.channelFlow {
        var failureMessage: String? = null
        streamChat(
            message = userQuery,
            onDelta = { chunk -> trySend(chunk) },
            onDone = { /* finished */ },
            onError = { err -> failureMessage = err }
        )
        if (failureMessage != null) {
            throw IOException(failureMessage)
        }
    }.flowOn(Dispatchers.IO)
}
