package app.lararium.assistant

/**
 * Pure JVM policy for the Settings assistant-role row (T4). No android.*
 * imports so it is testable without framework stubs.
 *
 * The role is additive: [Row.Fallback] reproduces today's behavior (an
 * explanation row that opens the system voice-input settings) and is chosen
 * whenever the role cannot be requested.
 */
object AssistantRolePolicy {
    /** RoleManager exists from API 29 (Q); minSdk is 26. */
    const val MIN_SDK_FOR_ROLE_REQUEST = 29

    enum class Row {
        /** Role not held: show "Set Lararium as assistant" (launches the role request). */
        RequestRole,

        /** Role held: show "Lararium is your assistant" + "Change assistant". */
        Held,

        /** API < 29 or role unavailable: plain explanation row, no role request. */
        Fallback
    }

    fun canRequestRole(sdkInt: Int, roleAvailable: Boolean): Boolean =
        sdkInt >= MIN_SDK_FOR_ROLE_REQUEST && roleAvailable

    fun rowFor(sdkInt: Int, roleAvailable: Boolean, roleHeld: Boolean): Row = when {
        !canRequestRole(sdkInt, roleAvailable) -> Row.Fallback
        roleHeld -> Row.Held
        else -> Row.RequestRole
    }
}
