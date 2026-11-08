package app.lararium.assistant

import android.service.voice.VoiceInteractionService

/**
 * The ROLE_ASSISTANT machinery (T4 stage 1). Holding the assistant role
 * requires an app to expose a [VoiceInteractionService]; the system then binds
 * it and routes power-button / hold-gesture / long-press-home summons to
 * [LarariumVoiceSession] via [LarariumVoiceSessionService].
 *
 * Deliberately empty: no wake word or hotword detection here (stages 2/3).
 * The role is additive — nothing in the app depends on this service running.
 */
class LarariumVoiceService : VoiceInteractionService()
