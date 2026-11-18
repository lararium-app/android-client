package app.lararium.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.lararium.assistant.ScreenContextPolicy

/** Pure helper: sanitized text, or empty string when the selection should be dropped. */
internal fun sanitizeProcessText(raw: CharSequence?, limit: Int = ScreenContextPolicy.SHARE_LIMIT): String =
    ScreenContextPolicy.sanitizeShared(raw?.toString().orEmpty(), limit)

/**
 * No-UI intake for ACTION_PROCESS_TEXT ("Ask Lararium" in the selection toolbar).
 * Forwards the sanitized selection to the overlay as ACTION_SEND for composer prefill only.
 */
class ShareTextGatewayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            val text = sanitizeProcessText(intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))
            if (text.isNotEmpty()) {
                startActivity(
                    Intent(Intent.ACTION_SEND).apply {
                        setClass(this@ShareTextGatewayActivity, CompactAssistantActivity::class.java)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                )
            }
        }
        finish()
    }
}
