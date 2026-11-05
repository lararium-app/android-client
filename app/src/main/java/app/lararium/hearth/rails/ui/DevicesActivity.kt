package app.lararium.hearth.rails.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.fragment.app.FragmentActivity
import app.lararium.hearth.rails.service.DeviceRailService
import app.lararium.ui.theme.AiintegratedintoandroidTheme

/**
 * Activity hosting the device rails management screens (G1/G2).
 * Handles deep-link entry for lararium://device-pair and hosts DevicesScreen and DevicePairingScreen.
 */
class DevicesActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val parsedLink = PairingValidation.parseDeepLink(intent?.dataString)

        setContent {
            AiintegratedintoandroidTheme {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val parsed = PairingValidation.parseDeepLink(intent.dataString)
        if (parsed != null) {
            recreate()
        }
    }
}
