package app.lararium.auth

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * AndroidX BiometricPrompt implementation of [Authenticator] (R1, R2, T6 A.4).
 * Uses [BiometricAuthPolicy] to enforce allowed authenticators matching platform capabilities.
 */
class BiometricPromptAuthenticator(
    private val policy: BiometricAuthPolicy = BiometricAuthPolicy
) : Authenticator {

    companion object {
        private const val TAG = "BiometricAuth"
    }

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
        val sdkInt = Build.VERSION.SDK_INT

        val strategy = policy.determineStrategy(
            sdkInt = sdkInt,
            hasStrongBiometric = hasStrongBiometric,
            hasDeviceCredential = isDeviceSecure
        )

        when (strategy) {
            BiometricAuthPolicy.AuthStrategy.NO_HARDWARE_PASSTHROUGH -> {
                // Neither biometrics nor device credentials are enrolled.
                // Warn explicitly (never silently) and allow the app to stay usable per roadmap fallback rules.
                val warning = policy.fallbackWarningFor(strategy)
                if (warning != null) {
                    Log.w(TAG, warning)
                    try {
                        Toast.makeText(fragmentActivity.applicationContext, warning, Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {
                        // Ignore toast errors in headless/test environments
                    }
                }
                onResult(true)
            }
            BiometricAuthPolicy.AuthStrategy.BIOMETRIC_AND_CREDENTIAL,
            BiometricAuthPolicy.AuthStrategy.DEVICE_CREDENTIAL_ONLY -> {
                launchPrompt(
                    activity = fragmentActivity,
                    title = reasonTitle,
                    subtitle = reasonText,
                    allowedAuthenticators = policy.allowedAuthenticatorsFor(strategy, sdkInt),
                    negativeButtonText = policy.negativeButtonTextFor(strategy, isDeviceSecure),
                    onNegativeButtonFallback = null,
                    onResult = onResult
                )
            }
            BiometricAuthPolicy.AuthStrategy.BIOMETRIC_STRONG_ONLY -> {
                val fallback: (() -> Unit)? = if (
                    policy.shouldFallbackToCredentialOnNegativeButton(strategy, isDeviceSecure)
                ) {
                    {
                        launchPrompt(
                            activity = fragmentActivity,
                            title = reasonTitle,
                            subtitle = reasonText,
                            allowedAuthenticators = policy.allowedAuthenticatorsFor(
                                BiometricAuthPolicy.AuthStrategy.DEVICE_CREDENTIAL_ONLY,
                                sdkInt
                            ),
                            negativeButtonText = null,
                            onNegativeButtonFallback = null,
                            onResult = onResult
                        )
                    }
                } else {
                    null
                }
                launchPrompt(
                    activity = fragmentActivity,
                    title = reasonTitle,
                    subtitle = reasonText,
                    allowedAuthenticators = policy.allowedAuthenticatorsFor(strategy, sdkInt),
                    negativeButtonText = policy.negativeButtonTextFor(strategy, isDeviceSecure),
                    onNegativeButtonFallback = fallback,
                    onResult = onResult
                )
            }
        }
    }

    private fun launchPrompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        allowedAuthenticators: Int,
        negativeButtonText: String?,
        onNegativeButtonFallback: (() -> Unit)?,
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
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON && onNegativeButtonFallback != null) {
                    onNegativeButtonFallback.invoke()
                    return
                }
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
