package app.lararium.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings manager for app preferences.
 */
class SettingsManager(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "assistant_settings"
        private const val KEY_VOICE_INPUT_METHOD = "voice_input_method"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_AUTO_SEND_VOICE = "auto_send_voice"
        private const val KEY_AUTO_START_VOICE = "auto_start_voice"
        private const val KEY_VOICE_LANGUAGE = "voice_language"
        private const val KEY_SECONDARY_LANGUAGE = "secondary_voice_language"
        private const val KEY_MULTILINGUAL_ENABLED = "multilingual_enabled"
        private const val KEY_TTS_LANGUAGE = "tts_language"
        const val KEY_LOCK_ORIENTATION = "lock_orientation"
        const val DEFAULT_LOCK_ORIENTATION = true
        
        const val VOICE_INPUT_SYSTEM = "system"
        const val VOICE_INPUT_VOSK = "vosk"
        
        const val DEFAULT_LANGUAGE = "en"
        const val DEFAULT_TTS_LANGUAGE = "en-US"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    var voiceInputMethod: String
        get() {
            val method = prefs.getString(KEY_VOICE_INPUT_METHOD, VOICE_INPUT_VOSK)
            return if (method == VOICE_INPUT_SYSTEM) VOICE_INPUT_SYSTEM else VOICE_INPUT_VOSK
        }
        set(value) {
            val sanitized = if (value == VOICE_INPUT_SYSTEM) VOICE_INPUT_SYSTEM else VOICE_INPUT_VOSK
            prefs.edit().putString(KEY_VOICE_INPUT_METHOD, sanitized).apply()
        }
    
    var voiceLanguage: String
        get() = prefs.getString(KEY_VOICE_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        set(value) = prefs.edit().putString(KEY_VOICE_LANGUAGE, value).apply()
    
    var secondaryVoiceLanguage: String
        get() = prefs.getString(KEY_SECONDARY_LANGUAGE, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_SECONDARY_LANGUAGE, value).apply()
    
    var multilingualEnabled: Boolean
        get() = prefs.getBoolean(KEY_MULTILINGUAL_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_MULTILINGUAL_ENABLED, value).apply()
    
    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    var lockOrientation: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ORIENTATION, DEFAULT_LOCK_ORIENTATION)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ORIENTATION, value).apply()
    
    var autoSendVoice: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SEND_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SEND_VOICE, value).apply()
    
    var autoStartVoice: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_VOICE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_VOICE, value).apply()

    var ttsLanguage: String
        get() = prefs.getString(KEY_TTS_LANGUAGE, DEFAULT_TTS_LANGUAGE) ?: DEFAULT_TTS_LANGUAGE
        set(value) = prefs.edit().putString(KEY_TTS_LANGUAGE, value).apply()
    
    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
}
