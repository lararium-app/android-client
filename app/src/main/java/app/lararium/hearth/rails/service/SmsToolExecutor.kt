package app.lararium.hearth.rails.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import app.lararium.hearth.rails.DispatchedAction

/**
 * Executes SMS dispatch via SmsManager.sendTextMessage (F4).
 * Delivery tracked via sent/delivered PendingIntent broadcasts.
 * Never re-checks permissions at execute time beyond try/catch SecurityException -> "permission_revoked".
 */
class SmsToolExecutor(
    private val context: Context,
    private val smsSender: ((destinationAddress: String, scAddress: String?, text: String, sentIntent: PendingIntent?, deliveryIntent: PendingIntent?) -> Unit)? = null
) : RailToolExecutor {

    companion object {
        const val ACTION_SMS_SENT = "app.lararium.rails.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "app.lararium.rails.SMS_DELIVERED"
    }

    override fun canHandle(tool: String): Boolean = (tool == "device.sms.send")

    override suspend fun execute(action: DispatchedAction): Pair<Boolean, String> {
        val phone = action.args.optString("phone").ifEmpty {
            action.args.optString("number").ifEmpty {
                action.args.optString("recipient")
            }
        }
        val text = action.args.optString("text").ifEmpty {
            action.args.optString("message")
        }

        if (phone.isBlank()) {
            return Pair(false, "missing_phone_number")
        }

        val sentIntent = PendingIntent.getBroadcast(
            context,
            action.actionId.hashCode(),
            Intent(ACTION_SMS_SENT).apply {
                setPackage(context.packageName)
                putExtra("action_id", action.actionId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deliveryIntent = PendingIntent.getBroadcast(
            context,
            action.actionId.hashCode(),
            Intent(ACTION_SMS_DELIVERED).apply {
                setPackage(context.packageName)
                putExtra("action_id", action.actionId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return try {
            if (smsSender != null) {
                smsSender.invoke(phone, null, text, sentIntent, deliveryIntent)
            } else {
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                smsManager.sendTextMessage(phone, null, text, sentIntent, deliveryIntent)
            }
            Pair(true, "sms_dispatched")
        } catch (e: SecurityException) {
            Pair(false, "permission_revoked")
        } catch (e: Exception) {
            Pair(false, e.message ?: "sms_error")
        }
    }
}
