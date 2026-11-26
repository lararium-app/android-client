package app.lararium.hearth.live

import app.lararium.hearth.watch.CardInfo
import app.lararium.hearth.watch.WatchLogic
import org.json.JSONObject

/**
 * Events the live custos subscription derives from daemon frames. Pure data:
 * the Android channel maps them onto UI deltas and socket writes.
 */
sealed class LiveEvent {
    object HelloOk : LiveEvent()
    data class Snapshot(val cards: List<CardInfo>) : LiveEvent()
    data class Upsert(val card: CardInfo) : LiveEvent()
    data class Gone(val id: String) : LiveEvent()
    /** Daemon asked for a full re-read (event gap); caller re-fetches /v1/custos/cards. */
    object ResyncRequired : LiveEvent()
    /** Daemon predates custos.cards.subscribe — do not reconnect-storm. */
    object Unsupported : LiveEvent()
}

/**
 * JSON-RPC sequencing for the device approver channel:
 * auth.hello → custos.cards.subscribe → snapshot → live card pushes.
 *
 * Pure JVM (org.json only): the socket, retries, and pings live in
 * [CustosLive]; everything decidable from frame content alone is decided here
 * and unit-tested without a network.
 */
class CustosLiveLogic {
    enum class Phase { AWAIT_HELLO, AWAIT_SUBSCRIBE, READY }

    var phase: Phase = Phase.AWAIT_HELLO
        private set

    /**
     * The daemon binds the connection's identity from `params.token` on the
     * very first frame (close 4003 if absent or invalid, close 4001 if it
     * misses the 5s window — RPC-SPEC §2).
     */
    fun helloRequest(token: String): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", HELLO_ID)
        .put("method", "auth.hello")
        .put("params", JSONObject().put("token", token))
        .toString()

    fun subscribeRequest(): String =
        """{"jsonrpc":"2.0","id":$SUBSCRIBE_ID,"method":"custos.cards.subscribe"}"""

    /**
     * Consumes one text frame and returns the actions it implies (possibly
     * none). Malformed JSON and unknown methods are ignored: the connection
     * stays up, the REST poll remains the backstop.
     */
    fun onFrame(text: String): List<LiveEvent> {
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            return emptyList()
        }

        val method = obj.optString("method", "")
        if (method.isNotEmpty()) {
            if (phase != Phase.READY) return emptyList()
            val params = obj.optJSONObject("params")
            return when (method) {
                // custosdoor.Card (§6.4a): {id, cell, cred, tool, dest,
                // review, age_s, expires_in_s} — pending cards only.
                "custos.card" -> {
                    val card = params?.let { WatchLogic.parseCardObject(it) }
                    if (card == null) emptyList() else listOf(LiveEvent.Upsert(card))
                }
                // custosdoor.Gone (§6.4b): {id, state, reason}.
                "custos.gone" -> {
                    val id = params?.optString("id", "") ?: ""
                    if (id.isBlank()) emptyList() else listOf(LiveEvent.Gone(id))
                }
                "custos.resync" -> listOf(LiveEvent.ResyncRequired)
                else -> emptyList()
            }
        }

        if (!obj.has("id")) return emptyList()
        val id = obj.optInt("id", -1)
        val error = obj.optJSONObject("error")
        return when (id) {
            HELLO_ID -> {
                if (error == null && phase == Phase.AWAIT_HELLO) {
                    phase = Phase.AWAIT_SUBSCRIBE
                    listOf(LiveEvent.HelloOk)
                } else {
                    emptyList()
                }
            }
            SUBSCRIBE_ID -> {
                if (phase != Phase.AWAIT_SUBSCRIBE) return emptyList()
                if (error != null) {
                    if (error.optInt("code") == RPC_METHOD_NOT_FOUND) {
                        listOf(LiveEvent.Unsupported)
                    } else {
                        emptyList()
                    }
                } else {
                    phase = Phase.READY
                    listOf(LiveEvent.Snapshot(parseSnapshot(obj.optJSONObject("result"))))
                }
            }
            else -> emptyList()
        }
    }

    /** Exponential backoff for reconnects: 2s doubling to a 60s ceiling. */
    companion object {
        const val HELLO_ID = 1
        const val SUBSCRIBE_ID = 2
        const val RPC_METHOD_NOT_FOUND = -32601

        /** Auth failures the daemon signals via websocket close codes. */
        const val CLOSE_GOING_AWAY = 1001
        const val CLOSE_POLICY_VIOLATION = 1008
        const val CLOSE_UNAUTHORIZED = 4003

        fun backoffMs(attempt: Int): Long {
            if (attempt <= 1) return 2_000L
            var ms = 2_000L
            repeat(minOf(attempt - 1, 5)) { ms = minOf(ms * 2, 60_000L) }
            return ms
        }

        private fun parseSnapshot(result: JSONObject?): List<CardInfo> {
            val cards = result?.optJSONArray("cards") ?: return emptyList()
            val out = mutableListOf<CardInfo>()
            for (i in 0 until cards.length()) {
                val item: JSONObject = cards.optJSONObject(i) ?: continue
                val parsed = WatchLogic.parseCardObject(item)
                if (parsed != null) out.add(parsed)
            }
            return out
        }
    }
}
