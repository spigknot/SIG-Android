package br.gov.sp.pcsp.launcher

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores API keys encrypted with a key held by Android Keystore.
 * Existing plaintext values are migrated the first time they are read.
 */
internal object EncryptedApiKeyStore {
    private const val TAG = "EncryptedApiKeyStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "sig_api_keys_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc:v1:"

    @Synchronized
    fun get(preferences: SharedPreferences, key: String): String {
        val stored = preferences.getString(key, null) ?: return ""
        if (!stored.startsWith(PREFIX)) {
            val legacy = stored.trim()
            if (legacy.isNotBlank()) {
                encrypt(legacy)?.let { encrypted ->
                    preferences.edit().putString(key, encrypted).apply()
                }
            }
            return legacy
        }
        return decrypt(stored).orEmpty()
    }

    @Synchronized
    fun put(preferences: SharedPreferences, key: String, value: String) {
        val clean = value.trim()
        if (clean.isBlank()) {
            preferences.edit().remove(key).apply()
            return
        }
        val encrypted = encrypt(clean)
        if (encrypted == null) {
            Log.e(TAG, "Não foi possível proteger a chave $key; valor não persistido")
            return
        }
        preferences.edit().putString(key, encrypted).apply()
    }

    private fun encrypt(value: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val payload = Base64.encodeToString(
            cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)),
            Base64.NO_WRAP
        )
        "$PREFIX$iv.$payload"
    }.onFailure { Log.e(TAG, "Falha ao criptografar chave", it) }.getOrNull()

    private fun decrypt(value: String): String? = runCatching {
        val encoded = value.removePrefix(PREFIX).split('.', limit = 2)
        require(encoded.size == 2)
        val iv = Base64.decode(encoded[0], Base64.NO_WRAP)
        val payload = Base64.decode(encoded[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        }
        String(cipher.doFinal(payload), StandardCharsets.UTF_8).trim()
    }.onFailure { Log.e(TAG, "Falha ao descriptografar chave", it) }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
