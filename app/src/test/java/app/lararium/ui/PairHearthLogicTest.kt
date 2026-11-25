package app.lararium.ui

import app.lararium.hearth.PairingLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairHearthLogicTest {

    companion object {
        private const val VALID_DEV1 = "dev1_AbCdEf0123456789XyZw"
        private const val VALID_PAIR1 = "pair1_1234567890abcdefABCD"
        private const val SERVER_URL = "https://hearth.example.com:8443"
    }

    @Test
    fun dev1CodeInTokenField_withExplicitUrl_routesToEnroll() {
        val action = PairHearthLogic.resolve(
            pairingLink = "",
            serverUrl = SERVER_URL,
            token = VALID_DEV1,
        )
        assertEquals(PairHearthAction.Enroll(SERVER_URL, VALID_DEV1), action)
    }

    @Test
    fun dev1CodeInTokenField_withBlankUrl_fallsBackToStoredEndpoint() {
        val action = PairHearthLogic.resolve(
            pairingLink = "",
            serverUrl = "",
            token = VALID_DEV1,
            storedBaseUrl = "$SERVER_URL/",
        )
        assertEquals(PairHearthAction.Enroll(SERVER_URL, VALID_DEV1), action)
    }

    @Test
    fun dev1CodeInPairingLinkField_withExplicitUrl_routesToEnroll() {
        val action = PairHearthLogic.resolve(
            pairingLink = VALID_DEV1,
            serverUrl = "localhost:7717",
            token = "",
        )
        assertEquals(PairHearthAction.Enroll("http://localhost:7717", VALID_DEV1), action)
    }

    @Test
    fun dev1CodeInPairingLinkField_withBlankUrl_fallsBackToStoredEndpoint() {
        val action = PairHearthLogic.resolve(
            pairingLink = "  $VALID_DEV1\n",
            serverUrl = "",
            token = "",
            storedBaseUrl = SERVER_URL,
        )
        assertEquals(PairHearthAction.Enroll(SERVER_URL, VALID_DEV1), action)
    }

    @Test
    fun dev1CodePrefix_caseInsensitiveInEitherField_normalizesPrefixOnly() {
        val upperInToken = PairHearthLogic.resolve(
            pairingLink = "",
            serverUrl = SERVER_URL,
            token = "  DEV1_AbCdEf0123456789XyZw  ",
        )
        assertEquals(PairHearthAction.Enroll(SERVER_URL, VALID_DEV1), upperInToken)

        val titleInLink = PairHearthLogic.resolve(
            pairingLink = "Dev1_AbCdEf0123456789XyZw",
            serverUrl = SERVER_URL,
            token = "",
        )
        assertEquals(PairHearthAction.Enroll(SERVER_URL, VALID_DEV1), titleInLink)
    }

    @Test
    fun dev1Code_withoutServerUrlOrStoredEndpoint_requiresServerUrlNotInvalidLink() {
        val inToken = PairHearthLogic.resolve(
            pairingLink = "",
            serverUrl = "",
            token = VALID_DEV1,
            storedBaseUrl = null,
        )
        assertEquals(PairHearthAction.Invalid(PairHearthInputError.SERVER_URL_REQUIRED), inToken)

        val inLink = PairHearthLogic.resolve(
            pairingLink = VALID_DEV1,
            serverUrl = "",
            token = "",
            storedBaseUrl = null,
        )
        assertEquals(PairHearthAction.Invalid(PairHearthInputError.SERVER_URL_REQUIRED), inLink)
    }

    @Test
    fun malformedDev1Prefix_reportsInvalidCodeNotInvalidLink() {
        val inToken = PairHearthLogic.resolve(
            pairingLink = "",
            serverUrl = SERVER_URL,
            token = "dev1_tooshort",
        )
        assertEquals(PairHearthAction.Invalid(PairHearthInputError.INVALID_CODE), inToken)

        val inLink = PairHearthLogic.resolve(
            pairingLink = "DEV1_tooshort",
            serverUrl = SERVER_URL,
            token = "",
        )
        assertEquals(PairHearthAction.Invalid(PairHearthInputError.INVALID_CODE), inLink)
    }

    @Test
    fun pair1Code_worksInEitherField() {
        val inLink = PairHearthLogic.resolve(
            pairingLink = VALID_PAIR1,
            serverUrl = SERVER_URL,
            token = "",
        )
        assertEquals(PairHearthAction.RedeemPairCode(SERVER_URL, VALID_PAIR1), inLink)

        val inToken = PairHearthLogic.resolve(
            pairingLink = "",
            serverUrl = SERVER_URL,
            token = VALID_PAIR1,
        )
        assertEquals(PairHearthAction.RedeemPairCode(SERVER_URL, VALID_PAIR1), inToken)
    }

    @Test
    fun pairingLink_routesToDirectEndpoint() {
        val action = PairHearthLogic.resolve(
            pairingLink = "lararium://pair?host=https%3A%2F%2Fhearth.example.com%3A8443&token=lar1_abc123",
            serverUrl = "",
            token = "",
        )
        assertTrue(action is PairHearthAction.DirectEndpoint)
        val ep = (action as PairHearthAction.DirectEndpoint).endpoint
        assertEquals("https://hearth.example.com:8443", ep.baseUrl)
        assertEquals("lar1_abc123", ep.token)
    }

    @Test
    fun legacyUrlAndLar1Token_routesToDirectEndpoint() {
        val action = PairHearthLogic.resolve(
            pairingLink = "",
            serverUrl = "hearth.example.com:8443",
            token = "lar1_legacytoken",
        )
        assertTrue(action is PairHearthAction.DirectEndpoint)
        val ep = (action as PairHearthAction.DirectEndpoint).endpoint
        assertEquals("https://hearth.example.com:8443", ep.baseUrl)
        assertEquals("lar1_legacytoken", ep.token)
    }

    @Test
    fun garbageToken_keepsLegacyInvalidErrorUnchanged() {
        val action = PairHearthLogic.resolve(
            pairingLink = "",
            serverUrl = SERVER_URL,
            token = "not_a_valid_token",
        )
        assertEquals(PairHearthAction.Invalid(PairHearthInputError.INVALID_INPUT), action)
    }

    @Test
    fun nonDev1UrlInPairingLink_doesNotRouteToEnroll() {
        val action = PairHearthLogic.resolve(
            pairingLink = "https://hearth.lararium.io/p#abc",
            serverUrl = "",
            token = "",
        )
        assertEquals(PairHearthAction.Invalid(PairHearthInputError.INVALID_INPUT), action)
    }

    @Test
    fun canSubmit_enablesForCodesInTokenFieldAndDisablesWhenEmpty() {
        assertFalse(PairHearthLogic.canSubmit("", "", ""))
        assertFalse(PairHearthLogic.canSubmit("", SERVER_URL, ""))
        assertFalse(PairHearthLogic.canSubmit("", "", "garbage"))
        assertTrue(PairHearthLogic.canSubmit(VALID_DEV1, "", ""))
        assertTrue(PairHearthLogic.canSubmit("", SERVER_URL, "garbage"))
        assertTrue(PairHearthLogic.canSubmit("", "", VALID_DEV1))
        assertTrue(PairHearthLogic.canSubmit("", "", "DEV1_AbCdEf0123456789XyZw"))
        assertTrue(PairHearthLogic.canSubmit("", "", VALID_PAIR1))
    }

    @Test
    fun normalizeServerUrl_lowercasesAutoCapitalizedSchemeAndHost() {
        assertEquals(
            "https://hearth.example.com:8443",
            PairHearthLogic.normalizeServerUrl("Https://hearth.example.com:8443/"),
        )
        assertEquals("http://localhost:7717", PairHearthLogic.normalizeServerUrl("Localhost:7717"))
        assertEquals("http://127.0.0.1:8443", PairHearthLogic.normalizeServerUrl("HTTP://127.0.0.1:8443"))
    }

    @Test
    fun explicitServerUrl_overridesStoredBaseUrl() {
        val action = PairHearthLogic.resolve(
            pairingLink = "",
            serverUrl = "https://other.example.com:9000",
            token = VALID_DEV1,
            storedBaseUrl = SERVER_URL,
        )
        assertEquals(PairHearthAction.Enroll("https://other.example.com:9000", VALID_DEV1), action)
    }

    @Test
    fun pairingLinkField_takesPrecedenceOverTokenField() {
        val action = PairHearthLogic.resolve(
            pairingLink = VALID_DEV1,
            serverUrl = SERVER_URL,
            token = "lar1_legacytoken",
        )
        assertEquals(PairHearthAction.Enroll(SERVER_URL, VALID_DEV1), action)
    }

    @Test
    fun pair1Code_blankUrl_fallsBackToStored_andMissingUrlIsServerUrlRequired() {
        val fallback = PairHearthLogic.resolve(
            pairingLink = "",
            serverUrl = "",
            token = VALID_PAIR1,
            storedBaseUrl = SERVER_URL,
        )
        assertEquals(PairHearthAction.RedeemPairCode(SERVER_URL, VALID_PAIR1), fallback)
        val missing = PairHearthLogic.resolve(
            pairingLink = "",
            serverUrl = "",
            token = VALID_PAIR1,
            storedBaseUrl = null,
        )
        assertEquals(PairHearthAction.Invalid(PairHearthInputError.SERVER_URL_REQUIRED), missing)
    }

    @Test
    fun pair1Code_uppercasePrefix_normalizesPrefixOnly() {
        val action = PairHearthLogic.resolve(
            pairingLink = "PAIR1_1234567890abcdefABCD",
            serverUrl = SERVER_URL,
            token = "",
        )
        assertEquals(PairHearthAction.RedeemPairCode(SERVER_URL, VALID_PAIR1), action)
    }

    @Test
    fun pairingLink_isDev1Code_caseInsensitivePrefixAndNormalize() {
        assertTrue(PairingLink.isDev1Code("DEV1_AbCdEf0123456789XyZw"))
        assertTrue(PairingLink.isDev1Code("Dev1_AbCdEf0123456789XyZw"))
        assertEquals(
            "dev1_AbCdEf0123456789XyZw",
            PairingLink.normalizeDev1Code("  DEV1_AbCdEf0123456789XyZw  "),
        )
    }
}
