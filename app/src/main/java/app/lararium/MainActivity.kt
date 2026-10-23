package app.lararium

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.lararium.hearth.watch.HearthWatchScheduler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.lararium.service.AssistantAccessibilityService
import app.lararium.service.AssistantService
import app.lararium.ui.SettingsManager
import app.lararium.ui.SettingsScreen
import app.lararium.ui.OrientationLogic
import app.lararium.ui.VoskLanguageManagerScreen
import app.lararium.ui.theme.AiintegratedintoandroidTheme
import app.lararium.audio.VoskTranscriber
import app.lararium.hearth.LarariumClient
import app.lararium.hearth.Endpoint
import app.lararium.hearth.PairingLink
import app.lararium.hearth.badge.BadgeLogic
import app.lararium.hearth.badge.HearthBadgeState
import app.lararium.security.SecureKeyManager
import app.lararium.ui.approvals.ApprovalsScreen
import app.lararium.ui.ChatHomeScreen
import app.lararium.ui.ChatViewModel
import app.lararium.ui.DrawerLogic
import app.lararium.ui.LarariumDrawerContent
import app.lararium.ui.SessionsScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BADGE_REFRESH_MS = 30_000L

class MainActivity : ComponentActivity() {

    /** Deep-link target route to navigate to once the compose navigation graph is ready. */
    private val openRoute = MutableStateFlow<String?>(null)

    /** Transient status shown to the user after a pairing attempt. */
    private val pairingStatus = mutableStateOf<String?>(null)

    /** Scope for fire-and-forget work that must outlive individual compositions. */
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Hot deep link: activity already running when link is tapped. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
        openRoute.value = intent.getStringExtra("lararium_open")
        intent.removeExtra("lararium_open")
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    /** Parse a lararium://pair deep link, persist, and verify via health(). */
    private fun handlePairingIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "lararium" || data.host != "pair") return
        val raw = intent.dataString ?: return

        val parsed = PairingLink.parse(raw)
        if (parsed == null) {
            pairingStatus.value = "Invalid pairing link"
            return
        }
        val (ep, _) = parsed

        // H9: a deep link never silently replaces an existing
        // pairing. The user confirms old -> new; the token is never
        // displayed.
        val current = (applicationContext as AssistantApplication)
            .secureKeyManager.getHearthEndpoint()
        val prompt = if (current == null) {
            "Pair this device with \"${ep.name}\"?\n\n${ep.baseUrl}"
        } else {
            val (curBase, _, curName) = current
            "Replace your pairing \"$curName\"\n($curBase)\n" +
                "\nwith \"${ep.name}\" (${ep.baseUrl})?"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Lararium pairing")
            .setMessage(prompt)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .setPositiveButton("Pair") { _, _ -> connectPairedEndpoint(ep) }
            .show()
    }

    /** Verified-pair step of a confirmed deep link. */
    private fun connectPairedEndpoint(ep: app.lararium.hearth.Endpoint) {
        pairingStatus.value = "Connecting to ${ep.name}…"

        activityScope.launch {
            val app = applicationContext as AssistantApplication
            try {
                val client = LarariumClient(ep)
                client.health() // verify reachability
                app.secureKeyManager.setHearthEndpoint(ep.baseUrl, ep.token, ep.name)
                HearthWatchScheduler.ensurePeriodic(app)
                HearthWatchScheduler.refreshNow(app)
                pairingStatus.value = "Paired with ${ep.name} ✓"
            } catch (e: Exception) {
                pairingStatus.value = "Pairing failed: ${e.message}"
            }
        }
    }

    private val chatViewModel: ChatViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[ChatViewModel::class.java]
    }

    /**
     * Prevents screen capture, screenshots, screen recording, and recents previews
     * for screens displaying secrets (L-10).
     *
     * Grep-documented list of covered screens in this single-Activity Compose host:
     * - Main chat (ChatHomeScreen): may display bearer tokens, approval cards, credentials, sensitive tool payloads
     * - Pairing / QR screen (PairingDialog / SettingsScreen): displays hearth pairing tokens, QR codes, endpoints
     * - Settings / keys screens (SettingsScreen): displays API keys (OpenAI, Anthropic, OpenRouter), local token secrets
     * - Sessions screen (SessionsScreen): lists past chat sessions and titles
     * - Custody approvals (ApprovalsScreen): displays approval requests with sensitive tool parameters and destinations
     */
    private fun applyFlagSecure() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        @Suppress("UNUSED_VARIABLE")
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
    }
    
    override fun onStart() {
        super.onStart()
        val app = applicationContext as AssistantApplication
        if (app.secureKeyManager.getHearthEndpoint() != null) {
            HearthWatchScheduler.refreshNow(this)
        }
        chatViewModel.assistantService.value?.retryConnectionNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFlagSecure()

        val settingsManager = SettingsManager(this)
        setRequestedOrientation(OrientationLogic.orientationForSetting(settingsManager.lockOrientation))

        enableEdgeToEdge()

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO
            )
        )

        // Cold start — handle a pairing link that launched the activity.
        handlePairingIntent(intent)
        openRoute.value = intent?.getStringExtra("lararium_open")
        intent?.removeExtra("lararium_open")

        setContent {
            AiintegratedintoandroidTheme {
                val context = LocalContext.current
                val app = context.applicationContext as AssistantApplication
                val isPaired = app.secureKeyManager.getHearthEndpoint() != null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { /* permission result handled by system */ }

                    LaunchedEffect(isPaired) {
                        if (isPaired && ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                // Pending-approval badge: refresh on every ON_START, then every 30s while foregrounded.
                LaunchedEffect(Unit) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (true) {
                            HearthBadgeState.refresh(context)
                            delay(BADGE_REFRESH_MS)
                        }
                    }
                }

                val chatViewModel = this.chatViewModel
                val boundService by chatViewModel.assistantService.collectAsStateWithLifecycle()
                val navController = rememberNavController()
                val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
                val snackbarHostState = remember { SnackbarHostState() }
                val status by pairingStatus
                val targetRoute by openRoute.collectAsStateWithLifecycle()
                val pendingApprovals by HearthBadgeState.pending.collectAsStateWithLifecycle()

                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                var hearthEndpoint by remember { mutableStateOf(app.secureKeyManager.getHearthEndpoint()) }

                LaunchedEffect(currentRoute) {
                    hearthEndpoint = app.secureKeyManager.getHearthEndpoint()
                }

                LaunchedEffect(targetRoute) {
                    if (targetRoute == "approvals") {
                        navController.navigate("approvals")
                        openRoute.value = null
                    }
                }

                // Show pairing status once as a snackbar, then clear.
                LaunchedEffect(status) {
                    val msg = status ?: return@LaunchedEffect
                    snackbarHostState.showSnackbar(msg)
                    pairingStatus.value = null
                    hearthEndpoint = app.secureKeyManager.getHearthEndpoint()
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = currentRoute == "main",
                    drawerContent = {
                        LarariumDrawerContent(
                            hearthEndpoint = hearthEndpoint,
                            pendingApprovals = pendingApprovals,
                            onNavigateToChats = {
                                scope.launch { drawerState.close() }
                                navController.navigate("sessions")
                            },
                            onNavigateToApprovals = {
                                scope.launch { drawerState.close() }
                                navController.navigate("approvals")
                            },
                            onNavigateToSettings = {
                                scope.launch { drawerState.close() }
                                navController.navigate("settings")
                            }
                        )
                    }
                ) {
                    Scaffold(
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        snackbarHost = { SnackbarHost(snackbarHostState) }
                    ) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            NavHost(navController = navController, startDestination = "main") {
                                composable("main") {
                                    ChatHomeScreen(
                                        assistantService = boundService,
                                        hearthEndpoint = hearthEndpoint,
                                        pendingApprovals = pendingApprovals,
                                        onOpenDrawer = { scope.launch { drawerState.open() } },
                                        onOpenApprovals = { navController.navigate("approvals") },
                                        onNewChat = { chatViewModel.clearSession() },
                                        viewModel = chatViewModel,
                                        onRepair = { navController.navigate("settings") }
                                    )
                                }
                                composable("sessions") {
                                    SessionsScreen(
                                        onBack = { navController.popBackStack() },
                                        onSessionSelected = { sessionId ->
                                            scope.launch(Dispatchers.IO) {
                                                val epTriple = app.secureKeyManager.getHearthEndpoint()
                                                val messages = if (epTriple != null) {
                                                    try {
                                                        val ep = Endpoint(epTriple.first, epTriple.second, epTriple.third)
                                                        val eventsJson = LarariumClient(ep).events(sessionId, afterSeq = 0)
                                                        DrawerLogic.parseSessionMessages(eventsJson.toString())
                                                    } catch (_: Exception) {
                                                        emptyList()
                                                    }
                                                } else emptyList()

                                                withContext(Dispatchers.Main) {
                                                    chatViewModel.loadHearthSession(sessionId, messages)
                                                    navController.navigate("main") {
                                                        popUpTo("main") { inclusive = true }
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                                composable("settings") {
                                    SettingsScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                        assistantService = boundService,
                                        onNavigateToLanguages = { navController.navigate("voice_languages") },
                                        onOpenAccessibilitySettings = ::openAccessibilitySettings,
                                        onOpenAssistantSettings = ::openAssistantSettings,
                                        onOrientationLockChanged = { locked ->
                                            setRequestedOrientation(OrientationLogic.orientationForSetting(locked))
                                        }
                                    )
                                }
                                composable("voice_languages") {
                                    VoskLanguageManagerScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                        assistantService = boundService
                                    )
                                }
                                composable("approvals") {
                                    ApprovalsScreen(onBack = { navController.popBackStack() })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAssistantSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun launchAssistant() {
        val intent = Intent(this, AssistantService::class.java).apply {
            action = AssistantService.ACTION_ACTIVATE
        }
        startForegroundService(intent)
    }
}
