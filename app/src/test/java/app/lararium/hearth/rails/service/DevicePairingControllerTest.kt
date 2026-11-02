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
}
