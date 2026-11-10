package app.lararium.service

/**
 * Pure Kotlin policy deciding whether the persistent foreground service shield
 * should remain active.
 *
 * Keeps the FGS shield active while a hearth endpoint is configured OR assistant-role
 * activation has ever happened, unless the user explicitly requested shutdown / opted out.
 */
object AssistantFgsPolicy {
    fun shouldKeepShieldUp(
        roleActivated: Boolean,
        endpointConfigured: Boolean,
        userOptOut: Boolean = false
    ): Boolean {
        if (userOptOut) return false
        return roleActivated || endpointConfigured
    }
}
