package app.lararium.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun allowedAuthenticators_api26to29_deviceCredentialOnly_doesNotSilentlyBypass() {
        val mask28 = BiometricAuthPolicy.allowedAuthenticatorsFor(
            BiometricAuthPolicy.AuthStrategy.DEVICE_CREDENTIAL_ONLY,
            sdkInt = 28
        )
        assertEquals(
            "API 26..29 DEVICE_CREDENTIAL_ONLY must use BIOMETRIC_WEAK | DEVICE_CREDENTIAL (0x80FF)",
            BiometricAuthPolicy.AUTH_BIOMETRIC_WEAK or BiometricAuthPolicy.AUTH_DEVICE_CREDENTIAL,
            mask28
        )

        val mask30 = BiometricAuthPolicy.allowedAuthenticatorsFor(
            BiometricAuthPolicy.AuthStrategy.DEVICE_CREDENTIAL_ONLY,
            sdkInt = 30
        )
        assertEquals(
            BiometricAuthPolicy.AUTH_DEVICE_CREDENTIAL,
            mask30
        )
    }

    @Test
    fun strongOnly_withDeviceCredential_offersPinFallback() {
        val strategy = BiometricAuthPolicy.AuthStrategy.BIOMETRIC_STRONG_ONLY
        assertEquals("Use PIN", BiometricAuthPolicy.negativeButtonTextFor(strategy, hasDeviceCredential = true))
        assertTrue(BiometricAuthPolicy.shouldFallbackToCredentialOnNegativeButton(strategy, hasDeviceCredential = true))

        assertEquals("Cancel", BiometricAuthPolicy.negativeButtonTextFor(strategy, hasDeviceCredential = false))
        assertFalse(BiometricAuthPolicy.shouldFallbackToCredentialOnNegativeButton(strategy, hasDeviceCredential = false))
    }

    @Test
    fun passthrough_emitsExplicitWarning_neverSilent() {
        val warning = BiometricAuthPolicy.fallbackWarningFor(BiometricAuthPolicy.AuthStrategy.NO_HARDWARE_PASSTHROUGH)
        assertNotNull("NO_HARDWARE_PASSTHROUGH must return a non-null warning message", warning)
        assertTrue(warning!!.isNotBlank())

        assertNull(BiometricAuthPolicy.fallbackWarningFor(BiometricAuthPolicy.AuthStrategy.BIOMETRIC_AND_CREDENTIAL))
        assertNull(BiometricAuthPolicy.fallbackWarningFor(BiometricAuthPolicy.AuthStrategy.BIOMETRIC_STRONG_ONLY))
        assertNull(BiometricAuthPolicy.fallbackWarningFor(BiometricAuthPolicy.AuthStrategy.DEVICE_CREDENTIAL_ONLY))
    }
}
