package app.lararium.hearth.rails.ui

import app.lararium.hearth.EndpointPolicy
import app.lararium.hearth.rails.service.DevicePairLinkParser
import app.lararium.hearth.rails.service.PairRequest

/**
 * Pure JVM URL and pairing token validation logic (G2).
 * Validates base URLs through EndpointPolicy and handles deep-link prefill parsing.
 * Contains ZERO android.* imports so it can be tested without Robolectric or framework stubs.
 */
object PairingValidation {

    data class ValidationResult(val isValid: Boolean, val error: String? = null)

    fun validateBaseUrl(url: String): ValidationResult {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            return ValidationResult(isValid = false, error = "Base URL cannot be blank")
        }

        val cleanUrl = trimmed.trimEnd('/')
        val reason = EndpointPolicy.rejectReason(cleanUrl)
        if (reason != null) {
            return ValidationResult(isValid = false, error = reason)
        }

        return ValidationResult(isValid = true, error = null)
    }

    fun validateToken(token: String): ValidationResult {
        val trimmed = token.trim()
        if (trimmed.isBlank()) {
            return ValidationResult(isValid = false, error = "Pairing token cannot be blank")
        }
        return ValidationResult(isValid = true, error = null)
    }

    fun parseDeepLink(uriString: String?): PairRequest? {
        if (uriString.isNullOrBlank()) return null
        return DevicePairLinkParser.parse(uriString)
    }

    fun friendlyPairErrorMessage(error: String): String {
        return when {
            error == "duplicate_name" -> "This device name is taken — retry"
            error.contains("401") || error.contains("403") || error.contains("bad token") -> "Invalid or expired pairing token"
            error.contains("already_paired") || error.contains("already-paired") -> "This device is already paired elsewhere"
            error.contains("unreachable") || error.contains("network error") || error.contains("Failed to connect") -> "Hearth unreachable — check endpoint and network"
            else -> error
        }
    }
}
