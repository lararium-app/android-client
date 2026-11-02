package app.lararium.hearth.rails.service

import app.lararium.hearth.EndpointPolicy
import app.lararium.hearth.Net
import app.lararium.hearth.rails.DeviceStreamState
import app.lararium.hearth.rails.DeviceStreamStateMachine
import app.lararium.hearth.rails.OfflineCause
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

/**
 * SSE streaming client for device rails action dispatches (F1).
 *
 * Connects to GET /v1/devices/<id>/stream with Bearer auth, feeds bytes to
 * DeviceStreamStateMachine, and passes incoming event frames to the callback.
 * Enforces strict epoch checking and non-retryable RepairRequired on 401/403 or pair_required.
 */
class DeviceStreamClient(
    private val endpointProvider: () -> DeviceEndpoint?,
    val stateMachine: DeviceStreamStateMachine,
    private val streamOpener: (
        urlString: String,
        headers: Map<String, String>,
        onStream: (Int, InputStream) -> Unit
    ) -> Unit = { url, headers, onStream ->
        Net.stream(url, headers, timeoutMs = 120_000, onStream = onStream)
    }
) {
    constructor(
        endpoint: DeviceEndpoint,
        stateMachine: DeviceStreamStateMachine,
        streamOpener: (
            urlString: String,
            headers: Map<String, String>,
            onStream: (Int, InputStream) -> Unit
        ) -> Unit = { url, headers, onStream ->
            Net.stream(url, headers, timeoutMs = 120_000, onStream = onStream)
        }
    ) : this({ endpoint }, stateMachine, streamOpener)

    suspend fun openStream(onFrame: (event: String?, data: JSONObject) -> Unit) {
        val endpoint = endpointProvider() ?: return
        if (stateMachine.state is DeviceStreamState.RepairRequired) {
            return
        }

        // Advance epoch and enter Connecting state
        stateMachine.onConnecting()
        val epoch = stateMachine.currentEpoch()

        val urlString = "${endpoint.baseUrl.trimEnd('/')}/v1/devices/${endpoint.deviceId}/stream"
        val rejectReason = EndpointPolicy.rejectReason(urlString)
        if (rejectReason != null) {
            if (stateMachine.isCurrentEpoch(epoch)) {
                stateMachine.onFailure(OfflineCause.NoNetwork)
            }
            throw IOException(rejectReason)
        }

        withContext(Dispatchers.IO) {
            try {
                val headers = mapOf(
                    "Authorization" to "Bearer ${endpoint.token}",
                    "Accept" to "text/event-stream"
                )

                streamOpener(urlString, headers) { responseCode, inputStream ->
                    if (!stateMachine.isCurrentEpoch(epoch)) {
                        return@streamOpener
                    }

                    if (responseCode == 401 || responseCode == 403) {
                        stateMachine.onFailure(OfflineCause.Unauthorized)
                        return@streamOpener
                    }

                    if (responseCode !in 200..299) {
                        stateMachine.onFailure(OfflineCause.NoNetwork)
                        return@streamOpener
                    }

                    stateMachine.onStreamOpen()

                    val reader = inputStream.bufferedReader(Charsets.UTF_8)
                    var currentEvent: String? = null
                    val dataBuilder = StringBuilder()

                    while (true) {
                        val line = try {
                            reader.readLine()
                        } catch (e: Exception) {
                            if (stateMachine.isCurrentEpoch(epoch)) {
                                stateMachine.onFailure(OfflineCause.NoNetwork)
                            }
                            return@streamOpener
                        } ?: break // EOF

                        if (!stateMachine.isCurrentEpoch(epoch)) {
                            return@streamOpener
                        }

                        // Feed stream bytes to state machine for liveness tracking
                        stateMachine.onStreamBytes()

                        when {
                            line.startsWith(":") -> {
                                // SSE comment / heartbeat (e.g., ": ping") — counts as bytes, no event to dispatch
                            }
                            line.startsWith("event:") -> {
                                currentEvent = line.removePrefix("event:").trim()
                            }
                            line.startsWith("data:") -> {
                                dataBuilder.append(line.removePrefix("data:").trim()).append('\n')
                            }
                            line.isEmpty() -> {
                                // Frame delimiter: dispatch accumulated frame
                                val rawData = dataBuilder.toString().trim()
                                if (currentEvent != null || rawData.isNotEmpty()) {
                                    val json = if (rawData.isNotEmpty()) {
                                        try {
                                            JSONObject(rawData)
                                        } catch (_: Exception) {
                                            JSONObject()
                                        }
                                    } else {
                                        JSONObject()
                                    }

                                    if (currentEvent == "pair_required" ||
                                        json.optString("event") == "pair_required" ||
                                        json.optString("type") == "pair_required"
                                    ) {
                                        if (stateMachine.isCurrentEpoch(epoch)) {
                                            stateMachine.onFailure(OfflineCause.PairRequired)
                                        }
                                        return@streamOpener
                                    }

                                    if (stateMachine.isCurrentEpoch(epoch)) {
                                        onFrame(currentEvent, json)
                                    }
                                }
                                currentEvent = null
                                dataBuilder.clear()
                            }
                        }
                    }

                    if (stateMachine.isCurrentEpoch(epoch)) {
                        stateMachine.onStreamClosed()
                    }
                }
            } catch (e: Exception) {
                if (stateMachine.isCurrentEpoch(epoch)) {
                    stateMachine.onFailure(OfflineCause.NoNetwork)
                }
            }
        }
    }
}
