package app.lararium.auth

/**
 * Pure Kotlin policy determining biometric & credential authentication capability (R1, R2).
 * MinSdk is 26.
 *
 * Handles platform fallbacks:
 * - API 30+ (Android R+): BIOMETRIC_STRONG or DEVICE_CREDENTIAL supported directly via BiometricPrompt.
 * - API 26..29: androidx.biometric 1.1.0 restricts DEVICE_CREDENTIAL when combined with BIOMETRIC_STRONG;
 *   falls back to BIOMETRIC_STRONG only.
 * - No biometric hardware / none enrolled: handles gracefully by falling back to device-credential
 *   where the platform supports it, or allowing the app to stay usable with a warning if neither is set.
 */
object BiometricAuthPolicy {

    enum class AuthStrategy {
        BIOMETRIC_AND_CREDENTIAL,
        BIOMETRIC_STRONG_ONLY,
        DEVICE_CREDENTIAL_ONLY,
        NO_HARDWARE_PASSTHROUGH
    }

    // Mirror AndroidX BiometricManager.Authenticators bitmasks for pure JVM unit testability.
    const val AUTH_BIOMETRIC_STRONG: Int = 0x000F
    const val AUTH_BIOMETRIC_WEAK: Int = 0x00FF
    const val AUTH_DEVICE_CREDENTIAL: Int = 0x8000

    const val NO_ENROLLMENT_WARNING: String =
        "No screen lock or biometrics enrolled — set a device PIN or biometric to lock Lararium"

    /**
     * Pure function determining which authentication strategy to employ.
     *
     * @param sdkInt The Android SDK version (e.g., Build.VERSION.SDK_INT)
     * @param hasStrongBiometric True if BIOMETRIC_STRONG hardware exists and has enrollments
     * @param hasDeviceCredential True if device PIN / pattern / password is secure
     */
    fun determineStrategy(
        sdkInt: Int,
        hasStrongBiometric: Boolean,
        hasDeviceCredential: Boolean
    ): AuthStrategy {
        return when {
            sdkInt >= 30 -> {
                if (hasStrongBiometric || hasDeviceCredential) {
                    AuthStrategy.BIOMETRIC_AND_CREDENTIAL
                } else {
                    AuthStrategy.NO_HARDWARE_PASSTHROUGH
                }
            }
            sdkInt >= 26 -> {
                if (hasStrongBiometric) {
                    AuthStrategy.BIOMETRIC_STRONG_ONLY
                } else if (hasDeviceCredential) {
                    AuthStrategy.DEVICE_CREDENTIAL_ONLY
                } else {
                    AuthStrategy.NO_HARDWARE_PASSTHROUGH
                }
            }
            else -> AuthStrategy.NO_HARDWARE_PASSTHROUGH
        }
    }

    /**
     * Pure mapping from [AuthStrategy] and [sdkInt] to AndroidX BiometricPrompt allowedAuthenticators bitmask.
     *
     * Note: On API 26..29, androidx.biometric 1.1.0 rejects `DEVICE_CREDENTIAL` alone or `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`,
     * but supports `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` (`0x80FF`), which falls back cleanly to the device PIN/pattern/password
     * when no biometrics are enrolled.
     */
    fun allowedAuthenticatorsFor(strategy: AuthStrategy, sdkInt: Int): Int {
        return when (strategy) {
            AuthStrategy.BIOMETRIC_AND_CREDENTIAL -> AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL
            AuthStrategy.BIOMETRIC_STRONG_ONLY -> AUTH_BIOMETRIC_STRONG
            AuthStrategy.DEVICE_CREDENTIAL_ONLY -> {
                if (sdkInt >= 30) {
                    AUTH_DEVICE_CREDENTIAL
                } else {
                    AUTH_BIOMETRIC_WEAK or AUTH_DEVICE_CREDENTIAL
                }
            }
            AuthStrategy.NO_HARDWARE_PASSTHROUGH -> 0
        }
    }

    /**
     * Negative button label for [BiometricPrompt.PromptInfo].
     * On API 26..29 (`BIOMETRIC_STRONG_ONLY`), offers `"Use PIN"` when a device credential is enrolled.
     */
    fun negativeButtonTextFor(strategy: AuthStrategy, hasDeviceCredential: Boolean): String? {
        return when (strategy) {
            AuthStrategy.BIOMETRIC_STRONG_ONLY -> if (hasDeviceCredential) "Use PIN" else "Cancel"
            else -> null
        }
    }

    /**
     * Whether tapping the negative button on [strategy] should trigger a device-credential fallback prompt.
     */
    fun shouldFallbackToCredentialOnNegativeButton(
        strategy: AuthStrategy,
        hasDeviceCredential: Boolean
    ): Boolean {
        return strategy == AuthStrategy.BIOMETRIC_STRONG_ONLY && hasDeviceCredential
    }

    /**
     * Non-null user-visible warning message when falling back without any enrolled biometric or credential.
     * Ensures passthrough is never silent (T6 A.4).
     */
    fun fallbackWarningFor(strategy: AuthStrategy): String? {
        return when (strategy) {
            AuthStrategy.NO_HARDWARE_PASSTHROUGH -> NO_ENROLLMENT_WARNING
            else -> null
        }
    }
}
