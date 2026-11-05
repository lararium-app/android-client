package app.lararium.ui.connectors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.lararium.auth.authGate
import app.lararium.hearth.Endpoint
import app.lararium.hearth.connectors.ConnectorFieldDescriptor
import app.lararium.hearth.connectors.ConnectorFormLayout
import app.lararium.hearth.connectors.ConnectorFormState
import app.lararium.hearth.connectors.ConnectorRow
import app.lararium.hearth.connectors.ConnectorsClient
import app.lararium.hearth.connectors.ConnectorsLogic
import app.lararium.hearth.connectors.RenderedControlKind
import app.lararium.hearth.connectors.SubmitErrorUi
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorFormScreen(
    endpoint: Endpoint?,
    existingRow: ConnectorRow?,
    onBack: () -> Unit,
    onSubmittedPending: (ConnectorRow) -> Unit,
    onSwitchToEdit: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isLoadingDescriptor by remember(existingRow?.name) { mutableStateOf(true) }
    var isOfflineFallback by remember(existingRow?.name) { mutableStateOf(false) }
    var formState by remember(existingRow?.name) {
        val initial = ConnectorsLogic.initialFormState(
            descriptor = ConnectorsLogic.defaultV1FormDescriptor(),
            existingRow = existingRow,
        )
        val transportField = initial.descriptor.fields.firstOrNull { it.key == "transport" }
        val allowedTransports = transportField?.allowed?.takeIf { it.isNotEmpty() } ?: listOf("stdio", "http")
        val stateWithHttp = if (existingRow == null && "http" in allowedTransports) {
            initial.withScalar("transport", "http")
        } else {
            initial
        }
        mutableStateOf(stateWithHttp)
    }

    var isSubmitting by remember { mutableStateOf(false) }
    var inlineError by remember(existingRow?.name) { mutableStateOf<SubmitErrorUi.InlineError?>(null) }
    var approvalDialogCardId by remember { mutableStateOf<String?>(null) }
    var pendingSubmittedRow by remember { mutableStateOf<ConnectorRow?>(null) }

    var isAdvancedExpanded by remember(existingRow?.name) { mutableStateOf(false) }
    var showTransportPicker by remember(existingRow?.name) { mutableStateOf(false) }
    var expandedDetailsKeys by remember(existingRow?.name) { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(endpoint, existingRow?.name) {
        isLoadingDescriptor = true
        inlineError = null
        val descriptor = if (endpoint == null) {
            isOfflineFallback = true
            ConnectorsLogic.defaultV1FormDescriptor()
        } else {
            try {
                val fetched = ConnectorsClient(endpoint).fetchFormDescriptor()
                isOfflineFallback = false
                fetched
            } catch (_: Exception) {
                isOfflineFallback = true
                ConnectorsLogic.defaultV1FormDescriptor()
            }
        }

        val initial = ConnectorsLogic.initialFormState(descriptor, existingRow)
        val transportField = descriptor.fields.firstOrNull { it.key == "transport" }
        val allowedTransports = transportField?.allowed?.takeIf { it.isNotEmpty() } ?: listOf("stdio", "http")
        formState = if (existingRow == null && "http" in allowedTransports) {
            initial.withScalar("transport", "http")
        } else {
            initial
        }
        isLoadingDescriptor = false
    }

    val isEditMode = existingRow != null
    val layout = remember(formState.descriptor, formState.scalarValues) {
        ConnectorFormLayout.compute(formState.descriptor, formState.scalarValues)
    }

    val canActuallySubmit = formState.canSubmit(
        isOfflineFallback = isOfflineFallback,
        isSubmitting = isSubmitting,
    )

    val hiddenAdvancedNudge = ConnectorFormLayout.getHiddenAdvancedBlockingNudge(
        layout = layout,
        formState = formState,
        isAdvancedExpanded = isAdvancedExpanded,
    )

    fun submitForm() {
        val ep = endpoint ?: return

        val payload = formState.toSubmitPayload()
        val transport = payload.optString("transport", "stdio")
        val target = if (transport == "http") {
            payload.optString("url", "")
        } else {
            payload.optString("command", "")
        }

        val reason = if (existingRow != null) "Update Connector" else "Save Connector"
        scope.launch {
            authGate(
                context = context,
                reasonTitle = reason,
                destructive = false
            ) {
                inlineError = null
                isSubmitting = true
                try {
                    val client = ConnectorsClient(ep)
                    val result = if (existingRow != null) {
                        client.updateConnector(existingRow.name, payload)
                    } else {
                        client.addConnector(payload)
                    }

                    when (result) {
                        is ConnectorsClient.SubmitResult.PendingApproval -> {
                            pendingSubmittedRow = ConnectorsLogic.createPendingRow(
                                name = result.name,
                                transport = transport,
                                target = target,
                            )
                            approvalDialogCardId = ConnectorsLogic.formatApprovalCardId(result.cardId)
                        }
                        is ConnectorsClient.SubmitResult.Failed -> {
                            when (val uiErr = result.uiError) {
                                is SubmitErrorUi.InlineError -> {
                                    inlineError = uiErr
                                }
                                is SubmitErrorUi.SnackbarError -> {
                                    snackbarHostState.showSnackbar(uiErr.message)
                                }
                            }
                        }
                    }
                } finally {
                    isSubmitting = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (isEditMode) "Edit Connector" else "Add Connector")
                        val subtitle = formState.currentTargetSubtitle
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
    ) { innerPadding ->
        if (isLoadingDescriptor) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (isOfflineFallback) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Hearth is unreachable — showing local v1 form schema. Submit is disabled until connected.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                if (isEditMode) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Full replacement config: secrets are never read back; leave blank only if you want to clear them.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                val currentInlineErr = inlineError
                if (currentInlineErr != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = currentInlineErr.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                            if (currentInlineErr.offerEditName != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(
                                        onClick = { onSwitchToEdit(currentInlineErr.offerEditName) },
                                    ) {
                                        Text(
                                            text = "Edit it instead",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // -------------------------------------------------------------------
                // SIMPLE SECTION: Short conversation, one question per card
                // -------------------------------------------------------------------
                layout.simpleFields.forEach { field ->
                    ConversationalCard(
                        field = field,
                        formState = formState,
                        isDetailsExpanded = expandedDetailsKeys.contains(field.key),
                        onToggleDetails = {
                            expandedDetailsKeys = if (expandedDetailsKeys.contains(field.key)) {
                                expandedDetailsKeys - field.key
                            } else {
                                expandedDetailsKeys + field.key
                            }
                        },
                        onStateChange = { next ->
                            formState = next
                            inlineError = null
                        },
                    )
                }

                // -------------------------------------------------------------------
                // COLLAPSIBLE ADVANCED OPTIONS DISCLOSURE ROW
                // -------------------------------------------------------------------
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isAdvancedExpanded = !isAdvancedExpanded },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Advanced options",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (isAdvancedExpanded) {
                                    "Hide advanced configuration"
                                } else {
                                    "Transport protocol, headers, environment variables, and timeouts"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = if (isAdvancedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isAdvancedExpanded) "Collapse Advanced options" else "Expand Advanced options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // -------------------------------------------------------------------
                // EXPANDED ADVANCED SECTION
                // -------------------------------------------------------------------
                if (isAdvancedExpanded) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        layout.advancedFields.forEach { field ->
                            if (field.key.equals("transport", ignoreCase = true)) {
                                AdvancedTransportItem(
                                    field = field,
                                    formState = formState,
                                    showTransportPicker = showTransportPicker,
                                    onShowPicker = { showTransportPicker = true },
                                    onStateChange = { next ->
                                        formState = next
                                        inlineError = null
                                    },
                                )
                            } else {
                                AdvancedFormFieldItem(
                                    field = field,
                                    formState = formState,
                                    isDetailsExpanded = expandedDetailsKeys.contains(field.key),
                                    onToggleDetails = {
                                        expandedDetailsKeys = if (expandedDetailsKeys.contains(field.key)) {
                                            expandedDetailsKeys - field.key
                                        } else {
                                            expandedDetailsKeys + field.key
                                        }
                                    },
                                    onStateChange = { next ->
                                        formState = next
                                        inlineError = null
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // -------------------------------------------------------------------
                // SUBMIT BUTTON
                // -------------------------------------------------------------------
                val submitButtonEnabled = !isOfflineFallback && !isSubmitting

                Button(
                    onClick = {
                        if (hiddenAdvancedNudge != null) {
                            // Blocked by hidden advanced required field: auto-expand and show friendly nudge
                            isAdvancedExpanded = true
                            inlineError = SubmitErrorUi.InlineError(message = hiddenAdvancedNudge)
                        } else if (!canActuallySubmit) {
                            val firstInvalid = layout.simpleFields.firstOrNull { formState.validateField(it) != null }
                                ?: layout.advancedFields.firstOrNull { formState.validateField(it) != null }
                            if (firstInvalid != null) {
                                val raw = formState.validateField(firstInvalid) ?: "Please fill this in first"
                                val friendly = ConnectorFormLayout.mapFriendlyError(firstInvalid, raw)
                                inlineError = SubmitErrorUi.InlineError(message = friendly.headline)
                                if (layout.advancedFields.contains(firstInvalid)) {
                                    isAdvancedExpanded = true
                                }
                            }
                        } else {
                            submitForm()
                        }
                    },
                    enabled = submitButtonEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(
                            text = if (isEditMode) "Save Changes" else "Add Connector",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (approvalDialogCardId != null) {
        val trimmedId = approvalDialogCardId!!
        AlertDialog(
            onDismissRequest = {
                val pending = pendingSubmittedRow
                approvalDialogCardId = null
                if (pending != null) {
                    onSubmittedPending(pending)
                } else {
                    onBack()
                }
            },
            title = { Text("Awaiting your approval on the hearth") },
            text = {
                Text("Approval card ID: $trimmedId\n\nApprove this connector on your hearth to activate it.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pending = pendingSubmittedRow
                        approvalDialogCardId = null
                        if (pending != null) {
                            onSubmittedPending(pending)
                        } else {
                            onBack()
                        }
                    },
                ) {
                    Text("OK")
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// CONVERSATIONAL CARD FOR SIMPLE SECTION
// ---------------------------------------------------------------------------

@Composable
private fun ConversationalCard(
    field: ConnectorFieldDescriptor,
    formState: ConnectorFormState,
    isDetailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    onStateChange: (ConnectorFormState) -> Unit,
) {
    val question = when (field.key.lowercase()) {
        "name" -> "What should we call it?"
        "url", "base_url" -> "Where does it live?"
        else -> ConnectorFormLayout.humanizeKey(field.key)
    }
    val hint = ConnectorFormLayout.getFieldDescription(field)
    val fieldError = formState.validateField(field)
    val isReadOnlyName = formState.isEditMode && field.key == "name"
    val value = if (isReadOnlyName && formState.editTargetName != null) {
        formState.editTargetName
    } else {
        formState.scalarValues[field.key].orEmpty()
    }
    val showError = value.isNotEmpty() && fieldError != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = question,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (hint.isNotBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = value,
                onValueChange = { next ->
                    if (!isReadOnlyName) {
                        if (field.key == "url" || field.key == "base_url") {
                            val nextState = ConnectorFormLayout.applyTargetInput(formState, next)
                            onStateChange(nextState)
                        } else {
                            onStateChange(formState.withScalar(field.key, next))
                        }
                    }
                },
                readOnly = isReadOnlyName,
                enabled = !isReadOnlyName,
                isError = showError,
                placeholder = {
                    val ph = when (field.key.lowercase()) {
                        "name" -> "Front door camera"
                        "url", "base_url" -> "https://…"
                        else -> ConnectorFormLayout.humanizeKey(field.key)
                    }
                    Text(ph)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            if (showError) {
                FriendlyErrorRow(
                    field = field,
                    rawError = fieldError!!,
                    isDetailsExpanded = isDetailsExpanded,
                    onToggleDetails = onToggleDetails,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// TRANSPORT DISPLAY IN ADVANCED SECTION (CHIP WITH CHANGE AFFORDANCE)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedTransportItem(
    field: ConnectorFieldDescriptor,
    formState: ConnectorFormState,
    showTransportPicker: Boolean,
    onShowPicker: () -> Unit,
    onStateChange: (ConnectorFormState) -> Unit,
) {
    val currentTransport = formState.scalarValues["transport"]?.takeIf { it.isNotBlank() } ?: "http"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Connection method",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (!showTransportPicker) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = if (currentTransport == "http") "Web service (HTTP)" else "Local command (stdio)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                    TextButton(onClick = onShowPicker) {
                        Text("Change")
                    }
                }
            } else {
                var expandedDropdown by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expandedDropdown,
                    onExpandedChange = { expandedDropdown = !expandedDropdown },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = currentTransport,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Transport protocol") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown)
                        },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    ExposedDropdownMenu(
                        expanded = expandedDropdown,
                        onDismissRequest = { expandedDropdown = false },
                    ) {
                        (field.allowed.ifEmpty { listOf("stdio", "http") }).forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    onStateChange(formState.withScalar("transport", option))
                                    expandedDropdown = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ADVANCED FIELD ITEM RENDERER
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedFormFieldItem(
    field: ConnectorFieldDescriptor,
    formState: ConnectorFormState,
    isDetailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    onStateChange: (ConnectorFormState) -> Unit,
) {
    val isRequired = formState.isFieldRequired(field)
    val humanizedTitle = ConnectorFormLayout.humanizeKey(field.key)
    val labelText = if (isRequired) "$humanizedTitle *" else humanizedTitle
    val fieldError = formState.validateField(field)
    val description = ConnectorFormLayout.getFieldDescription(field)

    when (ConnectorsLogic.controlKindForField(field)) {
        RenderedControlKind.TEXT -> {
            val isReadOnlyName = formState.isEditMode && field.key == "name"
            val value = formState.scalarValues[field.key].orEmpty()
            val showError = value.isNotEmpty() && fieldError != null

            OutlinedTextField(
                value = value,
                onValueChange = { next ->
                    if (!isReadOnlyName) {
                        if (field.key == "command") {
                            val allowedTransports = formState.descriptor.fields.firstOrNull { it.key == "transport" }?.allowed
                                ?: listOf("stdio", "http")
                            val inferred = ConnectorsLogic.inferTransportFromTarget(next, allowedTransports)
                            val nextState = formState
                                .withScalar("transport", inferred)
                                .withScalar("command", next)
                            onStateChange(nextState)
                        } else {
                            onStateChange(formState.withScalar(field.key, next))
                        }
                    }
                },
                label = { Text(labelText) },
                readOnly = isReadOnlyName,
                enabled = !isReadOnlyName,
                isError = showError,
                supportingText = description.takeIf { it.isNotBlank() }?.let { desc ->
                    { Text(desc) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            if (showError) {
                FriendlyErrorRow(
                    field = field,
                    rawError = fieldError!!,
                    isDetailsExpanded = isDetailsExpanded,
                    onToggleDetails = onToggleDetails,
                )
            }
        }

        RenderedControlKind.DROPDOWN -> {
            var expanded by remember { mutableStateOf(false) }
            val currentVal = formState.scalarValues[field.key].orEmpty()

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = currentVal,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(labelText) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    supportingText = description.takeIf { it.isNotBlank() }?.let { desc ->
                        { Text(desc) }
                    },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    field.allowed.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onStateChange(formState.withScalar(field.key, option))
                                expanded = false
                            },
                        )
                    }
                }
            }
        }

        RenderedControlKind.INT -> {
            val value = formState.scalarValues[field.key].orEmpty()
            val showError = value.isNotEmpty() && fieldError != null
            val rangeHint = buildString {
                if (description.isNotBlank()) append(description)
                val min = field.validation.min
                val max = field.validation.max
                if (min != null || max != null) {
                    if (isNotEmpty()) append(" ")
                    append("(range: ${min ?: "-∞"}..${max ?: "∞"})")
                }
            }

            OutlinedTextField(
                value = value,
                onValueChange = { next ->
                    onStateChange(formState.copy(scalarValues = formState.scalarValues + (field.key to next)))
                },
                label = { Text(labelText) },
                isError = showError,
                supportingText = rangeHint.takeIf { it.isNotBlank() }?.let { hint ->
                    { Text(hint) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            if (showError) {
                FriendlyErrorRow(
                    field = field,
                    rawError = fieldError!!,
                    isDetailsExpanded = isDetailsExpanded,
                    onToggleDetails = onToggleDetails,
                )
            }
        }

        RenderedControlKind.DURATION -> {
            val value = formState.scalarValues[field.key].orEmpty()
            val showError = value.isNotEmpty() && fieldError != null
            val rulesHint = buildString {
                if (description.isNotBlank()) append(description)
                if (field.validation.rules.isNotEmpty()) {
                    if (isNotEmpty()) append(" — ")
                    append(field.validation.rules.joinToString("; "))
                }
            }

            OutlinedTextField(
                value = value,
                onValueChange = { next ->
                    onStateChange(formState.withScalar(field.key, next))
                },
                label = { Text(labelText) },
                placeholder = { Text("30s") },
                isError = showError,
                supportingText = rulesHint.takeIf { it.isNotBlank() }?.let { hint ->
                    { Text(hint) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            if (showError) {
                FriendlyErrorRow(
                    field = field,
                    rawError = fieldError!!,
                    isDetailsExpanded = isDetailsExpanded,
                    onToggleDetails = onToggleDetails,
                )
            }
        }

        RenderedControlKind.STRING_LIST -> {
            val items = formState.listValues[field.key].orEmpty()
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = labelText,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (description.isNotBlank()) {
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(
                            onClick = { onStateChange(formState.addListItem(field.key, "")) },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add")
                        }
                    }

                    items.forEachIndexed { idx, itemValue ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = itemValue,
                                onValueChange = { next ->
                                    onStateChange(formState.updateListItem(field.key, idx, next))
                                },
                                placeholder = { Text("$humanizedTitle [$idx]") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            )
                            IconButton(
                                onClick = { onStateChange(formState.removeListItem(field.key, idx)) },
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }

        RenderedControlKind.SECRET_TEXT -> {
            val value = formState.scalarValues[field.key].orEmpty()
            val secretHint = buildString {
                if (description.isNotBlank()) append(description)
                if (formState.isEditMode) {
                    if (isNotEmpty()) append(" — ")
                    append("Secrets are never read back; leave blank only if you want to clear them.")
                }
            }

            OutlinedTextField(
                value = value,
                onValueChange = { next ->
                    onStateChange(formState.withScalar(field.key, next))
                },
                label = { Text(labelText) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = secretHint.takeIf { it.isNotBlank() }?.let { hint ->
                    { Text(hint) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
        }

        RenderedControlKind.SECRET_MAP -> {
            val entries = formState.mapValues[field.key].orEmpty()
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = labelText,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (description.isNotBlank()) {
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (formState.isEditMode) {
                                val existingNamesNote = if (formState.existingEnvNames.isNotEmpty()) {
                                    " Current keys: ${formState.existingEnvNames.joinToString(", ")}."
                                } else {
                                    ""
                                }
                                Text(
                                    text = "Secrets are never read back; leave blank only if you want to clear them.$existingNamesNote",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(
                            onClick = { onStateChange(formState.addMapEntry(field.key)) },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add")
                        }
                    }

                    entries.forEachIndexed { idx, entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = entry.name,
                                onValueChange = { nextName ->
                                    onStateChange(formState.updateMapEntry(field.key, idx, nextName, entry.value))
                                },
                                label = { Text("Name") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            )
                            OutlinedTextField(
                                value = entry.value,
                                onValueChange = { nextVal ->
                                    onStateChange(formState.updateMapEntry(field.key, idx, entry.name, nextVal))
                                },
                                label = { Text("Secret value") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            )
                            IconButton(
                                onClick = { onStateChange(formState.removeMapEntry(field.key, idx)) },
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    if (fieldError != null) {
                        FriendlyErrorRow(
                            field = field,
                            rawError = fieldError,
                            isDetailsExpanded = isDetailsExpanded,
                            onToggleDetails = onToggleDetails,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// FRIENDLY ERROR ROW WITH DETAILS EXPANDER
// ---------------------------------------------------------------------------

@Composable
private fun FriendlyErrorRow(
    field: ConnectorFieldDescriptor,
    rawError: String,
    isDetailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
) {
    val friendly = ConnectorFormLayout.mapFriendlyError(field, rawError)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(
            text = friendly.headline,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        val raw = friendly.rawDetail
        if (!raw.isNullOrBlank() && raw != friendly.headline) {
            TextButton(
                onClick = onToggleDetails,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(26.dp),
            ) {
                Text(
                    text = if (isDetailsExpanded) "Hide details" else "Details",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (isDetailsExpanded) {
                Text(
                    text = raw,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
