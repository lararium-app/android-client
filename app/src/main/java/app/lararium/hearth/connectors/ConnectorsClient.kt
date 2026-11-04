package app.lararium.hearth.connectors

import app.lararium.hearth.Endpoint
import app.lararium.hearth.HttpResponse
import app.lararium.hearth.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder

/**
 * HTTP + SSE client for the hearthd v1 MCP connectors lifecycle surface.
 * Uses Bearer pair-token auth and Net (EndpointPolicy transport rules).
 */
class ConnectorsClient(
    private val endpoint: Endpoint,
    private val httpGet: (String, Map<String, String>) -> HttpResponse = { url, headers ->
        Net.get(url, headers)
    },
    private val httpPost: (String, String, Map<String, String>) -> HttpResponse = { url, body, headers ->
        Net.post(url, body, headers)
    },
    private val httpPatch: (String, String, Map<String, String>) -> HttpResponse = { url, body, headers ->
        Net.patch(url, body, headers)
    },
    private val httpDelete: (String, Map<String, String>) -> HttpResponse = { url, headers ->
        Net.delete(url, headers)
    },
    private val sseStreamOpener: (
        urlString: String,
        headers: Map<String, String>,
        onStream: (Int, InputStream) -> Unit,
    ) -> Unit = { url, headers, onStream ->
        Net.stream(url, headers, timeoutMs = 120_000, onStream = onStream)
    },
) {

    private val baseUrl: String = endpoint.baseUrl.trimEnd('/')
    private val auth: Map<String, String>
        get() = mapOf("Authorization" to "Bearer ${endpoint.token}")

    sealed class SubmitResult {
        data class PendingApproval(
            val name: String,
            val cardId: String,
        ) : SubmitResult()

        data class Failed(
            val status: Int,
            val body: String,
            val uiError: SubmitErrorUi,
        ) : SubmitResult()
    }

    sealed class ReauthResult {
        data class Success(val row: ConnectorRow) : ReauthResult()
        data class Failure(val status: Int, val message: String) : ReauthResult()
    }

    suspend fun listConnectors(): List<ConnectorRow> = withContext(Dispatchers.IO) {
        val resp = httpGet("$baseUrl/v1/mcp/connectors", auth)
        if (resp.status != 200) {
            throw IOException("listConnectors returned ${resp.status}")
        }
        ConnectorsLogic.parseRows(resp.body)
    }

    suspend fun fetchFormDescriptor(): ConnectorFormDescriptor = withContext(Dispatchers.IO) {
        val resp = httpGet("$baseUrl/v1/mcp/connector-form", auth)
        if (resp.status != 200) {
            throw IOException("connector-form returned ${resp.status}")
        }
        ConnectorsLogic.parseFormDescriptor(resp.body)
    }

    suspend fun addConnector(payload: JSONObject): SubmitResult = withContext(Dispatchers.IO) {
        val attemptedName = payload.optString("name", "").trim()
        val resp = try {
            httpPost("$baseUrl/v1/mcp/connectors", payload.toString(), auth)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext SubmitResult.Failed(
                status = 0,
                body = "",
                uiError = SubmitErrorUi.SnackbarError("Hearth not reachable / not ready."),
            )
        }
        parseSubmitResponse(resp, attemptedName)
    }

    suspend fun updateConnector(name: String, payload: JSONObject): SubmitResult = withContext(Dispatchers.IO) {
        val bodyCopy = JSONObject(payload.toString()).apply {
            put("name", name)
        }
        val encodedName = encodeSegment(name)
        val resp = try {
            httpPatch("$baseUrl/v1/mcp/connectors/$encodedName", bodyCopy.toString(), auth)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext SubmitResult.Failed(
                status = 0,
                body = "",
                uiError = SubmitErrorUi.SnackbarError("Hearth not reachable / not ready."),
            )
        }
        parseSubmitResponse(resp, name)
    }

    suspend fun deleteConnector(name: String): Boolean = withContext(Dispatchers.IO) {
        val encodedName = encodeSegment(name)
        val resp = httpDelete("$baseUrl/v1/mcp/connectors/$encodedName", auth)
        resp.status == 204
    }

    suspend fun enableConnector(name: String): ConnectorRow = withContext(Dispatchers.IO) {
        val encodedName = encodeSegment(name)
        val resp = httpPost("$baseUrl/v1/mcp/connectors/$encodedName/enable", "{}", auth)
        if (resp.status != 200) {
            throw IOException("enableConnector returned ${resp.status}")
        }
        ConnectorsLogic.parseRow(JSONObject(resp.body))
    }

    suspend fun disableConnector(name: String): ConnectorRow = withContext(Dispatchers.IO) {
        val encodedName = encodeSegment(name)
        val resp = httpPost("$baseUrl/v1/mcp/connectors/$encodedName/disable", "{}", auth)
        if (resp.status != 200) {
            throw IOException("disableConnector returned ${resp.status}")
        }
        ConnectorsLogic.parseRow(JSONObject(resp.body))
    }

    suspend fun reauthConnector(name: String): ReauthResult = withContext(Dispatchers.IO) {
        val encodedName = encodeSegment(name)
        val resp = try {
            httpPost("$baseUrl/v1/mcp/connectors/$encodedName/reauth", "{}", auth)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext ReauthResult.Failure(0, "Hearth not reachable / not ready.")
        }
        if (resp.status == 200) {
            ReauthResult.Success(ConnectorsLogic.parseRow(JSONObject(resp.body)))
        } else {
            ReauthResult.Failure(resp.status, ConnectorsLogic.mapReauthError(resp.status, resp.body))
        }
    }

    /**
     * Performs a single bootstrap-then-SSE cycle:
     * 1. Bootstraps full state from GET /v1/mcp/connectors
     * 2. Opens GET /v1/mcp/connectors/events (never sends Last-Event-ID)
     * 3. Applies connector_state / connector_gone events incrementally
     */
    suspend fun bootstrapAndStreamOnce(
        onStateUpdated: (List<ConnectorRow>) -> Unit,
        onEvent: ((ConnectorSseEvent) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        var currentRows = listConnectors()
        onStateUpdated(currentRows)

        val headers = auth + mapOf("Accept" to "text/event-stream")
        sseStreamOpener("$baseUrl/v1/mcp/connectors/events", headers) { status, inputStream ->
            if (status != 200) {
                throw IOException("connectors events returned $status")
            }
            parseSseStream(inputStream) { event ->
                currentRows = ConnectorsLogic.applySseEvent(currentRows, event)
                onEvent?.invoke(event)
                onStateUpdated(currentRows)
            }
        }
    }

    /**
     * Continuously synchronizes connector state while the calling coroutine is active.
     * On every connect or reconnect after disconnect/slow-client drop, always
     * re-bootstraps from GET /v1/mcp/connectors first before consuming SSE events.
     */
    suspend fun streamWithReconnect(
        maxCycles: Int = Int.MAX_VALUE,
        reconnectDelayMs: Long = 2_000L,
        onStateUpdated: (List<ConnectorRow>) -> Unit,
        onError: ((Throwable) -> Unit)? = null,
    ) {
        var cycle = 0
        while (currentCoroutineContext().isActive && cycle < maxCycles) {
            cycle++
            try {
                bootstrapAndStreamOnce(onStateUpdated = onStateUpdated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError?.invoke(e)
            }
            if (currentCoroutineContext().isActive && cycle < maxCycles && reconnectDelayMs > 0L) {
                delay(reconnectDelayMs)
            }
        }
    }

    private fun parseSubmitResponse(resp: HttpResponse, attemptedName: String): SubmitResult {
        if (resp.status == 202) {
            val json = try {
                JSONObject(resp.body)
            } catch (_: Exception) {
                JSONObject()
            }
            val name = json.optString("name", attemptedName).trim().ifEmpty { attemptedName }
            val cardId = json.optString("card_id", "").trim()
            return SubmitResult.PendingApproval(name = name, cardId = cardId)
        }
        return SubmitResult.Failed(
            status = resp.status,
            body = resp.body,
            uiError = ConnectorsLogic.mapSubmitError(resp.status, resp.body, attemptedName),
        )
    }

    companion object {
        private fun encodeSegment(segment: String): String =
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

        /**
         * Byte-accurate, UTF-8 safe SSE stream parser for /v1/mcp/connectors/events.
         * Ignores comment heartbeats (": ping") and dispatches "connector_state" and "connector_gone".
         */
        fun parseSseStream(input: InputStream, onEvent: (ConnectorSseEvent) -> Unit) {
            var eventName = ""
            val dataLines = StringBuilder()
            val lineBuf = ArrayList<Byte>(256)

            fun dispatchFrame() {
                if (eventName.isBlank() && dataLines.isBlank()) return
                val data = dataLines.toString().trimEnd('\n')
                val parsed = ConnectorsLogic.parseSseEvent(eventName, data)
                if (parsed != null) {
                    onEvent(parsed)
                }
                eventName = ""
                dataLines.clear()
            }

            fun readLine(): String? {
                lineBuf.clear()
                while (true) {
                    val b = input.read()
                    if (b == -1) {
                        return if (lineBuf.isEmpty()) null
                        else lineBuf.toByteArray().toString(Charsets.UTF_8)
                    }
                    if (b == '\n'.code) {
                        if (lineBuf.isNotEmpty() && lineBuf.last() == '\r'.code.toByte()) {
                            lineBuf.removeAt(lineBuf.size - 1)
                        }
                        return lineBuf.toByteArray().toString(Charsets.UTF_8)
                    }
                    lineBuf.add(b.toByte())
                }
            }

            while (true) {
                val line = readLine() ?: break
                when {
                    line.startsWith(":") -> {
                        // Heartbeat comment (e.g. ": ping") — ignore
                    }
                    line.isEmpty() -> dispatchFrame()
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        dataLines.append(line.removePrefix("data:").trim()).append('\n')
                    }
                    line == "event" -> eventName = ""
                    line == "data" -> dataLines.append('\n')
                    else -> {
                        // Ignore unknown SSE fields
                    }
                }
            }
            if (eventName.isNotBlank() || dataLines.isNotBlank()) {
                dispatchFrame()
            }
        }
    }
}
