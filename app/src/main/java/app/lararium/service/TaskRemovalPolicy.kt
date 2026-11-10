package app.lararium.service

/**
 * Pure Kotlin policy governing resurrection of [AssistantService] after the user
 * swipes Lararium away from recent apps (issue #124).
 * Pure logic — zero Android dependencies for pure JVM unit testability.
 *
 * When the app task is swiped away from recent apps on stock Android, the package
 * enters stopped-state, unbinding VoiceInteractionManagerService until the package runs
 * again. If the user has configured Lararium (a hearth endpoint is present), the service
 * reschedules itself to restore process and foreground service state.
 *
 * Note: Unpair clears the hearth endpoint synchronously BEFORE stopping the service
 * (see SecureKeyManager.clearHearthEndpoint() in SettingsScreen), so endpoint presence
 * itself serves as the intent signal that the user desires background readiness.
 */
class TaskRemovalPolicy(
    private val nowMs: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Delay before rescheduling AssistantService via AlarmManager (~3 s).
         * Tuned for immediate retry without triggering system crash loops.
         */
        const val RESCHEDULE_DELAY_MS = 3_000L

        /**
         * Window during which resurrection is suppressed following a deliberate unpair event.
         */
        const val RESURRECT_SUPPRESSION_MS = 60_000L
    }

    /**
     * Determine whether the assistant service should be rescheduled on task removal.
     *
     * @param hearthConfigured true if a hearth endpoint is currently configured in secure storage.
     * @return true if the service should be rescheduled.
     */
    fun shouldReschedule(hearthConfigured: Boolean): Boolean {
        return hearthConfigured
    }

    /**
     * Determine whether the assistant service should be rescheduled on task removal,
     * taking into account a potential deliberate unpair timestamp.
     *
     * @param hearthConfigured true if a hearth endpoint is currently configured in secure storage.
     * @param deliberatelyUnpairedAt optional timestamp (ms) of deliberate unpair; if provided and within
     * [RESURRECT_SUPPRESSION_MS], suppresses resurrection.
     * @return true if the service should be rescheduled.
     */
    fun shouldReschedule(
        hearthConfigured: Boolean,
        deliberatelyUnpairedAt: Long?
    ): Boolean {
        return hearthConfigured && (deliberatelyUnpairedAt == null || nowMs - deliberatelyUnpairedAt > RESURRECT_SUPPRESSION_MS)
    }
}
