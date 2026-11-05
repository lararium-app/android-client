package app.lararium.hearth.rails.service

import android.content.Context
import android.content.SharedPreferences
import app.lararium.security.SecureKeyManager

/**
 * Storage manager for device rails endpoint and credentials (F1, T6).
 *
 * SecureKeyManager law: tokens and device credentials go through secure keystore-wrapped
 * storage via SecureKeyManager, NEVER plain shared_prefs. Migrates any legacy plain values
 * on first read (read old, write new encrypted, delete old).
 */
class DevicePairingStore(
    private val plainPrefs: SharedPreferences,
    private val secureStorage: SecureStorageSeam,
) {
    interface SecureStorageSeam {
        fun put(key: String, value: String)
        fun get(key: String): String?
        fun remove(key: String)
        fun contains(key: String): Boolean
    }

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

    constructor(context: Context, keyManager: SecureKeyManager = SecureKeyManager(context)) : this(
        plainPrefs = context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE),
        secureStorage = object : SecureStorageSeam {
            override fun put(key: String, value: String) {
                when (key) {
                    KEY_BASE_URL -> keyManager.setDeviceBaseUrl(value)
                    KEY_TOKEN -> keyManager.setDeviceToken(value)
                    KEY_DEVICE_ID -> keyManager.setDeviceId(value)
                }
            }

            override fun get(key: String): String? = when (key) {
                KEY_BASE_URL -> keyManager.getDeviceBaseUrl()
                KEY_TOKEN -> keyManager.getDeviceToken()
                KEY_DEVICE_ID -> keyManager.getDeviceId()
                else -> null
            }

            override fun remove(key: String) {
                when (key) {
                    KEY_BASE_URL -> keyManager.clearDeviceBaseUrl()
                    KEY_TOKEN -> keyManager.clearDeviceToken()
                    KEY_DEVICE_ID -> keyManager.clearDeviceId()
                }
            }

            override fun contains(key: String): Boolean = when (key) {
                KEY_BASE_URL -> keyManager.hasDeviceBaseUrl()
                KEY_TOKEN -> keyManager.hasDeviceToken()
                KEY_DEVICE_ID -> keyManager.hasDeviceId()
                else -> false
            }
        }
    )

    companion object {
        const val PLAIN_PREFS_NAME = "device_rails_plain"
        const val SECURE_PREFS_NAME = "device_rails_secure"
        // Base URL is a public network endpoint, but stored alongside device credentials
        const val KEY_BASE_URL = "device_base_url"
        const val KEY_TOKEN = "device_token"
        const val KEY_DEVICE_ID = "device_id"
    }

    /**
     * Retrieves the stored DeviceEndpoint, migrating legacy plain values if present.
     */
    fun getEndpoint(): DeviceEndpoint? {
        val baseUrl = getOrMigrate(KEY_BASE_URL)
        val token = getOrMigrate(KEY_TOKEN)
        val deviceId = getOrMigrate(KEY_DEVICE_ID)

        if (!baseUrl.isNullOrBlank() && !token.isNullOrBlank() && !deviceId.isNullOrBlank()) {
            return DeviceEndpoint(baseUrl = baseUrl, token = token, deviceId = deviceId)
        }
        return null
    }

    private fun getOrMigrate(key: String): String? {
        val secureVal = secureStorage.get(key)?.takeIf { it.isNotBlank() }
        if (secureVal != null) {
            return secureVal
        }

        val plainVal = plainPrefs.getString(key, null)?.takeIf { it.isNotBlank() }
        if (plainVal != null) {
            secureStorage.put(key, plainVal)
            plainPrefs.edit().remove(key).apply()
            return plainVal
        }

        return null
    }

    /**
     * Stores the DeviceEndpoint exclusively in secure storage and cleans plain storage.
     */
    fun saveEndpoint(endpoint: DeviceEndpoint) {
        saveEndpoint(endpoint.baseUrl, endpoint.token, endpoint.deviceId)
    }

    fun saveEndpoint(baseUrl: String, token: String, deviceId: String) {
        secureStorage.put(KEY_BASE_URL, baseUrl)
        secureStorage.put(KEY_TOKEN, token)
        secureStorage.put(KEY_DEVICE_ID, deviceId)

        val editor = plainPrefs.edit()
        var hasPlain = false
        if (plainPrefs.contains(KEY_BASE_URL)) { editor.remove(KEY_BASE_URL); hasPlain = true }
        if (plainPrefs.contains(KEY_TOKEN)) { editor.remove(KEY_TOKEN); hasPlain = true }
        if (plainPrefs.contains(KEY_DEVICE_ID)) { editor.remove(KEY_DEVICE_ID); hasPlain = true }
        if (hasPlain) {
            editor.apply()
        }
    }

    /**
     * Clears device pairing information from both secure and plain stores.
     */
    fun clear() {
        secureStorage.remove(KEY_BASE_URL)
        secureStorage.remove(KEY_TOKEN)
        secureStorage.remove(KEY_DEVICE_ID)

        plainPrefs.edit()
            .remove(KEY_BASE_URL)
            .remove(KEY_TOKEN)
            .remove(KEY_DEVICE_ID)
            .apply()
    }

    fun isPaired(): Boolean = (getEndpoint() != null)
}
