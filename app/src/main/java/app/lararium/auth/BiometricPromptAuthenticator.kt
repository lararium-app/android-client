package app.lararium.auth

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * AndroidX BiometricPrompt implementation of [Authenticator] (R1, R2).
 * Uses [BiometricAuthPolicy] to enforce allowed authenticators matching platform capabilities.
 */
class BiometricPromptAuthenticator(
    private val policy: BiometricAuthPolicy = BiometricAuthPolicy
) : Authenticator {

    override fun authenticate(
        activity: Any?,
        reasonTitle: String,
        reasonText: String,
        onResult: (Boolean) -> Unit
    ) {
        val fragmentActivity = activity as? FragmentActivity
        if (fragmentActivity == null) {
            onResult(false)
            return
        }

        val biometricManager = BiometricManager.from(fragmentActivity)
        val keyguardManager = fragmentActivity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

        val hasStrongBiometric = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS

        val isDeviceSecure = keyguardManager?.isDeviceSecure == true

        val strategy = policy.determineStrategy(
            sdkInt = Build.VERSION.SDK_INT,
            hasStrongBiometric = hasStrongBiometric,
            hasDeviceCredential = isDeviceSecure
        )

        when (strategy) {
            BiometricAuthPolicy.AuthStrategy.NO_HARDWARE_PASSTHROUGH -> {
                // No biometric hardware or enrollment, and credentials not usable.
                // Allow the app to stay usable per roadmap fallback rules.
                onResult(true)
            }
            BiometricAuthPolicy.AuthStrategy.BIOMETRIC_AND_CREDENTIAL -> {
                launchPrompt(
                    activity = fragmentActivity,
                    title = reasonTitle,
                    subtitle = reasonText,
                    allowedAuthenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    negativeButtonText = null,
                    onResult = onResult
                )
            }
            BiometricAuthPolicy.AuthStrategy.BIOMETRIC_STRONG_ONLY -> {
                launchPrompt(
                    activity = fragmentActivity,
                    title = reasonTitle,
                    subtitle = reasonText,
                    allowedAuthenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG,
                    negativeButtonText = "Cancel",
                    onResult = onResult
                )
            }
            BiometricAuthPolicy.AuthStrategy.DEVICE_CREDENTIAL_ONLY -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    launchPrompt(
                        activity = fragmentActivity,
                        title = reasonTitle,
                        subtitle = reasonText,
                        allowedAuthenticators = BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                        negativeButtonText = null,
                        onResult = onResult
                    )
                } else {
                    // On API 26-29 without strong biometric hardware, pass through
                    onResult(true)
                }
            }
        }
    }

    private fun launchPrompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        allowedAuthenticators: Int,
        negativeButtonText: String?,
        onResult: (Boolean) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onResult(false)
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val builder = BiometricPrompt.PromptInfo.Builder().setTitle(title)

        if (subtitle.isNotBlank()) {
            builder.setSubtitle(subtitle)
        }

        builder.setAllowedAuthenticators(allowedAuthenticators)
        if (negativeButtonText != null) {
            builder.setNegativeButtonText(negativeButtonText)
        }

        try {
            prompt.authenticate(builder.build())
        } catch (_: Exception) {
            onResult(false)
        }
    }
}
