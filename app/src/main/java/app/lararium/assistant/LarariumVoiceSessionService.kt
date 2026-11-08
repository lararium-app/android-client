package app.lararium.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Session factory bound by the framework for [LarariumVoiceService]. */
class LarariumVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        LarariumVoiceSession(this)
}
