package app.lararium.hearth

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket

/**
 * Policy is a trust boundary: tokens must never travel over the public
 * internet unencrypted. Widening the accepted set must fail this test on
 * purpose.
 */
class EndpointPolicyTest {

    private fun ok(url: String) = assertNull(EndpointPolicy.rejectReason(url))
    private fun no(url: String) = assertNotNull(EndpointPolicy.rejectReason(url))

    // -----------------------------------------------------------------------
    // EndpointPolicy — accept / reject set
    // -----------------------------------------------------------------------

    @Test fun httpsAnywhereAllowed() {
        ok("https://hearth.example.com:7717")
        ok("https://100.111.222.33:7717")
        ok("https://203.0.113.50:7717")   // documentation range, still https → ok
    }

    @Test fun httpOnlyOnPrivateNetworks() {
        ok("http://192.168.99.99:7717")
        ok("http://10.0.3.111:7717")
        ok("http://172.16.0.5:7717")
        ok("http://100.123.45.67:7717")  // tailnet
        ok("http://localhost:7717")
        ok("http://127.0.0.1:7717")       // loopback octet (fake-server tests bind it)
        no("http://9.255.255.255:7717")  // just outside RFC1918
        no("http://172.32.0.1:7717")     // 172.32 is NOT private
        no("http://100.63.0.1:7717")     // below CGNAT block
        no("http://100.128.0.1:7717")    // above CGNAT block
        no("http://128.0.0.1:7717")      // just above loopback range
        no("http://example.com:7717")
    }

    @Test fun rejectsGarbage() {
        no("not a url")
        no("ftp://host:21")
        no("http://")
    }

    // -----------------------------------------------------------------------
    // PairingLink parsing
    // -----------------------------------------------------------------------

    @Test fun pairLinkParses() {
        val (ep, _) = PairingLink.parse(
            "lararium://pair?host=http%3A%2F%2F192.168.99.99%3A7717&token=lar1_abc123&name=home"
        )!!
        assertEquals("http://192.168.99.99:7717", ep.baseUrl)
        assertEquals("lar1_abc123", ep.token)
        assertEquals("home", ep.name)
    }

    @Test fun pairLinkParsesLar0Token() {
        val (ep, _) = PairingLink.parse(
            "lararium://pair?host=https%3A%2F%2F192.0.2.1%3A7717&token=lar0_SETUP-token_99"
        )!!
        assertEquals("lar0_SETUP-token_99", ep.token)
    }

    @Test fun pairLinkRejectsPublicCleartext() {
        assertNull(
            PairingLink.parse(
                "lararium://pair?host=http%3A%2F%2Fevil.example.com%3A7717&token=lar1_x"
            )
        )
    }

    @Test fun pairLinkRejectsForeignScheme() {
        // Outer scheme must be lararium://pair
        assertNull(PairingLink.parse("https://pair?host=x&token=lar1_a"))
        assertNull(PairingLink.parse("garbage"))
    }

    @Test fun pairLinkRejectsCRLFInToken() {
        // CRLF in token → regex fails → null
        assertNull(
            PairingLink.parse(
                "lararium://pair?host=http%3A%2F%2F192.168.1.1%3A7717&token=lar1_abc%0d%0aX-Injected:bad"
            )
        )
    }

    @Test fun pairLinkRejectsInvalidTokenFormat() {
        // Must start with lar0_ or lar1_
        assertNull(
            PairingLink.parse(
                "lararium://pair?host=http%3A%2F%2F192.168.1.1%3A7717&token=sk-openai123"
            )
        )
        // Empty suffix
        assertNull(
            PairingLink.parse(
                "lararium://pair?host=http%3A%2F%2F192.168.1.1%3A7717&token=lar1_"
            )
        )
    }

    @Test fun pairLinkRejectsControlCharsInName() {
        // Tab and newline are control chars — must be rejected.
        assertNull(
            PairingLink.parse(
                "lararium://pair?host=http%3A%2F%2F192.168.1.1%3A7717&token=lar1_good&name=bad%09name"
            )
        )
        assertNull(
            PairingLink.parse(
                "lararium://pair?host=http%3A%2F%2F192.168.1.1%3A7717&token=lar1_good&name=line%0abreak"
            )
        )
    }

    @Test fun pairLinkUrlencodedHost() {
        // URL-encoded host with tailnet address.
        val (ep, _) = PairingLink.parse(
            "lararium://pair?host=http%3A%2F%2F100.123.45.67%3A7717&token=lar1_tailnet"
        )!!
        assertEquals("http://100.123.45.67:7717", ep.baseUrl)
    }

    // -----------------------------------------------------------------------
    // Header injection guard
    // -----------------------------------------------------------------------

    @Test fun headerInjectionCrRejected() {
        val ex = runCatching {
            Net.get("http://example.com/", mapOf("X-Foo\r" to "bar"))
        }.exceptionOrNull()
        assertNotNull("CR in header name must throw", ex)
    }

    @Test fun headerInjectionLfRejected() {
        val ex = runCatching {
            Net.get("http://example.com/", mapOf("X-Foo" to "bar\nX-Extra: injected"))
        }.exceptionOrNull()
        assertNotNull("LF in header value must throw", ex)
    }

    @Test fun headerInjectionNulRejected() {
        val ex = runCatching {
            Net.get("http://example.com/", mapOf("X-Foo" to "bar\u0000baz"))
        }.exceptionOrNull()
        assertNotNull("NUL in header value must throw", ex)
    }

    // -----------------------------------------------------------------------
    // Net — policy gate (no socket opened for public cleartext)
    // -----------------------------------------------------------------------

    @Test fun netRefusesPublicCleartextBeforeAnySocket() {
        val e = runCatching { Net.get("http://example.com/v1/health") }.exceptionOrNull()
        assertNotNull("public http must be rejected before a socket is opened", e)
    }

    // -----------------------------------------------------------------------
    // SSE parser fixtures
    // -----------------------------------------------------------------------

    @Test fun sseParserBasicDelta() {
        val sse = "event: delta\ndata: {\"text\":\"hello\",\"seq\":1}\n\n"
        val events = parseSse(sse)
        assertEquals(1, events.size)
        val ev = events[0] as HearthEvent.Delta
        assertEquals("hello", ev.text)
        assertEquals(1, ev.seq)
    }

    @Test fun sseParserTerminalTurnDone() {
        val sse = """
            event: delta
            data: {"text":"a"}
            
            event: turn_done
            data: {"seq":5,"result":"ok"}
            
            event: delta
            data: {"text":"should not appear"}
            
        """.trimIndent()
        val events = parseSse(sse)
        // delta then turn_done; the post-terminal delta must NOT appear.
        assertEquals(2, events.size)
        assertTrue(events[0] is HearthEvent.Delta)
        assertTrue(events[1] is HearthEvent.TurnDone)
    }

    @Test fun sseParserTerminalError() {
        // Fixture mirrors hearthd's real terminal payload shape ({"error":…}).
        val sse = "event: error\ndata: {\"error\":\"turn failed\",\"seq\":3}\n\n"
        val events = parseSse(sse)
        val ev = events[0] as HearthEvent.Error
        assertEquals("turn failed", ev.message)
        assertEquals(3, ev.seq)
    }

    @Test fun sseParserCommentsAndHeartbeatsIgnored() {
        val sse = ": keep-alive\n\nevent: delta\ndata: {\"text\":\"x\"}\n\n"
        val events = parseSse(sse)
        assertEquals(1, events.size)
    }

    @Test fun sseParserDataOnlyFrame() {
        // data-only frames (no event: field) are not in hearthd's protocol, but
        // the parser must not crash on them.
        val sse = "data: {\"text\":\"no event type\"}\n\n"
        val events = parseSse(sse)
        // name is empty → returns null → list is empty.
        assertEquals(0, events.size)
    }

    @Test fun sseParserMultiByteUtf8Delta() {
        // é (U+00E9) = 0xC3 0xA9 in UTF-8 — two bytes.
        // Emoji: 😀 (U+1F600) = 0xF0 0x9F 0x98 0x80 — four bytes.
        val text = "café \uD83D\uDE00"
        val sse = "event: delta\ndata: {\"text\":\"$text\"}\n\n"
        val events = parseSse(sse)
        val ev = events[0] as HearthEvent.Delta
        assertEquals(text, ev.text)
    }

    @Test fun sseParserEventSplitAcrossReads() {
        // Simulate the stream being delivered in two chunks.
        val part1 = "event: delta\ndata: {\"text\":\"split"
        val part2 = " here\"}\n\n"
        val combined = (part1 + part2).byteInputStream()
        val events = mutableListOf<HearthEvent>()
        invokeSseParser(combined, events::add)
        assertEquals(1, events.size)
        assertEquals("split here", (events[0] as HearthEvent.Delta).text)
    }

    @Test fun sseParserAllEventTypes() {
        val sse = """
            event: tool_start
            data: {"name":"search","seq":1}

            event: tool_done
            data: {"name":"search","seq":2}

            event: approval_request
            data: {"seq":3}

            event: turn_done
            data: {"seq":4}

        """.trimIndent()
        val events = parseSse(sse)
        assertTrue(events[0] is HearthEvent.ToolStart)
        assertTrue(events[1] is HearthEvent.ToolDone)
        assertTrue(events[2] is HearthEvent.ApprovalRequest)
        assertTrue(events[3] is HearthEvent.TurnDone)
    }

    // -----------------------------------------------------------------------
    // LarariumClient against a local fake ServerSocket
    // -----------------------------------------------------------------------

    @Test fun clientHealthOk() {
        val body = "{\"ok\":true,\"version\":\"0.1.0\"}"
        withFakeServer(
            response = httpOk(body),
        ) { port ->
            val ep = Endpoint("http://127.0.0.1:$port", "lar1_test", "local")
            val client = LarariumClient(ep)
            val result = kotlinx.coroutines.runBlocking { client.health() }
            assertTrue(result.optBoolean("ok"))
        }
    }

    @Test fun clientListSessions() {
        val body = "[{\"id\":\"s1\",\"title\":\"chat\",\"turn_count\":3}]"
        withFakeServer(response = httpOk(body)) { port ->
            val ep = Endpoint("http://127.0.0.1:$port", "lar1_test", "local")
            val sessions = kotlinx.coroutines.runBlocking { LarariumClient(ep).listSessions() }
            assertEquals(1, sessions.length())
            assertEquals("s1", sessions.getJSONObject(0).getString("id"))
        }
    }

    @Test fun clientCreateSession() {
        val body = "{\"id\":\"s2\",\"title\":\"new\"}"
        withFakeServer(response = httpStatus(201, body)) { port ->
            val ep = Endpoint("http://127.0.0.1:$port", "lar1_test", "local")
            val session = kotlinx.coroutines.runBlocking {
                LarariumClient(ep).createSession("new")
            }
            assertEquals("s2", session.getString("id"))
        }
    }

    @Test fun clientEvents() {
        val body = "{\"events\":[{\"seq\":1,\"event_type\":\"delta\"}],\"in_flight\":false}"
        withFakeServer(response = httpOk(body)) { port ->
            val ep = Endpoint("http://127.0.0.1:$port", "lar1_test", "local")
            val evts = kotlinx.coroutines.runBlocking { LarariumClient(ep).events("s1") }
            assertEquals(1, evts.getJSONArray("events").length())
        }
    }

    // -----------------------------------------------------------------------
    // Live integration probe (env-gated, self-skips in CI)
    // -----------------------------------------------------------------------

    @Test fun rawHttpAgainstLiveTestHearth() {
        val target = System.getenv("LARARIUM_TEST_HEARTH")
        org.junit.Assume.assumeTrue("LARARIUM_TEST_HEARTH not set", target != null)
        val (host, portStr) = target!!.split(":")
        val reachable = try {
            java.net.Socket().apply {
                connect(java.net.InetSocketAddress(host, portStr.toInt()), 400)
            }.close()
            true
        } catch (e: Exception) { false }
        org.junit.Assume.assumeTrue("test hearth unreachable", reachable)
        val resp = Net.get("http://$target/v1/health")
        assertEquals(200, resp.status)
        assertTrue(resp.body.contains("\"ok\":true"))
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Run the SSE parser over a plain string and collect events. */
    private fun parseSse(text: String): List<HearthEvent> {
        val events = mutableListOf<HearthEvent>()
        invokeSseParser(text.byteInputStream(), events::add)
        return events
    }

    /**
     * Reflectively call LarariumClient's private `parseSseStream`.
     * This avoids making it internal/public just for tests.
     */
    private fun invokeSseParser(
        input: java.io.InputStream,
        onEvent: (HearthEvent) -> Unit,
    ) {
        val client = LarariumClient(Endpoint("http://127.0.0.1:1", "lar1_x", "t"))
        val m = client.javaClass.getDeclaredMethod(
            "parseSseStream",
            java.io.InputStream::class.java,
            Function1::class.java,
        )
        m.isAccessible = true
        m.invoke(client, input, onEvent)
    }

    /**
     * Starts a single-shot ServerSocket on a random port, runs [block] with
     * that port, responds to the first connection with [response], then closes.
     */
    private fun withFakeServer(response: ByteArray, block: (Int) -> Unit) {
        ServerSocket(0).use { server ->
            val port = server.localPort
            val thread = Thread {
                try {
                    server.accept().use { sock ->
                        // drain the request
                        val buf = ByteArray(4096)
                        sock.getInputStream().read(buf)
                        sock.getOutputStream().write(response)
                        sock.getOutputStream().flush()
                    }
                } catch (_: Exception) {}
            }
            thread.isDaemon = true
            thread.start()
            block(port)
            thread.join(2_000)
        }
    }

    private fun httpOk(body: String) = httpStatus(200, body)

    private fun httpStatus(code: Int, body: String): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code OK\r\nContent-Length: ${bodyBytes.size}\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n"
        return header.toByteArray(Charsets.US_ASCII) + bodyBytes
    }
}
