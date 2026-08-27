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
 * Armazena chaves de API em texto simples no SharedPreferences do app.
 *
 * As chaves não são criptografadas: ficam no armazenamento privado do app
 * (SharedPreferences MODE_PRIVATE) e são mascaradas na tela pela UI.
 * Valores legados criptografados (prefixo "enc:v1:") são migrados para texto
 * simples na primeira leitura.
 */
internal object ApiKeyStore {
    private const val TAG = "ApiKeyStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "sig_api_keys_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc:v1:"

    fun get(preferences: SharedPreferences, key: String): String {
        val stored = preferences.getString(key, null) ?: return ""
        if (!stored.startsWith(PREFIX)) return stored
        // Migração one-time de valor legado criptografado para texto simples.
        val plain = decrypt(stored).orEmpty()
        preferences.edit().putString(key, plain).apply()
        return plain
    }

    fun put(preferences: SharedPreferences, key: String, value: String) {
        val clean = value.trim()
        if (clean.isBlank()) {
            preferences.edit().remove(key).apply()
            return
        }
        preferences.edit().putString(key, clean).apply()
    }

    // --- apenas migração de valores legados criptografados ---

    private fun decrypt(value: String): String? = runCatching {
        val encoded = value.removePrefix(PREFIX).split('.', limit = 2)
        require(encoded.size == 2)
        val iv = Base64.decode(encoded[0], Base64.NO_WRAP)
        val payload = Base64.decode(encoded[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        }
        String(cipher.doFinal(payload), StandardCharsets.UTF_8).trim()
    }.onFailure { Log.e(TAG, "Falha ao migrar chave", it) }.getOrNull()

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
