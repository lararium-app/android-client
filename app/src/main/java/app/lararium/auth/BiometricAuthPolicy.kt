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
}
