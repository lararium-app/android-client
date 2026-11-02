package app.lararium.hearth.rails.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingValidationTest {

    @Test
    fun testUrlPolicyAcceptMatrix() {
        val validUrls = listOf(
            "http://localhost",
            "http://localhost:8080",
            "http://127.0.0.1:8080",
            "http://10.0.0.1:8080",
            "http://172.16.0.1:8080",
            "http://172.31.255.254:8080",
            "http://192.168.1.1:8080",
            "http://100.64.0.1:8080",
            "http://100.127.255.254:8080",
            "https://hearth.example.com",
            "https://hearth.example.com:8443",
            "https://1.1.1.1:8443"
        )

        for (url in validUrls) {
            val result = PairingValidation.validateBaseUrl(url)
            assertTrue("Expected '$url' to be accepted but got error: ${result.error}", result.isValid)
            assertNull("Expected error to be null for '$url'", result.error)
        }
    }

    @Test
    fun testUrlPolicyRejectMatrix() {
        // Cleartext HTTP on public hosts is rejected
        val publicHttp = PairingValidation.validateBaseUrl("http://example.com")
        assertFalse(publicHttp.isValid)
        assertTrue(publicHttp.error?.contains("cleartext http is only allowed on LAN/tailnet hosts; use https") == true)

        val publicIpHttp = PairingValidation.validateBaseUrl("http://8.8.8.8")
        assertFalse(publicIpHttp.isValid)
        assertTrue(publicIpHttp.error?.contains("cleartext http is only allowed on LAN/tailnet hosts; use https") == true)

        // Unsupported scheme
        val ftpScheme = PairingValidation.validateBaseUrl("ftp://localhost")
        assertFalse(ftpScheme.isValid)
        assertTrue(ftpScheme.error?.contains("unsupported scheme ftp") == true)

        // Blank or invalid
        val blank = PairingValidation.validateBaseUrl("")
        assertFalse(blank.isValid)
        assertEquals("Base URL cannot be blank", blank.error)

        val spaces = PairingValidation.validateBaseUrl("   ")
        assertFalse(spaces.isValid)
        assertEquals("Base URL cannot be blank", spaces.error)

        val invalid = PairingValidation.validateBaseUrl("://missing-scheme")
        assertFalse(invalid.isValid)
        assertNotNull(invalid.error)
    }

    @Test
    fun testTokenBlankHandling() {
        val blank = PairingValidation.validateToken("")
        assertFalse(blank.isValid)
        assertEquals("Pairing token cannot be blank", blank.error)

        val whitespace = PairingValidation.validateToken("   \t\n  ")
        assertFalse(whitespace.isValid)
        assertEquals("Pairing token cannot be blank", whitespace.error)

        val valid = PairingValidation.validateToken("lar1_test_token_value_abc123")
        assertTrue(valid.isValid)
        assertNull(valid.error)
    }

    @Test
    fun testDeepLinkPrefillParse() {
        // Valid deep link with base and token
        val link1 = "lararium://device-pair?base=https://example.com&token=lar1_secret123"
        val parsed1 = PairingValidation.parseDeepLink(link1)
        assertNotNull(parsed1)
        assertEquals("https://example.com", parsed1?.baseUrl)
        assertEquals("lar1_secret123", parsed1?.token)

        // Valid deep link with LAN HTTP
        val link2 = "lararium://device-pair?baseUrl=http://192.168.1.10:8080&token=lar1_secret456"
        val parsed2 = PairingValidation.parseDeepLink(link2)
        assertNotNull(parsed2)
        assertEquals("http://192.168.1.10:8080", parsed2?.baseUrl)
        assertEquals("lar1_secret456", parsed2?.token)

        // Rejected public cleartext HTTP
        val linkBadHttp = "lararium://device-pair?base=http://example.com&token=secret"
        val parsedBad = PairingValidation.parseDeepLink(linkBadHttp)
        assertNull("Public cleartext HTTP in deep link must be rejected", parsedBad)

        // Missing token
        val linkNoToken = "lararium://device-pair?base=https://example.com"
        assertNull(PairingValidation.parseDeepLink(linkNoToken))

        // Missing base
        val linkNoBase = "lararium://device-pair?token=secret"
        assertNull(PairingValidation.parseDeepLink(linkNoBase))

        // Non device-pair host
        val linkWrongHost = "lararium://pair?base=https://example.com&token=secret"
        assertNull(PairingValidation.parseDeepLink(linkWrongHost))

        // Null/empty link
        assertNull(PairingValidation.parseDeepLink(null))
        assertNull(PairingValidation.parseDeepLink(""))
    }

    @Test
    fun testFriendlyPairErrorMessage() {
        assertEquals(
            "This device name is taken — retry",
            PairingValidation.friendlyPairErrorMessage("duplicate_name")
        )
        assertEquals(
            "Invalid or expired pairing token",
            PairingValidation.friendlyPairErrorMessage("401 Unauthorized")
        )
        assertEquals(
            "This device is already paired elsewhere",
            PairingValidation.friendlyPairErrorMessage("already_paired")
        )
        assertEquals(
            "Hearth unreachable — check endpoint and network",
            PairingValidation.friendlyPairErrorMessage("Failed to connect to hearth: network error")
        )
    }
}
