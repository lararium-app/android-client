package app.lararium.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import app.lararium.AssistantApplication
import app.lararium.audio.VoskTranscriber
import app.lararium.service.AssistantAccessibilityService
import app.lararium.service.AssistantService
import app.lararium.tts.TTSManager
import app.lararium.BuildConfig
import app.lararium.R
import app.lararium.hearth.Endpoint
import app.lararium.hearth.EndpointPolicy
import app.lararium.hearth.HearthChatClient
import app.lararium.hearth.LarariumClient
import app.lararium.hearth.PairError
import app.lararium.hearth.PairingLink
import app.lararium.hearth.watch.HearthWatchScheduler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

private enum class SettingsCategory(val label: String) {
    CONNECTION("Connection"),
    ASSISTANT("Assistant"),
    NOTIFICATIONS("Notifications"),
    ABOUT("About")
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    assistantService: AssistantService? = null,
    onNavigateToLanguages: (() -> Unit)? = null,
    onOpenAccessibilitySettings: (() -> Unit)? = null,
    onOpenAssistantSettings: (() -> Unit)? = null,
    onOrientationLockChanged: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val settingsManager = remember { SettingsManager(context) }
    val voskTranscriber = remember { VoskTranscriber(context) }
    val scope = rememberCoroutineScope()

    var voiceInputMethod by remember { mutableStateOf(settingsManager.voiceInputMethod) }
    var ttsEnabled by remember { mutableStateOf(settingsManager.ttsEnabled) }
    var lockOrientation by remember { mutableStateOf(settingsManager.lockOrientation) }
    var autoSendVoice by remember { mutableStateOf(settingsManager.autoSendVoice) }
    var autoStartVoice by remember { mutableStateOf(settingsManager.autoStartVoice) }
    var multilingualEnabled by remember { mutableStateOf(settingsManager.multilingualEnabled) }
    var secondaryLanguage by remember { mutableStateOf(settingsManager.secondaryVoiceLanguage) }

    var showPairHearthDialog by remember { mutableStateOf(false) }
    var hearthEndpoint by remember { mutableStateOf(app.secureKeyManager.getHearthEndpoint()) }
    val hasHearthEndpoint = hearthEndpoint != null

    var selectedLanguage by remember { mutableStateOf(settingsManager.voiceLanguage) }
    var downloadedLanguages by remember { mutableStateOf(voskTranscriber.getDownloadedLanguages()) }

    var isVoskModelDownloaded by remember { mutableStateOf(!voskTranscriber.needsModelDownload(selectedLanguage)) }
    var isDownloading by remember { mutableStateOf(false) }
    var isExtracting by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showSecondaryLanguageDialog by remember { mutableStateOf(false) }

    var settingsCategoryName by rememberSaveable { mutableStateOf(SettingsCategory.CONNECTION.name) }
    val settingsCategory = SettingsCategory.valueOf(settingsCategoryName)
    val settingsScrollState = rememberScrollState()

    val isAccessibilityEnabled = AssistantAccessibilityService.isServiceRunning
    val isDefaultAssistant = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) ?: false
        } else false
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    LaunchedEffect(settingsCategory) {
        settingsScrollState.scrollTo(0)
    }

    val voiceLabel = when (voiceInputMethod) {
        SettingsManager.VOICE_INPUT_SYSTEM -> "Android system"
        else -> "Vosk offline"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(settingsScrollState)
                    .padding(bottom = 20.dp)
            ) {
                SettingsOverviewCard(
                    provider = stringResource(R.string.provider_lararium),
                    model = hearthEndpoint?.third ?: stringResource(R.string.hearth_not_paired),
                    voice = voiceLabel,
                    search = null,
                    keyStatus = if (hasHearthEndpoint) "Paired" else "Not paired"
                )

                SettingsCategoryTabs(
                    selectedCategory = settingsCategory,
                    onCategorySelected = { settingsCategoryName = it.name }
                )

                if (settingsCategory == SettingsCategory.CONNECTION) {
                    SettingsSection(title = stringResource(R.string.settings_section_connection)) {
                        SettingsItem(
                            icon = Icons.Default.Link,
                            title = stringResource(R.string.hearth_pair_title),
                            subtitle = if (hearthEndpoint != null) {
                                stringResource(R.string.hearth_paired_as, hearthEndpoint!!.third)
                            } else {
                                stringResource(R.string.hearth_not_paired)
                            },
                            onClick = { showPairHearthDialog = true }
                        )
                        if (hearthEndpoint != null) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "Endpoint: ${hearthEndpoint!!.first}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        app.secureKeyManager.clearHearthEndpoint()
                                        context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)
                                            .edit()
                                            .remove(HearthChatClient.KEY_SESSION_ID)
                                            .apply()
                                        hearthEndpoint = null
                                        assistantService?.reloadSettings()
                                    },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Text(stringResource(R.string.hearth_unpair))
                                }
                            }
                        }
                    }
                }

                if (settingsCategory == SettingsCategory.ASSISTANT) {
                    SettingsSection(title = "Voice Input") {
                        var showVoiceMethodDialog by remember { mutableStateOf(false) }

                        val voiceMethodName = when (voiceInputMethod) {
                            SettingsManager.VOICE_INPUT_SYSTEM -> "System"
                            else -> "Vosk (Offline)"
                        }
                        SettingsItem(
                            icon = Icons.Default.RecordVoiceOver,
                            title = "Voice Recognition Method",
                            subtitle = voiceMethodName,
                            onClick = { showVoiceMethodDialog = true }
                        )

                        if (showVoiceMethodDialog) {
                            AlertDialog(
                                onDismissRequest = { showVoiceMethodDialog = false },
                                title = { Text("Voice Recognition Method", style = MaterialTheme.typography.headlineSmall) },
                                text = {
                                    Column {
                                        listOf(
                                            Triple(SettingsManager.VOICE_INPUT_SYSTEM, "System", "Uses Android's built-in speech recognition"),
                                            Triple(SettingsManager.VOICE_INPUT_VOSK, "Vosk (Offline)", "Fully private, works without internet")
                                        ).forEach { (method, name, description) ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth()
                                                    .clickable {
                                                        voiceInputMethod = method
                                                        settingsManager.voiceInputMethod = method
                                                        showVoiceMethodDialog = false
                                                    }
                                                    .padding(vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = voiceInputMethod == method,
                                                    onClick = {
                                                        voiceInputMethod = method
                                                        settingsManager.voiceInputMethod = method
                                                        showVoiceMethodDialog = false
                                                    }
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column {
                                                    Text(name, style = MaterialTheme.typography.bodyLarge)
                                                    Text(description,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { showVoiceMethodDialog = false }) { Text("Cancel") }
                                }
                            )
                        }

                        SettingsItem(
                            icon = Icons.Default.Language,
                            title = "Voice Languages",
                            subtitle = "${downloadedLanguages.size} downloaded • Manage Vosk models",
                            onClick = {
                                if (onNavigateToLanguages != null) {
                                    onNavigateToLanguages()
                                } else {
                                    showLanguageDialog = true
                                }
                            }
                        )

                        if (voiceInputMethod == SettingsManager.VOICE_INPUT_VOSK) {
                            val currentLang = VoskTranscriber.getLanguageByCode(selectedLanguage)
                            val isLangDownloaded = downloadedLanguages.contains(selectedLanguage)
                            SettingsItem(
                                icon = Icons.Default.Language,
                                title = "Recognition Language",
                                subtitle = "${currentLang.displayName}" + if (isLangDownloaded) " ✓" else " (download required)",
                                onClick = { showLanguageDialog = true }
                            )

                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(16.dp),
                                tonalElevation = 1.dp,
                                color = if (isLangDownloaded) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (isLangDownloaded) Icons.Default.CheckCircle else Icons.Default.Download,
                                            contentDescription = null,
                                            tint = if (isLangDownloaded) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("${currentLang.displayName} Model",
                                                style = MaterialTheme.typography.bodyLarge)
                                            Text(
                                                if (isLangDownloaded) "Downloaded ✓ (~${currentLang.sizeBytes / 1_000_000}MB)"
                                                else "Required for offline voice (~${currentLang.sizeBytes / 1_000_000}MB)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }

                                        if (!isLangDownloaded && !isDownloading && !isExtracting) {
                                            FilledTonalButton(onClick = {
                                                isDownloading = true
                                                isExtracting = false
                                                downloadError = null
                                                scope.launch {
                                                    voskTranscriber.downloadModel(selectedLanguage).collect { state ->
                                                        when (state) {
                                                            is VoskTranscriber.DownloadState.Downloading -> {
                                                                isDownloading = true; isExtracting = false
                                                                downloadProgress = state.progress
                                                            }
                                                            is VoskTranscriber.DownloadState.Extracting -> {
                                                                isDownloading = false; isExtracting = true
                                                                downloadProgress = state.progress
                                                            }
                                                            is VoskTranscriber.DownloadState.Complete -> {
                                                                isDownloading = false; isExtracting = false
                                                                isVoskModelDownloaded = true
                                                                downloadedLanguages = voskTranscriber.getDownloadedLanguages()
                                                                assistantService?.reloadSettings()
                                                            }
                                                            is VoskTranscriber.DownloadState.Error -> {
                                                                isDownloading = false; isExtracting = false
                                                                downloadError = state.message
                                                            }
                                                            else -> {}
                                                        }
                                                    }
                                                }
                                            }) { Text("Download") }
                                        }
                                    }

                                    if (isDownloading || isExtracting) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        LinearProgressIndicator(progress = { downloadProgress / 100f },
                                            modifier = Modifier.fillMaxWidth())
                                        Text(
                                            if (isExtracting) "Extracting... $downloadProgress%"
                                            else "Downloading... $downloadProgress%",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 4.dp))
                                        if (isExtracting) {
                                            Text("This may take a while for large models...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 2.dp))
                                        }
                                    }

                                    downloadError?.let { error ->
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(error, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }

                            SettingsItemWithSwitch(
                                icon = Icons.Default.Translate,
                                title = "Multilingual Mode",
                                subtitle = "Recognize mixed language speech (e.g., Russian + English)",
                                checked = multilingualEnabled,
                                onCheckedChange = {
                                    multilingualEnabled = it
                                    settingsManager.multilingualEnabled = it
                                    assistantService?.reloadSettings()
                                }
                            )

                            if (multilingualEnabled) {
                                val secondaryLang = VoskTranscriber.getLanguageByCode(secondaryLanguage)
                                val isSecondaryDownloaded = downloadedLanguages.contains(secondaryLanguage)
                                SettingsItem(
                                    icon = Icons.Default.Language,
                                    title = "Secondary Language",
                                    subtitle = "${secondaryLang.displayName}" + if (isSecondaryDownloaded) " ✓" else " (download required)",
                                    onClick = { showSecondaryLanguageDialog = true }
                                )
                            }
                        }

                        SettingsItemWithSwitch(
                            icon = Icons.AutoMirrored.Default.Send,
                            title = "Auto-send after speech",
                            subtitle = "Automatically send query when speech ends",
                            checked = autoSendVoice,
                            onCheckedChange = {
                                autoSendVoice = it
                                settingsManager.autoSendVoice = it
                            }
                        )

                        SettingsItemWithSwitch(
                            icon = Icons.Default.Mic,
                            title = "Auto-start voice input",
                            subtitle = "Start listening when assistant opens",
                            checked = autoStartVoice,
                            onCheckedChange = {
                                autoStartVoice = it
                                settingsManager.autoStartVoice = it
                            }
                        )
                    }

                    SettingsSection(title = "Output") {
                        val ttsAvailable = remember { TTSManager.isTTSAvailable(context) }

                        SettingsItemWithSwitch(
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            title = "Text-to-Speech",
                            subtitle = if (ttsAvailable) "Read responses aloud" else "Not available on this device",
                            checked = ttsEnabled && ttsAvailable,
                            onCheckedChange = {
                                if (ttsAvailable) {
                                    ttsEnabled = it
                                    settingsManager.ttsEnabled = it
                                }
                            },
                            enabled = ttsAvailable
                        )

                        if (!ttsAvailable) {
                            Text(
                                "Text-to-speech is not available on this device. Install a TTS engine from the Play Store to enable this feature.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        if (ttsAvailable) {
                            var ttsLanguage by remember { mutableStateOf(settingsManager.ttsLanguage) }
                            var showTtsLanguageDialog by remember { mutableStateOf(false) }

                            val ttsLocaleInfo = TTSManager.getLocaleByTag(ttsLanguage)

                            SettingsItem(
                                icon = Icons.Default.Language,
                                title = "TTS Language",
                                subtitle = ttsLocaleInfo.displayName,
                                onClick = {
                                    if (ttsAvailable) {
                                        showTtsLanguageDialog = true
                                    }
                                }
                            )

                            if (showTtsLanguageDialog) {
                                TtsLanguageSelectionDialog(
                                    currentLanguage = ttsLanguage,
                                    ttsManager = assistantService?.ttsManager,
                                    onLanguageSelected = { tag ->
                                        ttsLanguage = tag
                                        settingsManager.ttsLanguage = tag
                                        assistantService?.let { service ->
                                            service.ttsManager.setLanguage(tag)
                                        }
                                        showTtsLanguageDialog = false
                                    },
                                    onDismiss = { showTtsLanguageDialog = false }
                                )
                            }
                        }

                        SettingsItemWithSwitch(
                            icon = Icons.Default.ScreenLockPortrait,
                            title = stringResource(R.string.settings_lock_orientation_title),
                            subtitle = stringResource(R.string.settings_lock_orientation_desc),
                            checked = lockOrientation,
                            onCheckedChange = {
                                lockOrientation = it
                                settingsManager.lockOrientation = it
                                onOrientationLockChanged?.invoke(it)
                            }
                        )
                    }

                    SettingsSection(title = "System Integration") {
                        SettingsItem(
                            icon = Icons.Filled.Home,
                            title = stringResource(R.string.settings_default_assistant_title),
                            subtitle = stringResource(
                                if (isDefaultAssistant) R.string.settings_default_assistant_set
                                else R.string.settings_default_assistant_not_set
                            ),
                            onClick = { onOpenAssistantSettings?.invoke() }
                        )
                        SettingsItem(
                            icon = Icons.Filled.TouchApp,
                            title = stringResource(R.string.settings_accessibility_title),
                            subtitle = stringResource(
                                if (isAccessibilityEnabled) R.string.settings_accessibility_enabled
                                else R.string.settings_accessibility_disabled
                            ),
                            onClick = { onOpenAccessibilitySettings?.invoke() }
                        )

                        Text(
                            stringResource(R.string.settings_activation_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        ActivationRow(
                            icon = Icons.Default.TouchApp,
                            title = stringResource(R.string.settings_activation_home_title),
                            description = stringResource(R.string.settings_activation_home_desc)
                        )
                        ActivationRow(
                            icon = Icons.Default.VolumeUp,
                            title = stringResource(R.string.settings_activation_volume_title),
                            description = stringResource(R.string.settings_activation_volume_desc)
                        )
                        ActivationRow(
                            icon = Icons.Default.Dashboard,
                            title = stringResource(R.string.settings_activation_tile_title),
                            description = stringResource(R.string.settings_activation_tile_desc)
                        )
                    }

                    SettingsSection(title = "Data & Privacy") {
                        var showClearHistoryDialog by remember { mutableStateOf(false) }
                        var showResetDialog by remember { mutableStateOf(false) }

                        SettingsItem(
                            icon = Icons.Default.Delete,
                            title = "Clear Chat History",
                            subtitle = "Delete all saved conversations",
                            onClick = { showClearHistoryDialog = true }
                        )

                        SettingsItem(
                            icon = Icons.Default.Restore,
                            title = "Reset All Settings",
                            subtitle = "Restore default settings",
                            onClick = { showResetDialog = true }
                        )

                        if (showClearHistoryDialog) {
                            AlertDialog(
                                onDismissRequest = { showClearHistoryDialog = false },
                                title = { Text("Clear Chat History") },
                                text = { Text("This will permanently delete all saved conversations. This action cannot be undone.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        assistantService?.chatHistoryManager?.clearAllChats()
                                        showClearHistoryDialog = false
                                    }) { Text("Clear All", color = MaterialTheme.colorScheme.error) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showClearHistoryDialog = false }) { Text("Cancel") }
                                }
                            )
                        }

                        if (showResetDialog) {
                            AlertDialog(
                                onDismissRequest = { showResetDialog = false },
                                title = { Text("Reset Settings") },
                                text = { Text("Restore all settings to their default values? Stored keys will be preserved.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        settingsManager.resetToDefaults()
                                        voiceInputMethod = settingsManager.voiceInputMethod
                                        ttsEnabled = settingsManager.ttsEnabled
                                        autoSendVoice = settingsManager.autoSendVoice
                                        autoStartVoice = settingsManager.autoStartVoice
                                        selectedLanguage = settingsManager.voiceLanguage
                                        secondaryLanguage = settingsManager.secondaryVoiceLanguage
                                        multilingualEnabled = settingsManager.multilingualEnabled
                                        assistantService?.reloadSettings()
                                        showResetDialog = false
                                    }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
                                }
                            )
                        }
                    }
                }

                if (settingsCategory == SettingsCategory.NOTIFICATIONS) {
                    SettingsSection(title = stringResource(R.string.settings_section_notifications)) {
                        SettingsItem(
                            icon = Icons.Default.Notifications,
                            title = stringResource(R.string.settings_notif_permission_title),
                            subtitle = stringResource(
                                if (hasNotificationPermission) R.string.settings_notif_permission_granted
                                else R.string.settings_notif_permission_needed
                            ),
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )
                        SettingsItem(
                            icon = Icons.AutoMirrored.Filled.OpenInNew,
                            title = stringResource(R.string.settings_notif_system_settings_title),
                            subtitle = stringResource(R.string.settings_notif_system_settings_desc),
                            onClick = {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                context.startActivity(intent)
                            }
                        )
                        SettingsItem(
                            icon = Icons.Default.Gavel,
                            title = stringResource(R.string.settings_notif_approvals_title),
                            subtitle = stringResource(R.string.settings_notif_approvals_desc),
                            onClick = {}
                        )
                        SettingsItem(
                            icon = Icons.Default.Reply,
                            title = stringResource(R.string.settings_notif_replies_title),
                            subtitle = stringResource(R.string.settings_notif_replies_desc),
                            onClick = {}
                        )
                        SettingsItem(
                            icon = Icons.Default.Schedule,
                            title = stringResource(R.string.settings_notif_watch_title),
                            subtitle = stringResource(R.string.settings_notif_watch_desc),
                            onClick = {}
                        )
                    }
                }

                if (settingsCategory == SettingsCategory.ABOUT) {
                    SettingsSection(title = "Lararium") {
                        SettingsItem(
                            icon = Icons.Default.Info,
                            title = "Version",
                            subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            onClick = {}
                        )

                        SettingsItem(
                            icon = Icons.Default.Security,
                            title = stringResource(R.string.settings_privacy_title),
                            subtitle = "Private by design • Local speech recognition • Pair-verified hearth",
                            onClick = {}
                        )

                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.app_tagline),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "• " + stringResource(R.string.settings_privacy_vosk) + "\n" +
                                "• " + stringResource(R.string.settings_privacy_encryption) + "\n" +
                                "• " + stringResource(R.string.settings_privacy_no_ids) + "\n" +
                                "• " + stringResource(R.string.settings_privacy_permissions) + "\n" +
                                "• " + stringResource(R.string.settings_privacy_tls),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPairHearthDialog) {
        PairHearthDialog(
            onDismiss = { showPairHearthDialog = false },
            onPaired = { ep ->
                hearthEndpoint = Triple(ep.baseUrl, ep.token, ep.name)
            }
        )
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = selectedLanguage,
            downloadedLanguages = downloadedLanguages,
            onLanguageSelected = { langCode ->
                selectedLanguage = langCode
                settingsManager.voiceLanguage = langCode
                isVoskModelDownloaded = downloadedLanguages.contains(langCode)
                assistantService?.reloadSettings()
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showSecondaryLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = secondaryLanguage,
            downloadedLanguages = downloadedLanguages,
            onLanguageSelected = { langCode ->
                secondaryLanguage = langCode
                settingsManager.secondaryVoiceLanguage = langCode
                assistantService?.reloadSettings()
                showSecondaryLanguageDialog = false
            },
            onDismiss = { showSecondaryLanguageDialog = false }
        )
    }
}

@Composable
private fun SettingsCategoryTabs(
    selectedCategory: SettingsCategory,
    onCategorySelected: (SettingsCategory) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsCategory.entries.forEach { category ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = { onCategorySelected(category) },
                label = { Text(category.label) }
            )
        }
    }
}

@Composable
fun SettingsOverviewCard(
    provider: String,
    model: String,
    voice: String,
    search: String?,
    keyStatus: String
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 4.dp),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Assistant setup",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        keyStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsStatusChip(
                    icon = Icons.Default.Cloud,
                    label = provider,
                    modifier = Modifier.weight(1f)
                )
                SettingsStatusChip(
                    icon = Icons.Default.RecordVoiceOver,
                    label = voice,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            SettingsStatusChip(
                icon = Icons.Default.SmartToy,
                label = model,
                modifier = Modifier.fillMaxWidth()
            )
            if (search != null) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsStatusChip(
                    icon = Icons.Default.Search,
                    label = search,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun SettingsStatusChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = 40.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
        )
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                content()
            }
        }
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        trailingContent = {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.large)
            .defaultMinSize(minHeight = 72.dp)
            .clickable(onClick = onClick)
    )
}

@Composable
fun SettingsItemWithSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    ListItem(
        headlineContent = {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
                          else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.large)
            .defaultMinSize(minHeight = 72.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
    )
}

@Composable
fun LanguageSelectionDialog(
    currentLanguage: String,
    downloadedLanguages: List<String>,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f).padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Select Voice Language",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                ) {
                    items(VoskTranscriber.AVAILABLE_LANGUAGES) { language ->
                        val isDownloaded = downloadedLanguages.contains(language.code)
                        val isSelected = currentLanguage == language.code

                        ListItem(
                            modifier = Modifier.clickable { onLanguageSelected(language.code) }.padding(vertical = 2.dp),
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(language.displayName, style = MaterialTheme.typography.bodyLarge)
                                    if (isDownloaded) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(Icons.Default.CheckCircle, "Downloaded",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            supportingContent = {
                                Text("${language.modelName} • ~${language.sizeBytes / 1_000_000}MB" +
                                    if (isDownloaded) " • Downloaded" else " • Download needed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            leadingContent = {
                                RadioButton(selected = isSelected, onClick = { onLanguageSelected(language.code) })
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
fun TtsLanguageSelectionDialog(
    currentLanguage: String,
    ttsManager: TTSManager? = null,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f).padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Select TTS Language",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp))

                Text("Availability depends on your device's TTS engine (e.g., Google TTS).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                ) {
                    items(TTSManager.AVAILABLE_TTS_LOCALES) { localeInfo ->
                        val isSelected = currentLanguage == localeInfo.localeTag()
                        val isAvailable = ttsManager?.isLanguageAvailable(localeInfo.locale) == true

                        ListItem(
                            modifier = Modifier
                                .clickable(enabled = isAvailable) {
                                    if (isAvailable) {
                                        onLanguageSelected(localeInfo.localeTag())
                                    }
                                }
                                .padding(vertical = 2.dp),
                            headlineContent = {
                                Text(localeInfo.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isAvailable) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                            },
                            supportingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(localeInfo.localeTag(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isAvailable) MaterialTheme.colorScheme.onSurfaceVariant
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
                                    if (!isAvailable) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("unavailable",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                                    }
                                }
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = isSelected,
                                    enabled = isAvailable,
                                    onClick = {
                                        if (isAvailable) {
                                            onLanguageSelected(localeInfo.localeTag())
                                        }
                                    }
                                )
                            },
                            trailingContent = {
                                if (isAvailable) {
                                    Icon(Icons.Default.CheckCircle, "Available",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
fun PairHearthDialog(
    onDismiss: () -> Unit,
    onPaired: (Endpoint) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val scope = rememberCoroutineScope()

    var pairingLink by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun doPair() {
        errorMessage = null
        val rawLink = pairingLink.trim()
        val rawUrl = serverUrl.trim()
        val rawToken = token.trim()

        if (PairingLink.isPair1Code(rawLink)) {
            if (rawUrl.isEmpty()) {
                errorMessage = context.getString(R.string.hearth_pair_error_invalid)
                return
            }
            val normalizedUrl = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                val hostPart = rawUrl.substringBefore(':')
                if (EndpointPolicy.isPrivateV4(hostPart) || hostPart == "localhost" || hostPart == "127.0.0.1") "http://$rawUrl" else "https://$rawUrl"
            } else rawUrl
            val cleanBaseUrl = normalizedUrl.trimEnd('/')

            val deviceName = (Build.MODEL ?: "")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .take(64)

            isConnecting = true
            scope.launch {
                try {
                    val (redeemedToken, _) = LarariumClient.redeemPairingCode(
                        cleanBaseUrl,
                        rawLink.trim(),
                        deviceName
                    )
                    val endpointName = try {
                        java.net.URI(cleanBaseUrl).host
                    } catch (e: Exception) {
                        null
                    } ?: "hearth"
                    val ep = Endpoint(cleanBaseUrl, redeemedToken, endpointName)

                    val client = LarariumClient(ep)
                    client.health()
                    app.secureKeyManager.setHearthEndpoint(ep.baseUrl, ep.token, ep.name)
                    context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)
                        .edit()
                        .remove(HearthChatClient.KEY_SESSION_ID)
                        .apply()
                    HearthWatchScheduler.ensurePeriodic(app)
                    HearthWatchScheduler.refreshNow(app)
                    onPaired(ep)
                    onDismiss()
                } catch (e: PairError) {
                    errorMessage = context.getString(e.messageResId)
                } catch (e: Exception) {
                    val friendly = HearthChatClient.friendlyErrorMessage(e.message ?: "")
                    errorMessage = context.getString(R.string.hearth_pair_error_failed, friendly)
                } finally {
                    isConnecting = false
                }
            }
            return
        }

        val parsed: Pair<Endpoint, String?>? = when {
            rawLink.isNotEmpty() -> PairingLink.parse(rawLink)
            rawUrl.isNotEmpty() && rawToken.isNotEmpty() -> {
                val normalizedUrl = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                    val hostPart = rawUrl.substringBefore(':')
                    if (EndpointPolicy.isPrivateV4(hostPart) || hostPart == "localhost" || hostPart == "127.0.0.1") "http://$rawUrl" else "https://$rawUrl"
                } else rawUrl
                val built = "lararium://pair?host=" +
                    java.net.URLEncoder.encode(normalizedUrl, "UTF-8") +
                    "&token=" + java.net.URLEncoder.encode(rawToken, "UTF-8")
                PairingLink.parse(built)
            }
            else -> null
        }

        if (parsed == null) {
            errorMessage = context.getString(R.string.hearth_pair_error_invalid)
            return
        }

        val (ep, _) = parsed
        isConnecting = true

        scope.launch {
            try {
                val client = LarariumClient(ep)
                client.health()
                app.secureKeyManager.setHearthEndpoint(ep.baseUrl, ep.token, ep.name)
                context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)
                    .edit()
                    .remove(HearthChatClient.KEY_SESSION_ID)
                    .apply()
                HearthWatchScheduler.ensurePeriodic(app)
                HearthWatchScheduler.refreshNow(app)
                onPaired(ep)
                onDismiss()
            } catch (e: Exception) {
                val friendly = HearthChatClient.friendlyErrorMessage(e.message ?: "")
                errorMessage = context.getString(R.string.hearth_pair_error_failed, friendly)
            } finally {
                isConnecting = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isConnecting) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.hearth_pair_dialog_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.hearth_pair_dialog_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = pairingLink,
                    onValueChange = {
                        pairingLink = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.hearth_pairing_link_label)) },
                    placeholder = { Text(stringResource(R.string.hearth_pairing_link_hint)) },
                    singleLine = true,
                    enabled = !isConnecting,
                    trailingIcon = {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!clip.isNullOrBlank()) {
                                pairingLink = clip.trim()
                                errorMessage = null
                            }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.hearth_or_divider),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f))
                }

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.hearth_server_url_label)) },
                    placeholder = { Text(stringResource(R.string.hearth_server_url_hint)) },
                    singleLine = true,
                    enabled = !isConnecting,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        token = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.hearth_token_label)) },
                    placeholder = { Text(stringResource(R.string.hearth_token_hint)) },
                    singleLine = true,
                    enabled = !isConnecting,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isConnecting) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.hearth_connecting),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { doPair() },
                enabled = !isConnecting && (pairingLink.isNotBlank() || (serverUrl.isNotBlank() && token.isNotBlank()))
            ) {
                Text(stringResource(R.string.hearth_pair_button))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isConnecting
            ) {
                Text(stringResource(R.string.hearth_cancel_button))
            }
        }
    )
}

@Composable
private fun ActivationRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
