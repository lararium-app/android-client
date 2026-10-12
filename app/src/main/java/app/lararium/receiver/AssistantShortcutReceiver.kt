package app.lararium.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.lararium.service.AssistantService

/**
 * Receiver for assistant shortcut actions.
 * Can be triggered via launcher shortcuts or Tasker/automation apps.
 */
class AssistantShortcutReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ACTIVATE_ASSISTANT -> {
                val serviceIntent = Intent(context, AssistantService::class.java).apply {
                    action = AssistantService.ACTION_ACTIVATE
                    putExtra(AssistantService.EXTRA_TRIGGER, "shortcut")
                }
                context.startForegroundService(serviceIntent)
            }
            ACTION_VOICE_INPUT -> {
                val serviceIntent = Intent(context, AssistantService::class.java).apply {
                    action = AssistantService.ACTION_START_VOICE
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }

    companion object {
        const val ACTION_ACTIVATE_ASSISTANT = "app.lararium.ACTIVATE_ASSISTANT"
        const val ACTION_VOICE_INPUT = "app.lararium.VOICE_INPUT"
    }
}
