package app.lararium.hearth.rails

/**
 * Pure JVM rail permission mapping and degraded UI state calculation (A4).
 * Contains ZERO android.* imports so it can be tested without Robolectric or framework stubs.
 */

const val NOTIFICATION_ACCESS = "notification_listener_enabled"
const val HEALTH_CONNECT = "health_connect"

const val PERM_READ_CALENDAR = "android.permission.READ_CALENDAR"
const val PERM_WRITE_CALENDAR = "android.permission.WRITE_CALENDAR"
const val PERM_READ_CONTACTS = "android.permission.READ_CONTACTS"
const val PERM_READ_CALL_LOG = "android.permission.READ_CALL_LOG"
const val PERM_CALL_PHONE = "android.permission.CALL_PHONE"
const val PERM_SEND_SMS = "android.permission.SEND_SMS"

data class RailUiState(
    val name: String,
    val enabled: Boolean,
    val degraded: Boolean,
    val missingPermissions: Set<String>
)

object RailPermissions {
    const val NOTIFICATION_ACCESS = app.lararium.hearth.rails.NOTIFICATION_ACCESS
    const val HEALTH_CONNECT = app.lararium.hearth.rails.HEALTH_CONNECT

    fun requiredPermissions(rail: String): Set<String> = app.lararium.hearth.rails.requiredPermissions(rail)
    fun togglePlan(rail: String, alreadyGranted: Set<String>): Set<String> = app.lararium.hearth.rails.togglePlan(rail, alreadyGranted)
    fun uiState(
        rail: String,
        enabled: Boolean,
        granted: Set<String>,
        accessState: Map<String, Boolean> = emptyMap()
    ): RailUiState = app.lararium.hearth.rails.uiState(rail, enabled, granted, accessState)
}

fun requiredPermissions(rail: String): Set<String> = when (rail) {
    "calendar" -> setOf(PERM_READ_CALENDAR, PERM_WRITE_CALENDAR)
    "contacts" -> setOf(PERM_READ_CONTACTS)
    "notifications" -> emptySet()
    "calllog" -> setOf(PERM_READ_CALL_LOG)
    "health" -> emptySet()
    "dialer" -> setOf(PERM_CALL_PHONE)
    "sms" -> setOf(PERM_SEND_SMS) // ONLY SEND_SMS — READ_SMS is strictly banned by spec
    else -> emptySet()
}

fun togglePlan(rail: String, alreadyGranted: Set<String>): Set<String> = when (rail) {
    "calendar", "contacts", "calllog", "dialer", "sms" -> {
        requiredPermissions(rail) - alreadyGranted
    }
    "notifications" -> {
        if (NOTIFICATION_ACCESS in alreadyGranted) emptySet() else setOf(NOTIFICATION_ACCESS)
    }
    "health" -> {
        if (HEALTH_CONNECT in alreadyGranted) emptySet() else setOf(HEALTH_CONNECT)
    }
    else -> emptySet()
}

fun uiState(
    rail: String,
    enabled: Boolean,
    granted: Set<String>,
    accessState: Map<String, Boolean> = emptyMap()
): RailUiState {
    if (rail !in RailsWire.RAIL_NAMES) {
        // Unknown rails are inert: never enabled, never degraded
        return RailUiState(
            name = rail,
            enabled = false,
            degraded = false,
            missingPermissions = emptySet()
        )
    }

    val missing = when (rail) {
        "notifications" -> {
            val hasAccess = accessState[NOTIFICATION_ACCESS] == true || NOTIFICATION_ACCESS in granted
            if (hasAccess) emptySet() else setOf(NOTIFICATION_ACCESS)
        }
        "health" -> {
            val hasAccess = accessState[HEALTH_CONNECT] == true || HEALTH_CONNECT in granted
            if (hasAccess) emptySet() else setOf(HEALTH_CONNECT)
        }
        else -> {
            requiredPermissions(rail) - granted
        }
    }

    return if (missing.isNotEmpty()) {
        RailUiState(
            name = rail,
            enabled = false, // missing grant/access => never enabled
            degraded = enabled, // degraded=true if user had it enabled (permission_revoked path)
            missingPermissions = missing
        )
    } else {
        RailUiState(
            name = rail,
            enabled = enabled,
            degraded = false,
            missingPermissions = emptySet()
        )
    }
}
