package app.lararium.hearth.rails.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.lararium.hearth.rails.DispatchedAction

/**
 * Executes device dialer calls via ACTION_CALL (F4).
 * Never re-checks permissions at execute time beyond try/catch SecurityException -> "permission_revoked".
 */
class DialerToolExecutor(
    private val context: Context,
    private val intentFactory: (action: String, phoneUri: String) -> Intent = { action, phoneUri ->
        Intent(action).apply {
            try { data = Uri.parse(phoneUri) } catch (_: Exception) {}
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    },
    private val callLauncher: (Intent) -> Unit = { intent -> context.startActivity(intent) }
) : RailToolExecutor {

    override fun canHandle(tool: String): Boolean = (tool == "device.dialer.call")

    override suspend fun execute(action: DispatchedAction): Pair<Boolean, String> {
        val phone = action.args.optString("phone").ifEmpty {
            action.args.optString("number")
        }
        if (phone.isBlank()) {
            return Pair(false, "missing_phone_number")
        }

        val phoneUri = "tel:$phone"
        val intent = intentFactory(Intent.ACTION_CALL, phoneUri)

        return try {
            callLauncher(intent)
            Pair(true, "call_placed")
        } catch (e: SecurityException) {
            Pair(false, "permission_revoked")
        } catch (e: Exception) {
            Pair(false, e.message ?: "dialer_error")
        }
    }
}
