package app.lararium.hearth.rails.service

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.lararium.hearth.EndpointPolicy
import app.lararium.hearth.HttpResponse
import app.lararium.hearth.Net
import app.lararium.hearth.rails.DeclaredRail
import app.lararium.hearth.rails.DeclaredRails
import app.lararium.hearth.rails.RailsWire
import app.lararium.security.SecureKeyManager
import org.json.JSONObject

/**
 * Orchestrates device pairing and registration intake (F6, spec A3).
 *
 * Wire contract per DEVICE-RAILS-SPEC (daemon: internal/surface/pair.go,
 * devices_routes.go):
 * 1. POST /v1/pair with body {"code": "<pair1_…>", "device_name": "…"}.
 *    A code-level failure must surface a token the UI maps to a friendly
 *    message ("bad token" → invalid/expired, "already_paired" → conflict).
 * 2. On success, generate device_id conforming to ^[a-z0-9][a-z0-9_-]{0,31}$.
 * 3. Build registration envelope via RailsWire.buildRegisterBody and POST
 *    /v1/devices/register with Bearer auth of the token MINTED in step 1 —
 *    the pairing code itself is never a bearer credential.
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

    /**
     * Every raw-socket call is dispatched off the caller's thread. The
     * pairing screen launches on the UI scope (Main) and Android throws
     * NetworkOnMainThreadException — with a null message — for plain
     * sockets opened there, which surfaced as "Hearth unreachable" with
     * zero packets on the wire.
     */
    private suspend fun postOffMain(
        httpPoster: suspend (String, String, Map<String, String>) -> HttpResponse,
        url: String,
        body: String,
        headers: Map<String, String>
    ): HttpResponse = withContext(Dispatchers.IO) { httpPoster(url, body, headers) }

    suspend fun autoRegister(
        context: Context,
        httpPoster: suspend (url: String, body: String, headers: Map<String, String>) -> HttpResponse = { u, b, h -> Net.post(u, b, h) },
        storeProvider: ((Context) -> DevicePairingStore)? = null,
        hearthEndpointProvider: ((Context) -> Pair<String, String>?)? = null,
        serviceStarter: ((Context) -> Unit)? = null
    ): Result {
        val stored = hearthEndpointProvider?.invoke(context)
            ?: try {
                SecureKeyManager(context).getHearthEndpoint()?.let { it.first to it.second }
            } catch (_: Throwable) {
                null
            }

        if (stored == null) {
            return Result.Failure("No hearth endpoint stored")
        }

        val (baseUrl, token) = stored
        val cleanBase = baseUrl.trimEnd('/')
        val rejectReason = EndpointPolicy.rejectReason(cleanBase)
        if (rejectReason != null) {
            return Result.Failure("Invalid endpoint URL: $rejectReason")
        }

        val deviceId = sanitizeDeviceId(Build.MODEL ?: "android")
        val displayName = sanitizeDisplayName(Build.MODEL ?: "android")

        val declaredRails = DeclaredRails.build()
        val registerBody = RailsWire.buildRegisterBody(
            deviceId = deviceId,
            displayName = displayName,
            railsDeclared = declaredRails
        )

        val registerUrl = "$cleanBase/v1/devices/register"
        val registerResponse = try {
            postOffMain(
                httpPoster,
                registerUrl,
                registerBody.toString(),
                mapOf(
                    "Authorization" to "Bearer $token",
                    "Content-Type" to "application/json"
                )
            )
        } catch (e: Exception) {
            return Result.Failure("Registration failed: ${e.message ?: "network error"}")
        }

        // Success (200/201/202 or 409 duplicate-name) marks rails registered.
        // On 409 treat as registered (idempotent).
        if (registerResponse.status in 200..299 || registerResponse.status == 409) {
            val endpoint = DeviceEndpoint(baseUrl = cleanBase, token = token, deviceId = deviceId)
            val store = storeProvider?.invoke(context) ?: DevicePairingStore(context)
            store.saveEndpoint(endpoint)

            if (serviceStarter != null) {
                serviceStarter(context)
            } else {
                DeviceRailService.ensureRunning(context)
            }

            return Result.Success(endpoint)
        }

        return Result.Failure("Registration rejected with status ${registerResponse.status}: ${registerResponse.body}")
    }

    suspend fun pair(
        context: Context,
        baseUrl: String = "",
        token: String = "",
        httpPoster: suspend (url: String, body: String, headers: Map<String, String>) -> HttpResponse = { u, b, h -> Net.post(u, b, h) },
        storeProvider: ((Context) -> DevicePairingStore)? = null,
        hearthEndpointProvider: ((Context) -> Pair<String, String>?)? = null,
        serviceStarter: ((Context) -> Unit)? = null
    ): Result {
        val storedEndpoint = hearthEndpointProvider?.invoke(context)
            ?: try {
                SecureKeyManager(context).getHearthEndpoint()?.let { it.first to it.second }
            } catch (_: Throwable) {
                null
            }

        val effectiveBase = (if (baseUrl.isNotBlank()) baseUrl else storedEndpoint?.first ?: "").trimEnd('/')
        val rejectReason = EndpointPolicy.rejectReason(effectiveBase)
        if (rejectReason != null) {
            return Result.Failure("Invalid endpoint URL: $rejectReason")
        }

        val authToken: String
        val isAutoPairing = storedEndpoint != null

        if (isAutoPairing) {
            // PO directive: one pairing lights up hearth AND rails.
            // If a hearth endpoint is already stored, reuse that stored token and skip pairing-code redemption entirely.
            authToken = storedEndpoint!!.second
        } else {
            // Step 1: redeem the pairing code. POST /v1/pair,
            // body {"code", "device_name"}; success -> {"token", "user_id"}.
            val pairUrl = "$effectiveBase/v1/pair"
            val pairBody = JSONObject()
                .put("code", token.trim())
                .put("device_name", Build.MODEL ?: "android")
                .toString()
            val pairResponse = try {
                postOffMain(
                    httpPoster,
                    pairUrl,
                    pairBody,
                    mapOf("Content-Type" to "application/json")
                )
            } catch (e: Exception) {
                return Result.Failure("Pairing failed: ${e.message ?: "network error"}")
            }

            // Friendly-token the UI maps: 401 -> invalid/expired code,
            // 409 -> hearth already paired / code spent.
            if (pairResponse.status == 401) {
                return Result.Failure("bad token", isRetryable = true)
            }
            if (pairResponse.status == 409) {
                return Result.Failure("already_paired", isRetryable = true)
            }
            if (pairResponse.status !in 200..299) {
                return Result.Failure(
                    "Pairing rejected with status ${pairResponse.status}: ${pairResponse.body}"
                )
            }
            val mintedToken = try {
                JSONObject(pairResponse.body).optString("token")
            } catch (_: Exception) {
                ""
            }
            if (mintedToken.isBlank()) {
                return Result.Failure("Pairing response carried no token")
            }
            authToken = mintedToken
        }

        // Step 2: Generate sanitized device_id matching ^[a-z0-9][a-z0-9_-]{0,31}$
        val deviceId = sanitizeDeviceId(Build.MODEL ?: "android")
        val displayName = sanitizeDisplayName(Build.MODEL ?: "android")

        // Step 3: Build registration payload and POST /v1/devices/register
        val declaredRails = DeclaredRails.build()
        val registerBody = RailsWire.buildRegisterBody(
            deviceId = deviceId,
            displayName = displayName,
            railsDeclared = declaredRails
        )

        val registerUrl = "$effectiveBase/v1/devices/register"
        val registerResponse = try {
            postOffMain(
                httpPoster,
                registerUrl,
                registerBody.toString(),
                mapOf(
                    "Authorization" to "Bearer $authToken",
                    "Content-Type" to "application/json"
                )
            )
        } catch (e: Exception) {
            return Result.Failure("Registration failed: ${e.message ?: "network error"}")
        }

        if (registerResponse.status == 409) {
            if (isAutoPairing) {
                // On 409 duplicate-name, treat as registered (idempotent)
                val endpoint = DeviceEndpoint(baseUrl = effectiveBase, token = authToken, deviceId = deviceId)
                val store = storeProvider?.invoke(context) ?: DevicePairingStore(context)
                store.saveEndpoint(endpoint)

                if (serviceStarter != null) {
                    serviceStarter(context)
                } else {
                    DeviceRailService.ensureRunning(context)
                }

                return Result.Success(endpoint)
            } else {
                return Result.Failure("duplicate_name", isRetryable = true)
            }
        }

        if (registerResponse.status !in 200..299) {
            return Result.Failure("Registration rejected with status ${registerResponse.status}: ${registerResponse.body}")
        }

        // Step 4: Persist credentials in DevicePairingStore —
        // the minted API token, never the spent pairing code.
        val endpoint = DeviceEndpoint(baseUrl = effectiveBase, token = authToken, deviceId = deviceId)
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

    fun sanitizeDisplayName(rawModel: String?): String {
        val clean = (rawModel ?: "android")
            .replace(Regex("[\\r\\n\\t]"), " ")
            .trim()
        return clean.ifEmpty { "android" }.take(64)
    }
}
