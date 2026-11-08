package app.lararium.hearth.rails

/**
 * Single source of truth for which rails this build declares to the daemon
 * (and shows in the UI). Since the flavor unification (T4) every build
 * declares every rail: dialer, sms and calllog degrade at runtime when their
 * permission is not granted (via the ASSISTANT role path or otherwise) instead
 * of being compiled out.
 */
object DeclaredRails {
    fun names(): Set<String> = RailsWire.RAIL_NAMES

    fun build(): List<DeclaredRail> =
        names().map { name ->
            DeclaredRail(
                name = name,
                readOnly = name in RailsWire.READ_RAILS,
                // health: placeholder data source (issue #94); flip when a real
                // Health Connect source lands.
                enabled = name != "health"
            )
        }
}
