package app.lararium.hearth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

// ---------------------------------------------------------------------------
// Domain model for events delivered by the turns SSE stream.
// ---------------------------------------------------------------------------

sealed class HearthEvent {
    /** A chunk of the assistant's text reply. */
    data class Delta(val text: String, val seq: Int?) : HearthEvent()

    /** A tool the assistant is invoking. */
    data class ToolStart(val name: String, val seq: Int?, val raw: JSONObject) : HearthEvent()

    /** A tool call completed. */
    data class ToolDone(val name: String, val seq: Int?, val raw: JSONObject) : HearthEvent()

    /** Server is requesting human approval before proceeding. */
    data class ApprovalRequest(val seq: Int?, val raw: JSONObject) : HearthEvent()

    /** Terminal — turn finished successfully. */
    data class TurnDone(val seq: Int?, val raw: JSONObject) : HearthEvent()

    /** Terminal — server-side error. */
    data class Error(val message: String, val seq: Int?) : HearthEvent()
}

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

/**
 * Thin, coroutine-friendly wrapper around the hearthd v1 REST surface.
 * Uses only platform-included `org.json`; no new Gradle dependencies.
 */
class LarariumClient(private val endpoint: Endpoint) {

    private val auth get() = mapOf("Authorization" to "Bearer ${endpoint.token}")

    // -----------------------------------------------------------------------
    // Health
    // -----------------------------------------------------------------------

    suspend fun health(): JSONObject = withContext(Dispatchers.IO) {
        val resp = Net.get("${endpoint.baseUrl}/v1/health", auth)
        check(resp.status == 200) { "health returned ${resp.status}" }
        JSONObject(resp.body)
    }

    // -----------------------------------------------------------------------
    // Sessions
    // -----------------------------------------------------------------------

    suspend fun listSessions(): JSONArray = withContext(Dispatchers.IO) {
        val resp = Net.get("${endpoint.baseUrl}/v1/sessions", auth)
        check(resp.status == 200) { "listSessions returned ${resp.status}" }
        JSONArray(resp.body)
    }

    suspend fun createSession(title: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val body = if (title != null) JSONObject().put("title", title).toString() else "{}"
        val resp = Net.post("${endpoint.baseUrl}/v1/sessions", body, auth)
        check(resp.status == 201) { "createSession returned ${resp.status}" }
        JSONObject(resp.body)
    }

    // -----------------------------------------------------------------------
    // Events (polling replay)
    // -----------------------------------------------------------------------

    suspend fun events(id: String, afterSeq: Int = 0): JSONObject = withContext(Dispatchers.IO) {
        val url = "${endpoint.baseUrl}/v1/sessions/$id/events?after_seq=$afterSeq&limit=200"
        val resp = Net.get(url, auth)
        check(resp.status == 200) { "events returned ${resp.status}" }
        JSONObject(resp.body)
    }

    // -----------------------------------------------------------------------
    // Cancel
    // -----------------------------------------------------------------------

    suspend fun cancel(id: String): JSONObject = withContext(Dispatchers.IO) {
        val resp = Net.post("${endpoint.baseUrl}/v1/sessions/$id/cancel", "{}", auth)
        check(resp.status in 200..299) { "cancel returned ${resp.status}" }
        JSONObject(resp.body)
    }

    // -----------------------------------------------------------------------
    // Custos (watch / approvals)
    // -----------------------------------------------------------------------

    suspend fun custosCards(): String = withContext(Dispatchers.IO) {
        val resp = Net.get("${endpoint.baseUrl}/v1/custos/cards", auth)
        check(resp.status == 200) { "custosCards returned ${resp.status}" }
        resp.body
    }

    // -----------------------------------------------------------------------
    // Stream turn (SSE)
    // -----------------------------------------------------------------------

    /**
     * Sends a turn to the session and streams back named SSE events. Each
     * parsed event is delivered synchronously on the calling (IO) coroutine
     * via [onEvent]. Returns when the stream reaches a terminal event
     * (`turn_done` or `error`) or the connection closes.
     *
     * The SSE parser handles:
     * - events/data lines split across arbitrary TCP reads,
     * - multi-byte UTF-8 split across HTTP chunk boundaries,
     * - comment lines (`:`) and keep-alive blank lines silently ignored,
     * - no character reader anywhere — everything over raw bytes decoded at
     *   the boundary where a complete UTF-8 string is known to be whole.
     */
    suspend fun streamTurn(
        id: String,
        text: String,
        approval: JSONObject? = null,
        onEvent: (HearthEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("text", text)
            if (approval != null) put("approval", approval)
        }.toString()
        val extraHeaders = auth + mapOf(
            "Accept" to "text/event-stream",
            "Content-Type" to "application/json",
        )

        openSseSocket(
            "${endpoint.baseUrl}/v1/sessions/$id/turns",
            body,
            extraHeaders,
        ).use { ctx ->
            parseSseStream(ctx.inputStream, onEvent)
        }
    }

    // -----------------------------------------------------------------------
    // Raw SSE socket (hand-rolled so we control every byte)
    // -----------------------------------------------------------------------

    private data class SseSocketContext(
        val socket: Socket,
        val inputStream: InputStream,
    ) : AutoCloseable {
        override fun close() = socket.close()
    }

    private fun openSseSocket(
        urlString: String,
        body: String,
        headers: Map<String, String>,
    ): SseSocketContext {
        EndpointPolicy.rejectReason(urlString)?.let { throw IOException(it) }
        headers.forEach { (k, v) -> requireSafeHeader(k); requireSafeHeader(v) }

        val url = URL(urlString)
        val port = if (url.port == -1) if (url.protocol == "https") 443 else 80 else url.port

        val socket: Socket = when (url.protocol) {
            "https" -> (SSLSocketFactory.getDefault().createSocket() as SSLSocket).also { ssl ->
                ssl.sslParameters = ssl.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                ssl.connect(InetSocketAddress(url.host, port), 15_000)
            }
            "http" -> {
                val addrs = try {
                    InetAddress.getAllByName(url.host)
                } catch (e: Exception) {
                    throw IOException("dns failed for ${url.host}")
                }
                val private = addrs.isNotEmpty() && addrs.all {
                    it.isLoopbackAddress || it.isSiteLocalAddress || Net.isCgnat(it)
                }
                if (!private) throw IOException(
                    "cleartext http is only allowed on LAN/tailnet hosts; use https"
                )
                Socket().also { it.connect(InetSocketAddress(addrs[0], port), 15_000) }
            }
            else -> throw IOException("unsupported scheme ${url.protocol}")
        }

        try {
            socket.soTimeout = 120_000
            val path = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")
            val bodyBytes = body.toByteArray(Charsets.UTF_8)

            val req = buildString {
                append("POST ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(url.host)
                if (url.port != -1) append(":").append(url.port)
                append("\r\n")
                append("Accept-Encoding: identity\r\n")
                append("Connection: close\r\n")
                append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                append("\r\n")
            }

            val out = socket.getOutputStream()
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.write(bodyBytes)
            out.flush()

            // Consume the HTTP response header — raw bytes, then hand over the
            // body InputStream to the SSE parser.
            val statusAndHeaders = readHeaderBytes(socket.getInputStream())
            val headerText = statusAndHeaders.toString(Charsets.ISO_8859_1)
            val statusLine = headerText.lineSequence().first()
            val statusCode = statusLine.substringAfter(' ').substringBefore(' ').toIntOrNull()
                ?: throw IOException("bad status line: $statusLine")
            if (statusCode != 200) throw IOException("turns returned $statusCode")

            return SseSocketContext(socket, socket.getInputStream())
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    /** Reads raw bytes until CRLFCRLF, leaving the stream at the body start. */
    private fun readHeaderBytes(input: InputStream): ByteArray {
        val buf = ArrayList<Byte>(512)
        var p3 = 0; var p2 = 0; var p1 = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            buf.add(b.toByte())
            if (p3 == '\r'.code && p2 == '\n'.code && p1 == '\r'.code && b == '\n'.code) break
            p3 = p2; p2 = p1; p1 = b
        }
        return buf.toByteArray()
    }

    // -----------------------------------------------------------------------
    // SSE parser — byte-accurate, UTF-8 safe
    // -----------------------------------------------------------------------

    /**
     * Reads named SSE events from [input]. Accumulates raw bytes per line
     * (never a character reader), decodes UTF-8 only at line boundaries so
     * multi-byte code points split across reads are always safe.
     *
     * Terminates when a terminal event (`turn_done` / `error`) is received
     * or the stream ends.
     */
    private fun parseSseStream(input: InputStream, onEvent: (HearthEvent) -> Unit) {
        var eventName = ""
        val dataLines = StringBuilder()

        var done = false

        fun dispatchAndCheck() {
            if (eventName.isBlank() && dataLines.isBlank()) return
            val data = dataLines.toString().trimEnd('\n')
            val evt = parseHearthEvent(eventName, data)
            if (evt != null) onEvent(evt)
            if (evt is HearthEvent.TurnDone || evt is HearthEvent.Error) done = true
            eventName = ""; dataLines.clear()
        }

        val lineBuf = ArrayList<Byte>(256)

        fun readLine(): String? {
            lineBuf.clear()
            while (true) {
                val b = input.read()
                if (b == -1) return if (lineBuf.isEmpty()) null
                    else lineBuf.toByteArray().toString(Charsets.UTF_8)
                if (b == '\n'.code) {
                    // Drop trailing CR if present.
                    if (lineBuf.isNotEmpty() && lineBuf.last() == '\r'.code.toByte())
                        lineBuf.removeAt(lineBuf.size - 1)
                    return lineBuf.toByteArray().toString(Charsets.UTF_8)
                }
                lineBuf.add(b.toByte())
            }
        }

        while (!done) {
            val line = readLine() ?: break
            when {
                line.startsWith(":") -> { /* comment / heartbeat — ignore */ }
                line.isEmpty() -> dispatchAndCheck()
                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    dataLines.append(line.removePrefix("data:").trim()).append('\n')
                }
                line == "event" -> eventName = ""
                line == "data" -> dataLines.append('\n')
                else -> { /* unknown field — ignore per spec */ }
            }
        }
    }

    private fun parseHearthEvent(name: String, data: String): HearthEvent? {
        if (data.isBlank()) return null
        val json = try { JSONObject(data) } catch (e: Exception) { JSONObject() }
        val seq = if (json.has("seq")) json.optInt("seq") else null
        return when (name) {
            "delta" -> HearthEvent.Delta(json.optString("text", ""), seq)
            "tool_start" -> HearthEvent.ToolStart(json.optString("name", ""), seq, json)
            "tool_done" -> HearthEvent.ToolDone(json.optString("name", ""), seq, json)
            "approval_request" -> HearthEvent.ApprovalRequest(seq, json)
            "turn_done" -> HearthEvent.TurnDone(seq, json)
            // hearthd's terminal payload is {"error":"…"}; keep "message" as a
            // forgiving fallback for older builds.
            "error" -> HearthEvent.Error(
                json.optString("error")
                    .ifEmpty { json.optString("message", "unknown error") },
                seq,
            )
            else -> null
        }
    }

    // -----------------------------------------------------------------------
    // Shared header guard
    // -----------------------------------------------------------------------

    private fun requireSafeHeader(value: String) {
        if (value.any { it == '\r' || it == '\n' || it == '\u0000' })
            throw IOException("header contains illegal character")
    }
}
