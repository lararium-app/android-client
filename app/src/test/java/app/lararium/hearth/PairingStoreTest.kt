package app.lararium.hearth

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PairingStore and pairingCode migration to encrypted storage (finding L-21).
 *
 * Verifies:
 * - Roundtrip store/load in encrypted preferences
 * - Migration from legacy plain local_token shared preferences on first read
 * - Deletion of legacy plain value upon migration
 * - Validation of 32-hex format
 */
class PairingStoreTest {

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
        val store = PairingStore(plain, secure)

        val code = "0123456789abcdef0123456789abcdef"
        store.setPairingCode(code)

        assertEquals(code, store.getPairingCode())
        assertTrue("Secure preferences must contain pairingCode", secure.contains(PairingStore.KEY_PAIRING_CODE))
        assertFalse("Plain preferences must NOT contain pairingCode", plain.contains(PairingStore.KEY_PAIRING_CODE))
    }

    @Test
    fun migrationFromLegacyPlainPrefsOnFirstRead() {
        val plain = FakeSharedPreferences()
        val secure = FakeSharedPreferences()
        val legacyCode = "fedcba9876543210fedcba9876543210"

        // Seed legacy plain preferences
        plain.edit().putString(PairingStore.KEY_PAIRING_CODE, legacyCode).commit()
        assertTrue("Plain prefs seeded with legacy code", plain.contains(PairingStore.KEY_PAIRING_CODE))
        assertFalse("Secure prefs initially empty", secure.contains(PairingStore.KEY_PAIRING_CODE))

        val store = PairingStore(plain, secure)

        // First read triggers migration
        val retrieved = store.getPairingCode()
        assertEquals(legacyCode, retrieved)

        // Verify: read old, write new, delete old
        assertFalse("Legacy plain prefs must be deleted after migration", plain.contains(PairingStore.KEY_PAIRING_CODE))
        assertTrue("Secure prefs must contain migrated code", secure.contains(PairingStore.KEY_PAIRING_CODE))
        assertEquals(legacyCode, secure.getString(PairingStore.KEY_PAIRING_CODE, null))

        // Subsequent read continues to serve from secure prefs
        assertEquals(legacyCode, store.getPairingCode())
    }

    @Test
    fun setPairingCodePurgesAnyExistingPlainValue() {
        val plain = FakeSharedPreferences()
        val secure = FakeSharedPreferences()
        plain.edit().putString(PairingStore.KEY_PAIRING_CODE, "oldplain000000000000000000000000").commit()

        val store = PairingStore(plain, secure)
        val newCode = "1111222233334444aaaabbbbccccdddd"
        store.setPairingCode(newCode)

        assertFalse(plain.contains(PairingStore.KEY_PAIRING_CODE))
        assertEquals(newCode, secure.getString(PairingStore.KEY_PAIRING_CODE, null))
        assertEquals(newCode, store.getPairingCode())
    }

    @Test
    fun clearRemovesFromBothStores() {
        val plain = FakeSharedPreferences()
        val secure = FakeSharedPreferences()
        val store = PairingStore(plain, secure)

        store.setPairingCode("12341234123412341234123412341234")
        assertTrue(store.hasPairingCode())

        store.clearPairingCode()
        assertFalse(store.hasPairingCode())
        assertNull(store.getPairingCode())
        assertFalse(secure.contains(PairingStore.KEY_PAIRING_CODE))
        assertFalse(plain.contains(PairingStore.KEY_PAIRING_CODE))
    }

    @Test
    fun validates32HexFormat() {
        assertTrue(PairingStore.isValidPairingCode("0123456789abcdef0123456789abcdef"))
        assertTrue(PairingStore.isValidPairingCode("ABCDEF0123456789ABCDEF0123456789"))
        assertTrue(PairingStore.isValidPairingCode("  0123456789abcdef0123456789abcdef  ")) // whitespace trimmed

        assertFalse(PairingStore.isValidPairingCode(""))
        assertFalse(PairingStore.isValidPairingCode("0123456789abcdef")) // 16 chars
        assertFalse(PairingStore.isValidPairingCode("0123456789abcdef0123456789abcdef0")) // 33 chars
        assertFalse(PairingStore.isValidPairingCode("0123456789abcdef0123456789abcdeg")) // non-hex 'g'
    }
}
