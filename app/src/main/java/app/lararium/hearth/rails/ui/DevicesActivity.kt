package app.lararium.hearth.rails.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lararium.auth.AppLock
import app.lararium.auth.ui.LockScreen
import app.lararium.hearth.rails.service.DeviceRailService
import app.lararium.ui.theme.AiintegratedintoandroidTheme

/**
 * Activity hosting the device rails management screens (G1/G2, T6 A.1).
 * Handles deep-link entry for lararium://device-pair and hosts DevicesScreen and DevicePairingScreen.
 * Gated behind [AppLock] so cold start or resume past the 5-min timeout requires biometric unlock.
 */
class DevicesActivity : FragmentActivity() {

    override fun onStart() {
        super.onStart()
        AppLock.onForeground(System.currentTimeMillis())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> AppLock.onForeground(System.currentTimeMillis())
                Lifecycle.Event.ON_STOP -> AppLock.onBackground(System.currentTimeMillis())
                else -> {}
            }
        })

        val parsedLink = PairingValidation.parseDeepLink(intent?.dataString)

        setContent {
            AiintegratedintoandroidTheme {
                val isUnlocked by AppLock.isUnlockedState.collectAsStateWithLifecycle()

                fun triggerUnlock() {
                    AppLock.authenticator.authenticate(
                        activity = this@DevicesActivity,
                        reasonTitle = "Unlock Lararium",
                        reasonText = "Confirm your identity to open Device Rails"
                    ) { success ->
                        if (success) {
                            AppLock.unlock()
                        }
                    }
                }

                LaunchedEffect(isUnlocked) {
                    if (!isUnlocked) {
                        triggerUnlock()
                    }
                }

                if (!isUnlocked) {
                    LockScreen(
                        onUnlock = { triggerUnlock() }
                    )
                } else {
                    var currentScreen by remember {
                        mutableStateOf(if (parsedLink != null) "pairing" else "devices")
                    }
                    var prefillBaseUrl by remember { mutableStateOf(parsedLink?.baseUrl ?: "") }
                    var prefillToken by remember { mutableStateOf(parsedLink?.token ?: "") }

                    when (currentScreen) {
                        "pairing" -> {
                            DevicePairingScreen(
                                initialBaseUrl = prefillBaseUrl,
                                initialToken = prefillToken,
                                onNavigateBack = {
                                    if (parsedLink != null) {
                                        finish()
                                    } else {
                                        currentScreen = "devices"
                                    }
                                },
                                onPairingSuccess = {
                                    DeviceRailService.ensureRunning(this@DevicesActivity)
                                    currentScreen = "devices"
                                }
                            )
                        }
                        else -> {
                            DevicesScreen(
                                onNavigateBack = { finish() },
                                onNavigateToPairing = { base, tok ->
                                    prefillBaseUrl = base ?: ""
                                    prefillToken = tok ?: ""
                                    currentScreen = "pairing"
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val parsed = PairingValidation.parseDeepLink(intent.dataString)
        if (parsed != null) {
            recreate()
        }
    }
}
