package app.lararium.hearth

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Cleartext is enforced HERE — the manifest keeps `usesCleartextTraffic=
 * "false"`, so the platform never opens a plain-HTTP socket for us. For the
 * LAN/tailnet http endpoints our policy allows, we open the socket ourselves:
 * string policy first, then a re-check on the RESOLVED addresses
 * (DNS-rebinding/TOCTOU), then a hand-rolled HTTP/1.1 request. https uses the
 * normal platform stack with hostname verification pinned.
 */
data class HttpResponse(val status: Int, val body: String)

object Net {

    const val MAX_BODY_BYTES: Int = 10 * 1024 * 1024 // 10 MiB cap

    fun get(
        urlString: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 5_000,
    ): HttpResponse = request("GET", urlString, null, headers, timeoutMs)

    fun post(
        urlString: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 10_000,
    ): HttpResponse = request("POST", urlString, body, headers, timeoutMs)

    fun delete(
        urlString: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 10_000,
    ): HttpResponse = request("DELETE", urlString, null, headers, timeoutMs)

    private fun request(
        method: String,
        urlString: String,
        body: String?,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): HttpResponse {
        EndpointPolicy.rejectReason(urlString)?.let { throw IOException(it) }

        // Header-injection guard: reject names/values with CR, LF, or NUL.
        headers.forEach { (k, v) ->
            requireSafeHeader(k)
            requireSafeHeader(v)
        }

        val url = URL(urlString)
        val port = if (url.port == -1) if (url.protocol == "https") 443 else 80 else url.port

        val socket: Socket = when (url.protocol) {
            "https" -> (SSLSocketFactory.getDefault().createSocket() as SSLSocket).also { ssl ->
                ssl.sslParameters = ssl.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                ssl.connect(InetSocketAddress(url.host, port), timeoutMs)
            }
            "http" -> {
                val addrs = try {
                    InetAddress.getAllByName(url.host)
                } catch (e: Exception) {
                    throw IOException("dns failed for ${url.host}")
                }
                val private = addrs.isNotEmpty() && addrs.all {
                    it.isLoopbackAddress || it.isSiteLocalAddress || isCgnat(it)
                }
                if (!private) throw IOException(
                    "cleartext http is only allowed on LAN/tailnet hosts; use https"
                )
                Socket().also { it.connect(InetSocketAddress(addrs[0], port), timeoutMs) }
            }
            else -> throw IOException("unsupported scheme ${url.protocol}")
        }

        socket.use { s ->
            s.soTimeout = timeoutMs + 15_000
            val path = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")
            val bodyBytes = body?.toByteArray(Charsets.UTF_8)

            val req = buildString {
                append(method).append(" ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(url.host)
                if (url.port != -1) append(":").append(url.port)
                append("\r\n")
                append("Accept-Encoding: identity\r\nConnection: close\r\n")
                if (bodyBytes != null) {
                    append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                    if (!headers.containsKey("Content-Type")) {
                        append("Content-Type: application/json\r\n")
                    }
                }
                headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                append("\r\n")
            }

            val out = s.getOutputStream()
            out.write(req.toByteArray(Charsets.US_ASCII))
            if (bodyBytes != null) out.write(bodyBytes)
            out.flush()

            return readResponse(s.getInputStream())
        }
    }

    /** Reads a complete HTTP/1.1 response. Chunk decoding works over raw bytes. */
    private fun readResponse(input: InputStream): HttpResponse {
        // Read raw bytes until we have the full header section.
        val headerBuf = readUntilBlankLine(input)
        val headerText = headerBuf.toString(Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")

        val statusLine = lines.firstOrNull() ?: throw IOException("empty response")
        val status = statusLine.substringAfter(' ').substringBefore(' ').toIntOrNull()
            ?: throw IOException("bad status line")

        val respHeaders = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val ci = line.indexOf(':')
            if (ci > 0) respHeaders[line.substring(0, ci).lowercase()] =
                line.substring(ci + 1).trim()
        }

        val bodyBytes = if (respHeaders["transfer-encoding"]?.contains("chunked") == true) {
            readChunkedBody(input)
        } else {
            val clStr = respHeaders["content-length"]
            if (clStr != null) {
                val cl = clStr.toLongOrNull() ?: throw IOException("invalid content-length: $clStr")
                if (cl < 0 || cl > MAX_BODY_BYTES) {
                    throw IOException("content-length $cl out of bounds (0..$MAX_BODY_BYTES)")
                }
                readExact(input, cl.toInt())
            } else {
                input.readBytes()
            }
        }

        return HttpResponse(status, bodyBytes.toString(Charsets.UTF_8))
    }

    /**
     * Reads until the CRLFCRLF header terminator, returning everything up to
     * and including it (so the caller can parse headers from raw bytes).
     */
    private fun readUntilBlankLine(input: InputStream): ByteArray {
        val buf = ArrayList<Byte>(512)
        var prev3 = 0; var prev2 = 0; var prev1 = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            buf.add(b.toByte())
            if (prev3 == '\r'.code && prev2 == '\n'.code &&
                prev1 == '\r'.code && b == '\n'.code
            ) break
            prev3 = prev2; prev2 = prev1; prev1 = b
        }
        return buf.toByteArray()
    }

    /**
     * Decodes chunked transfer-encoding over raw bytes — no character reader
     * involved, so multi-byte UTF-8 sequences are always intact.
     */
    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = ArrayList<Byte>(512)
        val lineBuf = StringBuilder()
        var total = 0
        while (true) {
            // Read chunk-size line.
            lineBuf.clear()
            while (true) {
                val b = input.read()
                if (b == -1 || b == '\n'.code) break
                if (b != '\r'.code) lineBuf.append(b.toChar())
            }
            val chunkSize = lineBuf.toString().trim()
                .substringBefore(';')  // ignore extensions
                .toIntOrNull(16) ?: break
            if (chunkSize == 0) break
            if (chunkSize < 0 || total + chunkSize > MAX_BODY_BYTES) {
                throw IOException("chunked body size exceeds maximum of $MAX_BODY_BYTES bytes")
            }
            val chunk = readExact(input, chunkSize)
            chunk.forEach { out.add(it) }
            total += chunkSize
            // Consume trailing CRLF.
            input.read(); input.read()
        }
        return out.toByteArray()
    }

    internal fun readExact(input: InputStream, n: Int): ByteArray {
        if (n < 0 || n > MAX_BODY_BYTES) {
            throw IOException("declared length $n out of bounds (0..$MAX_BODY_BYTES)")
        }
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return buf
    }

    private fun requireSafeHeader(value: String) {
        if (value.any { it == '\r' || it == '\n' || it == '\u0000' })
            throw IOException("header name/value contains illegal character: ${value.take(80)}")
    }

    /** CGNAT / Tailscale range 100.64.0.0/10. */
    fun isCgnat(a: InetAddress): Boolean {
        val b = a.address
        return b.size == 4 && b[0] == 100.toByte() && b[1].toInt() and 0xFF in 64..127
    }
}
