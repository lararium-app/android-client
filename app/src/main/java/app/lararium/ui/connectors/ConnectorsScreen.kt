package app.lararium.ui.connectors

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.lararium.auth.authGate
import app.lararium.hearth.Endpoint
import app.lararium.hearth.connectors.ConnectorRow
import app.lararium.hearth.connectors.ConnectorSseEvent
import app.lararium.hearth.connectors.ConnectorsClient
import app.lararium.hearth.connectors.ConnectorsLogic
import app.lararium.security.SecureKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class ActiveConnectorForm {
    object Add : ActiveConnectorForm()
    data class Edit(val row: ConnectorRow) : ActiveConnectorForm()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsScreen(
    onBack: () -> Unit,
    onNavigateToSettings: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var endpoint by remember {
        mutableStateOf(
            SecureKeyManager(context).getHearthEndpoint()?.let {
                Endpoint(it.first, it.second, it.third)
            }
        )
    }

    var rows by remember { mutableStateOf<List<ConnectorRow>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var connectorToDelete by remember { mutableStateOf<ConnectorRow?>(null) }
    var activeForm by remember { mutableStateOf<ActiveConnectorForm?>(null) }

    val refreshConnectors: suspend () -> Unit = {
        val ep = SecureKeyManager(context).getHearthEndpoint()?.let {
            Endpoint(it.first, it.second, it.third)
        }
        endpoint = ep
        if (ep == null) {
            loadError = "Not paired with a hearth yet."
            rows = null
        } else {
            try {
                val loaded = ConnectorsClient(ep).listConnectors()
                rows = loaded
                loadError = null
            } catch (_: Exception) {
                if (rows == null) {
                    loadError = "Hearth not reachable / not ready."
                } else {
                    snackbarHostState.showSnackbar("Hearth not reachable / not ready.")
                }
            }
        }
    }

    // Live SSE rail: bootstrap on foreground and on every reconnect; pause in background.
    LaunchedEffect(lifecycleOwner, activeForm == null) {
        if (activeForm != null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val ep = SecureKeyManager(context).getHearthEndpoint()?.let {
                Endpoint(it.first, it.second, it.third)
            }
            endpoint = ep
            if (ep == null) {
                loadError = "Not paired with a hearth yet."
                rows = null
                return@repeatOnLifecycle
            }
            val client = ConnectorsClient(ep)
            client.streamWithReconnect(
                reconnectDelayMs = 2_000L,
                onStateUpdated = { updatedRows ->
                    rows = updatedRows
                    loadError = null
                },
                onError = {
                    if (rows == null) {
                        loadError = "Hearth not reachable / not ready."
                    }
                },
            )
        }
    }

    val currentForm = activeForm
    if (currentForm != null) {
        val existingRow = (currentForm as? ActiveConnectorForm.Edit)?.row
        ConnectorFormScreen(
            endpoint = endpoint,
            existingRow = existingRow,
            onBack = { activeForm = null },
            onSubmittedPending = { pendingRow ->
                val currentList = rows.orEmpty()
                rows = ConnectorsLogic.applySseEvent(
                    currentList,
                    ConnectorSseEvent.State(pendingRow),
                )
                activeForm = null
            },
            onSwitchToEdit = { duplicateName ->
                val targetRow = rows?.firstOrNull { it.name == duplicateName }
                    ?: ConnectorRow(
                        name = duplicateName,
                        state = "ready",
                        tools = 0,
                        restarts = 0,
                        appState = "not-connected",
                        enabled = true,
                    )
                activeForm = ActiveConnectorForm.Edit(targetRow)
            },
        )
        return
    }

    val handleToggleEnabled: (ConnectorRow) -> Unit = { row ->
        val ep = endpoint
        if (ep != null) {
            scope.launch {
                try {
                    val client = ConnectorsClient(ep)
                    val updated = if (row.enabled) {
                        client.disableConnector(row.name)
                    } else {
                        client.enableConnector(row.name)
                    }
                    rows = ConnectorsLogic.applySseEvent(
                        rows.orEmpty(),
                        ConnectorSseEvent.State(updated),
                    )
                } catch (_: Exception) {
                    snackbarHostState.showSnackbar("Hearth not reachable / not ready.")
                }
            }
        }
    }

    val handleReauth: (ConnectorRow) -> Unit = { row ->
        val ep = endpoint
        if (ep != null) {
            scope.launch {
                when (val result = ConnectorsClient(ep).reauthConnector(row.name)) {
                    is ConnectorsClient.ReauthResult.Success -> {
                        rows = ConnectorsLogic.applySseEvent(
                            rows.orEmpty(),
                            ConnectorSseEvent.State(result.row),
                        )
                    }
                    is ConnectorsClient.ReauthResult.Failure -> {
                        snackbarHostState.showSnackbar(result.message)
                    }
                }
            }
        }
    }

    val handleDeleteConfirmed: (ConnectorRow) -> Unit = { row ->
        val ep = endpoint
        if (ep != null) {
            scope.launch {
                authGate(
                    context = context,
                    reasonTitle = "Delete Connector",
                    destructive = true
                ) {
                    try {
                        val ok = ConnectorsClient(ep).deleteConnector(row.name)
                        if (ok) {
                            rows = ConnectorsLogic.applySseEvent(
                                rows.orEmpty(),
                                ConnectorSseEvent.Gone(row.name),
                            )
                        } else {
                            snackbarHostState.showSnackbar("Failed to delete connector \"${row.name}\".")
                        }
                    } catch (_: Exception) {
                        snackbarHostState.showSnackbar("Failed to delete connector \"${row.name}\".")
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connectors") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { refreshConnectors() } }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        floatingActionButton = {
            if (endpoint != null) {
                FloatingActionButton(
                    onClick = { activeForm = ActiveConnectorForm.Add },
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add connector",
                    )
                }
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                endpoint == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "Not paired with a hearth yet.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            if (onNavigateToSettings != null) {
                                Button(onClick = onNavigateToSettings) {
                                    Text("Pair hearth in Settings")
                                }
                            }
                        }
                    }
                }

                loadError != null && rows == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = loadError ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            FilledTonalButton(onClick = { scope.launch { refreshConnectors() } }) {
                                Text("Retry")
                            }
                        }
                    }
                }

                rows == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                rows!!.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.Extension,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                            Text(
                                text = "No connectors configured",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Connect MCP tool servers to your hearth to extend its capabilities.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = { activeForm = ActiveConnectorForm.Add },
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add connector")
                            }
                        }
                    }
                }

                else -> {
                    val nowMillis = System.currentTimeMillis()
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(rows!!, key = { it.name }) { row ->
                            ConnectorRowCard(
                                row = row,
                                nowMillis = nowMillis,
                                onToggleEnabled = { handleToggleEnabled(row) },
                                onReauthenticate = { handleReauth(row) },
                                onEdit = { activeForm = ActiveConnectorForm.Edit(row) },
                                onDelete = { connectorToDelete = row },
                            )
                        }
                    }
                }
            }
        }
    }

    if (connectorToDelete != null) {
        val target = connectorToDelete!!
        AlertDialog(
            onDismissRequest = { connectorToDelete = null },
            title = { Text("Delete connector?") },
            text = {
                Text("Delete connector \"${target.name}\"? This will remove its configuration from your hearth.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        connectorToDelete = null
                        handleDeleteConfirmed(target)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { connectorToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ConnectorRowCard(
    row: ConnectorRow,
    nowMillis: Long,
    onToggleEnabled: () -> Unit,
    onReauthenticate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val chipSpec = ConnectorsLogic.chipSpecForRow(row)
    val chipColor = Color(chipSpec.colorHex)
    val secondaryText = ConnectorsLogic.formatSecondaryLine(row, nowMillis)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = chipColor.copy(alpha = 0.16f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Canvas(modifier = Modifier.size(6.dp)) {
                                drawCircle(color = chipColor)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = chipSpec.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = chipColor,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Connector actions for ${row.name}",
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(if (row.enabled) "Disable" else "Enable") },
                        onClick = {
                            menuExpanded = false
                            onToggleEnabled()
                        },
                    )
                    if (ConnectorsLogic.canReauthenticate(row)) {
                        DropdownMenuItem(
                            text = { Text("Re-authenticate") },
                            onClick = {
                                menuExpanded = false
                                onReauthenticate()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Delete",
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
