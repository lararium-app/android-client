package app.lararium.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure key management using Android Keystore.
 * Encrypts API keys and sensitive data at rest.
 */
class SecureKeyManager(private val context: Context) {

    companion object {
        private const val TAG = "SecureKeyManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "assistant_encryption_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        
        private const val PREFS_NAME = "assistant_secure_prefs"
        private const val PREF_TOKEN_REFRESH_TIME = "token_refresh_time"

        /** Pref key holding the paired hearth endpoint (public: the
         *  service listens for changes to it to refresh the connection
         *  dot immediately after pairing, wherever pairing happened). */
        const val PREF_HEARTH_ENDPOINT = "hearth_endpoint"
        private const val PREF_PAIRING_CODE = "pairing_code"
        private const val PLAIN_PREFS_NAME = "local_token"
        private const val PLAIN_PAIRING_CODE_KEY = "pairingCode"
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        createKeyIfNeeded()
    }

    /**
     * Create encryption key in Android Keystore if it doesn't exist.
     */
    private fun createKeyIfNeeded() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )

            val keySpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false) // Set true for biometric protection
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(keySpec)
            keyGenerator.generateKey()
            Log.i(TAG, "Encryption key created in Keystore")
        }
    }

    private fun getSecretKey(): SecretKey {
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    /**
     * Encrypt data using AES-GCM with Android Keystore key.
     * Returns Base64-encoded string containing IV + ciphertext.
     */
    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        
        // Combine IV and ciphertext
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypt Base64-encoded data using Android Keystore key.
     */
    private fun decrypt(encryptedData: String): String {
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
        
        // Extract IV and ciphertext
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
        
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    // ========== Hearth Endpoint Storage ==========

    /**
     * Persist the hearth endpoint (baseUrl + token + name) encrypted.
     * Serialized as JSON so fields can be added later without migration pain.
     */
    fun setHearthEndpoint(baseUrl: String, token: String, name: String) {
        val json = org.json.JSONObject()
            .put("baseUrl", baseUrl)
            .put("token", token)
            .put("name", name)
            .toString()
        val encrypted = encrypt(json)
        prefs.edit().putString(PREF_HEARTH_ENDPOINT, encrypted).apply()
        Log.i(TAG, "Hearth endpoint stored securely")
    }

    /**
     * Observe writes to the secure store (pair/unpair re-probes the
     * connection the moment an endpoint lands, instead of waiting for
     * the next network event or activity restart). Register-once; the
     * callback fires on an arbitrary thread.
     */
    fun addEndpointChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeEndpointChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * Retrieve the persisted hearth endpoint, or null if none is stored.
     * Returns a Triple(baseUrl, token, name).
     */
    fun getHearthEndpoint(): Triple<String, String, String>? {
        val encrypted = prefs.getString(PREF_HEARTH_ENDPOINT, null) ?: return null
        return try {
            val json = org.json.JSONObject(decrypt(encrypted))
            Triple(
                json.getString("baseUrl"),
                json.getString("token"),
                json.getString("name"),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt hearth endpoint", e)
            null
        }
    }

    fun hasHearthEndpoint(): Boolean = prefs.contains(PREF_HEARTH_ENDPOINT)

    fun clearHearthEndpoint() {
        prefs.edit().remove(PREF_HEARTH_ENDPOINT).apply()
    }

    // ========== Pairing Code Storage (L-21) ==========

    /**
     * Store 32-hex pairingCode in keystore-wrapped encrypted preferences (finding L-21).
     * Purges legacy plain value from local_token if present.
     */
    fun setPairingCode(pairingCode: String) {
        val encrypted = encrypt(pairingCode)
        prefs.edit().putString(PREF_PAIRING_CODE, encrypted).apply()
        val plainPrefs = context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)
        if (plainPrefs.contains(PLAIN_PAIRING_CODE_KEY)) {
            plainPrefs.edit().remove(PLAIN_PAIRING_CODE_KEY).apply()
        }
        Log.i(TAG, "Pairing code stored securely in keystore-wrapped preferences")
    }

    /**
     * Retrieve 32-hex pairingCode.
     * Automatically migrates legacy plain value from local_token prefs on first read (L-21):
     * reads old, writes to secure prefs, deletes old.
     */
    fun getPairingCode(): String? {
        val encrypted = prefs.getString(PREF_PAIRING_CODE, null)
        if (encrypted != null) {
            return try {
                decrypt(encrypted)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt pairing code", e)
                null
            }
        }

        // Migrate legacy plain value on first read: read old, write new, delete old
        val plainPrefs = context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyCode = plainPrefs.getString(PLAIN_PAIRING_CODE_KEY, null)
        if (legacyCode != null) {
            Log.i(TAG, "Migrating legacy pairingCode from local_token to secure storage (L-21)")
            setPairingCode(legacyCode)
            plainPrefs.edit().remove(PLAIN_PAIRING_CODE_KEY).apply()
            return legacyCode
        }

        return null
    }

    fun hasPairingCode(): Boolean {
        if (prefs.contains(PREF_PAIRING_CODE)) return true
        val plainPrefs = context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)
        return plainPrefs.contains(PLAIN_PAIRING_CODE_KEY)
    }

    fun clearPairingCode() {
        prefs.edit().remove(PREF_PAIRING_CODE).apply()
        val plainPrefs = context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)
        plainPrefs.edit().remove(PLAIN_PAIRING_CODE_KEY).apply()
    }

    // ========== Token Rotation Support ==========

    /**
     * Store token refresh timestamp for rotation scheduling.
     */
    fun setTokenRefreshTime(timestamp: Long) {
        prefs.edit().putLong(PREF_TOKEN_REFRESH_TIME, timestamp).apply()
    }

    fun getTokenRefreshTime(): Long {
        return prefs.getLong(PREF_TOKEN_REFRESH_TIME, 0)
    }

    /**
     * Check if token needs rotation (e.g., every 24 hours).
     */
    fun needsTokenRotation(rotationIntervalMs: Long = 24 * 60 * 60 * 1000): Boolean {
        val lastRefresh = getTokenRefreshTime()
        return System.currentTimeMillis() - lastRefresh > rotationIntervalMs
    }
}
