package app.lararium.llm

import android.util.Log
import app.lararium.hearth.Net
import org.json.JSONObject

/**
 * Model discovery for self-hosted OpenAI-compatible servers.
 * Queries GET {baseUrl}/models — supported by llama-server, Ollama (/v1),
 * vLLM, LM Studio, Hermes API, etc.
 */
object SelfHostedModels {

    private const val TAG = "SelfHostedModels"
    private const val TIMEOUT_MS = 10000

    /**
     * Fetch available model IDs from an OpenAI-compatible endpoint.
     * Must be called from a background thread.
     *
     * @param baseUrl e.g. "http://host:8080/v1" (with or without /chat/completions)
     * @return sorted list of model IDs; empty on any failure (caller shows fallback UI)
     */
    fun fetch(baseUrl: String, apiKey: String?): List<String> {
        if (baseUrl.isBlank()) return emptyList()
        val root = baseUrl.trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
        return try {
            // M14: one enforcement point. Net applies the private-host
            // cleartext policy (with the resolved-address re-check) instead
            // of leaving it to NSC rules that cannot express address ranges.
            // Log lines stay host-free (logcat hygiene).
            val headers = mutableMapOf("Accept" to "application/json")
            if (!apiKey.isNullOrBlank()) {
                headers["Authorization"] = "Bearer $apiKey"
            }
            val resp = Net.get("$root/models", headers, TIMEOUT_MS)
            if (resp.status !in 200..299) {
                Log.w(TAG, "GET /models -> HTTP ${resp.status}")
                return emptyList()
            }
            parse(resp.body)
        } catch (e: Exception) {
            Log.w(TAG, "Model discovery failed: ${e.message}")
            emptyList()
        }
    }

    /** Parse {"data":[{"id":...}]} or {"models":[{"id":...}]}/{"models":["id",...]}. */
    internal fun parse(body: String): List<String> {
        return try {
            val root = JSONObject(body)
            val ids = mutableListOf<String>()
            root.optJSONArray("data")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("id")
                        ?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
                }
            }
            if (ids.isEmpty()) {
                root.optJSONArray("models")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        when (val el = arr.opt(i)) {
                            is JSONObject -> el.optString("id")
                                .takeIf { it.isNotBlank() }?.let { ids.add(it) }
                            is String -> if (el.isNotBlank()) ids.add(el)
                        }
                    }
                }
            }
            ids.distinct().sorted()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse /models response: ${e.message}")
            emptyList()
        }
    }
}
