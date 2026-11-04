package app.lararium.hearth.connectors

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

data class ConnectorTool(
    val name: String,
    val readOnly: Boolean,
    val destructive: Boolean,
    val openWorld: Boolean,
)

data class ConnectorRow(
    val name: String,
    val state: String,
    val tools: Int,
    val restarts: Int,
    val reason: String = "",
    val appState: String,
    val enabled: Boolean,
    val toolList: List<ConnectorTool> = emptyList(),
    val hasWriteTools: Boolean = false,
    val fingerprint: String = "",
    val lastUsed: String? = null,
    val urlOrCommand: String = "",
    val env: List<String> = emptyList(),
) {
    val isPendingApproval: Boolean
        get() = state == "pending" || reason == "pending_approval"
}

enum class ConnectorBadgeTone {
    GREEN,
    TEAL,
    AMBER,
    GREY,
    OFF,
    PENDING,
}

data class ConnectorChipSpec(
    val label: String,
    val tone: ConnectorBadgeTone,
    val colorHex: Long,
)

data class ConnectorFieldValidation(
    val pattern: String? = null,
    val maxLength: Int? = null,
    val min: Int? = null,
    val max: Int? = null,
    val reserved: List<String> = emptyList(),
    val rules: List<String> = emptyList(),
)

data class ConnectorFieldDescriptor(
    val key: String,
    val type: String,
    val required: Boolean = false,
    val requiredWhen: Map<String, String> = emptyMap(),
    val defaultValue: Any? = null,
    val validation: ConnectorFieldValidation = ConnectorFieldValidation(),
    val allowed: List<String> = emptyList(),
    val isMap: Boolean = false,
    val description: String = "",
)

data class ConnectorFormDescriptor(
    val schemaVersion: Int,
    val fields: List<ConnectorFieldDescriptor>,
)

enum class RenderedControlKind {
    TEXT,
    DROPDOWN,
    INT,
    DURATION,
    STRING_LIST,
    SECRET_TEXT,
    SECRET_MAP,
}

data class EnvEntry(
    val name: String = "",
    val value: String = "",
)

sealed class ConnectorSseEvent {
    data class State(val row: ConnectorRow) : ConnectorSseEvent()
    data class Gone(val name: String) : ConnectorSseEvent()
}

sealed class SubmitErrorUi {
    data class InlineError(
        val message: String,
        val reason: String? = null,
        val offerEditName: String? = null,
    ) : SubmitErrorUi()

    data class SnackbarError(
        val message: String,
    ) : SubmitErrorUi()
}

data class ConnectorFormState(
    val descriptor: ConnectorFormDescriptor,
    val isEditMode: Boolean = false,
    val editTargetName: String? = null,
    val currentTargetSubtitle: String? = null,
    val existingEnvNames: List<String> = emptyList(),
    val scalarValues: Map<String, String> = emptyMap(),
    val listValues: Map<String, List<String>> = emptyMap(),
    val mapValues: Map<String, List<EnvEntry>> = emptyMap(),
) {
    fun withScalar(key: String, value: String): ConnectorFormState {
        val field = descriptor.fields.firstOrNull { it.key == key }
        // In edit mode, the name field is read-only and cannot be changed.
        if (isEditMode && key == "name" && editTargetName != null) {
            return this
        }
        val finalVal = if (field != null && field.type == "int") {
            val parsed = value.trim().toIntOrNull()
            if (parsed != null) {
                clampInt(parsed, field.validation).toString()
            } else {
                value
            }
        } else {
            value
        }
        return copy(scalarValues = scalarValues + (key to finalVal))
    }

    fun withClampedInt(key: String, rawValue: Int): ConnectorFormState {
        val field = descriptor.fields.firstOrNull { it.key == key }
        val clamped = if (field != null) clampInt(rawValue, field.validation) else rawValue
        return copy(scalarValues = scalarValues + (key to clamped.toString()))
    }

    fun addListItem(key: String, value: String = ""): ConnectorFormState {
        val current = listValues[key].orEmpty()
        return copy(listValues = listValues + (key to (current + value)))
    }

    fun updateListItem(key: String, index: Int, value: String): ConnectorFormState {
        val current = listValues[key].orEmpty().toMutableList()
        if (index in current.indices) {
            current[index] = value
        }
        return copy(listValues = listValues + (key to current))
    }

    fun removeListItem(key: String, index: Int): ConnectorFormState {
        val current = listValues[key].orEmpty().toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
        }
        return copy(listValues = listValues + (key to current))
    }

    fun addMapEntry(key: String, name: String = "", value: String = ""): ConnectorFormState {
        val current = mapValues[key].orEmpty()
        return copy(mapValues = mapValues + (key to (current + EnvEntry(name, value))))
    }

    fun updateMapEntry(key: String, index: Int, name: String, value: String): ConnectorFormState {
        val current = mapValues[key].orEmpty().toMutableList()
        if (index in current.indices) {
            current[index] = EnvEntry(name, value)
        }
        return copy(mapValues = mapValues + (key to current))
    }

    fun removeMapEntry(key: String, index: Int): ConnectorFormState {
        val current = mapValues[key].orEmpty().toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
        }
        return copy(mapValues = mapValues + (key to current))
    }

    fun isFieldRequired(field: ConnectorFieldDescriptor): Boolean {
        if (field.required) return true
        if (field.requiredWhen.isEmpty()) return false
        return field.requiredWhen.all { (otherKey, expectedValue) ->
            scalarValues[otherKey] == expectedValue
        }
    }

    fun isFieldRelevantForTransport(field: ConnectorFieldDescriptor): Boolean {
        if (field.requiredWhen.isEmpty()) return true
        return field.requiredWhen.all { (otherKey, expectedValue) ->
            val currentOther = scalarValues[otherKey]
            currentOther == null || currentOther == expectedValue
        }
    }

    fun validateField(field: ConnectorFieldDescriptor): String? {
        val kind = ConnectorsLogic.controlKindForField(field)
        val requiredNow = isFieldRequired(field)

        return when (kind) {
            RenderedControlKind.TEXT -> {
                val value = if (isEditMode && field.key == "name" && editTargetName != null) {
                    editTargetName
                } else {
                    scalarValues[field.key].orEmpty().trim()
                }
                if (requiredNow && value.isEmpty()) {
                    return "${field.key} is required"
                }
                if (value.isNotEmpty()) {
                    val maxLen = field.validation.maxLength
                    if (maxLen != null && value.length > maxLen) {
                        return "Maximum length is $maxLen"
                    }
                    if (!isEditMode && field.validation.reserved.any { it.equals(value, ignoreCase = true) }) {
                        return "that name is reserved"
                    }
                    val pattern = field.validation.pattern
                    if (!pattern.isNullOrBlank()) {
                        val regex = try { Regex(pattern) } catch (_: Exception) { null }
                        if (regex != null && !regex.matches(value)) {
                            return if (field.key == "name") {
                                "name must match lowercase letters/digits/_/-, start with a letter, max 32"
                            } else {
                                "Invalid format for ${field.key}"
                            }
                        }
                    }
                }
                null
            }
            RenderedControlKind.DROPDOWN -> {
                val value = scalarValues[field.key].orEmpty().trim()
                if (requiredNow && value.isEmpty()) {
                    return "${field.key} is required"
                }
                if (value.isNotEmpty() && field.allowed.isNotEmpty() && value !in field.allowed) {
                    return "Must be one of: ${field.allowed.joinToString(", ")}"
                }
                null
            }
            RenderedControlKind.INT -> {
                val raw = scalarValues[field.key].orEmpty().trim()
                if (raw.isEmpty()) {
                    return if (requiredNow) "${field.key} is required" else null
                }
                val parsed = raw.toIntOrNull() ?: return "Must be a whole number"
                val min = field.validation.min
                val max = field.validation.max
                if (min != null && parsed < min) return "Minimum value is $min"
                if (max != null && parsed > max) return "Maximum value is $max"
                null
            }
            RenderedControlKind.DURATION -> {
                val raw = scalarValues[field.key].orEmpty().trim()
                if (raw.isEmpty()) {
                    return if (requiredNow) "${field.key} is required" else null
                }
                if (!ConnectorsLogic.isValidGoDuration(raw)) {
                    return "Enter a valid duration (e.g. 30s, 1m)"
                }
                null
            }
            RenderedControlKind.STRING_LIST -> {
                val list = listValues[field.key].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                if (requiredNow && list.isEmpty()) {
                    return "${field.key} requires at least one entry"
                }
                null
            }
            RenderedControlKind.SECRET_TEXT -> {
                val secret = scalarValues[field.key].orEmpty()
                if (requiredNow && secret.isEmpty()) {
                    return "${field.key} is required"
                }
                null
            }
            RenderedControlKind.SECRET_MAP -> {
                val entries = mapValues[field.key].orEmpty()
                for (entry in entries) {
                    if (entry.value.isNotEmpty() && entry.name.trim().isEmpty()) {
                        return "Environment variable name cannot be empty"
                    }
                }
                null
            }
        }
    }

    fun canSubmit(isOfflineFallback: Boolean = false, isSubmitting: Boolean = false): Boolean {
        if (isOfflineFallback || isSubmitting) return false
        return descriptor.fields.all { field -> validateField(field) == null }
    }

    fun toSubmitPayload(): JSONObject {
        val out = JSONObject()
        val transport = scalarValues["transport"]?.trim()

        for (field in descriptor.fields) {
            when (ConnectorsLogic.controlKindForField(field)) {
                RenderedControlKind.TEXT -> {
                    val value = when {
                        isEditMode && field.key == "name" && editTargetName != null -> editTargetName
                        field.key == "command" && transport == "http" -> ""
                        field.key == "url" && transport == "stdio" -> ""
                        else -> scalarValues[field.key].orEmpty().trim()
                    }
                    out.put(field.key, value)
                }
                RenderedControlKind.DROPDOWN -> {
                    val value = scalarValues[field.key]?.trim()?.takeIf { it.isNotEmpty() }
                        ?: (field.defaultValue as? String)?.takeIf { it.isNotEmpty() }
                        ?: field.allowed.firstOrNull().orEmpty()
                    out.put(field.key, value)
                }
                RenderedControlKind.INT -> {
                    val defaultInt = (field.defaultValue as? Number)?.toInt() ?: 4
                    val parsed = scalarValues[field.key]?.trim()?.toIntOrNull() ?: defaultInt
                    val clamped = clampInt(parsed, field.validation)
                    out.put(field.key, clamped)
                }
                RenderedControlKind.DURATION -> {
                    val defaultDur = (field.defaultValue as? String)?.takeIf { it.isNotEmpty() } ?: "30s"
                    val value = scalarValues[field.key]?.trim()?.takeIf { it.isNotEmpty() } ?: defaultDur
                    out.put(field.key, value)
                }
                RenderedControlKind.STRING_LIST -> {
                    val arr = JSONArray()
                    if (!(field.key == "args" && transport == "http")) {
                        listValues[field.key].orEmpty()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .forEach { arr.put(it) }
                    }
                    out.put(field.key, arr)
                }
                RenderedControlKind.SECRET_TEXT -> {
                    val secret = scalarValues[field.key].orEmpty()
                    // Write-only secret: omit blank secret from body
                    if (secret.isNotEmpty()) {
                        out.put(field.key, secret)
                    }
                }
                RenderedControlKind.SECRET_MAP -> {
                    val entries = mapValues[field.key].orEmpty()
                        .map { EnvEntry(it.name.trim(), it.value) }
                        .filter { it.name.isNotEmpty() && it.value.isNotEmpty() }
                    // Write-only secret map: omit blank/empty secret map from body
                    if (entries.isNotEmpty()) {
                        val mapObj = JSONObject()
                        entries.forEach { mapObj.put(it.name, it.value) }
                        out.put(field.key, mapObj)
                    }
                }
            }
        }

        if (isEditMode && editTargetName != null) {
            out.put("name", editTargetName)
        }
        return out
    }

    companion object {
        fun clampInt(value: Int, validation: ConnectorFieldValidation): Int {
            var res = value
            if (validation.min != null && res < validation.min) res = validation.min
            if (validation.max != null && res > validation.max) res = validation.max
            return res
        }
    }
}

object ConnectorsLogic {

    private val GO_DURATION_REGEX = Regex("^(\\d+(\\.\\d+)?(ns|us|µs|ms|s|m|h))+$")

    fun isValidGoDuration(value: String): Boolean = GO_DURATION_REGEX.matches(value.trim())

    fun controlKindForField(field: ConnectorFieldDescriptor): RenderedControlKind {
        return when (field.type) {
            "string" -> RenderedControlKind.TEXT
            "enum" -> RenderedControlKind.DROPDOWN
            "int" -> RenderedControlKind.INT
            "duration" -> RenderedControlKind.DURATION
            "string_list" -> RenderedControlKind.STRING_LIST
            "secret_string" -> if (field.isMap) RenderedControlKind.SECRET_MAP else RenderedControlKind.SECRET_TEXT
            else -> RenderedControlKind.TEXT
        }
    }

    fun parseRow(json: JSONObject): ConnectorRow {
        val toolArray = json.optJSONArray("tool_list")
        val toolsList = mutableListOf<ConnectorTool>()
        if (toolArray != null) {
            for (i in 0 until toolArray.length()) {
                val tObj = toolArray.optJSONObject(i) ?: continue
                val tName = tObj.optString("name", "").trim()
                if (tName.isNotEmpty()) {
                    toolsList.add(
                        ConnectorTool(
                            name = tName,
                            readOnly = tObj.optBoolean("read_only", false),
                            destructive = tObj.optBoolean("destructive", false),
                            openWorld = tObj.optBoolean("open_world", false),
                        )
                    )
                }
            }
        }

        val envArray = json.optJSONArray("env")
        val envNames = mutableListOf<String>()
        if (envArray != null) {
            for (i in 0 until envArray.length()) {
                val envName = envArray.optString(i, "").trim()
                if (envName.isNotEmpty()) {
                    envNames.add(envName)
                }
            }
        }

        val lastUsedRaw = if (json.has("last_used") && !json.isNull("last_used")) {
            json.optString("last_used", "").trim().takeIf { it.isNotEmpty() && it != "null" }
        } else {
            null
        }

        val reason = if (json.has("reason") && !json.isNull("reason")) {
            json.optString("reason", "").trim()
        } else {
            ""
        }

        return ConnectorRow(
            name = json.optString("name", "").trim(),
            state = json.optString("state", "").trim(),
            tools = json.optInt("tools", 0),
            restarts = json.optInt("restarts", 0),
            reason = reason,
            appState = json.optString("app_state", "not-connected").trim(),
            enabled = json.optBoolean("enabled", false),
            toolList = toolsList,
            hasWriteTools = json.optBoolean("has_write_tools", false),
            fingerprint = if (json.has("fingerprint") && !json.isNull("fingerprint")) json.optString("fingerprint", "") else "",
            lastUsed = lastUsedRaw,
            urlOrCommand = if (json.has("url_or_command") && !json.isNull("url_or_command")) json.optString("url_or_command", "") else "",
            env = envNames,
        )
    }

    fun parseRows(rawJsonArray: String): List<ConnectorRow> {
        if (rawJsonArray.isBlank()) return emptyList()
        val arr = JSONArray(rawJsonArray)
        val result = mutableListOf<ConnectorRow>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val row = parseRow(obj)
            if (row.name.isNotEmpty()) {
                result.add(row)
            }
        }
        return result.sortedBy { it.name }
    }

    fun createPendingRow(name: String, transport: String = "", target: String = ""): ConnectorRow {
        return ConnectorRow(
            name = name.trim(),
            state = "pending",
            tools = 0,
            restarts = 0,
            reason = "pending_approval",
            appState = "not-connected",
            enabled = false,
            urlOrCommand = target,
        )
    }

    fun chipSpecForRow(row: ConnectorRow): ConnectorChipSpec {
        if (row.isPendingApproval) {
            return ConnectorChipSpec(
                label = "Pending approval",
                tone = ConnectorBadgeTone.PENDING,
                colorHex = 0xFFFFA000,
            )
        }
        return when (row.appState) {
            "read+act" -> ConnectorChipSpec("Ready", ConnectorBadgeTone.GREEN, 0xFF4CAF50)
            "read-only" -> ConnectorChipSpec("Read-only", ConnectorBadgeTone.TEAL, 0xFF009688)
            "needs-re-auth" -> ConnectorChipSpec("Needs re-auth", ConnectorBadgeTone.AMBER, 0xFFFFA000)
            "disabled" -> ConnectorChipSpec("Disabled", ConnectorBadgeTone.GREY, 0xFF9E9E9E)
            "not-connected" -> ConnectorChipSpec("Not connected", ConnectorBadgeTone.OFF, 0xFF757575)
            else -> ConnectorChipSpec("Not connected", ConnectorBadgeTone.OFF, 0xFF757575)
        }
    }

    fun canReauthenticate(row: ConnectorRow): Boolean = row.appState == "needs-re-auth"

    fun parseRfc3339Millis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        return try {
            var s = if (trimmed.endsWith("Z") || trimmed.endsWith("z")) {
                trimmed.dropLast(1) + "+00:00"
            } else {
                trimmed
            }
            val m = Regex("^(.*?:\\d{2})(\\.\\d+)?([+-]\\d{2}:\\d{2})$").find(s) ?: return null
            val frac = if (m.groupValues[2].isNotEmpty()) {
                m.groupValues[2].drop(1).padEnd(3, '0').take(3)
            } else {
                "000"
            }
            s = m.groupValues[1] + "." + frac + m.groupValues[3]
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
            fmt.isLenient = false
            fmt.parse(s)?.time
        } catch (_: Exception) {
            null
        }
    }

    fun formatRelativeTime(lastUsedRfc3339: String?, nowMillis: Long): String {
        val ts = parseRfc3339Millis(lastUsedRfc3339) ?: return "never"
        val diffSeconds = ((nowMillis - ts) / 1000L).coerceAtLeast(0L)
        return when {
            diffSeconds < 60L -> "${diffSeconds}s ago"
            diffSeconds < 3600L -> "${diffSeconds / 60L}m ago"
            diffSeconds < 86400L -> "${diffSeconds / 3600L}h ago"
            else -> "${diffSeconds / 86400L}d ago"
        }
    }

    fun formatSecondaryLine(row: ConnectorRow, nowMillis: Long): String {
        val toolsPart = if (row.tools == 1) "1 tool" else "${row.tools} tools"
        val lastUsedPart = "last used: ${formatRelativeTime(row.lastUsed, nowMillis)}"
        return if (row.restarts > 0) {
            "$toolsPart · $lastUsedPart · restarted ${row.restarts}x"
        } else {
            "$toolsPart · $lastUsedPart"
        }
    }

    fun parseFormDescriptor(rawJson: String): ConnectorFormDescriptor {
        val root = JSONObject(rawJson)
        val schemaVersion = root.optInt("schema_version", 1)
        val fieldsArray = root.optJSONArray("fields") ?: JSONArray()
        val fields = mutableListOf<ConnectorFieldDescriptor>()

        for (i in 0 until fieldsArray.length()) {
            val fObj = fieldsArray.optJSONObject(i) ?: continue
            val key = fObj.optString("key", "").trim()
            if (key.isEmpty()) continue
            val type = fObj.optString("type", "string").trim()
            val required = fObj.optBoolean("required", false)

            val reqWhenMap = mutableMapOf<String, String>()
            val reqWhenObj = fObj.optJSONObject("required_when")
            if (reqWhenObj != null) {
                val keys = reqWhenObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    reqWhenMap[k] = reqWhenObj.optString(k, "")
                }
            }

            val defaultVal: Any? = if (!fObj.has("default") || fObj.isNull("default")) {
                null
            } else {
                when (val rawDef = fObj.get("default")) {
                    is JSONArray -> {
                        val list = mutableListOf<String>()
                        for (j in 0 until rawDef.length()) {
                            list.add(rawDef.optString(j, ""))
                        }
                        list
                    }
                    else -> rawDef
                }
            }

            val valObj = fObj.optJSONObject("validation")
            val validation = if (valObj != null) {
                val reservedList = mutableListOf<String>()
                val resArr = valObj.optJSONArray("reserved")
                if (resArr != null) {
                    for (j in 0 until resArr.length()) {
                        val r = resArr.optString(j, "").trim()
                        if (r.isNotEmpty()) reservedList.add(r)
                    }
                }
                val rulesList = mutableListOf<String>()
                val rulesArr = valObj.optJSONArray("rules")
                if (rulesArr != null) {
                    for (j in 0 until rulesArr.length()) {
                        val r = rulesArr.optString(j, "").trim()
                        if (r.isNotEmpty()) rulesList.add(r)
                    }
                }
                ConnectorFieldValidation(
                    pattern = if (valObj.has("pattern") && !valObj.isNull("pattern")) valObj.optString("pattern").takeIf { it.isNotEmpty() } else null,
                    maxLength = if (valObj.has("max_length") && !valObj.isNull("max_length")) valObj.optInt("max_length") else null,
                    min = if (valObj.has("min") && !valObj.isNull("min")) valObj.optInt("min") else null,
                    max = if (valObj.has("max") && !valObj.isNull("max")) valObj.optInt("max") else null,
                    reserved = reservedList,
                    rules = rulesList,
                )
            } else {
                ConnectorFieldValidation()
            }

            val allowedList = mutableListOf<String>()
            val allowedArr = fObj.optJSONArray("allowed")
            if (allowedArr != null) {
                for (j in 0 until allowedArr.length()) {
                    val a = allowedArr.optString(j, "").trim()
                    if (a.isNotEmpty()) allowedList.add(a)
                }
            }

            fields.add(
                ConnectorFieldDescriptor(
                    key = key,
                    type = type,
                    required = required,
                    requiredWhen = reqWhenMap,
                    defaultValue = defaultVal,
                    validation = validation,
                    allowed = allowedList,
                    isMap = fObj.optBoolean("map", false),
                    description = if (fObj.has("description") && !fObj.isNull("description")) fObj.optString("description", "") else "",
                )
            )
        }

        return ConnectorFormDescriptor(
            schemaVersion = schemaVersion,
            fields = fields,
        )
    }

    fun defaultV1FormDescriptor(): ConnectorFormDescriptor = parseFormDescriptor(DEFAULT_V1_FORM_JSON)

    fun inferTransportFromTarget(urlOrCommand: String, allowedTransports: List<String> = listOf("stdio", "http")): String {
        val trimmed = urlOrCommand.trim()
        val isHttp = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        return when {
            isHttp && "http" in allowedTransports -> "http"
            trimmed.isNotEmpty() && !isHttp && "stdio" in allowedTransports -> "stdio"
            else -> allowedTransports.firstOrNull() ?: "stdio"
        }
    }

    fun plausiblyMatchesTransport(transport: String, urlOrCommand: String): Boolean {
        val trimmed = urlOrCommand.trim()
        if (trimmed.isEmpty()) return false
        val isHttpUrl = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        return when (transport) {
            "http" -> isHttpUrl
            "stdio" -> !isHttpUrl
            else -> false
        }
    }

    fun initialFormState(
        descriptor: ConnectorFormDescriptor,
        existingRow: ConnectorRow? = null,
    ): ConnectorFormState {
        val scalars = mutableMapOf<String, String>()
        val lists = mutableMapOf<String, List<String>>()
        val maps = mutableMapOf<String, List<EnvEntry>>()

        val transportField = descriptor.fields.firstOrNull { it.key == "transport" }
        val allowedTransports = transportField?.allowed?.takeIf { it.isNotEmpty() } ?: listOf("stdio", "http")
        val initialTransport = if (existingRow != null) {
            inferTransportFromTarget(existingRow.urlOrCommand, allowedTransports)
        } else {
            (transportField?.defaultValue as? String)?.takeIf { it.isNotEmpty() }
                ?: allowedTransports.firstOrNull()
                ?: "stdio"
        }

        for (field in descriptor.fields) {
            when (controlKindForField(field)) {
                RenderedControlKind.TEXT -> {
                    val value = when {
                        existingRow != null && field.key == "name" -> existingRow.name
                        existingRow != null && field.key == "url" &&
                            plausiblyMatchesTransport("http", existingRow.urlOrCommand) &&
                            initialTransport == "http" -> existingRow.urlOrCommand.trim()
                        existingRow != null && field.key == "command" &&
                            plausiblyMatchesTransport("stdio", existingRow.urlOrCommand) &&
                            initialTransport == "stdio" -> existingRow.urlOrCommand.trim()
                        field.defaultValue is String -> field.defaultValue
                        else -> ""
                    }
                    scalars[field.key] = value
                }
                RenderedControlKind.DROPDOWN -> {
                    val value = if (field.key == "transport") {
                        initialTransport
                    } else {
                        (field.defaultValue as? String)?.takeIf { it.isNotEmpty() }
                            ?: field.allowed.firstOrNull().orEmpty()
                    }
                    scalars[field.key] = value
                }
                RenderedControlKind.INT -> {
                    val defInt = (field.defaultValue as? Number)?.toInt() ?: 4
                    val clamped = ConnectorFormState.clampInt(defInt, field.validation)
                    scalars[field.key] = clamped.toString()
                }
                RenderedControlKind.DURATION -> {
                    val defDur = (field.defaultValue as? String)?.takeIf { it.isNotEmpty() } ?: "30s"
                    scalars[field.key] = defDur
                }
                RenderedControlKind.STRING_LIST -> {
                    val defList = if (existingRow != null) {
                        emptyList()
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        (field.defaultValue as? List<String>) ?: emptyList()
                    }
                    lists[field.key] = defList
                }
                RenderedControlKind.SECRET_TEXT -> {
                    // Never pre-fill secrets
                    scalars[field.key] = ""
                }
                RenderedControlKind.SECRET_MAP -> {
                    // Never pre-fill secret values
                    maps[field.key] = emptyList()
                }
            }
        }

        val subtitle = existingRow?.urlOrCommand?.trim()?.takeIf { it.isNotEmpty() }?.let {
            "Current target: $it"
        }

        return ConnectorFormState(
            descriptor = descriptor,
            isEditMode = existingRow != null,
            editTargetName = existingRow?.name,
            currentTargetSubtitle = subtitle,
            existingEnvNames = existingRow?.env.orEmpty(),
            scalarValues = scalars,
            listValues = lists,
            mapValues = maps,
        )
    }

    fun formatApprovalCardId(cardId: String): String = cardId.trim().take(12)

    fun mapSubmitError(status: Int, body: String, attemptedName: String = ""): SubmitErrorUi {
        val json = try {
            if (body.isNotBlank()) JSONObject(body) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        val error = if (json.has("error") && !json.isNull("error")) json.optString("error", "").trim() else ""
        val reason = if (json.has("reason") && !json.isNull("reason")) json.optString("reason", "").trim() else ""

        return when (status) {
            409 -> when (reason) {
                "duplicate_name" -> SubmitErrorUi.InlineError(
                    message = "Connector already exists; edit it instead.",
                    reason = "duplicate_name",
                    offerEditName = attemptedName.trim().ifEmpty { null },
                )
                "pending_add" -> SubmitErrorUi.InlineError(
                    message = "An add or edit of this connector is already pending approval.",
                    reason = "pending_add",
                )
                else -> SubmitErrorUi.InlineError(
                    message = error.ifEmpty { "Conflict (${reason.ifEmpty { "409" }})" },
                    reason = reason.ifEmpty { null },
                )
            }
            400 -> when {
                reason == "bad_name" -> SubmitErrorUi.InlineError(
                    message = "name must match lowercase letters/digits/_/-, start with a letter, max 32",
                    reason = "bad_name",
                )
                reason == "reserved_name" -> SubmitErrorUi.InlineError(
                    message = "that name is reserved",
                    reason = "reserved_name",
                )
                reason == "unknown_key" || reason == "missing_key" || reason.startsWith("bad_") -> {
                    val detail = if (error.isNotEmpty() && error != reason) ": $error" else ""
                    SubmitErrorUi.InlineError(
                        message = "daemon rejected the config ($reason)$detail",
                        reason = reason,
                    )
                }
                else -> {
                    val tokenPart = if (reason.isNotEmpty()) " ($reason)" else ""
                    val errPart = if (error.isNotEmpty() && error != reason) ": $error" else ""
                    SubmitErrorUi.InlineError(
                        message = "daemon rejected the config$tokenPart$errPart",
                        reason = reason.ifEmpty { null },
                    )
                }
            }
            404 -> SubmitErrorUi.InlineError(
                message = error.ifEmpty { "Connector not found." },
                reason = reason.ifEmpty { "not_found" },
            )
            423, 502, 503, 0 -> SubmitErrorUi.SnackbarError(
                message = "Hearth not reachable / not ready.",
            )
            else -> SubmitErrorUi.SnackbarError(
                message = "Hearth not reachable / not ready (HTTP $status).",
            )
        }
    }

    fun mapReauthError(status: Int, body: String): String {
        val json = try {
            if (body.isNotBlank()) JSONObject(body) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        val error = if (json.has("error") && !json.isNull("error")) json.optString("error", "").trim() else ""
        val reason = if (json.has("reason") && !json.isNull("reason")) json.optString("reason", "").trim() else ""
        return when {
            error.isNotEmpty() && reason.isNotEmpty() && error != reason -> "$error ($reason)"
            error.isNotEmpty() -> error
            reason.isNotEmpty() -> "Still needs re-authentication ($reason)"
            status == 409 -> "Still needs re-authentication."
            else -> "Re-authentication failed (HTTP $status)."
        }
    }

    fun applySseEvent(current: List<ConnectorRow>, event: ConnectorSseEvent): List<ConnectorRow> {
        return when (event) {
            is ConnectorSseEvent.State -> {
                val filtered = current.filterNot { it.name == event.row.name }
                (filtered + event.row).sortedBy { it.name }
            }
            is ConnectorSseEvent.Gone -> {
                current.filterNot { it.name == event.name }
            }
        }
    }

    fun parseSseEvent(eventName: String, data: String): ConnectorSseEvent? {
        val trimmedData = data.trim()
        if (trimmedData.isEmpty()) return null
        return try {
            val json = JSONObject(trimmedData)
            when (eventName.trim()) {
                "connector_state" -> {
                    val row = parseRow(json)
                    if (row.name.isNotEmpty()) ConnectorSseEvent.State(row) else null
                }
                "connector_gone" -> {
                    val name = json.optString("name", "").trim()
                    if (name.isNotEmpty()) ConnectorSseEvent.Gone(name) else null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    const val DEFAULT_V1_FORM_JSON = """
    {
      "schema_version": 1,
      "fields": [
        {
          "key": "name",
          "type": "string",
          "required": true,
          "default": "",
          "validation": {
            "pattern": "^[a-z][a-z0-9_-]{0,31}$",
            "max_length": 32,
            "reserved": ["gmail", "custos", "events"]
          },
          "description": "Unique lowercase identifier for this connector"
        },
        {
          "key": "transport",
          "type": "enum",
          "required": true,
          "default": "stdio",
          "allowed": ["stdio", "http"],
          "description": "MCP transport protocol"
        },
        {
          "key": "command",
          "type": "string",
          "required": false,
          "required_when": {"transport": "stdio"},
          "default": "",
          "description": "Executable command for stdio transport"
        },
        {
          "key": "args",
          "type": "string_list",
          "required": false,
          "default": [],
          "description": "Command-line arguments for stdio transport"
        },
        {
          "key": "url",
          "type": "string",
          "required": false,
          "required_when": {"transport": "http"},
          "default": "",
          "description": "Endpoint URL for HTTP MCP server"
        },
        {
          "key": "env",
          "type": "secret_string",
          "required": false,
          "map": true,
          "description": "Secret environment variables (NAME -> value)"
        },
        {
          "key": "bearer",
          "type": "secret_string",
          "required": false,
          "default": "",
          "description": "Bearer token for HTTP MCP authentication"
        },
        {
          "key": "tool_allowlist",
          "type": "string_list",
          "required": false,
          "default": [],
          "description": "Optional list of allowed tool names"
        },
        {
          "key": "tool_denylist",
          "type": "string_list",
          "required": false,
          "default": [],
          "description": "Optional list of denied tool names"
        },
        {
          "key": "max_concurrent_calls",
          "type": "int",
          "required": false,
          "default": 4,
          "validation": {
            "min": 1,
            "max": 16
          },
          "description": "Maximum concurrent tool invocations (1-16)"
        },
        {
          "key": "list_timeout",
          "type": "duration",
          "required": false,
          "default": "30s",
          "validation": {
            "rules": ["Go duration string e.g. 30s"]
          },
          "description": "Timeout for listing tools (Go duration, e.g. 30s)"
        }
      ]
    }
    """
}
