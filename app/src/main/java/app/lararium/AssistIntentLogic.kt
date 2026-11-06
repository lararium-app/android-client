package app.lararium

/**
 * Decides where platform assist launches (power-button long-press, assist gesture) land.
 *
 * Exists so that ASSIST / VOICE_COMMAND intents reach the hearth chat surface ("main"),
 * which already rehydrates the stored session or shows pairing onboarding when unpaired.
 * Kept free of Android imports (actions are plain strings) so it is JVM unit-testable.
 */
object AssistIntentLogic {
    // Intent.ACTION_ASSIST
    private const val ACTION_ASSIST = "android.intent.action.ASSIST"
    // Intent.ACTION_VOICE_COMMAND
    private const val ACTION_VOICE_COMMAND = "android.intent.action.VOICE_COMMAND"

    fun isAssistantLaunch(action: String?): Boolean =
        action == ACTION_ASSIST || action == ACTION_VOICE_COMMAND

    fun routeFor(action: String?): String? = if (isAssistantLaunch(action)) "main" else null
}
