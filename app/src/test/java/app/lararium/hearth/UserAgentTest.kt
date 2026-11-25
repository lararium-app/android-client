package app.lararium.hearth

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

class UserAgentTest {

    private lateinit var originalUserAgent: String

    @Before
    fun saveUserAgent() {
        originalUserAgent = Net.userAgent()
    }

    @After
    fun restoreUserAgent() {
        Net.init(originalUserAgent)
    }

    @Test
    fun build_formatsExactExpectedString() {
        val ua = UserAgent.build(
            versionName = "0.11.20-alpha",
            androidRelease = "15",
            brand = "google",
            model = "Pixel 9",
        )
        assertEquals("lararium-android/0.11.20-alpha (Android 15; google Pixel 9)", ua)
    }

    @Test
    fun sanitizeComponent_stripsCrLfAndControlChars_replacesCommas_collapsesWhitespace() {
        assertEquals(
            "Acme+ Inc. Model+2",
            UserAgent.sanitizeComponent("  Acme,\r\n  Inc.\tModel,2\u0000  "),
        )
        assertEquals(
            "Pixel 9 Pro",
            UserAgent.sanitizeComponent("Pixel   9\t\tPro"),
        )
        assertEquals(
            "FooBar",
            UserAgent.sanitizeComponent("Foo\r\nBar"),
        )
    }

    @Test
    fun build_handlesMissingOrBlankDeviceFieldsCleanly() {
        assertEquals(
            "lararium-android/1.0.0 (Android 14; Pixel 8)",
            UserAgent.build("1.0.0", "14", "", "Pixel 8"),
        )
        assertEquals(
            "lararium-android/1.0.0 (Android unknown; unknown)",
            UserAgent.build("1.0.0", null, null, "   "),
        )
        assertEquals(
            "lararium-android/unknown (Android 15; samsung SM-S928U)",
            UserAgent.build("", "15", "samsung", "SM-S928U"),
        )
    }

    @Test
    fun build_sanitizesMaliciousBrandAndModel() {
        val ua = UserAgent.build(
            versionName = "0.11.20-alpha",
            androidRelease = "15",
            brand = "evil,\r\nX-Injected: 1",
            model = "Phone,  9\tPro\n",
        )
        assertEquals(
            "lararium-android/0.11.20-alpha (Android 15; evil+X-Injected: 1 Phone+ 9 Pro)",
            ua,
        )
        assertTrue(!ua.contains('\r') && !ua.contains('\n') && !ua.contains(','))
    }

    @Test
    fun netInit_rejectsIllegalCharactersAndBlank() {
        val badValues = listOf("", "   ", "lararium\r\nX-Evil: 1", "lararium\nfoo", "lararium\u0000foo")
        for (bad in badValues) {
            val ex = runCatching { Net.init(bad) }.exceptionOrNull()
            assertNotNull("Expected Net.init to reject '$bad'", ex)
            assertTrue(ex is IllegalArgumentException || ex is IOException)
        }
    }

    @Test
    fun netRequest_emitsDefaultUserAgentAndAllowsCallerOverride() {
        val expectedUa = "lararium-android/0.11.20-alpha (Android 15; google Pixel 9)"
        Net.init(expectedUa)

        // Default UA emitted on GET
        val capturedDefault = captureRequestHeaders { port ->
            val resp = Net.get("http://127.0.0.1:$port/v1/health")
            assertEquals(200, resp.status)
        }
        assertEquals(listOf(expectedUa), capturedDefault.headersNamed("user-agent"))

        // Caller override replaces default without duplicating the header
        val customUa = "custom-client/2.0"
        val capturedOverride = captureRequestHeaders { port ->
            val resp = Net.post(
                "http://127.0.0.1:$port/v1/pair",
                "{}",
                mapOf("User-Agent" to customUa),
            )
            assertEquals(200, resp.status)
        }
        assertEquals(listOf(customUa), capturedOverride.headersNamed("user-agent"))

        // Case-insensitive override also prevents duplicate User-Agent headers
        val capturedLowerOverride = captureRequestHeaders { port ->
            val resp = Net.get(
                "http://127.0.0.1:$port/v1/health",
                mapOf("user-agent" to customUa),
            )
            assertEquals(200, resp.status)
        }
        assertEquals(listOf(customUa), capturedLowerOverride.headersNamed("user-agent"))
    }

    @Test
    fun netStream_emitsDefaultUserAgentAndAllowsCallerOverride() {
        val expectedUa = "lararium-android/0.11.20-alpha (Android 15; google Pixel 9)"
        Net.init(expectedUa)

        val capturedDefault = captureRequestHeaders(
            response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n",
        ) { port ->
            Net.stream("http://127.0.0.1:$port/v1/devices/d1/stream") { status, _ ->
                assertEquals(200, status)
            }
        }
        assertEquals(listOf(expectedUa), capturedDefault.headersNamed("user-agent"))

        val customUa = "stream-override/1.0"
        val capturedOverride = captureRequestHeaders(
            response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n",
        ) { port ->
            Net.stream(
                "http://127.0.0.1:$port/v1/devices/d1/stream",
                mapOf("User-Agent" to customUa),
            ) { status, _ ->
                assertEquals(200, status)
            }
        }
        assertEquals(listOf(customUa), capturedOverride.headersNamed("user-agent"))
    }

    @Test
    fun larariumClientStreamTurn_emitsDefaultUserAgent() {
        val expectedUa = "lararium-android/0.11.20-alpha (Android 15; google Pixel 9)"
        Net.init(expectedUa)

        val sseResponse = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" +
            "event: turn_done\ndata: {\"seq\":1}\n\n"
        val captured = captureRequestHeaders(response = sseResponse) { port ->
            val client = LarariumClient(Endpoint("http://127.0.0.1:$port", "lar1_test", "test"))
            val events = mutableListOf<HearthEvent>()
            runBlocking {
                client.streamTurn("sess-1", "hi") { events.add(it) }
            }
            assertEquals(1, events.size)
            assertTrue(events[0] is HearthEvent.TurnDone)
        }
        assertEquals(listOf(expectedUa), captured.headersNamed("user-agent"))
    }

    private fun captureRequestHeaders(
        response: String = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}",
        block: (Int) -> Unit,
    ): String {
        val captured = AtomicReference("")
        ServerSocket(0).use { server ->
            val port = server.localPort
            val thread = Thread {
                try {
                    server.accept().use { sock ->
                        val input = sock.getInputStream()
                        val buf = ArrayList<Byte>(512)
                        var p3 = 0; var p2 = 0; var p1 = 0
                        while (true) {
                            val b = input.read()
                            if (b == -1) break
                            buf.add(b.toByte())
                            if (p3 == '\r'.code && p2 == '\n'.code && p1 == '\r'.code && b == '\n'.code) break
                            p3 = p2; p2 = p1; p1 = b
                        }
                        captured.set(buf.toByteArray().toString(Charsets.ISO_8859_1))
                        val out = sock.getOutputStream()
                        out.write(response.toByteArray(Charsets.US_ASCII))
                        out.flush()
                    }
                } catch (_: Exception) {}
            }
            thread.isDaemon = true
            thread.start()
            block(port)
            thread.join(2_000)
        }
        return captured.get()
    }

    private fun String.headersNamed(name: String): List<String> =
        split("\r\n")
            .drop(1)
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null
                else {
                    val k = line.substring(0, idx).trim()
                    val v = line.substring(idx + 1).trim()
                    if (k.equals(name, ignoreCase = true)) v else null
                }
            }

    @Test
    fun sanitizeComponent_stripsNonAscii() {
        assertEquals("", UserAgent.sanitizeComponent("✓•…"))
        assertEquals("Pixel 9", UserAgent.sanitizeComponent("Pöixel ✓9"))
        assertEquals(
            "lararium-android/1.0 (Android 14; Google Nte)",
            UserAgent.build("1.0", "14", "Google", "Nöte✓"),
        )
    }
}
