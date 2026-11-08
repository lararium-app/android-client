package app.lararium.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import app.lararium.service.AssistantService
import app.lararium.ui.CompactAssistantActivity

/**
 * Thin session: a system summon routes into the same entry the QS tile uses
 * ([AssistantService.ACTION_ACTIVATE]) and surfaces the existing
 * [CompactAssistantActivity] overlay. No UI of its own.
 */
class LarariumVoiceSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "LarariumVoiceSession"
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        try {
            // A VoiceInteractionSession is allowed to surface UI for the active
            // assistant. Start it through the session rather than from the
            // background service; recent OxygenOS/Android builds can reject the
            // latter and previously leave the user with a brief flash.
            val serviceIntent = Intent(context, AssistantService::class.java).apply {
                action = AssistantService.ACTION_ACTIVATE
                putExtra(AssistantService.EXTRA_TRIGGER, "voice_interaction")
                putExtra(AssistantService.EXTRA_LAUNCH_OVERLAY, false)
            }
            context.startForegroundService(serviceIntent)
            startVoiceActivity(Intent(context, CompactAssistantActivity::class.java))
        } catch (error: RuntimeException) {
            // Never let a system-assistant callback crash the process; the
            // error stays in logcat for device-specific policy diagnosis.
            Log.e(TAG, "Could not launch assistant from voice interaction", error)
        } finally {
            // The overlay activity owns the visible UI once launched.
            safeHide()
        }
    }

    private fun safeHide() {
        try {
            hide()
        } catch (error: RuntimeException) {
            Log.w(TAG, "hide() failed; session already torn down", error)
        }
    }
}
