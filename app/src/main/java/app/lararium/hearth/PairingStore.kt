package app.lararium.hearth

import android.content.Context
import android.content.SharedPreferences
import app.lararium.security.SecureKeyManager

/**
 * Storage manager for 32-hex pairingCode (finding L-21).
 *
 * Law: pairingCode must be stored in keystore-wrapped encrypted preferences
 * (following the token store law of SecureKeyManager), NOT in plain local_token shared_prefs.
 * Migrates existing plain values on first read (read old, write new encrypted, delete old).
 * Keeps single-operator UX identical.
 */
class PairingStore(
    private val plainPrefs: SharedPreferences,
    private val secureStorage: SecureStorageSeam,
) {
    interface SecureStorageSeam {
        fun put(key: String, value: String)
        fun get(key: String): String?
        fun remove(key: String)
        fun contains(key: String): Boolean
    }

    constructor(context: Context, keyManager: SecureKeyManager = SecureKeyManager(context)) : this(
        plainPrefs = context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE),
        secureStorage = object : SecureStorageSeam {
            override fun put(key: String, value: String) {
                if (key == KEY_PAIRING_CODE) keyManager.setPairingCode(value)
            }
            override fun get(key: String): String? {
                return if (key == KEY_PAIRING_CODE) keyManager.getPairingCode() else null
            }
            override fun remove(key: String) {
                if (key == KEY_PAIRING_CODE) keyManager.clearPairingCode()
            }
            override fun contains(key: String): Boolean {
                return if (key == KEY_PAIRING_CODE) keyManager.hasPairingCode() else false
            }
        }
    )

    constructor(plainPrefs: SharedPreferences, securePrefs: SharedPreferences) : this(
        plainPrefs = plainPrefs,
        secureStorage = object : SecureStorageSeam {
            override fun put(key: String, value: String) {
                securePrefs.edit().putString(key, value).apply()
            }
            override fun get(key: String): String? = securePrefs.getString(key, null)
            override fun remove(key: String) {
                securePrefs.edit().remove(key).apply()
            }
            override fun contains(key: String): Boolean = securePrefs.contains(key)
        }
    )

    companion object {
        const val PLAIN_PREFS_NAME = "local_token"
        const val KEY_PAIRING_CODE = "pairingCode"
        private val HEX_32_REGEX = Regex("^[0-9a-fA-F]{32}$")

        fun isValidPairingCode(code: String): Boolean = HEX_32_REGEX.matches(code.trim())
    }

    /**
     * Retrieves the pairing code, migrating from plain local_token prefs if present.
     * Reading plain value triggers write to encrypted storage and immediate delete from plain storage.
     */
    fun getPairingCode(): String? {
        val secureCode = secureStorage.get(KEY_PAIRING_CODE)
        if (secureCode != null) {
            return secureCode
        }

        // Migrate on first read: read old, write new, delete old
        val plainCode = plainPrefs.getString(KEY_PAIRING_CODE, null)
        if (plainCode != null) {
            secureStorage.put(KEY_PAIRING_CODE, plainCode)
            plainPrefs.edit().remove(KEY_PAIRING_CODE).apply()
            return plainCode
        }

        return null
    }

    /**
     * Stores the pairing code into encrypted storage and ensures plain storage is purged.
     */
    fun setPairingCode(code: String) {
        secureStorage.put(KEY_PAIRING_CODE, code)
        if (plainPrefs.contains(KEY_PAIRING_CODE)) {
            plainPrefs.edit().remove(KEY_PAIRING_CODE).apply()
        }
    }

    /**
     * Clears pairing code from both secure and plain storage.
     */
    fun clearPairingCode() {
        secureStorage.remove(KEY_PAIRING_CODE)
        plainPrefs.edit().remove(KEY_PAIRING_CODE).apply()
    }

    fun hasPairingCode(): Boolean {
        return secureStorage.contains(KEY_PAIRING_CODE) || plainPrefs.contains(KEY_PAIRING_CODE)
    }
}
