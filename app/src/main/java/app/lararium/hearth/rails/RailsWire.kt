package app.lararium.hearth.rails

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JVM wire formats and serialization for Hearth rails (A1).
 * Contains ZERO android.* imports so it can be tested without Robolectric or framework stubs.
 */

class RailsWireException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class DeclaredRail(val name: String, val readOnly: Boolean, val enabled: Boolean)

data class DispatchedAction(val actionId: String, val tool: String, val args: JSONObject)

sealed interface StreamEvent {
    data class ActionDispatch(val action: DispatchedAction) : StreamEvent
    object PairRequired : StreamEvent
    object Unknown : StreamEvent
}

object RailsWire {
    val RAIL_NAMES: Set<String> = setOf(
        "calendar",
        "contacts",
        "notifications",
        "calllog",
        "health",
        "dialer",
        "sms"
    )

    val READ_RAILS: Set<String> = setOf(
        "calendar",
        "contacts",
        "notifications",
        "calllog",
        "health"
    )

    val ACTION_RAILS: Set<String> = setOf(
        "dialer",
        "sms"
    )

    private val TOOL_REGEX = Regex("""^device\.[a-z.]+$""")

    fun buildRegisterBody(
        deviceId: String,
        displayName: String,
        railsDeclared: List<DeclaredRail>
    ): JSONObject {
        if (deviceId.isBlank() || displayName.isBlank()) {
            throw RailsWireException("deviceId and displayName must not be blank")
        }

        val railsArray = JSONArray()
        for (rail in railsDeclared) {
            if (rail.name in READ_RAILS && !rail.readOnly) {
                throw RailsWireException("Read rail '${rail.name}' must declare read_only = true")
            }
            if (rail.name in ACTION_RAILS && rail.readOnly) {
                throw RailsWireException("Action rail '${rail.name}' must declare read_only = false")
            }

            val railObj = JSONObject()
            railObj.put("name", rail.name)
            railObj.put("read_only", rail.readOnly)
            railObj.put("enabled", rail.enabled)
            railsArray.put(railObj)
        }

        val root = JSONObject()
        root.put("device_id", deviceId)
        root.put("display_name", displayName)
        root.put("rails_declared", railsArray)
        return root
    }

    fun parseActionFrame(frameType: String, data: JSONObject): DispatchedAction? {
        if (frameType != "action_dispatch") {
            return null
        }

        if (!data.has("action_id")) {
            throw RailsWireException("Missing action_id in action_dispatch frame")
        }
        val actionId = data.optString("action_id", "").trim()
        if (actionId.isEmpty()) {
            throw RailsWireException("action_id must not be blank")
        }

        if (!data.has("tool")) {
            throw RailsWireException("Missing tool in action_dispatch frame")
        }
        val tool = data.optString("tool", "")
        if (!tool.matches(TOOL_REGEX)) {
            throw RailsWireException("tool '$tool' does not match device.[a-z.]+")
        }

        if (!data.has("args")) {
            throw RailsWireException("Missing args in action_dispatch frame")
        }
        val args = data.optJSONObject("args")
            ?: throw RailsWireException("args must be a JSONObject in action_dispatch frame")

        return DispatchedAction(actionId = actionId, tool = tool, args = args)
    }

    fun parseStreamEvent(rawEvent: String?, data: JSONObject): StreamEvent {
        val eventType = rawEvent
            ?: data.optString("event").takeIf { it.isNotEmpty() }
            ?: data.optString("type").takeIf { it.isNotEmpty() }

        if (eventType == "pair_required") {
            return StreamEvent.PairRequired
        }

        if (eventType == "action_dispatch" || (eventType == null && data.has("action_id") && data.has("tool"))) {
            val action = parseActionFrame("action_dispatch", data)
                ?: throw RailsWireException("Failed to parse action_dispatch")
            return StreamEvent.ActionDispatch(action)
        }

        return StreamEvent.Unknown
    }

    fun buildSyncEnvelope(
        rail: String,
        seq: Long,
        snapshot: JSONArray?,
        deltas: JSONArray?
    ): JSONObject {
        if (rail.isBlank()) {
            throw RailsWireException("rail must not be blank")
        }
        val envelope = JSONObject()
        envelope.put("rail", rail)
        envelope.put("seq", seq)
        if (snapshot != null) {
            envelope.put("snapshot", snapshot)
        }
        if (deltas != null) {
            envelope.put("deltas", deltas)
        }
        return envelope
    }

    fun buildReceiptBody(
        actionId: String,
        ok: Boolean,
        result: String?,
        error: String?
    ): JSONObject {
        if (actionId.isBlank()) {
            throw RailsWireException("actionId must not be blank")
        }
        val receipt = JSONObject()
        receipt.put("ok", ok)
        if (ok && result != null) {
            receipt.put("result", result)
        }
        if (!ok && error != null) {
            receipt.put("error", error)
        }
        return receipt
    }
}
