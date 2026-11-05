package app.lararium.hearth.rails.service

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for DevicePairingStore (F1).
 * Mirrors PairingStoreTest with in-memory FakeSharedPreferences.
 *
 * Verifies:
 * - Roundtrip store/load in secure storage
 * - Plain storage is never populated on write
 * - Migration from legacy plain preferences on first read
 * - Deletion from plain preferences on migration
 * - Clear removes from both stores
 */
class DevicePairingStoreTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? =
            data[key] as? String ?: defValue
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

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) changes[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) changes[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) changes[key] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) data.clear()
                for ((k, v) in changes) {
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test
    fun roundtripStoreAndLoad() {
        val plain = FakeSharedPreferences()
        val secure = FakeSharedPreferences()
        val store = DevicePairingStore(plain, secure)

        val endpoint = DeviceEndpoint("https://example.com:8443", "lar1_test_token_123", "device-slug-1")
        store.saveEndpoint(endpoint)

        val loaded = store.getEndpoint()
        assertNotNull(loaded)
        assertEquals(endpoint.baseUrl, loaded!!.baseUrl)
        assertEquals(endpoint.token, loaded.token)
        assertEquals(endpoint.deviceId, loaded.deviceId)

        // Secure storage contains keys; plain storage contains NONE
        assertTrue(secure.contains(DevicePairingStore.KEY_BASE_URL))
        assertTrue(secure.contains(DevicePairingStore.KEY_TOKEN))
        assertTrue(secure.contains(DevicePairingStore.KEY_DEVICE_ID))

        assertFalse(plain.contains(DevicePairingStore.KEY_BASE_URL))
        assertFalse(plain.contains(DevicePairingStore.KEY_TOKEN))
        assertFalse(plain.contains(DevicePairingStore.KEY_DEVICE_ID))
    }

    @Test
    fun migrationFromLegacyPlainPrefsOnFirstRead() {
        val plain = FakeSharedPreferences()
        val secure = FakeSharedPreferences()

        // Seed plain prefs
        plain.edit()
            .putString(DevicePairingStore.KEY_BASE_URL, "https://example.com:7717")
            .putString(DevicePairingStore.KEY_TOKEN, "lar1_legacy_token")
            .putString(DevicePairingStore.KEY_DEVICE_ID, "legacy-device-id")
            .commit()

        assertTrue(plain.contains(DevicePairingStore.KEY_BASE_URL))
        assertFalse(secure.contains(DevicePairingStore.KEY_BASE_URL))

        val store = DevicePairingStore(plain, secure)
        val loaded = store.getEndpoint()

        assertNotNull(loaded)
        assertEquals("https://example.com:7717", loaded!!.baseUrl)
        assertEquals("lar1_legacy_token", loaded.token)
        assertEquals("legacy-device-id", loaded.deviceId)

        // After first read, plain prefs are purged and secure prefs hold values
        assertFalse(plain.contains(DevicePairingStore.KEY_BASE_URL))
        assertFalse(plain.contains(DevicePairingStore.KEY_TOKEN))
        assertFalse(plain.contains(DevicePairingStore.KEY_DEVICE_ID))

        assertTrue(secure.contains(DevicePairingStore.KEY_BASE_URL))
        assertTrue(secure.contains(DevicePairingStore.KEY_TOKEN))
        assertTrue(secure.contains(DevicePairingStore.KEY_DEVICE_ID))
    }

    @Test
    fun saveEndpointPurgesAnyLegacyPlainValues() {
        val plain = FakeSharedPreferences()
        val secure = FakeSharedPreferences()

        plain.edit()
            .putString(DevicePairingStore.KEY_BASE_URL, "https://old.example.com")
            .putString(DevicePairingStore.KEY_TOKEN, "old_plain_token")
            .putString(DevicePairingStore.KEY_DEVICE_ID, "old_plain_id")
            .commit()

        val store = DevicePairingStore(plain, secure)
        store.saveEndpoint(DeviceEndpoint("https://new.example.com", "tok", "dev"))

        assertFalse(plain.contains(DevicePairingStore.KEY_BASE_URL))
        assertFalse(plain.contains(DevicePairingStore.KEY_TOKEN))
        assertFalse(plain.contains(DevicePairingStore.KEY_DEVICE_ID))
        assertEquals("https://new.example.com", secure.getString(DevicePairingStore.KEY_BASE_URL, null))
        assertEquals("tok", secure.getString(DevicePairingStore.KEY_TOKEN, null))
        assertEquals("dev", secure.getString(DevicePairingStore.KEY_DEVICE_ID, null))
    }

    @Test
    fun clearRemovesFromBothStores() {
        val plain = FakeSharedPreferences()
        val secure = FakeSharedPreferences()
        val store = DevicePairingStore(plain, secure)

        store.saveEndpoint(DeviceEndpoint("https://example.com", "tok", "dev"))
        assertTrue(store.isPaired())

        store.clear()
        assertFalse(store.isPaired())
        assertNull(store.getEndpoint())
        assertFalse(secure.contains(DevicePairingStore.KEY_BASE_URL))
        assertFalse(plain.contains(DevicePairingStore.KEY_BASE_URL))
    }
}
