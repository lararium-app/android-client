package app.lararium.hearth.rails.service

import android.content.Context
import android.os.Build
import app.lararium.hearth.EndpointPolicy
import app.lararium.hearth.HttpResponse
import app.lararium.hearth.Net
import app.lararium.hearth.rails.DeclaredRail
import app.lararium.hearth.rails.RailsWire

/**
 * Orchestrates device pairing and registration intake (F6, spec A3).
 *
 * Sequence:
 * 1. POST /local/device/v1/pair with Bearer token per spec.
 * 2. On success, generate device_id conforming to ^[a-z0-9][a-z0-9_-]{0,31}$.
 * 3. Build registration envelope via RailsWire.buildRegisterBody and POST /local/device/v1/register.
 * 4. Persist endpoint credentials securely via DevicePairingStore.
 * 5. Start DeviceRailService.
 */
object DevicePairingController {

    sealed interface Result {
        val isSuccess: Boolean
        val endpoint: DeviceEndpoint?
        val error: String?
        val isRetryable: Boolean

        data class Success(override val endpoint: DeviceEndpoint) : Result {
            override val isSuccess: Boolean get() = true
            override val error: String? get() = null
            override val isRetryable: Boolean get() = false
        }

        data class Failure(
            override val error: String,
            override val isRetryable: Boolean = false
        ) : Result {
            override val isSuccess: Boolean get() = false
            override val endpoint: DeviceEndpoint? get() = null
        }
    }

    suspend fun pair(
        context: Context,
        baseUrl: String,
        token: String,
        httpPoster: suspend (url: String, body: String, headers: Map<String, String>) -> HttpResponse = { u, b, h -> Net.post(u, b, h) },
        storeProvider: ((Context) -> DevicePairingStore)? = null,
        serviceStarter: ((Context) -> Unit)? = null
    ): Result {
        val cleanBase = baseUrl.trimEnd('/')
        val rejectReason = EndpointPolicy.rejectReason(cleanBase)
        if (rejectReason != null) {
            return Result.Failure("Invalid endpoint URL: $rejectReason")
        }

        val authHeaders = mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json"
        )

        // Step 1: POST /local/device/v1/pair
        val pairUrl = "$cleanBase/local/device/v1/pair"
        val pairResponse = try {
            httpPoster(pairUrl, "{}", authHeaders)
        } catch (e: Exception) {
            return Result.Failure("Pairing failed: ${e.message ?: "network error"}")
        }

        if (pairResponse.status !in 200..299) {
            return Result.Failure("Pairing rejected with status ${pairResponse.status}: ${pairResponse.body}")
        }

        // Step 2: Generate sanitized device_id matching ^[a-z0-9][a-z0-9_-]{0,31}$
        val deviceId = sanitizeDeviceId(Build.MODEL ?: "android")

        // Step 3: Build registration payload and POST /local/device/v1/register
        val declaredRails = RailsWire.RAIL_NAMES.map { name ->
            DeclaredRail(
                name = name,
                readOnly = name in RailsWire.READ_RAILS,
                enabled = true
            )
        }
        val registerBody = RailsWire.buildRegisterBody(
            deviceId = deviceId,
            displayName = "Lararium Device",
            railsDeclared = declaredRails
        )

        val registerUrl = "$cleanBase/local/device/v1/register"
        val registerResponse = try {
            httpPoster(registerUrl, registerBody.toString(), authHeaders)
        } catch (e: Exception) {
            return Result.Failure("Registration failed: ${e.message ?: "network error"}")
        }

        if (registerResponse.status == 409) {
            return Result.Failure("duplicate_name", isRetryable = true)
        }

        if (registerResponse.status !in 200..299) {
            return Result.Failure("Registration rejected with status ${registerResponse.status}: ${registerResponse.body}")
        }

        // Step 4: Persist credentials in DevicePairingStore
        val endpoint = DeviceEndpoint(baseUrl = cleanBase, token = token, deviceId = deviceId)
        val store = storeProvider?.invoke(context) ?: DevicePairingStore(context)
        store.saveEndpoint(endpoint)

        // Step 5: Start DeviceRailService
        if (serviceStarter != null) {
            serviceStarter(context)
        } else {
            DeviceRailService.ensureRunning(context)
        }

        return Result.Success(endpoint)
    }

    fun sanitizeDeviceId(rawModel: String): String {
        var slug = rawModel.lowercase()
            .replace(Regex("[^a-z0-9_-]"), "-")
            .trim('-')
            .ifEmpty { "device" }

        if (!slug.first().isLetterOrDigit()) {
            slug = "d$slug"
        }
        return slug.take(32)
    }
}
