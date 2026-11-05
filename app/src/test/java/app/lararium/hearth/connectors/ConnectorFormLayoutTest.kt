package app.lararium.hearth.connectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorFormLayoutTest {

    // -----------------------------------------------------------------------
    // 1. ConnectorFormLayout field splitting rules
    // -----------------------------------------------------------------------

    @Test
    fun splitsRequiredAndCleanFieldsIntoSimple_andJargonIntoAdvanced() {
        val cleanRequired = ConnectorFieldDescriptor(
            key = "display_name",
            type = "string",
            required = true,
            description = "Friendly display name",
        )
        val jargonRequiredTimeout = ConnectorFieldDescriptor(
            key = "timeout",
            type = "duration",
            required = true,
        )
        val jargonRequiredHeaders = ConnectorFieldDescriptor(
            key = "headers",
            type = "string",
            required = true,
        )

        val descriptor = ConnectorFormDescriptor(
            schemaVersion = 1,
            fields = listOf(cleanRequired, jargonRequiredTimeout, jargonRequiredHeaders),
        )

        val layout = ConnectorFormLayout.compute(descriptor)
        assertEquals(listOf(cleanRequired), layout.simpleFields)
        assertEquals(listOf(jargonRequiredTimeout, jargonRequiredHeaders), layout.advancedFields)
    }

    @Test
    fun serverAdvancedMarkerWins_evenWhenCleanAndRequired() {
        val serverAdvancedField = ConnectorFieldDescriptor(
            key = "service_tag",
            type = "string",
            required = true,
            description = "advanced: Internal tag for hearth diagnostics",
        )
        val normalField = ConnectorFieldDescriptor(
            key = "name",
            type = "string",
            required = true,
            description = "Connector name",
        )

        val descriptor = ConnectorFormDescriptor(
            schemaVersion = 1,
            fields = listOf(normalField, serverAdvancedField),
        )

        val layout = ConnectorFormLayout.compute(descriptor)
        assertEquals(listOf(normalField), layout.simpleFields)
        assertEquals(listOf(serverAdvancedField), layout.advancedFields)
    }

    @Test
    fun optionalJargonFieldNotRequired_staysAdvanced() {
        val optionalRetries = ConnectorFieldDescriptor(
            key = "retries",
            type = "int",
            required = false,
            defaultValue = 3,
            description = "Number of retries",
        )
        val optionalPollInterval = ConnectorFieldDescriptor(
            key = "poll_interval_seconds",
            type = "int",
            required = false,
        )

        val descriptor = ConnectorFormDescriptor(
            schemaVersion = 1,
            fields = listOf(optionalRetries, optionalPollInterval),
        )

        val layout = ConnectorFormLayout.compute(descriptor)
        assertTrue(layout.simpleFields.isEmpty())
        assertEquals(2, layout.advancedFields.size)
    }

    @Test
    fun descriptorWithUnknownNonJargonRequiredField_isSimple() {
        val unknownField = ConnectorFieldDescriptor(
            key = "cluster_region",
            type = "string",
            required = true,
            description = "Cloud cluster region",
        )

        val descriptor = ConnectorFormDescriptor(
            schemaVersion = 1,
            fields = listOf(unknownField),
        )

        val layout = ConnectorFormLayout.compute(descriptor)
        assertEquals(listOf(unknownField), layout.simpleFields)
        assertTrue(layout.advancedFields.isEmpty())
    }

    @Test
    fun mapAndJsonObjectTypes_areAlwaysAdvanced() {
        val mapField = ConnectorFieldDescriptor(
            key = "metadata",
            type = "string",
            required = true,
            isMap = true,
        )
        val jsonField = ConnectorFieldDescriptor(
            key = "config_blob",
            type = "json",
            required = true,
        )

        val descriptor = ConnectorFormDescriptor(
            schemaVersion = 1,
            fields = listOf(mapField, jsonField),
        )

        val layout = ConnectorFormLayout.compute(descriptor)
        assertTrue(layout.simpleFields.isEmpty())
        assertEquals(2, layout.advancedFields.size)
    }

    @Test
    fun transportIsAlwaysAdvanced_andHiddenFromSimple() {
        val transportField = ConnectorFieldDescriptor(
            key = "transport",
            type = "enum",
            required = true,
            allowed = listOf("stdio", "http"),
        )
        val descriptor = ConnectorFormDescriptor(
            schemaVersion = 1,
            fields = listOf(transportField),
        )
        val layout = ConnectorFormLayout.compute(descriptor)
        assertTrue(layout.simpleFields.isEmpty())
        assertEquals(listOf(transportField), layout.advancedFields)
    }

    // -----------------------------------------------------------------------
    // 2. Transport auto-infer
    // -----------------------------------------------------------------------

    @Test
    fun transportAutoInfer_typingHttpTargetSetsHttp() {
        val transport = ConnectorFormLayout.autoInferTransport("https://x", listOf("stdio", "http"))
        assertEquals("http", transport)

        val descriptor = ConnectorsLogic.defaultV1FormDescriptor()
        val state = ConnectorsLogic.initialFormState(descriptor)
        val updated = ConnectorFormLayout.applyTargetInput(state, "https://x")
        assertEquals("http", updated.scalarValues["transport"])
        assertEquals("https://x", updated.scalarValues["url"])
        assertEquals("", updated.scalarValues["command"])
    }

    @Test
    fun transportAutoInfer_typingCommandSetsStdio() {
        val transport = ConnectorFormLayout.autoInferTransport("npx -y foo", listOf("stdio", "http"))
        assertEquals("stdio", transport)

        val descriptor = ConnectorsLogic.defaultV1FormDescriptor()
        val state = ConnectorsLogic.initialFormState(descriptor)
        val updated = ConnectorFormLayout.applyTargetInput(state, "npx -y foo")
        assertEquals("stdio", updated.scalarValues["transport"])
        assertEquals("npx -y foo", updated.scalarValues["command"])
        assertEquals("", updated.scalarValues["url"])
    }

    @Test
    fun transportAutoInfer_ifServerAllowsOnlyStdio_staysStdio() {
        val transport = ConnectorFormLayout.autoInferTransport("https://x", listOf("stdio"))
        assertEquals("stdio", transport)

        val descriptor = ConnectorsLogic.defaultV1FormDescriptor().let { desc ->
            desc.copy(
                fields = desc.fields.map { f ->
                    if (f.key == "transport") f.copy(allowed = listOf("stdio")) else f
                }
            )
        }
        val state = ConnectorsLogic.initialFormState(descriptor)
        val updated = ConnectorFormLayout.applyTargetInput(state, "https://x")
        assertEquals("stdio", updated.scalarValues["transport"])
    }

    // -----------------------------------------------------------------------
    // 3. Friendly error mapping + Details expander keeps raw message
    // -----------------------------------------------------------------------

    @Test
    fun friendlyErrorMapping_emptyRequiredField() {
        val field = ConnectorFieldDescriptor(
            key = "name",
            type = "string",
            required = true,
        )
        val raw = "name is required"
        val friendly = ConnectorFormLayout.mapFriendlyError(field, raw)

        assertEquals("Please fill this in first", friendly.headline)
        assertEquals("name is required", friendly.rawDetail)
        assertNotNull(friendly.rawDetail)
    }

    @Test
    fun friendlyErrorMapping_urlShapedFieldFailingPattern() {
        val field = ConnectorFieldDescriptor(
            key = "url",
            type = "string",
            required = true,
            validation = ConnectorFieldValidation(pattern = "^https?://.*"),
        )
        val raw = "Invalid format for url"
        val friendly = ConnectorFormLayout.mapFriendlyError(field, raw)

        assertEquals(
            "That doesn't look like a web address — should it start with http:// or https://?",
            friendly.headline,
        )
        assertEquals("Invalid format for url", friendly.rawDetail)
        assertNotNull(friendly.rawDetail)
    }

    @Test
    fun friendlyErrorMapping_jsonTemplateInvalid() {
        val field = ConnectorFieldDescriptor(
            key = "payload_template",
            type = "string",
            required = false,
        )
        val raw = "Invalid JSON: syntax error at char 12"
        val friendly = ConnectorFormLayout.mapFriendlyError(field, raw)

        assertEquals(
            "That's not quite valid JSON — the details button shows an example",
            friendly.headline,
        )
        assertEquals("Invalid JSON: syntax error at char 12", friendly.rawDetail)
        assertNotNull(friendly.rawDetail)
    }

    // -----------------------------------------------------------------------
    // 4. Submit-blocking with hidden required advanced field
    // -----------------------------------------------------------------------

    @Test
    fun submitBlockingWithHiddenRequiredAdvancedField_returnsNudgeText() {
        val nameField = ConnectorFieldDescriptor(
            key = "name",
            type = "string",
            required = true,
        )
        val hiddenReqJargon = ConnectorFieldDescriptor(
            key = "auth_header",
            type = "string",
            required = true,
        )
        val descriptor = ConnectorFormDescriptor(
            schemaVersion = 1,
            fields = listOf(nameField, hiddenReqJargon),
        )

        var state = ConnectorsLogic.initialFormState(descriptor)
        state = state.withScalar("name", "door_cam")
        // auth_header is required and blank => invalid

        val layout = ConnectorFormLayout.compute(descriptor, state.scalarValues)

        // When collapsed: returns nudge text
        val nudgeCollapsed = ConnectorFormLayout.getHiddenAdvancedBlockingNudge(
            layout = layout,
            formState = state,
            isAdvancedExpanded = false,
        )
        assertNotNull(nudgeCollapsed)
        assertTrue(nudgeCollapsed!!.contains("Advanced options"))
        assertTrue(nudgeCollapsed.contains("Auth header"))

        // When expanded: returns null because inline errors are directly visible
        val nudgeExpanded = ConnectorFormLayout.getHiddenAdvancedBlockingNudge(
            layout = layout,
            formState = state,
            isAdvancedExpanded = true,
        )
        assertNull(nudgeExpanded)

        // Once filled in: returns null even when collapsed
        val fixedState = state.withScalar("auth_header", "Bearer secret")
        val fixedNudge = ConnectorFormLayout.getHiddenAdvancedBlockingNudge(
            layout = layout,
            formState = fixedState,
            isAdvancedExpanded = false,
        )
        assertNull(fixedNudge)
    }

    // -----------------------------------------------------------------------
    // 5. Friendly copy fallbacks & humanization
    // -----------------------------------------------------------------------

    @Test
    fun friendlyCopyFallbacks_usedOnlyWhenDescriptorDescriptionIsEmpty() {
        val emptyDescName = ConnectorFieldDescriptor(key = "name", type = "string", description = "")
        assertEquals(
            "What should we call it? Anything you'll recognize, like 'Front door camera'.",
            ConnectorFormLayout.getFieldDescription(emptyDescName),
        )

        val customDescName = ConnectorFieldDescriptor(key = "name", type = "string", description = "Server custom hint")
        assertEquals(
            "Server custom hint",
            ConnectorFormLayout.getFieldDescription(customDescName),
        )

        val advancedDesc = ConnectorFieldDescriptor(key = "tls", type = "string", description = "advanced: Certificate authority bundle")
        assertEquals(
            "Certificate authority bundle",
            ConnectorFormLayout.getFieldDescription(advancedDesc),
        )
    }

    @Test
    fun humanizeKey_convertsSnakeCaseToCapitalizedWordsWithoutSnakeCase() {
        assertEquals("Poll interval seconds", ConnectorFormLayout.humanizeKey("poll_interval_seconds"))
        assertEquals("Max concurrent calls", ConnectorFormLayout.humanizeKey("max_concurrent_calls"))
        assertEquals("URL", ConnectorFormLayout.humanizeKey("url"))
        assertEquals("Base URL", ConnectorFormLayout.humanizeKey("base_url"))
        assertEquals("TLS", ConnectorFormLayout.humanizeKey("tls"))
        assertEquals("API key", ConnectorFormLayout.humanizeKey("api_key"))
        assertEquals("Name", ConnectorFormLayout.humanizeKey("name"))
        assertFalse(ConnectorFormLayout.humanizeKey("max_concurrent_calls").contains("_"))
    }
}
