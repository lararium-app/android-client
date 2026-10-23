package app.lararium.util

import java.net.URI

/**
 * H9: links rendered from model/agent-authored text may only launch
 * the browser. Anything else — content:, file:, intent:, tel:, and
 * other privileged or app-internal schemes — is refused outright, so
 * a hostile completion can neither exfiltrate local data through an
 * exported provider nor chain into an intent redirect.
 */
object LinkPolicy {
    fun isSafeExternal(url: String): Boolean {
        val u = try {
            URI(url.trim())
        } catch (_: Exception) {
            return false
        }
        return when (u.scheme?.lowercase()) {
            "http", "https" -> u.host != null
            else -> false
        }
    }
}
