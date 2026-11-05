package app.lararium.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM unit tests for [BiometricAuthPolicy] (R1, R2, R7).
 * Tests API level fallbacks (API 30+ credential support vs API 26-29 fallback and no-hardware passthrough).
 * ZERO Android dependencies.
 */
class BiometricAuthPolicyTest {

    @Test
    fun api30_withBiometricsAndCredentials_usesBoth() {
        val strategy = BiometricAuthPolicy.determineStrategy(
            sdkInt = 34,
            hasStrongBiometric = true,
            hasDeviceCredential = true
        )
        assertEquals(BiometricAuthPolicy.AuthStrategy.BIOMETRIC_AND_CREDENTIAL, strategy)
    }

    @Test
    fun api30_withCredentialOnly_usesBiometricAndCredential() {
        val strategy = BiometricAuthPolicy.determineStrategy(
            sdkInt = 30,
            hasStrongBiometric = false,
            hasDeviceCredential = true
        )
        assertEquals(BiometricAuthPolicy.AuthStrategy.BIOMETRIC_AND_CREDENTIAL, strategy)
    }

    @Test
    fun api30_noHardwareOrCredential_fallsBackToPassthrough() {
        val strategy = BiometricAuthPolicy.determineStrategy(
            sdkInt = 30,
            hasStrongBiometric = false,
            hasDeviceCredential = false
        )
        assertEquals(BiometricAuthPolicy.AuthStrategy.NO_HARDWARE_PASSTHROUGH, strategy)
    }

    @Test
    fun api26to29_withStrongBiometric_usesStrongOnly() {
        val strategy = BiometricAuthPolicy.determineStrategy(
            sdkInt = 28,
            hasStrongBiometric = true,
            hasDeviceCredential = true
        )
        assertEquals(BiometricAuthPolicy.AuthStrategy.BIOMETRIC_STRONG_ONLY, strategy)
    }

    @Test
    fun api26to29_withCredentialOnly_usesCredentialOnly() {
        val strategy = BiometricAuthPolicy.determineStrategy(
            sdkInt = 29,
            hasStrongBiometric = false,
            hasDeviceCredential = true
        )
        assertEquals(BiometricAuthPolicy.AuthStrategy.DEVICE_CREDENTIAL_ONLY, strategy)
    }

    @Test
    fun api26to29_withNeither_fallsBackToPassthrough() {
        val strategy = BiometricAuthPolicy.determineStrategy(
            sdkInt = 26,
            hasStrongBiometric = false,
            hasDeviceCredential = false
        )
        assertEquals(BiometricAuthPolicy.AuthStrategy.NO_HARDWARE_PASSTHROUGH, strategy)
    }
}
