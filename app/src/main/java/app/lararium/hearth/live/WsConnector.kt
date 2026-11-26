package app.lararium.hearth.live

import android.util.Base64
import app.lararium.hearth.Endpoint
import app.lararium.hearth.EndpointPolicy
import app.lararium.hearth.UserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** A live websocket: byte streams plus the peer socket for timeouts and teardown. */
class WsSocket internal constructor(
    val socket: Socket,
    val input: InputStream,
    val output: OutputStream,
) {
    fun closeQuietly() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Opens the JSON-RPC websocket against `GET <base>/v1/ws`.
 *
 * Same raw-socket discipline as [app.lararium.hearth.Net]: plain `ws` over
 * loopback/tailnet http endpoints, `wss` with default hostname verification
 * for https, no third-party HTTP stack. The bearer token never rides in the
 * handshake — the daemon authenticates the very first JSON-RPC frame
 * (auth.hello) after the upgrade.
 */
object WsConnector {

    class HandshakeError(message: String) : IOException(message)

    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    private val secureRandom = java.security.SecureRandom()

    /** Maps a paired https/http base URL onto its wss/ws endpoint. */
    fun wsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        val ws = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            else -> throw HandshakeError("unsupported endpoint scheme")
        }
        return "$ws/v1/ws"
    }

    suspend fun open(endpoint: Endpoint, timeoutMs: Int = 15_000): WsSocket {
        val url = wsUrl(endpoint.baseUrl)
        EndpointPolicy.rejectReason(url)?.let { throw HandshakeError(it) }
        return withContext(Dispatchers.IO) { openBlocking(url, timeoutMs) }
    }

    private fun openBlocking(url: String, timeoutMs: Int): WsSocket {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase()
            ?: throw HandshakeError("missing scheme")
        val host = uri.host ?: throw HandshakeError("missing host")
        val secure = scheme == "wss"
        if (!secure && scheme != "ws") throw HandshakeError("unsupported scheme $scheme")
        val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
        val path = if (uri.path.isNullOrBlank()) "/v1/ws" else uri.path

        val socket: Socket = if (secure) {
            // Explicit context + explicit HTTPS endpoint identification: the
            // platform default factory does not guarantee hostname
            // verification on all supported API levels.
            val trust = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as java.security.KeyStore?) }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
                .socketFactory.createSocket() as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            ssl.connect(InetSocketAddress(host, port), timeoutMs)
            ssl
        } else {
            Socket().also { it.connect(InetSocketAddress(host, port), timeoutMs) }
        }
        socket.keepAlive = true
        // Bound the handshake read too: connect() only covers TCP establishment,
        // and a black-holed proxy must not hang the upgrade forever.
        socket.soTimeout = timeoutMs

        val keyBytes = ByteArray(16).also { secureRandom.nextBytes(it) }
        val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)

        val hostHeader = if (uri.port != -1) "$host:${uri.port}" else host
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("User-Agent: ").append(UserAgent.forCurrentDevice()).append("\r\n")
            append("\r\n")
        }

        val out = socket.getOutputStream()
        out.write(request.toByteArray(Charsets.US_ASCII))
        out.flush()

        // Read the response headers one raw byte at a time. Buffered reads are
        // forbidden here: any byte past the terminating CRLF CRLF is already
        // websocket framing and must stay in the stream for the frame reader.
        val headerBytes = ByteArrayOutputStream()
        val raw = socket.getInputStream()
        var matched = 0
        while (matched < 4) {
            val b = raw.read()
            if (b == -1) {
                socket.close()
                throw HandshakeError("handshake ended mid-headers")
            }
            headerBytes.write(b)
            matched = when (matched) {
                0, 2 -> if (b == '\r'.code) matched + 1 else 0
                1, 3 -> if (b == '\n'.code) matched + 1 else if (b == '\r'.code) matched else 0
                else -> 0
            }
            if (headerBytes.size() > 16 * 1024) {
                socket.close()
                throw HandshakeError("handshake headers too large")
            }
        }
        val headers = headerBytes.toString("US-ASCII")
        val lines = headers.split("\r\n")
        val statusLine = lines.firstOrNull()
            ?: run { socket.close(); throw HandshakeError("empty handshake response") }
        val parts = statusLine.split(" ")
        if (parts.size < 2 || parts[1] != "101") {
            socket.close()
            throw HandshakeError("websocket upgrade rejected: $statusLine")
        }

        var accept: String? = null
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("sec-websocket-accept", true)) {
                accept = line.substring(idx + 1).trim()
            }
        }
        val expected = expectedAccept(key)
        if (accept == null || accept != expected) {
            socket.close()
            throw HandshakeError("bad Sec-WebSocket-Accept")
        }

        return WsSocket(socket, raw, out)
    }

    private fun expectedAccept(key: String): String {
        // codeql[java/weak-cryptographic-algorithm] SHA-1 is not a security
        // boundary here: RFC 6455 defines the Sec-WebSocket-Accept token as
        // SHA-1(key + GUID) purely to detect accidental mismatches.
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key + WS_GUID).toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }
}
