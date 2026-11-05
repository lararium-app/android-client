package app.lararium.ui.connectors

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
        mutableStateOf(
            ConnectorsLogic.initialFormState(
                descriptor = ConnectorsLogic.defaultV1FormDescriptor(),
                existingRow = existingRow,
            )
        )
    }

    var isSubmitting by remember { mutableStateOf(false) }
    var inlineError by remember(existingRow?.name) { mutableStateOf<SubmitErrorUi.InlineError?>(null) }
    var approvalDialogCardId by remember { mutableStateOf<String?>(null) }
    var pendingSubmittedRow by remember { mutableStateOf<ConnectorRow?>(null) }

    LaunchedEffect(endpoint, existingRow?.name) {
        isLoadingDescriptor = true
        inlineError = null
        if (endpoint == null) {
            val fallback = ConnectorsLogic.defaultV1FormDescriptor()
            formState = ConnectorsLogic.initialFormState(fallback, existingRow)
            isOfflineFallback = true
            isLoadingDescriptor = false
        } else {
            try {
                val descriptor = ConnectorsClient(endpoint).fetchFormDescriptor()
                formState = ConnectorsLogic.initialFormState(descriptor, existingRow)
                isOfflineFallback = false
            } catch (_: Exception) {
                val fallback = ConnectorsLogic.defaultV1FormDescriptor()
                formState = ConnectorsLogic.initialFormState(fallback, existingRow)
                isOfflineFallback = true
            } finally {
                isLoadingDescriptor = false
            }
        }
    }

    val isEditMode = existingRow != null
    val submitEnabled = formState.canSubmit(
        isOfflineFallback = isOfflineFallback,
        isSubmitting = isSubmitting,
    )

    fun submitForm() {
        val ep = endpoint ?: return
        if (!submitEnabled) return

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

                // Generic field renderer: iterate fields in descriptor order
                for (field in formState.descriptor.fields) {
                    GenericFormFieldItem(
                        field = field,
                        formState = formState,
                        onStateChange = { updated ->
                            formState = updated
                            inlineError = null
                        },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { submitForm() },
                    enabled = submitEnabled,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenericFormFieldItem(
    field: ConnectorFieldDescriptor,
    formState: ConnectorFormState,
    onStateChange: (ConnectorFormState) -> Unit,
) {
    val isRequired = formState.isFieldRequired(field)
    val labelText = if (isRequired) "${field.key} *" else field.key
    val fieldError = formState.validateField(field)

    when (ConnectorsLogic.controlKindForField(field)) {
        RenderedControlKind.TEXT -> {
            val isReadOnlyName = formState.isEditMode && field.key == "name"
            val value = formState.scalarValues[field.key].orEmpty()
            val showLiveError = value.isNotEmpty() && fieldError != null

            val supportingHint = buildString {
                if (field.description.isNotBlank()) append(field.description)
                if (field.validation.maxLength != null) {
                    if (isNotEmpty()) append(" · ")
                    append("max ${field.validation.maxLength} chars")
                }
            }

            OutlinedTextField(
                value = value,
                onValueChange = { next ->
                    if (!isReadOnlyName) {
                        onStateChange(formState.withScalar(field.key, next))
                    }
                },
                label = { Text(labelText) },
                readOnly = isReadOnlyName,
                enabled = !isReadOnlyName,
                isError = showLiveError,
                supportingText = {
                    if (showLiveError) {
                        Text(fieldError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    } else if (supportingHint.isNotBlank()) {
                        Text(supportingHint)
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
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
                    supportingText = field.description.takeIf { it.isNotBlank() }?.let { desc ->
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
                if (field.description.isNotBlank()) append(field.description)
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
                supportingText = {
                    if (showError) {
                        Text(fieldError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    } else if (rangeHint.isNotBlank()) {
                        Text(rangeHint)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
        }

        RenderedControlKind.DURATION -> {
            val value = formState.scalarValues[field.key].orEmpty()
            val showError = value.isNotEmpty() && fieldError != null
            val rulesHint = buildString {
                if (field.description.isNotBlank()) append(field.description)
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
                supportingText = {
                    if (showError) {
                        Text(fieldError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    } else if (rulesHint.isNotBlank()) {
                        Text(rulesHint)
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
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
                            if (field.description.isNotBlank()) {
                                Text(
                                    text = field.description,
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
                                placeholder = { Text("${field.key}[$idx]") },
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
                if (field.description.isNotBlank()) append(field.description)
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
                            if (field.description.isNotBlank()) {
                                Text(
                                    text = field.description,
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
                                label = { Text("NAME") },
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
                        Text(
                            text = fieldError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
