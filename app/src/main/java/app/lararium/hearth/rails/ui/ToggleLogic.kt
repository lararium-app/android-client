package app.lararium.hearth.rails.ui

import app.lararium.hearth.rails.NOTIFICATION_ACCESS
import app.lararium.hearth.rails.HEALTH_CONNECT
import app.lararium.hearth.rails.RailPermissions
import app.lararium.hearth.rails.RailUiState
import app.lararium.hearth.rails.RailsWire

/**
 * Pure JVM presenter and toggle plan logic for device rails control panel (G1).
 * Contains ZERO android.* imports so it can be tested without Robolectric or framework stubs.
 */
object ToggleLogic {

    sealed interface TogglePlanAction {
        object ReadyToEnable : TogglePlanAction
        data class RequestRuntimePermissions(val permissions: Set<String>) : TogglePlanAction
        object OpenNotificationAccessSettings : TogglePlanAction
        object ShowHealthConnectPlaceholder : TogglePlanAction
    }

    data class RailRowDisplay(
        val name: String,
        val displayName: String,
        val isChecked: Boolean,
        val isDegraded: Boolean,
        val hint: String?,
        val isReadOnly: Boolean
    )

    fun displayName(rail: String): String = when (rail) {
        "calendar" -> "Calendar"
        "contacts" -> "Contacts"
        "notifications" -> "Notifications"
        "calllog" -> "Call Log"
        "health" -> "Health Connect"
        "dialer" -> "Phone Dialer"
        "sms" -> "SMS Dispatch"
        else -> rail.replaceFirstChar { it.uppercase() }
    }

    fun planToggleOn(
        rail: String,
        granted: Set<String>,
        accessState: Map<String, Boolean> = emptyMap()
    ): TogglePlanAction {
        val effectiveGranted = granted + accessState.filterValues { it }.keys
        val missing = RailPermissions.togglePlan(rail, effectiveGranted)

        return when {
            missing.isEmpty() -> TogglePlanAction.ReadyToEnable
            NOTIFICATION_ACCESS in missing -> TogglePlanAction.OpenNotificationAccessSettings
            HEALTH_CONNECT in missing -> TogglePlanAction.ShowHealthConnectPlaceholder
            else -> TogglePlanAction.RequestRuntimePermissions(missing)
        }
    }

    fun canEnableAfterGrants(
        rail: String,
        granted: Set<String>,
        accessState: Map<String, Boolean> = emptyMap()
    ): Boolean {
        val effectiveGranted = granted + accessState.filterValues { it }.keys
        return RailPermissions.togglePlan(rail, effectiveGranted).isEmpty()
    }

    fun permissionHint(railUiState: RailUiState): String? {
        if (railUiState.missingPermissions.isEmpty()) return null
        val missing = railUiState.missingPermissions
        return when {
            NOTIFICATION_ACCESS in missing -> "Notification listener access required in system settings"
            HEALTH_CONNECT in missing -> "Health Connect placeholder (preview feature)"
            else -> {
                val readableNames = missing.map { perm ->
                    perm.substringAfterLast('.')
                }.sorted().joinToString(", ")
                "Permission required: $readableNames"
            }
        }
    }

    fun buildRowDisplay(
        rail: String,
        storeEnabled: Boolean,
        granted: Set<String>,
        accessState: Map<String, Boolean> = emptyMap()
    ): RailRowDisplay {
        val uiState = RailPermissions.uiState(rail, storeEnabled, granted, accessState)
        val hint = permissionHint(uiState)
        return RailRowDisplay(
            name = rail,
            displayName = displayName(rail),
            isChecked = uiState.enabled,
            isDegraded = uiState.degraded,
            hint = hint,
            isReadOnly = rail in RailsWire.READ_RAILS
        )
    }

    fun buildAllRows(
        storeEnabledMap: Map<String, Boolean>,
        granted: Set<String>,
        accessState: Map<String, Boolean> = emptyMap()
    ): List<RailRowDisplay> {
        return RailsWire.RAIL_NAMES.map { rail ->
            buildRowDisplay(
                rail = rail,
                storeEnabled = storeEnabledMap[rail] ?: false,
                granted = granted,
                accessState = accessState
            )
        }
    }
}
