package app.lararium.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Minimal stub satisfying the voice-interaction metadata contract.
 *
 * Holding ROLE_ASSISTANT requires android:recognitionService in the
 * voice_interaction_service metadata per AOSP VoiceInteractionServiceInfo.
 * The app's own mic pipeline is untouched, wake-word detection via
 * [RecognizerIntent] is out of scope by product decision, and summon flows do
 * not ask this service to listen.
 * Any listening request immediately reports [SpeechRecognizer.ERROR_NO_MATCH].
 */
class LarariumRecognitionService : RecognitionService() {

    protected override fun onStartListening(recognizerIntent: Intent?, callback: Callback?) {
        callback?.error(SpeechRecognizer.ERROR_NO_MATCH)
    }

    protected override fun onStopListening(callback: Callback?) {
        // No-op stub: recognition is not performed by this service.
    }

    protected override fun onCancel(callback: Callback?) {
        // No-op stub: recognition is not performed by this service.
    }
}
