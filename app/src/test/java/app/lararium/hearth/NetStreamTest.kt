package app.lararium.hearth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket

/**
 * Unit tests for Net.stream (F1).
 *
 * Verifies:
 * - Happy path against a local ServerSocket with status and incremental reads
 * - Policy rejection of a public hostname without touching the network
 */
class NetStreamTest {

    @Test
    fun stream_happyPath_readsStatusAndStreamedBodyIncrementally() {
        ServerSocket(0).use { server ->
            val port = server.localPort
            val thread = Thread {
                try {
                    server.accept().use { sock ->
                        val buf = ByteArray(4096)
                        sock.getInputStream().read(buf) // drain GET
                        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"
                        val out = sock.getOutputStream()
                        out.write(header.toByteArray(Charsets.US_ASCII))
                        out.flush()

                        out.write("event: greeting\ndata: hello 1\n\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                        Thread.sleep(30)
                        out.write("event: greeting\ndata: hello 2\n\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                } catch (_: Exception) {}
            }
            thread.isDaemon = true
            thread.start()

            val lines = mutableListOf<String>()
            Net.stream("http://127.0.0.1:$port/v1/devices/d1/stream", emptyMap(), 5_000) { status, input ->
                assertEquals(200, status)
                val reader = input.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readLine() ?: break
                    lines.add(line)
                    if (lines.size == 6) break
                }
            }

            assertEquals("event: greeting", lines[0])
            assertEquals("data: hello 1", lines[1])
            assertEquals("", lines[2])
            assertEquals("event: greeting", lines[3])
            assertEquals("data: hello 2", lines[4])
            assertEquals("", lines[5])

            thread.join(2_000)
        }
    }

    @Test
    fun stream_policyRejection_doesNotTouchNetwork() {
        var reachedStream = false
        try {
            // Public hostname with cleartext http must be rejected by EndpointPolicy
            Net.stream("http://example.com:7717/v1/devices/d1/stream", emptyMap(), 5_000) { _, _ ->
                reachedStream = true
            }
            fail("Expected IOException from EndpointPolicy rejection")
        } catch (e: IOException) {
            assertTrue(
                "Expected cleartext error, got: ${e.message}",
                e.message?.contains("cleartext") == true || e.message?.contains("only allowed") == true
            )
        }
        assertFalse(reachedStream)
    }
}
