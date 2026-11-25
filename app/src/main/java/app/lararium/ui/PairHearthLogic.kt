package app.lararium.ui

import app.lararium.hearth.Endpoint
import app.lararium.hearth.EndpointPolicy
import app.lararium.hearth.PairingLink
import java.net.URLEncoder

/**
 * Pure-JVM input routing and validation for the Pair-with-hearth dialog.
 *
 * Accepts one-time enrollment (`dev1_...`) and pairing (`pair1_...`) codes from
 * either the pairing-link field or the token/code field, falling back to an
 * already-stored hearth base URL when the Server URL field is left blank.
 */
enum class PairHearthInputError {
    /** Generic invalid pairing link, URL, or legacy token. */
    INVALID_INPUT,
    /** Valid one-time code supplied, but no Server URL or stored endpoint is available. */
    SERVER_URL_REQUIRED,
    /** Input carries a `dev1_` or `pair1_` prefix but does not match the valid code shape. */
    INVALID_CODE,
}

sealed interface PairHearthAction {
    data class Enroll(val baseUrl: String, val code: String) : PairHearthAction
    data class RedeemPairCode(val baseUrl: String, val code: String) : PairHearthAction
    data class DirectEndpoint(val endpoint: Endpoint) : PairHearthAction
    data class Invalid(val error: PairHearthInputError) : PairHearthAction
}

object PairHearthLogic {

    fun normalizeServerUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val explicitScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> "http://"
            trimmed.startsWith("https://", ignoreCase = true) -> "https://"
            else -> null
        }
        val withScheme = if (explicitScheme != null) {
            // Lowercase the scheme itself: Android keyboards auto-capitalize
            // ("Https://...") and downstream endpoint policy compares schemes
            // case-sensitively.
            explicitScheme + trimmed.substring(explicitScheme.length)
        } else {
            val hostPart = trimmed.substringBefore(':').substringBefore('/')
            val scheme = if (
                EndpointPolicy.isPrivateV4(hostPart) ||
                hostPart.equals("localhost", ignoreCase = true) ||
                hostPart == "127.0.0.1"
            ) {
                "http"
            } else {
                "https"
            }
            // Hostnames are case-insensitive by DNS rules; lowercase so a
            // keyboard-capitalized "Localhost:7717" matches policy checks.
            "$scheme://${hostPart.lowercase()}${trimmed.substring(hostPart.length)}"
        }
        return withScheme.trimEnd('/')
    }

    fun canSubmit(
        pairingLink: String,
        serverUrl: String,
        token: String,
    ): Boolean =
        pairingLink.isNotBlank() ||
            (serverUrl.isNotBlank() && token.isNotBlank()) ||
            PairingLink.hasDev1Prefix(token) ||
            PairingLink.hasPair1Prefix(token)

    fun resolve(
        pairingLink: String,
        serverUrl: String,
        token: String,
        storedBaseUrl: String? = null,
    ): PairHearthAction {
        val rawLink = pairingLink.trim()
        val rawUrl = serverUrl.trim()
        val rawToken = token.trim()
        val effectiveBaseUrl = resolveBaseUrl(rawUrl, storedBaseUrl)

        if (rawLink.isNotEmpty()) {
            classifyOneTimeCode(rawLink, effectiveBaseUrl)?.let { return it }
            val parsed = PairingLink.parse(rawLink)
            return if (parsed != null) {
                PairHearthAction.DirectEndpoint(parsed.first)
            } else {
                PairHearthAction.Invalid(PairHearthInputError.INVALID_INPUT)
            }
        }

        if (rawToken.isNotEmpty()) {
            classifyOneTimeCode(rawToken, effectiveBaseUrl)?.let { return it }
        }

        if (rawUrl.isNotEmpty() && rawToken.isNotEmpty()) {
            val normalizedUrl = normalizeServerUrl(rawUrl)
            val built = "lararium://pair?host=" +
                URLEncoder.encode(normalizedUrl, "UTF-8") +
                "&token=" + URLEncoder.encode(rawToken, "UTF-8")
            val parsed = PairingLink.parse(built)
            return if (parsed != null) {
                PairHearthAction.DirectEndpoint(parsed.first)
            } else {
                PairHearthAction.Invalid(PairHearthInputError.INVALID_INPUT)
            }
        }

        return PairHearthAction.Invalid(PairHearthInputError.INVALID_INPUT)
    }

    private fun resolveBaseUrl(rawUrl: String, storedBaseUrl: String?): String? {
        if (rawUrl.isNotEmpty()) return normalizeServerUrl(rawUrl)
        val stored = storedBaseUrl?.trim()?.trimEnd('/')
        return stored?.takeIf { it.isNotEmpty() }
    }

    private fun classifyOneTimeCode(candidate: String, baseUrl: String?): PairHearthAction? {
        if (PairingLink.isDev1Code(candidate)) {
            if (baseUrl == null) {
                return PairHearthAction.Invalid(PairHearthInputError.SERVER_URL_REQUIRED)
            }
            return PairHearthAction.Enroll(
                baseUrl = baseUrl,
                code = PairingLink.normalizeDev1Code(candidate),
            )
        }
        if (PairingLink.hasDev1Prefix(candidate)) {
            return PairHearthAction.Invalid(PairHearthInputError.INVALID_CODE)
        }
        if (PairingLink.isPair1Code(candidate)) {
            if (baseUrl == null) {
                return PairHearthAction.Invalid(PairHearthInputError.SERVER_URL_REQUIRED)
            }
            return PairHearthAction.RedeemPairCode(
                baseUrl = baseUrl,
                code = PairingLink.normalizePair1Code(candidate),
            )
        }
        if (PairingLink.hasPair1Prefix(candidate)) {
            return PairHearthAction.Invalid(PairHearthInputError.INVALID_CODE)
        }
        return null
    }
}
