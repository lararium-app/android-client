package app.lararium.hearth.rails.ui

import org.json.JSONObject

/**
 * Pure JVM humanized label and countdown formatting for action confirmation sheets (G6).
 * Contains ZERO android.* imports so it can be tested without Robolectric or framework stubs.
 */
object ActionFormatting {

    fun humanLabel(tool: String, args: JSONObject): String {
        return when (tool) {
            "device.dialer.call", "device.dialer.dial", "device.dialer_call", "dialer_call" -> {
                val phone = extractPhone(args)
                if (phone.isNotBlank()) {
                    "Call $phone"
                } else {
                    "Call"
                }
            }
            "device.sms.send", "device.sms.draft", "device.sms_send", "sms_send" -> {
                val phone = extractPhone(args)
                val text = extractMessage(args)
                when {
                    phone.isNotBlank() && text.isNotBlank() -> "Send SMS to $phone: $text"
                    phone.isNotBlank() -> "Send SMS to $phone"
                    text.isNotBlank() -> "Send SMS: $text"
                    else -> "Send SMS"
                }
            }
            else -> "Run device action $tool"
        }
    }

    private fun extractPhone(args: JSONObject): String {
        return args.optString("phone").ifEmpty {
            args.optString("number").ifEmpty {
                args.optString("recipient").ifEmpty {
                    args.optString("to")
                }
            }
        }.trim()
    }

    private fun extractMessage(args: JSONObject): String {
        return args.optString("text").ifEmpty {
            args.optString("message").ifEmpty {
                args.optString("body").ifEmpty {
                    args.optString("content")
                }
            }
        }.trim()
    }

    fun countdownLabel(msRemaining: Long): String {
        if (msRemaining <= 0L) {
            return "Blocked"
        }
        val seconds = ((msRemaining + 999L) / 1000L).coerceAtMost(60L)
        return "${seconds}s"
    }
}
