package app.lararium.hearth.rails.service

import android.content.SharedPreferences
import app.lararium.hearth.HttpResponse
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for DevicePairingController (F6).
 *
 * Verifies:
 * - End-to-end pairing & registration handshake
 * - Endpoint validation rejects invalid scheme
 * - 409 duplicate_name surfaces retryable error
 * - Device slug sanitation conforms to ^[a-z0-9][a-z0-9_-]{0,31}$
 */
class DevicePairingControllerTest {

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

    @Test
    fun pair_successfulIntake() = runBlocking {
        val plain = FakeSharedPreferences()
        val secure = FakeSharedPreferences()
        val fakeStore = DevicePairingStore(plain, secure)

        val postedUrls = mutableListOf<String>()
        val pairBodies = mutableMapOf<String, String>()
        val registerAuth = mutableListOf<String>()
        var serviceStarted = false

        val result = DevicePairingController.pair(
            context = mockk(relaxed = true),
            baseUrl = "https://example.com:7717",
            token = "pair1_testcode",
            httpPoster = { url, body, headers ->
                postedUrls.add(url)
                pairBodies[url] = body
                if (url.endsWith("/v1/pair")) {
                    HttpResponse(200, "{\"token\":\"lar1_minted\",\"user_id\":\"app:pixel\"}")
                } else {
                    registerAuth.add(headers["Authorization"] ?: "")
                    HttpResponse(202, "{\"device_id\":\"pixel\",\"card_id\":\"c1\"}")
                }
            },
            storeProvider = { fakeStore },
            serviceStarter = { serviceStarted = true }
        )

        assertTrue(result.isSuccess)
        assertNotNull(result.endpoint)
        assertEquals("https://example.com:7717", result.endpoint!!.baseUrl)
        // Persisted credential is the MINTED token, never the spent code.
        assertEquals("lar1_minted", result.endpoint!!.token)
        assertTrue(serviceStarted)

        assertEquals(2, postedUrls.size)
        assertEquals("https://example.com:7717/v1/pair", postedUrls[0])
        assertEquals("https://example.com:7717/v1/devices/register", postedUrls[1])
        // Pair redeems the code in the BODY, not a bearer header.
        assertEquals("pair1_testcode", JSONObject(pairBodies[postedUrls[0]]).getString("code"))
        // Register rides on the minted token.
        assertEquals(listOf("Bearer lar1_minted"), registerAuth)

        assertTrue(fakeStore.isPaired())
        assertEquals("lar1_minted", fakeStore.getEndpoint()?.token)
    }

    @Test
    fun pair_insecureUrl_rejectedImmediately() = runBlocking {
        val result = DevicePairingController.pair(
            context = mockk(relaxed = true),
            baseUrl = "http://example.com:7717",
            token = "lar1_test"
        )

        assertFalse(result.isSuccess)
        assertTrue(result.error?.contains("cleartext") == true)
    }

    @Test
    fun pair_duplicateName409_returnsRetryableFailure() = runBlocking {
        val plain = FakeSharedPreferences()
        val secure = FakeSharedPreferences()
        val fakeStore = DevicePairingStore(plain, secure)

        val result = DevicePairingController.pair(
            context = mockk(relaxed = true),
            baseUrl = "https://example.com:7717",
            token = "lar1_test",
            httpPoster = { url, _, _ ->
                if (url.endsWith("/pair")) {
                    HttpResponse(200, "{\"token\":\"lar1_minted\",\"user_id\":\"app:pixel\"}")
                } else {
                    HttpResponse(409, "{\"error\":\"duplicate_name\"}")
                }
            },
            storeProvider = { fakeStore }
        )

        assertFalse(result.isSuccess)
        assertTrue(result.isRetryable)
        assertEquals("duplicate_name", result.error)
    }

    @Test
    fun sanitizeDeviceId_conformsToRegex() {
        val regex = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

        val s1 = DevicePairingController.sanitizeDeviceId("Pixel 8 Pro!")
        assertTrue("Slug '$s1' must match regex", s1.matches(regex))

        val s2 = DevicePairingController.sanitizeDeviceId("___---Special---___")
        assertTrue("Slug '$s2' must match regex", s2.matches(regex))

        val s3 = DevicePairingController.sanitizeDeviceId("A".repeat(100))
        assertTrue("Slug '$s3' must be <= 32 chars", s3.length <= 32)
        assertTrue("Slug '$s3' must match regex", s3.matches(regex))
    }

    @Test
    fun autoRegister_happyPath_registersWithBearerAndRails() = runBlocking {
        val plain = FakeSharedPreferences()
        val secure = FakeSharedPreferences()
        val fakeStore = DevicePairingStore(plain, secure)

        val postedUrls = mutableListOf<String>()
        val registerAuth = mutableListOf<String>()
        var registerBodyString: String? = null
        var serviceStarted = false

        val result = DevicePairingController.autoRegister(
            context = mockk(relaxed = true),
            httpPoster = { url, body, headers ->
                postedUrls.add(url)
                if (url.endsWith("/v1/devices/register")) {
                    registerAuth.add(headers["Authorization"] ?: "")
                    registerBodyString = body
                    HttpResponse(200, "{\"device_id\":\"pixel\",\"status\":\"registered\"}")
                } else {
                    HttpResponse(404, "not found")
                }
            },
            storeProvider = { fakeStore },
            hearthEndpointProvider = { "https://example.com:7717" to "stored_bearer_token" },
            serviceStarter = { serviceStarted = true }
        )

        assertTrue(result.isSuccess)
        assertNotNull(result.endpoint)
        assertEquals("https://example.com:7717", result.endpoint!!.baseUrl)
        assertEquals("stored_bearer_token", result.endpoint!!.token)
        assertTrue(serviceStarted)

        assertEquals(1, postedUrls.size)
        assertEquals("https://example.com:7717/v1/devices/register", postedUrls[0])
        assertEquals(listOf("Bearer stored_bearer_token"), registerAuth)

        assertNotNull(registerBodyString)
        val bodyJson = JSONObject(registerBodyString!!)
        assertTrue(bodyJson.has("device_id"))
        assertTrue(bodyJson.has("display_name"))
        assertTrue(bodyJson.has("rails_declared"))
        val railsArray = bodyJson.getJSONArray("rails_declared")
        assertTrue(railsArray.length() > 0)

        assertTrue(fakeStore.isPaired())
        assertEquals("stored_bearer_token", fakeStore.getEndpoint()?.token)
    }

    @Test
    fun autoRegister_idempotentOn409() = runBlocking {
        val plain = FakeSharedPreferences()
        val secure = FakeSharedPreferences()
        val fakeStore = DevicePairingStore(plain, secure)

        var serviceStarted = false

        val result = DevicePairingController.autoRegister(
            context = mockk(relaxed = true),
            httpPoster = { url, _, _ ->
                if (url.endsWith("/v1/devices/register")) {
                    HttpResponse(409, "{\"error\":\"duplicate_name\"}")
                } else {
                    HttpResponse(500, "error")
                }
            },
            storeProvider = { fakeStore },
            hearthEndpointProvider = { "https://example.com:7717" to "stored_token_409" },
            serviceStarter = { serviceStarted = true }
        )

        // 409 duplicate-name is idempotent success
        assertTrue(result.isSuccess)
        assertNotNull(result.endpoint)
        assertEquals("stored_token_409", result.endpoint!!.token)
        assertTrue(serviceStarted)
        assertTrue(fakeStore.isPaired())
        assertEquals("stored_token_409", fakeStore.getEndpoint()?.token)
    }

    @Test
    fun pair_whenTokenExists_skipsRedemptionPost() = runBlocking {
        val plain = FakeSharedPreferences()
        val secure = FakeSharedPreferences()
        val fakeStore = DevicePairingStore(plain, secure)

        val postedUrls = mutableListOf<String>()
        val registerAuth = mutableListOf<String>()
        var serviceStarted = false

        val result = DevicePairingController.pair(
            context = mockk(relaxed = true),
            baseUrl = "https://example.com:7717",
            token = "", // No QR / code needed
            httpPoster = { url, _, headers ->
                postedUrls.add(url)
                if (url.endsWith("/v1/pair")) {
                    throw AssertionError("POST /v1/pair must NOT be called when token exists")
                } else {
                    registerAuth.add(headers["Authorization"] ?: "")
                    HttpResponse(200, "{\"device_id\":\"pixel\",\"status\":\"ok\"}")
                }
            },
            storeProvider = { fakeStore },
            hearthEndpointProvider = { "https://example.com:7717" to "existing_hearth_token" },
            serviceStarter = { serviceStarted = true }
        )

        assertTrue(result.isSuccess)
        assertEquals(1, postedUrls.size)
        assertEquals("https://example.com:7717/v1/devices/register", postedUrls[0])
        assertEquals(listOf("Bearer existing_hearth_token"), registerAuth)
        assertTrue(fakeStore.isPaired())
        assertEquals("existing_hearth_token", fakeStore.getEndpoint()?.token)
        assertTrue(serviceStarted)
    }

    @Test
    fun pair_whenNoTokenExists_manualControllerRedeemsCode() = runBlocking {
        val plain = FakeSharedPreferences()
        val secure = FakeSharedPreferences()
        val fakeStore = DevicePairingStore(plain, secure)

        val postedUrls = mutableListOf<String>()
        val pairBodies = mutableMapOf<String, String>()
        val registerAuth = mutableListOf<String>()

        val result = DevicePairingController.pair(
            context = mockk(relaxed = true),
            baseUrl = "https://example.com:7717",
            token = "pair1_manual_code",
            httpPoster = { url, body, headers ->
                postedUrls.add(url)
                if (url.endsWith("/v1/pair")) {
                    pairBodies[url] = body
                    HttpResponse(200, "{\"token\":\"lar1_minted_from_code\",\"user_id\":\"u1\"}")
                } else {
                    registerAuth.add(headers["Authorization"] ?: "")
                    HttpResponse(200, "{\"device_id\":\"pixel\"}")
                }
            },
            storeProvider = { fakeStore },
            hearthEndpointProvider = { null } // No existing hearth token
        )

        assertTrue(result.isSuccess)
        assertEquals(2, postedUrls.size)
        assertEquals("https://example.com:7717/v1/pair", postedUrls[0])
        assertEquals("https://example.com:7717/v1/devices/register", postedUrls[1])
        assertEquals("pair1_manual_code", JSONObject(pairBodies[postedUrls[0]]).getString("code"))
        assertEquals(listOf("Bearer lar1_minted_from_code"), registerAuth)
        assertTrue(fakeStore.isPaired())
        assertEquals("lar1_minted_from_code", fakeStore.getEndpoint()?.token)
    }
}
