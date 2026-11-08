package app.lararium.hearth.rails.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import app.lararium.hearth.rails.DispatchedAction

/**
 * Executes device dialer calls via ACTION_CALL (F4).
 * The intent is pinned to the default dialer package so the dialed number never leaks
 * through implicit resolution to arbitrary handlers.
 * Never re-checks permissions at execute time beyond try/catch SecurityException -> "permission_revoked".
 */
class DialerToolExecutor(
    private val context: Context,
    private val intentFactory: (action: String, phoneUri: String, dialerPackage: String) -> Intent =
        { action, phoneUri, dialerPackage ->
            // Pinned at construction: the tel: data must never sit on an implicit intent.
            Intent(action).apply {
                setPackage(dialerPackage)
                try { data = Uri.parse(phoneUri) } catch (_: Exception) {}
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        },
    private val callLauncher: (Intent) -> Unit = { intent -> context.startActivity(intent) },
    private val defaultDialerPackage: () -> String? = {
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
    }
) : RailToolExecutor {

    override fun canHandle(tool: String): Boolean = (tool == "device.dialer.call")

    override suspend fun execute(action: DispatchedAction): Pair<Boolean, String> {
        val phone = action.args.optString("phone").ifEmpty {
            action.args.optString("number")
        }
        if (phone.isBlank()) {
            return Pair(false, "missing_phone_number")
        }

        val dialerPackage = defaultDialerPackage() ?: return Pair(false, "no_default_dialer")
        val phoneUri = "tel:$phone"

        // Everything past this point is inside the try: a missing/revoked
        // CALL_PHONE (or any other failure) must degrade to a result, never
        // throw to the caller.
        return try {
            val intent = intentFactory(Intent.ACTION_CALL, phoneUri, dialerPackage)
            intent.setPackage(dialerPackage)
            callLauncher(intent)
            Pair(true, "call_placed")
        } catch (e: SecurityException) {
            Pair(false, "permission_revoked")
        } catch (e: Exception) {
            Pair(false, e.message ?: "dialer_error")
        }
    }
}
