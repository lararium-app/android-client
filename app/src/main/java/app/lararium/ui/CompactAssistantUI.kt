package app.lararium.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lararium.MainActivity
import app.lararium.R
import app.lararium.auth.AppLock
import app.lararium.auth.ui.LockScreen
import app.lararium.hearth.watch.HearthNotifier
import app.lararium.service.AssistantService
import app.lararium.service.AssistantState
import app.lararium.service.VoiceCaptureOrigin
import app.lararium.ui.theme.AiintegratedintoandroidTheme
import app.lararium.util.PdfExtractor
import app.lararium.util.PdfResult
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import app.lararium.util.LinkPolicy
import app.lararium.assistant.NotificationSummary
import app.lararium.assistant.ScreenContextPolicy
import app.lararium.assistant.ScreenContextProvider
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.text.style.TextOverflow

internal object ScrimDismissPolicy {
    const val DEBOUNCE_MS = 300L
    fun shouldDismiss(shownAtMs: Long, nowMs: Long): Boolean = nowMs - shownAtMs >= DEBOUNCE_MS
}

/** One offered notification line; [checked] defaults to true and is user-controlled. */
class ContextEntry(val summary: NotificationSummary) {
    var checked by mutableStateOf(true)
}

class CompactAssistantActivity : FragmentActivity() {

    private var assistantService: AssistantService? = null
    private var bound = false
    private val serviceState = mutableStateOf<AssistantService?>(null)
    private val contextEntries = mutableStateListOf<ContextEntry>()
    private val sharedText = mutableStateOf("")
    private var overlayShownAtMs = 0L

    private fun loadContext() {
        runCatching {
            contextEntries.clear()
            ScreenContextProvider.snapshot(SettingsManager(this)).forEach { contextEntries.add(ContextEntry(it)) }
        }
    }

    private fun excludePackage(pkg: String) {
        runCatching {
            val manager = SettingsManager(this)
            manager.screenContextExcludedPackages = manager.screenContextExcludedPackages + pkg
            contextEntries.removeAll { it.summary.packageName == pkg }
        }
    }

    private fun readSharedText(source: Intent?): String = runCatching {
        if (source?.action == Intent.ACTION_SEND && source.type == "text/plain") {
            ScreenContextPolicy.sanitizeShared(source.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        } else ""
    }.getOrDefault("")

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText.value = readSharedText(intent)
        loadContext()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AssistantService.AssistantBinder
            assistantService = binder.getService()
            serviceState.value = assistantService
            bound = true
            if (AppLock.policy.canReachHearth()) {
                assistantService?.retryConnectionNow()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            assistantService = null
            serviceState.value = null
            bound = false
        }
    }

    private fun bindServiceIfUnlocked() {
        if (!bound && AppLock.policy.canReachHearth()) {
            Intent(this, AssistantService::class.java).also { intent ->
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    /**
     * Prevents screen capture of the assistant overlay popup (L-10), unless
     * the user opted into allowing screenshots in settings.
     */
    private fun applyFlagSecure() {
        val allowScreenshots = runCatching {
            SettingsManager(this).allowScreenshots
        }.getOrDefault(false)

        if (!allowScreenshots) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onResume() {
        super.onResume()
        overlayShownAtMs = SystemClock.elapsedRealtime()
        applyFlagSecure()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFlagSecure()
        enableEdgeToEdge()
        sharedText.value = readSharedText(intent)
        loadContext()

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> AppLock.onForeground(System.currentTimeMillis())
                Lifecycle.Event.ON_STOP -> AppLock.onBackground(System.currentTimeMillis())
                else -> {}
            }
        })

        setContent {
            AiintegratedintoandroidTheme {
                val isUnlocked by AppLock.isUnlockedState.collectAsStateWithLifecycle()

                fun triggerUnlock() {
                    AppLock.authenticator.authenticate(
                        activity = this@CompactAssistantActivity,
                        reasonTitle = "Unlock Lararium",
                        reasonText = "Confirm your identity to use the assistant"
                    ) { success ->
                        if (success) {
                            AppLock.unlock()
                            bindServiceIfUnlocked()
                        }
                    }
                }

                LaunchedEffect(isUnlocked) {
                    if (!isUnlocked) {
                        triggerUnlock()
                    } else {
                        bindServiceIfUnlocked()
                    }
                }

                if (!isUnlocked) {
                    LockScreen(
                        onUnlock = { triggerUnlock() }
                    )
                } else {
                    val service by serviceState

                    if (service != null) {
                        val state by service!!.assistantState.collectAsStateWithLifecycle()
                        val response by service!!.response.collectAsStateWithLifecycle()
                        val transcription by service!!.transcription.collectAsStateWithLifecycle()
                        val pendingUrls by service!!.pendingUrls.collectAsStateWithLifecycle()

                        if (pendingUrls.isNotEmpty()) {
                            UrlSelectionDialog(
                                urls = pendingUrls,
                                onUrlSelected = { url -> service!!.openUrl(url) },
                                onDismiss = { service!!.clearPendingUrls() }
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.4f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        if (ScrimDismissPolicy.shouldDismiss(overlayShownAtMs, SystemClock.elapsedRealtime())) {
                                            finish()
                                        }
                                    }
                                )
                        ) {
                            CompactAssistantView(
                                service = service!!,
                                state = state,
                                transcription = transcription,
                                response = response,
                                onExpand = { openFullAppOnCurrentSession() },
                                onDismiss = { finish() },
                                contextEntries = contextEntries,
                                onExcludePackage = { excludePackage(it) },
                                initialText = sharedText.value
                            )
                        }
                    }
                }
            }
        }
    }

    /** Expand = hand off to the full app on the same hearth session (#T4 polish). */
    private fun openFullAppOnCurrentSession() {
        val sessionId = AssistantService.activeSessionId
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (!sessionId.isNullOrBlank()) {
                putExtra(HearthNotifier.EXTRA_SESSION_ID, sessionId)
            }
        }
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not open main app; staying in overlay", e)
            return
        }
        finish()
    }

    override fun onStart() {
        super.onStart()
        AppLock.onForeground(System.currentTimeMillis())
        bindServiceIfUnlocked()
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    companion object {
        private const val TAG = "CompactAssistantActivity"
    }
}


@Composable
fun CompactAssistantView(
    service: AssistantService,
    state: AssistantState,
    transcription: String,
    response: String,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    contextEntries: MutableList<ContextEntry> = mutableListOf(),
    onExcludePackage: (String) -> Unit = {},
    initialText: String = ""
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    var textInput by remember(initialText) { mutableStateOf(initialText) }
    val scrollState = rememberScrollState()

    var selectedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }

    var pdfText by remember { mutableStateOf<String?>(null) }
    var pdfSummary by remember { mutableStateOf<String?>(null) }
    var pdfLoading by remember { mutableStateOf(false) }
    var pdfError by remember { mutableStateOf<String?>(null) }

    val autoStartVoice = settingsManager.autoStartVoice
    LaunchedEffect(Unit) {
        if (autoStartVoice && state is AssistantState.Idle) {
            service.startVoiceCapture(VoiceCaptureOrigin.OVERLAY_AUTOSTART)
        }
    }

    val isGenerating = state is AssistantState.Processing ||
        state is AssistantState.Searching ||
        state is AssistantState.Responding ||
        state is AssistantState.Speaking

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            pdfLoading = true
            pdfError = null
            scope.launch {
                when (val result = PdfExtractor.extractText(context, it)) {
                    is PdfResult.Success -> {
                        pdfText = result.text
                        pdfSummary = result.summary
                        pdfError = null
                    }
                    is PdfResult.Error -> {
                        pdfError = result.message
                        pdfText = null
                        pdfSummary = null
                    }
                }
                pdfLoading = false
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap == null) return@let

                val maxSize = 1024
                val scaledBitmap = if (bitmap.width > maxSize || bitmap.height > maxSize) {
                    val ratio = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
                } else bitmap

                selectedImageBitmap = scaledBitmap
                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                val base64Data = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                selectedImageBase64 = base64Data
            } catch (e: Exception) { }
        }
    }

    val displayTranscription = if (state is AssistantState.Listening && transcription.isNotEmpty()) {
        "You: $transcription"
    } else if (transcription.isNotEmpty() && (state is AssistantState.Processing || state is AssistantState.Searching)) {
        "You: $transcription"
    } else ""

    val displayResponse = when {
        response.isNotEmpty() -> response
        state is AssistantState.Error -> state.message
        else -> ""
    }

    val statusText = when (state) {
        is AssistantState.Listening -> if (transcription.isEmpty()) "Listening..." else ""
        is AssistantState.Processing -> "Thinking..."
        is AssistantState.Searching -> "Searching web..."
        is AssistantState.Responding -> ""
        is AssistantState.Speaking -> ""
        else -> ""
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        val sheetMaxHeight = maxHeight * 0.92f
        val responseMaxHeight = if (maxHeight < 420.dp) 120.dp else 200.dp

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .imePadding()
                .navigationBarsPadding()
                .heightIn(max = sheetMaxHeight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(36.dp),
            shadowElevation = 24.dp,
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Surface(
                        modifier = Modifier.width(36.dp).height(4.dp),
                        shape = RoundedCornerShape(2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    ) {}
                }

                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(34.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            tonalElevation = 0.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(app.lararium.R.string.app_name),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Row {
                        IconButton(
                            onClick = onExpand,
                            modifier = Modifier
                                .size(48.dp)
                                .clearAndSetSemantics {
                                    contentDescription = "Expand"
                                    role = Role.Button
                                }
                        ) {
                            Icon(Icons.Default.OpenInFull, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .clearAndSetSemantics {
                                    contentDescription = "Close"
                                    role = Role.Button
                                }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (displayTranscription.isNotEmpty() || displayResponse.isNotEmpty() || statusText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = responseMaxHeight)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(14.dp)
                            .verticalScroll(scrollState)
                    ) {
                        if (displayTranscription.isNotEmpty()) {
                            Text(
                                displayTranscription,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (displayResponse.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }

                        if (statusText.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    statusText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (displayResponse.isNotEmpty()) {
                            if (state is AssistantState.Error) {
                                Text(
                                    displayResponse,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                MarkdownText(
                                    text = displayResponse,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    linkColor = MaterialTheme.colorScheme.primary,
                                    onLinkClick = { url ->
                                        if (LinkPolicy.isSafeExternal(url)) {
                                            try {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                    context.startActivity(intent)
                                            } catch (e: Exception) { }
                                        }
                                    }
                                )
                            }
                        }
                    }

                    LaunchedEffect(displayResponse, displayTranscription) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                selectedImageBitmap?.let { bitmap ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Selected image",
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Image attached",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                selectedImageBitmap = null
                                selectedImageBase64 = null
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clearAndSetSemantics {
                                    contentDescription = "Remove"
                                    role = Role.Button
                                }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                if (pdfLoading) {
                    Row(
                        modifier = Modifier.padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Loading PDF...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                pdfError?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp))
                }

                pdfSummary?.let { summary ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "PDF",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(summary, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                pdfText = null; pdfSummary = null; pdfError = null
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clearAndSetSemantics {
                                    contentDescription = "Remove"
                                    role = Role.Button
                                }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                NotificationContextSection(contextEntries, onExcludePackage)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { pdfPickerLauncher.launch("application/pdf") },
                        enabled = !isGenerating,
                        modifier = Modifier
                            .size(48.dp)
                            .clearAndSetSemantics {
                                contentDescription = "Attach PDF"
                                role = Role.Button
                            }
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null,
                            tint = if (pdfText != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask anything...") },
                        shape = RoundedCornerShape(28.dp),
                        singleLine = true,
                        enabled = !isGenerating,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                        )
                    )

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable(
                                role = Role.Button,
                                onClick = {
                                    when {
                                        isGenerating -> service.stopResponse()
                                        state is AssistantState.Listening -> service.stopVoiceCapture()
                                        else -> service.startVoiceCapture(VoiceCaptureOrigin.UI_TAP)
                                    }
                                }
                            )
                            .clearAndSetSemantics {
                                contentDescription = if (isGenerating) "Stop response" else "Voice"
                                role = Role.Button
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            color = if (isGenerating || state is AssistantState.Listening)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    when {
                                        isGenerating || state is AssistantState.Listening -> Icons.Default.Stop
                                        else -> Icons.Default.Mic
                                    },
                                    contentDescription = null,
                                    tint = if (isGenerating || state is AssistantState.Listening)
                                        MaterialTheme.colorScheme.onError
                                    else MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = !isGenerating && (textInput.isNotEmpty() || selectedImageBase64 != null || pdfText != null)) {
                        FilledIconButton(
                            onClick = {
                                val currentText = textInput
                                val currentImageBase64 = selectedImageBase64
                                val currentPdfText = pdfText

                                val queryText = if (currentPdfText != null) {
                                    val userQuery = currentText.ifBlank { "Summarize this document" }
                                    "Document content:\n---\n$currentPdfText\n---\n\nUser question: $userQuery"
                                } else {
                                    currentText.ifBlank { "What's in this image?" }
                                }

                                val outgoing = ScreenContextPolicy.prependContext(
                                    contextEntries.filter { it.checked }.map { it.summary }, queryText)
                                service.processTextQuery(outgoing, currentImageBase64)
                                contextEntries.clear()
                                textInput = ""
                                selectedImageBitmap = null
                                selectedImageBase64 = null
                                pdfText = null
                                pdfSummary = null
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clearAndSetSemantics {
                                    contentDescription = "Send"
                                    role = Role.Button
                                }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
fun UrlSelectionDialog(
    urls: List<String>,
    onUrlSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open Link") },
        text = {
            Column {
                Text("Which link would you like to open?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))

                urls.forEachIndexed { index, url ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onUrlSelected(url) },
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 1.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = url.take(50) + if (url.length > 50) "..." else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotificationContextSection(
    entries: MutableList<ContextEntry>,
    onExcludePackage: (String) -> Unit
) {
    if (entries.isEmpty()) return
    var expanded by remember { mutableStateOf(true) }
    var menuFor by remember { mutableStateOf<ContextEntry?>(null) }
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Context from recent notifications (${entries.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            entries.toList().forEach { entry ->
                val pkg = entry.summary.packageName
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { entry.checked = !entry.checked },
                                onLongClick = { menuFor = entry }
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = entry.checked, onCheckedChange = { entry.checked = it })
                        Text(
                            "$pkg: ${ScreenContextPolicy.displayLine(entry.summary)}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menuFor === entry, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(
                            text = { Text("Exclude $pkg") },
                            onClick = {
                                menuFor = null
                                onExcludePackage(pkg)
                            }
                        )
                    }
                }
            }
        }
    }
}
