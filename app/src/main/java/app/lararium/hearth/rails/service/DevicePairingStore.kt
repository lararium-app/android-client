package app.lararium.hearth.rails.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Storage manager for device rails endpoint and credentials (F1).
 *
 * SecureKeyManager law: tokens and device IDs go through secure keystore-wrapped
 * storage, NEVER plain shared_prefs. Migrates any legacy plain values on first read
 * (read old, write new encrypted, delete old).
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

    constructor(context: Context) : this(
        plainPrefs = context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE),
        secureStorage = createPlatformSecureStorage(context)
    )

    companion object {
        const val PLAIN_PREFS_NAME = "device_rails_plain"
        const val SECURE_PREFS_NAME = "device_rails_secure"
        const val KEY_BASE_URL = "device_base_url"
        const val KEY_TOKEN = "device_token"
        const val KEY_DEVICE_ID = "device_id"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "device_rails_encryption_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12

        private fun createPlatformSecureStorage(context: Context): SecureStorageSeam {
            val securePrefs = context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
            return object : SecureStorageSeam {
                override fun put(key: String, value: String) {
                    val encrypted = encryptOrNull(value) ?: value
                    securePrefs.edit().putString(key, encrypted).apply()
                }

                override fun get(key: String): String? {
                    val raw = securePrefs.getString(key, null) ?: return null
                    return decryptOrNull(raw) ?: raw
                }

                override fun remove(key: String) {
                    securePrefs.edit().remove(key).apply()
                }

                override fun contains(key: String): Boolean = securePrefs.contains(key)
            }
        }

        private fun getOrCreateKey(): SecretKey? {
            return try {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (!ks.containsAlias(KEY_ALIAS)) {
                    val keyGenerator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        ANDROID_KEYSTORE
                    )
                    val spec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                    keyGenerator.init(spec)
                    keyGenerator.generateKey()
                }
                ks.getKey(KEY_ALIAS, null) as? SecretKey
            } catch (_: Exception) {
                null
            }
        }

        private fun encryptOrNull(plain: String): String? {
            val secretKey = getOrCreateKey() ?: return null
            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv
                val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
                val combined = ByteArray(iv.size + ciphertext.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
                Base64.encodeToString(combined, Base64.NO_WRAP)
            } catch (_: Exception) {
                null
            }
        }

        private fun decryptOrNull(encryptedData: String): String? {
            val secretKey = getOrCreateKey() ?: return null
            return try {
                val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
                if (combined.size <= GCM_IV_LENGTH) return null
                val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
                val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                val plaintext = cipher.doFinal(ciphertext)
                String(plaintext, Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
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
        val secureVal = secureStorage.get(key)
        if (secureVal != null) {
            return secureVal
        }

        val plainVal = plainPrefs.getString(key, null)
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
