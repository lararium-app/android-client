package app.lararium.hearth.rails.service

import app.lararium.hearth.EndpointPolicy
import java.net.URI

/**
 * Endpoint configuration for device rails HTTP and SSE stream connections.
 * Reuses the existing Endpoint / EndpointPolicy pattern for base URL handling.
 */
data class DeviceEndpoint(
    val baseUrl: String,
    val token: String,
    val deviceId: String
) {
    val isLocal: Boolean
        get() {
            val host = try {
                URI(baseUrl).host
            } catch (_: Exception) {
                null
            } ?: return false
            return host == "localhost" || host == "::1" || EndpointPolicy.isPrivateV4(host)
        }

    fun authHeaders(): Map<String, String> = mapOf(
        "Authorization" to "Bearer $token"
    )

    fun validate(): String? = EndpointPolicy.rejectReason(baseUrl)
}
