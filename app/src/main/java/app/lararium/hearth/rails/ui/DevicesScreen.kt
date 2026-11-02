package app.lararium.hearth.rails.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.lararium.hearth.rails.DeviceStreamState
import app.lararium.hearth.rails.NOTIFICATION_ACCESS
import app.lararium.hearth.rails.HEALTH_CONNECT
import app.lararium.hearth.rails.RailPermissions
import app.lararium.hearth.rails.RailsWire
import app.lararium.hearth.rails.service.DevicePairingStore
import app.lararium.hearth.rails.service.DeviceRailService

/**
 * Devices control panel (G1, spec A3/A4).
 * Lists all rails, controls per-rail switches with least-privilege permission gating,
 * displays hearth connection state banner, persistent re-pair hard gate, and forget device action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPairing: (baseUrl: String?, token: String?) -> Unit
) {
    val context = LocalContext.current
    val store = remember { DevicePairingStore(context) }
    val plainPrefs = remember {
        context.getSharedPreferences(DevicePairingStore.PLAIN_PREFS_NAME, Context.MODE_PRIVATE)
    }

    var isPaired by remember { mutableStateOf(store.isPaired()) }
    var endpoint by remember { mutableStateOf(store.getEndpoint()) }
    val streamState by DeviceRailService.streamState.collectAsState()

    var showForgetDialog by remember { mutableStateOf(false) }
    var showHealthConnectDialog by remember { mutableStateOf(false) }
    var pendingRailToEnable by remember { mutableStateOf<String?>(null) }

    fun readStoreEnabledMap(): Map<String, Boolean> {
        return RailsWire.RAIL_NAMES.associateWith { rail ->
            plainPrefs.getBoolean("rail_enabled_$rail", true)
        }
    }

    var storeEnabledMap by remember { mutableStateOf(readStoreEnabledMap()) }

    fun refreshGrantedPermissions(): Set<String> {
        val allPermissions = RailsWire.RAIL_NAMES.flatMap {
            RailPermissions.requiredPermissions(it)
        }.toSet()

        return allPermissions.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }.toSet()
    }

    fun isNotificationAccessGranted(): Boolean {
        return try {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""
            enabledListeners.contains(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    var grantedPermissions by remember { mutableStateOf(refreshGrantedPermissions()) }
    var accessState by remember {
        mutableStateOf(
            mapOf(
                NOTIFICATION_ACCESS to isNotificationAccessGranted(),
                HEALTH_CONNECT to false
            )
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val newlyGranted = refreshGrantedPermissions()
        grantedPermissions = newlyGranted

        val rail = pendingRailToEnable
        if (rail != null) {
            pendingRailToEnable = null
            if (ToggleLogic.canEnableAfterGrants(rail, newlyGranted, accessState)) {
                plainPrefs.edit().putBoolean("rail_enabled_$rail", true).apply()
                storeEnabledMap = readStoreEnabledMap()
            }
        }
    }

    LaunchedEffect(Unit) {
        isPaired = store.isPaired()
        endpoint = store.getEndpoint()
        storeEnabledMap = readStoreEnabledMap()
        grantedPermissions = refreshGrantedPermissions()
        accessState = mapOf(
            NOTIFICATION_ACCESS to isNotificationAccessGranted(),
            HEALTH_CONNECT to false
        )
        if (isPaired) {
            DeviceRailService.ensureRunning(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Rails") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        if (!isPaired) {
            // Empty State: Not paired -> Pair button
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Devices,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "No Device Linked",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Connect this phone to your hearth to expose capabilities and execute double-gated local actions.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (app.lararium.BuildConfig.DIST_CHANNEL == "play") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Some rails (SMS, call log) are only in the GitHub build — github.com/lararium-app/android-client/releases",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { onNavigateToPairing(null, null) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Pair with Hearth", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            // Paired state: Rails control panel
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header Block: Connection status and controls
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = endpoint?.deviceId ?: "Device Link",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = endpoint?.baseUrl ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                ConnectionStatusBadge(state = streamState)
                            }

                            // Persistent Re-pair button if RepairRequired
                            if (streamState is DeviceStreamState.RepairRequired) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.errorContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Pairing Revoked",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            Text(
                                                text = "Hearth pairing has expired or was revoked.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                val oldBase = endpoint?.baseUrl
                                                store.clear()
                                                DeviceRailService.stopIfIdle(context)
                                                isPaired = false
                                                onNavigateToPairing(oldBase, null)
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error
                                            )
                                        ) {
                                            Text("Re-pair")
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { showForgetDialog = true },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Forget Device")
                                }
                            }
                        }
                    }
                }

                // Section header
                item {
                    Text(
                        text = "EXPOSED CAPABILITIES",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                    )
                }

                if (app.lararium.BuildConfig.DIST_CHANNEL == "play") {
                    item {
                        Text(
                            text = "Some rails (SMS, call log) are only in the GitHub build — github.com/lararium-app/android-client/releases",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                    }
                }

                // Rails rows
                val rows = ToggleLogic.buildAllRows(
                    storeEnabledMap = storeEnabledMap,
                    granted = grantedPermissions,
                    accessState = accessState
                )

                items(rows, key = { it.name }) { row ->
                    RailRowItem(
                        display = row,
                        onCheckedChange = { willEnable ->
                            if (!willEnable) {
                                // Toggling OFF: disable in store, no permission revocation
                                plainPrefs.edit().putBoolean("rail_enabled_${row.name}", false).apply()
                                storeEnabledMap = readStoreEnabledMap()
                            } else {
                                // Toggling ON: run togglePlan first
                                when (val plan = ToggleLogic.planToggleOn(row.name, grantedPermissions, accessState)) {
                                    is ToggleLogic.TogglePlanAction.ReadyToEnable -> {
                                        plainPrefs.edit().putBoolean("rail_enabled_${row.name}", true).apply()
                                        storeEnabledMap = readStoreEnabledMap()
                                    }
                                    is ToggleLogic.TogglePlanAction.RequestRuntimePermissions -> {
                                        pendingRailToEnable = row.name
                                        permissionLauncher.launch(plan.permissions.toTypedArray())
                                    }
                                    is ToggleLogic.TogglePlanAction.OpenNotificationAccessSettings -> {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                        } catch (_: Exception) {}
                                    }
                                    is ToggleLogic.TogglePlanAction.ShowHealthConnectPlaceholder -> {
                                        showHealthConnectDialog = true
                                    }
                                }
                            }
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    // Forget Device Confirmation Dialog
    if (showForgetDialog) {
        AlertDialog(
            onDismissRequest = { showForgetDialog = false },
            title = { Text("Forget Device?") },
            text = {
                Text("This will stop all device rails and remove connection credentials for this device.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.clear()
                        DeviceRailService.stopIfIdle(context)
                        isPaired = false
                        endpoint = null
                        showForgetDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Forget")
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Health Connect Placeholder Dialog
    if (showHealthConnectDialog) {
        AlertDialog(
            onDismissRequest = { showHealthConnectDialog = false },
            title = { Text("Health Connect") },
            text = {
                Text("Health Connect integration is currently a preview placeholder and requires platform provider support.")
            },
            confirmButton = {
                TextButton(onClick = { showHealthConnectDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
fun ConnectionStatusBadge(state: DeviceStreamState) {
    val (statusColor, statusText) = when (state) {
        is DeviceStreamState.Live -> Pair(Color(0xFF4CAF50), "Live")
        is DeviceStreamState.Connecting -> Pair(Color(0xFF2196F3), "Connecting")
        is DeviceStreamState.Reconnecting -> Pair(Color(0xFFFF9800), "Reconnecting (${state.attempt})")
        is DeviceStreamState.RepairRequired -> Pair(Color(0xFFF44336), "Repair Required")
        is DeviceStreamState.Idle -> Pair(Color(0xFF9E9E9E), "Idle")
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = statusColor.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(color = statusColor)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
        }
    }
}

@Composable
fun RailRowItem(
    display: ToggleLogic.RailRowDisplay,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = display.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = if (display.isReadOnly) "Read" else "Action",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (display.isDegraded) {
                    Text(
                        text = "Degraded: ${display.hint ?: "permission revoked"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                } else if (!display.isChecked && display.hint != null) {
                    Text(
                        text = display.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (display.isChecked) {
                    Text(
                        text = "Enabled and active",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50)
                    )
                }
            }

            Switch(
                checked = display.isChecked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
