package app.lararium.hearth.rails.service

import android.net.Uri
import app.lararium.hearth.EndpointPolicy
import java.net.URI
import java.net.URLDecoder

/**
 * Parsed payload from a lararium://device-pair deep link.
 */
data class PairRequest(
    val baseUrl: String,
    val token: String
)

/**
 * Pure function parsing device rails deep links (F6, spec A3).
 *
 * Pattern: lararium://device-pair?base=URL&token=TOKEN
 * Validates endpoint scheme policy via EndpointPolicy (HTTPS universally; cleartext HTTP
 * only on localhost, private LAN, or tailnet CGNAT).
 */
object DevicePairLinkParser {

    fun parse(uri: Uri): PairRequest? = parse(uri.toString())

    fun parse(link: String): PairRequest? {
        val uri = try {
            URI(link)
        } catch (_: Exception) {
            return null
        }

        if (uri.scheme != "lararium" || uri.host != "device-pair") {
            return null
        }

        val rawQuery = uri.rawQuery ?: return null
        val queryMap = mutableMapOf<String, String>()
        for (part in rawQuery.split("&")) {
            val idx = part.indexOf('=')
            if (idx > 0) {
                val key = part.substring(0, idx)
                val rawVal = part.substring(idx + 1)
                val decoded = try {
                    URLDecoder.decode(rawVal, "UTF-8")
                } catch (_: Exception) {
                    rawVal
                }
                queryMap[key] = decoded
            }
        }

        val token = queryMap["token"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val base = (queryMap["base"] ?: queryMap["baseUrl"] ?: queryMap["host"])
            ?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null

        if (EndpointPolicy.rejectReason(base) != null) {
            return null
        }

        return PairRequest(baseUrl = base.trimEnd('/'), token = token)
    }
}
