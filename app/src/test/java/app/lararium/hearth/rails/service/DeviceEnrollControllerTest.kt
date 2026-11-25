package app.lararium.hearth.rails.service

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.lararium.BuildConfig
import app.lararium.hearth.HearthChatClient
import app.lararium.hearth.HttpResponse
import app.lararium.hearth.PairingLink
import app.lararium.hearth.rails.ui.PairingValidation
import app.lararium.security.SecureKeyManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Unit tests for per-device enrollment (DEVICE-RAILS-SPEC v4 D-ENROLL client lane).
 */
class DeviceEnrollControllerTest {

    companion object {
        private const val TEST_BASE_URL = "https://example.com:7717"
        private const val VALID_DEV1_CODE = "dev1_AbCdEf0123456789XyZw"
        private const val FAKE_MODEL = "FixturePhone X"
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor { changes[key ?: ""] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { changes[key ?: ""] = values; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { changes[key ?: ""] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { changes[key ?: ""] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { changes[key ?: ""] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { changes[key ?: ""] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { changes[key ?: ""] = null; return this }
            override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) data.clear()
                for ((k, v) in changes) { if (v == null) data.remove(k) else data[k] = v }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeContext : ContextWrapper(null) {
        val prefsByName = mutableMapOf<String, FakeSharedPreferences>()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            prefsByName.getOrPut(name) { FakeSharedPreferences() }
    }

    private fun generateTestAesKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    private fun newKeyManager(context: Context): SecureKeyManager {
        val secretKey = generateTestAesKey()
        return SecureKeyManager(context) { secretKey }
    }

    private fun newFakeStore(): DevicePairingStore =
        DevicePairingStore(FakeSharedPreferences(), FakeSharedPreferences())

    // 1. enroll request: POST to /v1/pair, closed 4-field JSON body, no Authorization header.
    @Test
    fun enroll_requestBodyAndHeaders_exactClosedFieldSetAndNoAuth() = runBlocking {
        val context = FakeContext()
        val keyManager = newKeyManager(context)
        val fakeStore = newFakeStore()

        val capturedUrls = mutableListOf<String>()
        var pairBodyRaw: String? = null
        var pairHeaders: Map<String, String>? = null

        val result = DevicePairingController.enroll(
            context = context,
            baseUrl = TEST_BASE_URL,
            code = VALID_DEV1_CODE,
            httpPoster = { url, body, headers ->
                capturedUrls.add(url)
                if (url.endsWith("/v1/pair")) {
                    pairBodyRaw = body
                    pairHeaders = headers
                    HttpResponse(200, """{"token":"lar1_fixture_req_tok","device_id":"fixture-model-x-1f3a"}""")
                } else {
                    HttpResponse(200, """{"status":"ok"}""")
                }
            },
            storeProvider = { fakeStore },
            serviceStarter = {},
            keyManagerProvider = { keyManager },
            deviceModelProvider = { FAKE_MODEL }
        )

        assertTrue("Expected enroll success, got ${result.error}", result.isSuccess)
        assertEquals(2, capturedUrls.size)
        assertEquals("$TEST_BASE_URL/v1/pair", capturedUrls[0])
        assertEquals("$TEST_BASE_URL/v1/devices/register", capturedUrls[1])

        assertNotNull(pairBodyRaw)
        val json = JSONObject(pairBodyRaw!!)
        val keys = json.keys().asSequence().toSet()
        assertEquals(setOf("code", "device_name", "platform", "app_version"), keys)
        assertEquals(VALID_DEV1_CODE, json.getString("code"))
        assertEquals(FAKE_MODEL, json.getString("device_name"))
        assertEquals("android", json.getString("platform"))
        assertEquals(BuildConfig.VERSION_NAME.take(32), json.getString("app_version"))

        assertNotNull(pairHeaders)
        assertTrue(
            "POST /v1/pair must not include Authorization header: $pairHeaders",
            pairHeaders!!.keys.none { it.equals("Authorization", ignoreCase = true) }
        )
    }

    // 2. success path adopts daemon device_id even when local slug differs.
    @Test
    fun enroll_success_adoptsDaemonDeviceIdOverLocalSlug() = runBlocking {
        val context = FakeContext()
        val keyManager = newKeyManager(context)
        val fakeStore = newFakeStore()

        val daemonDeviceId = "fixture-model-x-1f3a"
        val mintedToken = "lar1_fixture_bound_token_001"
        val localSlug = DevicePairingController.sanitizeDeviceId(FAKE_MODEL)
        assertFalse("Fixture must ensure local slug differs from daemon id", localSlug == daemonDeviceId)

        var capturedRegisterBody: String? = null
        var capturedRegisterAuth: String? = null
        var serviceStarted = false

        val result = DevicePairingController.enroll(
            context = context,
            baseUrl = TEST_BASE_URL,
            code = VALID_DEV1_CODE,
            httpPoster = { url, body, headers ->
                if (url.endsWith("/v1/pair")) {
                    HttpResponse(200, """{"token":"$mintedToken","device_id":"$daemonDeviceId"}""")
                } else {
                    capturedRegisterBody = body
                    capturedRegisterAuth = headers["Authorization"]
                    HttpResponse(200, """{"device_id":"$daemonDeviceId","status":"registered"}""")
                }
            },
            storeProvider = { fakeStore },
            serviceStarter = { serviceStarted = true },
            keyManagerProvider = { keyManager },
            deviceModelProvider = { FAKE_MODEL }
        )

        assertTrue(result.isSuccess)
        assertTrue(serviceStarted)
        val expectedEndpoint = DeviceEndpoint(TEST_BASE_URL, mintedToken, daemonDeviceId)
        assertEquals(expectedEndpoint, result.endpoint)
        assertEquals(expectedEndpoint, fakeStore.getEndpoint())

        assertEquals("Bearer $mintedToken", capturedRegisterAuth)
        assertNotNull(capturedRegisterBody)
        val regJson = JSONObject(capturedRegisterBody!!)
        assertEquals(daemonDeviceId, regJson.getString("device_id"))
        assertEquals(FAKE_MODEL, regJson.getString("display_name"))
        assertTrue(regJson.getJSONArray("rails_declared").length() > 0)
    }

    // 3. pre-stored hearth endpoint replaced (new token), existing name preserved, KEY_SESSION_ID removed.
    @Test
    fun enroll_withPreStoredHearthEndpoint_replacesTokenPreservesNameAndClearsSessionId() = runBlocking {
        val context = FakeContext()
        val keyManager = newKeyManager(context)
        keyManager.setHearthEndpoint(TEST_BASE_URL, "lar1_old_shared_token", "Fixture Hearth")

        val assistantPrefs = context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)
        assistantPrefs.edit().putString(HearthChatClient.KEY_SESSION_ID, "sess_stale_fixture").apply()
        assertTrue(assistantPrefs.contains(HearthChatClient.KEY_SESSION_ID))

        val fakeStore = newFakeStore()
        val mintedToken = "lar1_fixture_rotated_token"

        val result = DevicePairingController.enroll(
            context = context,
            baseUrl = "", // exercises stored hearth baseUrl fallback too
            code = VALID_DEV1_CODE,
            httpPoster = { url, _, _ ->
                if (url.endsWith("/v1/pair")) {
                    HttpResponse(200, """{"token":"$mintedToken","device_id":"fixture-model-x-9c01"}""")
                } else {
                    HttpResponse(200, """{"status":"ok"}""")
                }
            },
            storeProvider = { fakeStore },
            serviceStarter = {},
            keyManagerProvider = { keyManager },
            deviceModelProvider = { FAKE_MODEL }
        )

        assertTrue(result.isSuccess)
        assertEquals(
            Triple(TEST_BASE_URL, mintedToken, "Fixture Hearth"),
            keyManager.getHearthEndpoint()
        )
        assertFalse(
            "Stale chat session ID must be cleared on enrollment",
            assistantPrefs.contains(HearthChatClient.KEY_SESSION_ID)
        )
    }

    // 4. NO pre-stored hearth endpoint -> created using URI-host name.
    @Test
    fun enroll_withNoPreStoredHearthEndpoint_createsWithUriHostName() = runBlocking {
        val context = FakeContext()
        val keyManager = newKeyManager(context)
        assertFalse(keyManager.hasHearthEndpoint())

        val fakeStore = newFakeStore()
        val mintedToken = "lar1_fixture_fresh_token"

        val result = DevicePairingController.enroll(
            context = context,
            baseUrl = TEST_BASE_URL,
            code = VALID_DEV1_CODE,
            httpPoster = { url, _, _ ->
                if (url.endsWith("/v1/pair")) {
                    HttpResponse(200, """{"token":"$mintedToken","device_id":"fixture-model-x-22aa"}""")
                } else {
                    HttpResponse(200, """{"status":"ok"}""")
                }
            },
            storeProvider = { fakeStore },
            serviceStarter = {},
            keyManagerProvider = { keyManager },
            deviceModelProvider = { FAKE_MODEL }
        )

        assertTrue(result.isSuccess)
        assertEquals(
            Triple(TEST_BASE_URL, mintedToken, "example.com"),
            keyManager.getHearthEndpoint()
        )
    }

    // 5a. 401 -> "bad token" retryable; nothing persisted.
    @Test
    fun enroll_401_returnsRetryableBadTokenAndPersistsNothing() = runBlocking {
        val context = FakeContext()
        val keyManager = newKeyManager(context)
        keyManager.setHearthEndpoint(TEST_BASE_URL, "lar1_unchanged", "Fixture Hearth")
        val assistantPrefs = context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)
        assistantPrefs.edit().putString(HearthChatClient.KEY_SESSION_ID, "sess_keep").apply()

        val fakeStore = newFakeStore()
        var serviceStarted = false
        val urls = mutableListOf<String>()

        val result = DevicePairingController.enroll(
            context = context,
            baseUrl = TEST_BASE_URL,
            code = VALID_DEV1_CODE,
            httpPoster = { url, _, _ ->
                urls.add(url)
                HttpResponse(401, """{"error":"invalid or expired pairing code"}""")
            },
            storeProvider = { fakeStore },
            serviceStarter = { serviceStarted = true },
            keyManagerProvider = { keyManager },
            deviceModelProvider = { FAKE_MODEL }
        )

        assertFalse(result.isSuccess)
        assertTrue(result.isRetryable)
        assertEquals("bad token", result.error)
        assertEquals(listOf("$TEST_BASE_URL/v1/pair"), urls)
        assertFalse(fakeStore.isPaired())
        assertEquals(Triple(TEST_BASE_URL, "lar1_unchanged", "Fixture Hearth"), keyManager.getHearthEndpoint())
        assertEquals("sess_keep", assistantPrefs.getString(HearthChatClient.KEY_SESSION_ID, null))
        assertFalse(serviceStarted)
    }

    // 5b. 429 -> "rate_limited" retryable; nothing persisted.
    @Test
    fun enroll_429_returnsRetryableRateLimitedAndPersistsNothing() = runBlocking {
        val context = FakeContext()
        val keyManager = newKeyManager(context)
        val fakeStore = newFakeStore()
        var serviceStarted = false

        val result = DevicePairingController.enroll(
            context = context,
            baseUrl = TEST_BASE_URL,
            code = VALID_DEV1_CODE,
            httpPoster = { _, _, _ -> HttpResponse(429, """{"error":"rate_limited"}""") },
            storeProvider = { fakeStore },
            serviceStarter = { serviceStarted = true },
            keyManagerProvider = { keyManager },
            deviceModelProvider = { FAKE_MODEL }
        )

        assertFalse(result.isSuccess)
        assertTrue(result.isRetryable)
        assertEquals("rate_limited", result.error)
        assertFalse(fakeStore.isPaired())
        assertFalse(keyManager.hasHearthEndpoint())
        assertFalse(serviceStarted)
    }

    // 5c. 200 missing device_id -> "enroll response incomplete"; nothing persisted.
    @Test
    fun enroll_200MissingDeviceId_returnsIncompleteAndPersistsNothing() = runBlocking {
        val context = FakeContext()
        val keyManager = newKeyManager(context)
        val fakeStore = newFakeStore()
        var serviceStarted = false
        val urls = mutableListOf<String>()

        val result = DevicePairingController.enroll(
            context = context,
            baseUrl = TEST_BASE_URL,
            code = VALID_DEV1_CODE,
            httpPoster = { url, _, _ ->
                urls.add(url)
                HttpResponse(200, """{"token":"lar1_fixture_missing_id"}""")
            },
            storeProvider = { fakeStore },
            serviceStarter = { serviceStarted = true },
            keyManagerProvider = { keyManager },
            deviceModelProvider = { FAKE_MODEL }
        )

        assertFalse(result.isSuccess)
        assertEquals("enroll response incomplete", result.error)
        assertEquals(listOf("$TEST_BASE_URL/v1/pair"), urls)
        assertFalse(fakeStore.isPaired())
        assertFalse(keyManager.hasHearthEndpoint())
        assertFalse(serviceStarted)
    }

    // 5d. register 403 -> failure mentions "code already consumed"; nothing persisted.
    @Test
    fun enroll_register403_mentionsCodeAlreadyConsumedAndPersistsNothing() = runBlocking {
        val context = FakeContext()
        val keyManager = newKeyManager(context)
        keyManager.setHearthEndpoint(TEST_BASE_URL, "lar1_prior_token", "Fixture Hearth")
        val assistantPrefs = context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)
        assistantPrefs.edit().putString(HearthChatClient.KEY_SESSION_ID, "sess_still_valid").apply()

        val fakeStore = newFakeStore()
        var serviceStarted = false

        val result = DevicePairingController.enroll(
            context = context,
            baseUrl = TEST_BASE_URL,
            code = VALID_DEV1_CODE,
            httpPoster = { url, _, _ ->
                if (url.endsWith("/v1/pair")) {
                    HttpResponse(200, """{"token":"lar1_fixture_bound_403","device_id":"fixture-model-x-1f3a"}""")
                } else {
                    HttpResponse(403, """{"error":"device_id mismatch"}""")
                }
            },
            storeProvider = { fakeStore },
            serviceStarter = { serviceStarted = true },
            keyManagerProvider = { keyManager },
            deviceModelProvider = { FAKE_MODEL }
        )

        assertFalse(result.isSuccess)
        assertTrue(
            "Expected failure mentioning 'code already consumed', got: ${result.error}",
            result.error?.contains("code already consumed") == true
        )
        assertFalse(fakeStore.isPaired())
        assertEquals(Triple(TEST_BASE_URL, "lar1_prior_token", "Fixture Hearth"), keyManager.getHearthEndpoint())
        assertEquals("sess_still_valid", assistantPrefs.getString(HearthChatClient.KEY_SESSION_ID, null))
        assertFalse(serviceStarted)
    }

    // 6. pair() with a valid dev1_ code and a PRE-STORED hearth endpoint still calls /v1/pair (no token reuse shortcut).
    @Test
    fun pair_withDev1CodeAndPreStoredHearthEndpoint_delegatesToEnroll() = runBlocking {
        val context = FakeContext()
        val fakeStore = newFakeStore()

        val postedUrls = mutableListOf<String>()
        var pairBody: String? = null
        var registerBody: String? = null
        var registerAuth: String? = null
        var serviceStarted = false

        val result = DevicePairingController.pair(
            context = context,
            baseUrl = TEST_BASE_URL,
            token = "  $VALID_DEV1_CODE  ",
            httpPoster = { url, body, headers ->
                postedUrls.add(url)
                if (url.endsWith("/v1/pair")) {
                    pairBody = body
                    HttpResponse(200, """{"token":"lar1_fixture_from_pair","device_id":"fixture-model-x-42ab"}""")
                } else {
                    registerBody = body
                    registerAuth = headers["Authorization"]
                    HttpResponse(200, """{"status":"ok"}""")
                }
            },
            storeProvider = { fakeStore },
            hearthEndpointProvider = { TEST_BASE_URL to "lar1_existing_shared_token" },
            serviceStarter = { serviceStarted = true }
        )

        assertTrue("Expected pair() with dev1_ code to succeed, got: ${result.error}", result.isSuccess)
        assertEquals(
            listOf("$TEST_BASE_URL/v1/pair", "$TEST_BASE_URL/v1/devices/register"),
            postedUrls
        )
        assertNotNull(pairBody)
        val pairJson = JSONObject(pairBody!!)
        assertEquals(VALID_DEV1_CODE, pairJson.getString("code"))
        assertEquals("android", pairJson.getString("platform"))

        assertEquals("Bearer lar1_fixture_from_pair", registerAuth)
        assertNotNull(registerBody)
        assertEquals("fixture-model-x-42ab", JSONObject(registerBody!!).getString("device_id"))

        assertEquals(
            DeviceEndpoint(TEST_BASE_URL, "lar1_fixture_from_pair", "fixture-model-x-42ab"),
            fakeStore.getEndpoint()
        )
        assertTrue(serviceStarted)
    }

    // 7. autoRegister prefers stored deviceId over Build.MODEL-derived slug.
    @Test
    fun autoRegister_prefersStoredDeviceIdOverModelSlug() = runBlocking {
        val context = FakeContext()
        val fakeStore = newFakeStore()
        val daemonAssignedId = "fixture-model-x-1f3a"
        fakeStore.saveEndpoint(DeviceEndpoint(TEST_BASE_URL, "lar1_fixture_bound", daemonAssignedId))

        var capturedRegisterBody: String? = null
        var serviceStarted = false

        val result = DevicePairingController.autoRegister(
            context = context,
            httpPoster = { url, body, _ ->
                if (url.endsWith("/v1/devices/register")) {
                    capturedRegisterBody = body
                    HttpResponse(200, """{"device_id":"$daemonAssignedId","status":"ok"}""")
                } else {
                    HttpResponse(404, "not found")
                }
            },
            storeProvider = { fakeStore },
            hearthEndpointProvider = { TEST_BASE_URL to "lar1_fixture_bound" },
            serviceStarter = { serviceStarted = true },
            deviceModelProvider = { FAKE_MODEL }
        )

        assertTrue(result.isSuccess)
        assertTrue(serviceStarted)
        assertNotNull(capturedRegisterBody)
        val bodyJson = JSONObject(capturedRegisterBody!!)
        assertEquals(daemonAssignedId, bodyJson.getString("device_id"))
        assertEquals(FAKE_MODEL, bodyJson.getString("display_name"))
        assertEquals(daemonAssignedId, result.endpoint?.deviceId)
        assertEquals(daemonAssignedId, fakeStore.getEndpoint()?.deviceId)
    }

    // 8. PairingLink.isDev1Code accept/reject table.
    @Test
    fun pairingLink_isDev1Code_acceptRejectTable() {
        val valid = listOf(
            "dev1_AbCdEf0123456789XyZw",
            "dev1_00000000000000000000",
            "  \tdev1_AbCdEf0123456789XyZw\n "
        )
        for (code in valid) {
            assertTrue("Expected isDev1Code to accept '$code'", PairingLink.isDev1Code(code))
        }

        val invalid = listOf(
            "",
            "   ",
            "dev2_AbCdEf0123456789XyZw",    // wrong prefix
            "dev1_AbCdEf0123456789XyZ",     // 19 payload chars
            "dev1_AbCdEf0123456789XyZw1",   // 21 payload chars
            "pair1_AbCdEf0123456789XyZw",   // pair1_ rejected
            "lar1_AbCdEf0123456789XyZw",    // lar1_ rejected
            "dev1_AbCdEf0123456789Xy_w",    // underscore in payload
            "dev1_AbCdEf0123456789Xy-w"     // hyphen in payload
        )
        for (code in invalid) {
            assertFalse("Expected isDev1Code to reject '$code'", PairingLink.isDev1Code(code))
        }
    }

    // 9. friendlyPairErrorMessage new mappings + legacy mappings intact.
    @Test
    fun friendlyPairErrorMessage_newAndLegacyMappings() {
        // New mappings
        assertEquals(
            "Too many pairing attempts — wait a minute and retry",
            PairingValidation.friendlyPairErrorMessage("rate_limited")
        )
        assertEquals(
            "Hearth returned an unexpected enrollment response",
            PairingValidation.friendlyPairErrorMessage("enroll response incomplete")
        )
        assertEquals(
            "Enrollment code was used — ask your hearth operator for a new one",
            PairingValidation.friendlyPairErrorMessage(
                "Registration rejected with status 403 (code already consumed): forbidden"
            )
        )

        // Legacy mappings intact
        assertEquals(
            "This device name is taken — retry",
            PairingValidation.friendlyPairErrorMessage("duplicate_name")
        )
        assertEquals(
            "Invalid or expired pairing token",
            PairingValidation.friendlyPairErrorMessage("bad token")
        )
        assertEquals(
            "This device is already paired elsewhere",
            PairingValidation.friendlyPairErrorMessage("already_paired")
        )
        assertEquals(
            "Hearth unreachable — check endpoint and network",
            PairingValidation.friendlyPairErrorMessage("Failed to connect: network error")
        )
    }
}
