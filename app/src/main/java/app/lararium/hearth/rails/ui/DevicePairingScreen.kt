package app.lararium.hearth.rails.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.lararium.hearth.rails.service.DevicePairingController
import app.lararium.hearth.rails.service.DeviceRailService
import kotlinx.coroutines.launch

/**
 * Screen for onboarding and linking this Android device with a Hearth instance (G2, spec A3).
 * Validates endpoint URLs through EndpointPolicy and executes DevicePairingController.pair.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePairingScreen(
    initialBaseUrl: String = "",
    initialToken: String = "",
    onNavigateBack: () -> Unit,
    onPairingSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(initialBaseUrl) }
    var token by remember { mutableStateOf(initialToken) }
    var isLoading by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var tokenError by remember { mutableStateOf<String?>(null) }
    var generalError by remember { mutableStateOf<String?>(null) }

    fun submitPairing() {
        urlError = null
        tokenError = null
        generalError = null

        val urlValidation = PairingValidation.validateBaseUrl(baseUrl)
        if (!urlValidation.isValid) {
            urlError = urlValidation.error
            return
        }

        val tokenValidation = PairingValidation.validateToken(token)
        if (!tokenValidation.isValid) {
            tokenError = tokenValidation.error
            return
        }

        isLoading = true
        scope.launch {
            try {
                val result = DevicePairingController.pair(
                    context = context,
                    baseUrl = baseUrl.trim(),
                    token = token.trim()
                )

                when (result) {
                    is DevicePairingController.Result.Success -> {
                        DeviceRailService.ensureRunning(context)
                        onPairingSuccess()
                    }
                    is DevicePairingController.Result.Failure -> {
                        generalError = PairingValidation.friendlyPairErrorMessage(result.error)
                    }
                }
            } catch (e: Exception) {
                generalError = PairingValidation.friendlyPairErrorMessage(e.message ?: "pairing_error")
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair Device Rails") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Devices,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Link Device Capabilities",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Expose your phone's calendar, contacts, and messaging to your local hearth.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(28.dp))

            // General error card
            if (generalError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = generalError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Base URL Field
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    if (urlError != null) urlError = null
                },
                label = { Text("Hearth Base URL") },
                placeholder = { Text("https://hearth.local:8443 or http://192.168.1.5:8080") },
                isError = urlError != null,
                supportingText = urlError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Pairing Token Field
            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                    if (tokenError != null) tokenError = null
                },
                label = { Text("Pairing Token") },
                placeholder = { Text("lar1_...") },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!clipText.isNullOrBlank()) {
                                token = clipText.trim()
                                tokenError = null
                            }
                        }
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste from clipboard")
                    }
                },
                isError = tokenError != null,
                supportingText = tokenError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = { submitPairing() },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = if (generalError == "This device name is taken — retry") "Retry Pairing" else "Pair Device",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
