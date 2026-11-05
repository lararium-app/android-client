package app.lararium.hearth.connectors

import java.util.Locale

data class FriendlyError(
    val headline: String,
    val rawDetail: String? = null,
)

data class ConnectorFormLayout(
    val simpleFields: List<ConnectorFieldDescriptor>,
    val advancedFields: List<ConnectorFieldDescriptor>,
) {
    companion object {
        const val ADVANCED_PREFIX = "advanced:"
        const val ADVANCED_NUDGE_TEXT = "Please check the required settings in Advanced options to continue."

        val JARGON_KEYS: Set<String> = setOf(
            "headers",
            "payload_template",
            "template",
            "tls",
            "ca_cert",
            "auth_header",
            "timeout",
            "retries",
            "interval",
            "poll_interval_seconds",
            "schema_version",
            "command",
            "args",
            "env",
        )

        val MAP_OR_JSON_TYPES: Set<String> = setOf("json", "object", "map")

        val FRIENDLY_COPY_FALLBACKS: Map<String, String> = mapOf(
            "name" to "What should we call it? Anything you'll recognize, like 'Front door camera'.",
            "url" to "Where does it live? Paste a web address, like https://… .",
            "base_url" to "Where does it live? Paste a web address, like https://… .",
            "command" to "Or run something on this phone — type the command.",
            "token" to "Your access key. It's stored encrypted on this phone and never leaves it.",
            "secret" to "Your access key. It's stored encrypted on this phone and never leaves it.",
            "api_key" to "Your access key. It's stored encrypted on this phone and never leaves it.",
            "key" to "Your access key. It's stored encrypted on this phone and never leaves it.",
        )

        fun isFieldRequired(
            field: ConnectorFieldDescriptor,
            currentValues: Map<String, String> = emptyMap(),
            descriptor: ConnectorFormDescriptor? = null,
        ): Boolean {
            if (field.required) return true
            if (field.requiredWhen.isEmpty()) return false
            return field.requiredWhen.all { (otherKey, expectedVal) ->
                val actual = currentValues[otherKey]
                    ?: (descriptor?.fields?.firstOrNull { it.key == otherKey }?.defaultValue as? String)
                actual == expectedVal
            }
        }

        fun isServerAdvanced(field: ConnectorFieldDescriptor): Boolean {
            return field.description.trimStart().startsWith(ADVANCED_PREFIX, ignoreCase = true)
        }

        fun isSimpleField(
            field: ConnectorFieldDescriptor,
            currentValues: Map<String, String> = emptyMap(),
            descriptor: ConnectorFormDescriptor? = null,
        ): Boolean {
            // Rule 2 in order: Server "advanced:" marker forces advanced regardless
            if (isServerAdvanced(field)) return false

            // Transport picker is hidden entirely from Simple
            if (field.key.equals("transport", ignoreCase = true)) return false

            // Rule 1 in order: Simple iff:
            // (a) required
            val required = isFieldRequired(field, currentValues, descriptor)
            if (!required) return false

            // (b) key is NOT in jargon set
            if (field.key.lowercase(Locale.US) in JARGON_KEYS) return false

            // (c) type is NOT a map/JSON-object type
            if (field.isMap) return false
            if (field.type.lowercase(Locale.US) in MAP_OR_JSON_TYPES) return false

            return true
        }

        fun compute(
            descriptor: ConnectorFormDescriptor,
            currentValues: Map<String, String> = emptyMap(),
        ): ConnectorFormLayout {
            val simple = mutableListOf<ConnectorFieldDescriptor>()
            val advanced = mutableListOf<ConnectorFieldDescriptor>()

            for (field in descriptor.fields) {
                if (isSimpleField(field, currentValues, descriptor)) {
                    simple.add(field)
                } else {
                    advanced.add(field)
                }
            }

            return ConnectorFormLayout(
                simpleFields = simple,
                advancedFields = advanced,
            )
        }

        fun humanizeKey(key: String): String {
            if (key.isBlank()) return ""
            val lower = key.lowercase(Locale.US)
            val specialLabels = mapOf(
                "url" to "URL",
                "base_url" to "Base URL",
                "tls" to "TLS",
                "ca_cert" to "CA certificate",
                "api_key" to "API key",
            )
            specialLabels[lower]?.let { return it }

            val words = key.split('_').filter { it.isNotEmpty() }
            if (words.isEmpty()) return key
            return words.mapIndexed { index, word ->
                if (index == 0) {
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                } else {
                    word.lowercase(Locale.US)
                }
            }.joinToString(" ")
        }

        fun getFieldDescription(field: ConnectorFieldDescriptor): String {
            val raw = field.description.trim()
            if (raw.isNotEmpty()) {
                return if (raw.startsWith(ADVANCED_PREFIX, ignoreCase = true)) {
                    raw.substring(ADVANCED_PREFIX.length).trim()
                } else {
                    raw
                }
            }
            return FRIENDLY_COPY_FALLBACKS[field.key.lowercase(Locale.US)] ?: ""
        }

        fun mapFriendlyError(field: ConnectorFieldDescriptor, rawError: String): FriendlyError {
            val lowerKey = field.key.lowercase(Locale.US)
            val lowerRaw = rawError.lowercase(Locale.US)

            // 1. Empty-required
            if (lowerRaw.contains("is required") ||
                lowerRaw.contains("requires at least one entry") ||
                lowerRaw == "required" ||
                lowerRaw.endsWith("is required")
            ) {
                return FriendlyError(
                    headline = "Please fill this in first",
                    rawDetail = rawError,
                )
            }

            // 2. URL-shaped fields failing pattern
            val isUrlField = lowerKey == "url" || lowerKey == "base_url" || lowerKey.endsWith("_url")
            val isPatternErr = lowerRaw.contains("invalid format") ||
                lowerRaw.contains("pattern") ||
                lowerRaw.contains("look like a web address")
            if (isUrlField && isPatternErr) {
                return FriendlyError(
                    headline = "That doesn't look like a web address — should it start with http:// or https://?",
                    rawDetail = rawError,
                )
            }

            // 3. JSON template invalid
            val isTemplateField = lowerKey in setOf("template", "payload_template") ||
                field.type.lowercase(Locale.US) in setOf("json", "template", "object")
            val isJsonErr = lowerRaw.contains("json") ||
                lowerRaw.contains("template") ||
                lowerRaw.contains("syntax")
            if (isTemplateField && isJsonErr) {
                return FriendlyError(
                    headline = "That's not quite valid JSON — the details button shows an example",
                    rawDetail = rawError,
                )
            }

            // Other validation cases
            if (lowerRaw.contains("maximum length") || lowerRaw.contains("max_length")) {
                val maxLen = field.validation.maxLength
                val hint = if (maxLen != null) " (maximum $maxLen characters)" else ""
                return FriendlyError(
                    headline = "Please keep this a bit shorter$hint.",
                    rawDetail = rawError,
                )
            }

            if (lowerRaw.contains("reserved")) {
                return FriendlyError(
                    headline = "That name is already reserved — please choose another name.",
                    rawDetail = rawError,
                )
            }

            if (lowerKey == "name" && (lowerRaw.contains("letters/digits") || lowerRaw.contains("bad_name"))) {
                return FriendlyError(
                    headline = "Use lowercase letters, numbers, hyphens, or underscores, starting with a letter.",
                    rawDetail = rawError,
                )
            }

            return FriendlyError(
                headline = rawError,
                rawDetail = rawError,
            )
        }

        fun getHiddenAdvancedBlockingNudge(
            layout: ConnectorFormLayout,
            formState: ConnectorFormState,
            isAdvancedExpanded: Boolean,
        ): String? {
            if (isAdvancedExpanded) return null
            val invalidAdvancedField = layout.advancedFields.firstOrNull { field ->
                formState.isFieldRequired(field) && formState.validateField(field) != null
            } ?: return null

            val friendlyName = humanizeKey(invalidAdvancedField.key)
            return "$ADVANCED_NUDGE_TEXT ($friendlyName is required)"
        }

        fun autoInferTransport(
            target: String,
            allowedTransports: List<String> = listOf("stdio", "http"),
        ): String {
            return ConnectorsLogic.inferTransportFromTarget(target, allowedTransports)
        }

        fun applyTargetInput(
            state: ConnectorFormState,
            targetInput: String,
        ): ConnectorFormState {
            val transportField = state.descriptor.fields.firstOrNull { it.key == "transport" }
            val allowedTransports = transportField?.allowed?.takeIf { it.isNotEmpty() } ?: listOf("stdio", "http")
            val inferred = autoInferTransport(targetInput, allowedTransports)
            var next = state.withScalar("transport", inferred)
            next = if (inferred == "http") {
                next.withScalar("url", targetInput).withScalar("command", "")
            } else {
                next.withScalar("command", targetInput).withScalar("url", "")
            }
            return next
        }
    }
}
