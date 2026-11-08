package app.lararium.security

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Pure JVM unit tests for [SecureKeyManager] (T6 A.3).
 *
 * Exercises:
 * 1. `getPairingCode()` migration: legacy plaintext in `local_token` -> encrypted in
 *    `assistant_secure_prefs`, deleted from `local_token`, decrypted value matches.
 * 2. Leftover plaintext purge when encrypted key already exists.
 * 3. Legacy device-rails credential migration (`device_rails_plain` -> `assistant_secure_prefs`).
 * 4. Backup/restore semantics: ciphertext restored without the original AndroidKeyStore key
 *    remains un-decryptable (fails closed to `null`) and never exposes plaintext.
 * 5. Backup XML rules exclude legacy plaintext preference files.
 */
class SecureKeyManagerTest {

    private class InMemorySharedPreferences : SharedPreferences {
        val data = mutableMapOf<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            data[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key] = values }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { removals.add(key) }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) data.clear()
                for (k in removals) {
                    data.remove(k)
                    listeners.forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, k) }
                }
                for ((k, v) in pending) {
                    if (v == null) data.remove(k) else data[k] = v
                    listeners.forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, k) }
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners.remove(listener)
        }
    }

    private class FakeContext : ContextWrapper(null) {
        val prefsByName = mutableMapOf<String, InMemorySharedPreferences>()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            return prefsByName.getOrPut(name) { InMemorySharedPreferences() }
        }
    }

    private fun generateAesKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    @Test
    fun getPairingCode_migratesLegacyPlaintext_deletesPlainPref_andReturnsDecryptedValue() {
        val context = FakeContext()
        val secretKey = generateAesKey()
        val manager = SecureKeyManager(context) { secretKey }

        val legacyPlainPrefs = context.getSharedPreferences("local_token", 0)
        val expectedCode = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        legacyPlainPrefs.edit().putString("pairingCode", expectedCode).apply()

        assertTrue(legacyPlainPrefs.contains("pairingCode"))

        // First read triggers migration: reads legacy, encrypts into secure prefs, deletes legacy
        val migrated = manager.getPairingCode()
        assertEquals("Migrated pairingCode must match legacy value", expectedCode, migrated)
        assertFalse(
            "Legacy plaintext pairingCode must be deleted from local_token after getPairingCode()",
            legacyPlainPrefs.contains("pairingCode")
        )

        val securePrefs = context.getSharedPreferences("assistant_secure_prefs", 0)
        val rawStored = securePrefs.getString("pairing_code", null)
        assertNotNull("Encrypted pairing_code must be present in assistant_secure_prefs", rawStored)
        assertNotEquals("Stored value must be ciphertext, not plaintext", expectedCode, rawStored)
        assertFalse("Ciphertext must not contain plaintext substring", rawStored!!.contains(expectedCode))

        // Second read decrypts directly from assistant_secure_prefs
        assertEquals(expectedCode, manager.getPairingCode())
    }

    @Test
    fun getPairingCode_whenEncryptedAlreadyExists_purgesLeftoverLegacyPlaintext() {
        val context = FakeContext()
        val secretKey = generateAesKey()
        val manager = SecureKeyManager(context) { secretKey }

        manager.setPairingCode("11112222333344445555666677778888")

        // Simulate a stale plaintext value re-appearing in local_token
        val legacyPlainPrefs = context.getSharedPreferences("local_token", 0)
        legacyPlainPrefs.edit().putString("pairingCode", "stale_plaintext_value").apply()
        assertTrue(legacyPlainPrefs.contains("pairingCode"))

        val code = manager.getPairingCode()
        assertEquals("11112222333344445555666677778888", code)
        assertFalse(
            "Leftover plaintext key in local_token must be purged even when encrypted value already exists",
            legacyPlainPrefs.contains("pairingCode")
        )
    }

    @Test
    fun deviceRailsCredentials_migrateFromLegacyPlaintextAndPurgeOld() {
        val context = FakeContext()
        val secretKey = generateAesKey()
        val manager = SecureKeyManager(context) { secretKey }

        val plainRails = context.getSharedPreferences("device_rails_plain", 0)
        plainRails.edit()
            .putString("device_token", "lar1_device_secret_token")
            .putString("device_id", "dev_99")
            .putString("device_base_url", "https://hearth.example.com")
            .apply()

        assertEquals("lar1_device_secret_token", manager.getDeviceToken())
        assertEquals("dev_99", manager.getDeviceId())
        assertEquals("https://hearth.example.com", manager.getDeviceBaseUrl())

        assertFalse(plainRails.contains("device_token"))
        assertFalse(plainRails.contains("device_id"))
        assertFalse(plainRails.contains("device_base_url"))
    }

    @Test
    fun pairToken_survivesBackupRestoreOnlyAsCiphertext() {
        val context = FakeContext()
        val originalDeviceKey = generateAesKey()
        val writerManager = SecureKeyManager(context) { originalDeviceKey }

        writerManager.setHearthEndpoint("https://hearth.local:8443", "lar1_super_secret_token", "home")
        writerManager.setPairingCode("deadbeefcafebabe0123456789abcdef")

        val securePrefs = context.getSharedPreferences("assistant_secure_prefs", 0)
        val rawEndpoint = securePrefs.getString(SecureKeyManager.PREF_HEARTH_ENDPOINT, null)
        val rawPairingCode = securePrefs.getString("pairing_code", null)

        assertNotNull(rawEndpoint)
        assertNotNull(rawPairingCode)
        assertFalse("Raw prefs must never contain plaintext bearer token", rawEndpoint!!.contains("lar1_super_secret_token"))
        assertFalse("Raw prefs must never contain plaintext pairing code", rawPairingCode!!.contains("deadbeefcafebabe0123456789abcdef"))

        // Simulate backup restore onto a fresh device whose Keystore generates a different AES key
        val restoredDeviceKey = generateAesKey()
        val restoredManager = SecureKeyManager(context) { restoredDeviceKey }

        assertNull(
            "Restored ciphertext without original Keystore key must fail closed to null",
            restoredManager.getHearthEndpoint()
        )
        assertNull(
            "Restored pairing code ciphertext without original Keystore key must fail closed to null",
            restoredManager.getPairingCode()
        )
    }

    @Test
    fun backupXmlRules_excludeLegacyPlaintextStores() {
        val xmlDir = listOf(
            File("src/main/res/xml"),
            File("app/src/main/res/xml")
        ).firstOrNull { it.exists() }
        assertNotNull("res/xml directory must exist", xmlDir)

        for (fileName in listOf("data_extraction_rules.xml", "full_backup_content.xml", "backup_rules.xml")) {
            val file = File(xmlDir!!, fileName)
            assertTrue("$fileName must exist", file.exists())
            val content = file.readText()
            assertTrue("$fileName must exclude local_token.xml", content.contains("local_token.xml"))
            assertTrue("$fileName must exclude device_rails_plain.xml", content.contains("device_rails_plain.xml"))
        }
    }
}
