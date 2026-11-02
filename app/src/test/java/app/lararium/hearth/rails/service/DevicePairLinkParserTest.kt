package app.lararium.hearth.rails.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for DevicePairLinkParser (F6).
 *
 * Verifies:
 * - Valid links
 * - Missing token
 * - Missing base
 * - Wrong host
 * - URL encoded params
 * - Cleartext policy enforcement
 */
class DevicePairLinkParserTest {

    @Test
    fun validDeepLink_parsesBaseAndToken() {
        val link = "lararium://device-pair?base=https://example.com:7717&token=lar1_secret_token_123"
        val req = DevicePairLinkParser.parse(link)
        assertNotNull(req)
        assertEquals("https://example.com:7717", req!!.baseUrl)
        assertEquals("lar1_secret_token_123", req.token)
    }

    @Test
    fun validDeepLink_trailingSlashStripped() {
        val link = "lararium://device-pair?base=https://example.com:7717/&token=tok"
        val req = DevicePairLinkParser.parse(link)
        assertNotNull(req)
        assertEquals("https://example.com:7717", req!!.baseUrl)
    }

    @Test
    fun missingToken_returnsNull() {
        val link = "lararium://device-pair?base=https://example.com"
        assertNull(DevicePairLinkParser.parse(link))
    }

    @Test
    fun missingBase_returnsNull() {
        val link = "lararium://device-pair?token=lar1_tok"
        assertNull(DevicePairLinkParser.parse(link))
    }

    @Test
    fun wrongHost_returnsNull() {
        // host is 'pair' instead of 'device-pair'
        val link = "lararium://pair?base=https://example.com&token=tok"
        assertNull(DevicePairLinkParser.parse(link))

        // host is 'other'
        val link2 = "lararium://other?base=https://example.com&token=tok"
        assertNull(DevicePairLinkParser.parse(link2))
    }

    @Test
    fun encodedParams_properlyDecoded() {
        val link = "lararium://device-pair?base=https%3A%2F%2Fhearth.example.com%3A9000&token=tok%2B123%3D"
        val req = DevicePairLinkParser.parse(link)
        assertNotNull(req)
        assertEquals("https://hearth.example.com:9000", req!!.baseUrl)
        assertEquals("tok+123=", req.token)
    }

    @Test
    fun insecureCleartextHttpOnPublicHost_rejectedByEndpointPolicy() {
        val link = "lararium://device-pair?base=http://example.com:7717&token=tok"
        assertNull(DevicePairLinkParser.parse(link))
    }

    @Test
    fun cleartextHttpOnLocalhost_permittedByEndpointPolicy() {
        val link = "lararium://device-pair?base=http://localhost:7717&token=tok"
        val req = DevicePairLinkParser.parse(link)
        assertNotNull(req)
        assertEquals("http://localhost:7717", req!!.baseUrl)
    }

    @Test
    fun malformedOrNonLarariumScheme_returnsNull() {
        assertNull(DevicePairLinkParser.parse("https://example.com/device-pair"))
        assertNull(DevicePairLinkParser.parse("not a valid link"))
        assertNull(DevicePairLinkParser.parse(""))
    }
}
