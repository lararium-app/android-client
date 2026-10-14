package app.lararium.hearth

import java.net.URI

/**
 * One hearth: the user's own Lararium endpoint + the token door.
 *
 * Trust rule: tokens may only travel over TLS. Loopback and RFC1918/tailnet
 * addresses may use http (LAN is the user's own wire); everything else must be
 * https.
 */
data class Endpoint(val baseUrl: String, val token: String, val name: String) {
    val isLocal: Boolean
        get() {
            val host = URI(baseUrl).host ?: return false
            return host == "localhost" || host == "::1" || EndpointPolicy.isPrivateV4(host)
        }
}

object EndpointPolicy {
    /** null = acceptable; otherwise a human-readable reason to reject. */
    fun rejectReason(url: String): String? {
        val uri = try { URI(url) } catch (e: Exception) { return "not a valid URL" }
        return when {
            uri.host.isNullOrBlank() -> "missing host"
            uri.scheme == "https" -> null
            uri.scheme == "http" -> {
                val h = uri.host
                if (h == "localhost" || h == "::1" || isPrivateV4(h)) null
                else "cleartext http is only allowed on LAN/tailnet hosts; use https"
            }
            else -> "unsupported scheme ${uri.scheme}"
        }
    }

    /** RFC1918, loopback, CGNAT and tailnet (100.64.0.0/10) ranges. */
    fun isPrivateV4(h: String): Boolean {
        val o = h.split(".").mapOrNull4() ?: return false
        return when {
            o[0] == 10 -> true
            o[0] == 127 -> true // loopback; mirrors isLoopbackAddress in the socket check
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 100 && o[1] in 64..127 -> true // tailnet/CGNAT
            else -> false
        }
    }

    private fun List<String>.mapOrNull4(): List<Int>? =
        if (size != 4) null else map { it.toIntOrNull() ?: return null }
}

/** Parses the hearthd pairing deep link: lararium://pair?host=URL&token=TOKEN[&name=N] */
object PairingLink {
    // Token must match lar0_… or lar1_… followed by URL-safe chars only.
    private val TOKEN_RE = Regex("^lar[01]_[A-Za-z0-9_\\-]+$")

    // Any C0/C1 control character.
    private val CONTROL_RE = Regex("[\\x00-\\x1f\\x7f-\\x9f]")

    fun parse(link: String): Pair<Endpoint, String?>? {
        if (!link.startsWith("lararium://pair?")) return null
        val q = link.substringAfter('?').split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i) to
                java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        val host = q["host"] ?: return null
        val token = q["token"] ?: return null

        // Enforce token format — fail closed.
        if (!TOKEN_RE.matches(token)) return null

        EndpointPolicy.rejectReason(host)?.let { return null }

        val name = q["name"]?.also { n ->
            // Reject names with control characters.
            if (CONTROL_RE.containsMatchIn(n)) return null
        } ?: java.net.URI(host).host ?: "hearth"

        return Endpoint(host.trimEnd('/'), token, name) to null
    }
}
