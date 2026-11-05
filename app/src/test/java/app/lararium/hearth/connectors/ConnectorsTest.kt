package app.lararium.hearth.connectors

import app.lararium.hearth.Endpoint
import app.lararium.hearth.HttpResponse
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ConnectorsTest {

    // -----------------------------------------------------------------------
    // 1. Row JSON parsing (all fields incl. last_used null, pending row, tool flags)
    // -----------------------------------------------------------------------

    @Test
    fun parseRows_parsesAllFieldsIncludingLastUsedNullPendingAndToolFlags() {
        val raw = """
        [
          {
            "name": "z-docs",
            "state": "ready",
            "tools": 2,
            "restarts": 3,
            "reason": "",
            "app_state": "read+act",
            "enabled": true,
            "tool_list": [
              {"name": "read_doc", "read_only": true, "destructive": false, "open_world": false},
              {"name": "delete_doc", "read_only": false, "destructive": true, "open_world": true}
            ],
            "has_write_tools": true,
            "fingerprint": "a1b2c3d4",
            "last_used": "2026-11-04T10:00:00Z",
            "url_or_command": "https://mcp.internal.local:8443/sse",
            "env": ["API_TOKEN", "WORKSPACE_ID"]
          },
          {
            "name": "a-pending",
            "state": "pending",
            "tools": 0,
            "restarts": 0,
            "reason": "pending_approval",
            "app_state": "not-connected",
            "enabled": false,
            "tool_list": [],
            "has_write_tools": false,
            "fingerprint": "",
            "last_used": null,
            "url_or_command": "/usr/local/bin/mcp-stdio",
            "env": []
          }
        ]
        """.trimIndent()

        val rows = ConnectorsLogic.parseRows(raw)
        assertEquals(2, rows.size)

        // Sorted by name: a-pending comes first, z-docs second
        val pending = rows[0]
        assertEquals("a-pending", pending.name)
        assertEquals("pending", pending.state)
        assertEquals("pending_approval", pending.reason)
        assertEquals("not-connected", pending.appState)
        assertFalse(pending.enabled)
        assertTrue(pending.isPendingApproval)
        assertNull(pending.lastUsed)
        assertEquals("/usr/local/bin/mcp-stdio", pending.urlOrCommand)
        assertTrue(pending.toolList.isEmpty())
        assertTrue(pending.env.isEmpty())

        val pendingChip = ConnectorsLogic.chipSpecForRow(pending)
        assertEquals("Pending approval", pendingChip.label)
        assertEquals(ConnectorBadgeTone.PENDING, pendingChip.tone)

        val ready = rows[1]
        assertEquals("z-docs", ready.name)
        assertEquals("ready", ready.state)
        assertEquals(2, ready.tools)
        assertEquals(3, ready.restarts)
        assertEquals("read+act", ready.appState)
        assertTrue(ready.enabled)
        assertFalse(ready.isPendingApproval)
        assertTrue(ready.hasWriteTools)
        assertEquals("a1b2c3d4", ready.fingerprint)
        assertEquals("2026-11-04T10:00:00Z", ready.lastUsed)
        assertEquals("https://mcp.internal.local:8443/sse", ready.urlOrCommand)
        assertEquals(listOf("API_TOKEN", "WORKSPACE_ID"), ready.env)
        assertEquals(2, ready.toolList.size)

        val t0 = ready.toolList[0]
        assertEquals("read_doc", t0.name)
        assertTrue(t0.readOnly)
        assertFalse(t0.destructive)
        assertFalse(t0.openWorld)

        val t1 = ready.toolList[1]
        assertEquals("delete_doc", t1.name)
        assertFalse(t1.readOnly)
        assertTrue(t1.destructive)
        assertTrue(t1.openWorld)

        val readyChip = ConnectorsLogic.chipSpecForRow(ready)
        assertEquals("Ready", readyChip.label)
        assertEquals(ConnectorBadgeTone.GREEN, readyChip.tone)

        // Secondary line formatting
        val usedMillis = ConnectorsLogic.parseRfc3339Millis("2026-11-04T10:00:00Z")!!
        val nowTwoHoursLater = usedMillis + 2 * 3600_000L
        val secondaryReady = ConnectorsLogic.formatSecondaryLine(ready, nowTwoHoursLater)
        assertTrue(secondaryReady.contains("2 tools"))
        assertTrue(secondaryReady.contains("2h ago"))
        assertTrue(secondaryReady.contains("restarted 3x"))

        val secondaryPending = ConnectorsLogic.formatSecondaryLine(pending, nowTwoHoursLater)
        assertTrue(secondaryPending.contains("0 tools"))
        assertTrue(secondaryPending.contains("never"))
        assertFalse(secondaryPending.contains("restarted"))
    }

    @Test
    fun chipSpecsAndReauthVisibility_matchAllAppStates() {
        val base = ConnectorRow(
            name = "test",
            state = "ready",
            tools = 1,
            restarts = 0,
            appState = "read-only",
            enabled = true,
        )
        assertEquals("Read-only", ConnectorsLogic.chipSpecForRow(base).label)
        assertFalse(ConnectorsLogic.canReauthenticate(base))

        val reauthRow = base.copy(appState = "needs-re-auth")
        assertEquals("Needs re-auth", ConnectorsLogic.chipSpecForRow(reauthRow).label)
        assertTrue(ConnectorsLogic.canReauthenticate(reauthRow))

        val disabledRow = base.copy(appState = "disabled", enabled = false)
        assertEquals("Disabled", ConnectorsLogic.chipSpecForRow(disabledRow).label)

        val disconnectedRow = base.copy(appState = "not-connected")
        assertEquals("Not connected", ConnectorsLogic.chipSpecForRow(disconnectedRow).label)
    }

    // -----------------------------------------------------------------------
    // 2. Form descriptor parsing + generic renderer logic
    // -----------------------------------------------------------------------

    @Test
    fun formDescriptorAndGenericState_requiredWhenMinMaxClampMapDefaultAndUnknownType() {
        val descriptorJson = JSONObject(ConnectorsLogic.DEFAULT_V1_FORM_JSON).apply {
            optJSONArray("fields")?.put(
                JSONObject().apply {
                    put("key", "future_custom_field")
                    put("type", "quantum_channel")
                    put("required", false)
                    put("default", "auto-tune")
                    put("description", "Future unknown type")
                }
            )
        }.toString()

        val descriptor = ConnectorsLogic.parseFormDescriptor(descriptorJson)
        assertEquals(1, descriptor.schemaVersion)
        assertEquals(12, descriptor.fields.size)

        val unknownField = descriptor.fields.last()
        assertEquals("future_custom_field", unknownField.key)
        assertEquals(
            "Unknown field types must fall back to plain TEXT input",
            RenderedControlKind.TEXT,
            ConnectorsLogic.controlKindForField(unknownField),
        )

        var state = ConnectorsLogic.initialFormState(descriptor)

        // Defaults applied
        assertEquals("stdio", state.scalarValues["transport"])
        assertEquals("4", state.scalarValues["max_concurrent_calls"])
        assertEquals("30s", state.scalarValues["list_timeout"])
        assertEquals("auto-tune", state.scalarValues["future_custom_field"])

        val commandField = descriptor.fields.first { it.key == "command" }
        val urlField = descriptor.fields.first { it.key == "url" }

        // required_when gating: transport=stdio => command required, url not required
        assertTrue(state.isFieldRequired(commandField))
        assertFalse(state.isFieldRequired(urlField))
        assertFalse("Cannot submit while name and command are blank", state.canSubmit())

        state = state.withScalar("name", "my_stdio_tool")
            .withScalar("command", "/usr/bin/mcp-tool")
        assertTrue("Should be submittable when name and stdio command are valid", state.canSubmit())

        // Switch transport to http => url becomes required, command no longer required
        state = state.withScalar("transport", "http")
        assertFalse(state.isFieldRequired(commandField))
        assertTrue(state.isFieldRequired(urlField))
        assertFalse("Cannot submit when transport=http and url is blank", state.canSubmit())

        state = state.withScalar("url", "https://mcp.local/v1")
        assertTrue("Should be submittable once url is provided", state.canSubmit())

        // Int min/max clamping (1..16)
        state = state.withClampedInt("max_concurrent_calls", 99)
        assertEquals("16", state.scalarValues["max_concurrent_calls"])
        state = state.withClampedInt("max_concurrent_calls", 0)
        assertEquals("1", state.scalarValues["max_concurrent_calls"])
        state = state.withScalar("max_concurrent_calls", "25")
        assertEquals("16", state.scalarValues["max_concurrent_calls"])

        // Map field add / update / remove
        state = state.addMapEntry("env", "TOKEN_A", "sec-1")
            .addMapEntry("env", "TOKEN_B", "sec-2")
        assertEquals(2, state.mapValues["env"]?.size)
        state = state.updateMapEntry("env", 0, "TOKEN_A_RENAMED", "sec-1-updated")
        assertEquals("TOKEN_A_RENAMED", state.mapValues["env"]!![0].name)
        assertEquals("sec-1-updated", state.mapValues["env"]!![0].value)
        state = state.removeMapEntry("env", 0)
        assertEquals(1, state.mapValues["env"]?.size)
        assertEquals("TOKEN_B", state.mapValues["env"]!![0].name)

        // String list add / update / remove
        state = state.addListItem("args", "--flag")
            .addListItem("args", "--verbose")
            .removeListItem("args", 0)
        assertEquals(listOf("--verbose"), state.listValues["args"])

        // Reserved name and pattern validation
        val reservedState = state.withScalar("name", "custos")
        assertEquals("that name is reserved", reservedState.validateField(descriptor.fields.first { it.key == "name" }))
        assertFalse(reservedState.canSubmit())

        val badPatternState = state.withScalar("name", "123-invalid")
        assertEquals(
            "name must match lowercase letters/digits/_/-, start with a letter, max 32",
            badPatternState.validateField(descriptor.fields.first { it.key == "name" }),
        )
        assertFalse(badPatternState.canSubmit())
    }

    // -----------------------------------------------------------------------
    // 3. Submit payload builder: stdio vs http, blank secrets omitted, PATCH name
    // -----------------------------------------------------------------------

    @Test
    fun submitPayloadBuilder_stdioVsHttp_omitsBlankSecrets_andForcesPatchName() {
        val descriptor = ConnectorsLogic.defaultV1FormDescriptor()

        // A) stdio connector with blank secrets -> bearer and env omitted, url is ""
        val stdioState = ConnectorsLogic.initialFormState(descriptor)
            .withScalar("name", "local_git")
            .withScalar("transport", "stdio")
            .withScalar("command", "/usr/bin/mcp-git")
            .withScalar("url", "https://should-be-cleared.local")
            .addListItem("args", "--repo")
            .addListItem("args", "/tmp/repo")
            .withScalar("bearer", "") // blank secret
            .addMapEntry("env", "EMPTY_SECRET", "") // blank secret value

        val stdioJson = stdioState.toSubmitPayload()
        assertEquals("local_git", stdioJson.getString("name"))
        assertEquals("stdio", stdioJson.getString("transport"))
        assertEquals("/usr/bin/mcp-git", stdioJson.getString("command"))
        assertEquals("", stdioJson.getString("url"))
        assertEquals(2, stdioJson.getJSONArray("args").length())
        assertEquals("--repo", stdioJson.getJSONArray("args").getString(0))
        assertEquals(4, stdioJson.getInt("max_concurrent_calls"))
        assertEquals("30s", stdioJson.getString("list_timeout"))
        assertFalse("Blank bearer must be omitted", stdioJson.has("bearer"))
        assertFalse("Blank env map must be omitted", stdioJson.has("env"))

        // B) http connector with secrets provided -> bearer and env included, command="" and args=[]
        val httpState = ConnectorsLogic.initialFormState(descriptor)
            .withScalar("name", "remote_api")
            .withScalar("transport", "http")
            .withScalar("url", "https://api.internal.local/mcp")
            .withScalar("command", "/usr/bin/ignored")
            .addListItem("args", "--ignored")
            .withScalar("bearer", "top-secret-bearer")
            .addMapEntry("env", "CLIENT_SECRET", "top-secret-env")

        val httpJson = httpState.toSubmitPayload()
        assertEquals("remote_api", httpJson.getString("name"))
        assertEquals("http", httpJson.getString("transport"))
        assertEquals("https://api.internal.local/mcp", httpJson.getString("url"))
        assertEquals("", httpJson.getString("command"))
        assertEquals(0, httpJson.getJSONArray("args").length())
        assertEquals("top-secret-bearer", httpJson.getString("bearer"))
        assertEquals("top-secret-env", httpJson.getJSONObject("env").getString("CLIENT_SECRET"))

        // C) Edit mode (PATCH): pre-fills from existing row, keeps name equal to path name
        val existingHttpRow = ConnectorRow(
            name = "Immutable_Path_Name".lowercase(),
            state = "ready",
            tools = 3,
            restarts = 0,
            appState = "read+act",
            enabled = true,
            urlOrCommand = "https://existing.internal.local/mcp",
            env = listOf("EXISTING_KEY"),
        )
        val editState = ConnectorsLogic.initialFormState(descriptor, existingHttpRow)
            .withScalar("name", "attempted_tamper_name") // must be ignored in edit mode
        assertEquals("Current target: https://existing.internal.local/mcp", editState.currentTargetSubtitle)
        assertEquals("http", editState.scalarValues["transport"])
        assertEquals("https://existing.internal.local/mcp", editState.scalarValues["url"])
        assertEquals("", editState.scalarValues["bearer"])
        assertTrue(editState.mapValues["env"].orEmpty().isEmpty())

        val patchPayload = editState.toSubmitPayload()
        assertEquals(existingHttpRow.name, patchPayload.getString("name"))
        assertFalse("Blank bearer in edit mode must be omitted", patchPayload.has("bearer"))
        assertFalse("Blank env in edit mode must be omitted", patchPayload.has("env"))
    }

    // -----------------------------------------------------------------------
    // 4. 400 / 409 / 502 / 503 error-reason mapping to UI strings
    // -----------------------------------------------------------------------

    @Test
    fun errorReasonMapping_maps400And409AndUnavailableCodes() {
        val badName = ConnectorsLogic.mapSubmitError(
            400,
            """{"error":"invalid connector name","reason":"bad_name"}""",
            "Bad!",
        ) as SubmitErrorUi.InlineError
        assertEquals("name must match lowercase letters/digits/_/-, start with a letter, max 32", badName.message)
        assertEquals("bad_name", badName.reason)
        assertNull(badName.offerEditName)

        val reserved = ConnectorsLogic.mapSubmitError(
            400,
            """{"error":"reserved","reason":"reserved_name"}""",
            "gmail",
        ) as SubmitErrorUi.InlineError
        assertEquals("that name is reserved", reserved.message)
        assertEquals("reserved_name", reserved.reason)

        val unknownKey = ConnectorsLogic.mapSubmitError(
            400,
            """{"error":"unexpected field foo","reason":"unknown_key"}""",
            "my_conn",
        ) as SubmitErrorUi.InlineError
        assertTrue(unknownKey.message.contains("daemon rejected the config"))
        assertTrue(unknownKey.message.contains("unknown_key"))

        val badUrl = ConnectorsLogic.mapSubmitError(
            400,
            """{"error":"url required","reason":"bad_url"}""",
            "my_conn",
        ) as SubmitErrorUi.InlineError
        assertTrue(badUrl.message.contains("daemon rejected the config"))
        assertTrue(badUrl.message.contains("bad_url"))

        val duplicate = ConnectorsLogic.mapSubmitError(
            409,
            """{"error":"connector already exists","reason":"duplicate_name"}""",
            "existing_conn",
        ) as SubmitErrorUi.InlineError
        assertTrue(duplicate.message.lowercase().contains("already exists"))
        assertEquals("duplicate_name", duplicate.reason)
        assertEquals("existing_conn", duplicate.offerEditName)

        val pendingAdd = ConnectorsLogic.mapSubmitError(
            409,
            """{"error":"pending approval","reason":"pending_add"}""",
            "existing_conn",
        ) as SubmitErrorUi.InlineError
        assertEquals("pending_add", pendingAdd.reason)
        assertNull(pendingAdd.offerEditName)

        val unavailable = ConnectorsLogic.mapSubmitError(
            503,
            """{"error":"unavailable","reason":"approvals_unavailable"}""",
            "my_conn",
        )
        assertTrue(unavailable is SubmitErrorUi.SnackbarError)
        assertTrue((unavailable as SubmitErrorUi.SnackbarError).message.lowercase().contains("hearth not reachable / not ready"))

        // 202 card id trimming to first 12 chars
        assertEquals("card_1234567", ConnectorsLogic.formatApprovalCardId("card_1234567890abcdef"))
    }

    // -----------------------------------------------------------------------
    // 5. SSE parser + reconnect triggers bootstrap-then-events order
    // -----------------------------------------------------------------------

    @Test
    fun sseParser_upsertsState_removesGone_ignoresPing() {
        val initial = listOf(
            ConnectorRow(
                name = "alpha",
                state = "starting",
                tools = 0,
                restarts = 0,
                appState = "not-connected",
                enabled = true,
            ),
            ConnectorRow(
                name = "beta",
                state = "ready",
                tools = 4,
                restarts = 0,
                appState = "read-only",
                enabled = true,
            ),
        )

        val ssePayload = buildString {
            append(": ping\n\n")
            append("event: connector_state\n")
            append(
                """data: {"name":"alpha","state":"ready","tools":5,"restarts":1,"app_state":"read+act","enabled":true,"tool_list":[],"has_write_tools":true,"fingerprint":"ff00","last_used":null,"url_or_command":"/bin/alpha","env":[]}"""
            )
            append("\n\n")
            append(": ping\n\n")
            append("event: connector_gone\n")
            append("""data: {"name":"beta"}""")
            append("\n\n")
        }

        val events = mutableListOf<ConnectorSseEvent>()
        ConnectorsClient.parseSseStream(
            ByteArrayInputStream(ssePayload.toByteArray(Charsets.UTF_8))
        ) { evt ->
            events.add(evt)
        }

        assertEquals(2, events.size)
        assertTrue(events[0] is ConnectorSseEvent.State)
        assertTrue(events[1] is ConnectorSseEvent.Gone)

        var current = initial
        for (evt in events) {
            current = ConnectorsLogic.applySseEvent(current, evt)
        }

        assertEquals(1, current.size)
        val alpha = current.single()
        assertEquals("alpha", alpha.name)
        assertEquals("ready", alpha.state)
        assertEquals(5, alpha.tools)
        assertEquals(1, alpha.restarts)
        assertEquals("read+act", alpha.appState)
    }

    @Test
    fun reconnect_alwaysBootstrapsFromGetBeforeConsumingEvents_andNeverSendsLastEventId() = runBlocking {
        val callOrder = mutableListOf<String>()
        val capturedEventHeaders = mutableListOf<Map<String, String>>()
        var getAttempt = 0
        var sseAttempt = 0

        val ep = Endpoint("https://hearth.local:8443", "lar1_secret_pair_token", "Hearth")
        val client = ConnectorsClient(
            endpoint = ep,
            httpGet = { url, headers ->
                assertEquals("Bearer lar1_secret_pair_token", headers["Authorization"])
                if (url.endsWith("/v1/mcp/connectors")) {
                    getAttempt++
                    callOrder.add("bootstrap_$getAttempt")
                    val body = if (getAttempt == 1) {
                        """[{"name":"conn1","state":"starting","tools":0,"restarts":0,"app_state":"not-connected","enabled":true}]"""
                    } else {
                        """[{"name":"conn1","state":"ready","tools":2,"restarts":0,"app_state":"read-only","enabled":true}]"""
                    }
                    HttpResponse(200, body)
                } else {
                    throw IOException("unexpected GET $url")
                }
            },
            sseStreamOpener = { url, headers, onStream ->
                assertTrue(url.endsWith("/v1/mcp/connectors/events"))
                capturedEventHeaders.add(headers)
                sseAttempt++
                callOrder.add("sse_$sseAttempt")
                val streamData = if (sseAttempt == 1) {
                    // First stream emits one event then disconnects (simulating slow client / drop)
                    ": ping\n\nevent: connector_state\ndata: {\"name\":\"conn2\",\"state\":\"ready\",\"tools\":1,\"restarts\":0,\"app_state\":\"read+act\",\"enabled\":true}\n\n"
                } else {
                    // Second stream after reconnect removes conn1
                    "event: connector_gone\ndata: {\"name\":\"conn1\"}\n\n"
                }
                onStream(200, ByteArrayInputStream(streamData.toByteArray(Charsets.UTF_8)))
            }
        )

        val snapshots = mutableListOf<List<ConnectorRow>>()
        client.streamWithReconnect(
            maxCycles = 2,
            reconnectDelayMs = 0L,
            onStateUpdated = { rows -> snapshots.add(rows) },
        )

        assertEquals(
            listOf("bootstrap_1", "sse_1", "bootstrap_2", "sse_2"),
            callOrder,
        )
        assertTrue(
            "Must never send Last-Event-ID header",
            capturedEventHeaders.all { hdrs -> hdrs.keys.none { it.equals("Last-Event-ID", ignoreCase = true) } },
        )

        // Snapshot 0: bootstrap_1 ([conn1])
        // Snapshot 1: sse_1 upsert conn2 ([conn1, conn2])
        // Snapshot 2: bootstrap_2 replaces full list from GET ([conn1 (ready)])
        // Snapshot 3: sse_2 removes conn1 ([])
        assertEquals(4, snapshots.size)
        assertEquals(listOf("conn1"), snapshots[0].map { it.name })
        assertEquals(listOf("conn1", "conn2"), snapshots[1].map { it.name })
        assertEquals(listOf("conn1"), snapshots[2].map { it.name })
        assertEquals("read-only", snapshots[2].single().appState)
        assertTrue(snapshots[3].isEmpty())
    }

    // -----------------------------------------------------------------------
    // Live daemon shape: GET /v1/mcp/connectors wraps rows in an object
    // ({"connectors":[...]}) — captured verbatim from a running daemon.
    // -----------------------------------------------------------------------

    @Test
    fun parseRows_acceptsWrappedDaemonPayload() {
        val raw = """
        {
          "connectors": [
            {
              "name": "searxng",
              "state": "ready",
              "tools": 4,
              "restarts": 1,
              "reason": "ok",
              "app_state": "read-only",
              "enabled": true,
              "tool_list": [
                {"name": "searxng_web_search", "read_only": true, "destructive": false, "open_world": true}
              ],
              "has_write_tools": false,
              "fingerprint": "",
              "last_used": null,
              "url_or_command": "",
              "env": []
            }
          ]
        }
        """.trimIndent()
        val rows = ConnectorsLogic.parseRows(raw)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("searxng", row.name)
        assertEquals("ready", row.state)
        assertEquals(4, row.tools)
        assertEquals(1, row.restarts)
        assertEquals("read-only", row.appState)
        assertTrue(row.enabled)
    }

    @Test
    fun parseRows_wrappedObjectWithoutConnectorsKeyYieldsEmpty() {
        assertTrue(ConnectorsLogic.parseRows("""{"other": true}""").isEmpty())
    }
}
