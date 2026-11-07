package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Abstraction for hardware-backed key encryption so production strictly uses AndroidKeyStore
 * while tests can verify both Keystore failure handling and round-trip encryption.
 */
interface KeyCipherEngine {
    fun encrypt(plainText: String): String?
    fun decrypt(payload: String): String?
}

class AndroidKeystoreCipherEngine : KeyCipherEngine {

    override fun encrypt(plainText: String): String? {
        return try {
            val secretKey = getOrCreateSecretKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
            "GCM:$ivB64:$cipherB64"
        } catch (e: Exception) {
            null
        }
    }

    override fun decrypt(payload: String): String? {
        if (!payload.startsWith("GCM:")) return null
        val parts = payload.split(":")
        if (parts.size != 3) return null
        return try {
            val secretKey = getOrCreateSecretKey() ?: return null
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                return existing.secretKey
            }
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
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "masroofi_api_credentials_aes_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * Hardware-backed Android Keystore AES-GCM encryption for storing user-supplied API keys locally.
 * Keys are never written in plaintext or Base64 fallback to disk, never logged, and never exported in backups.
 * If Keystore encryption is unavailable or fails, saving fails safely without persisting secrets.
 */
class SecureKeyStorage(
    context: Context,
    private val cipherEngine: KeyCipherEngine = AndroidKeystoreCipherEngine()
) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun saveApiKey(providerKeyId: String, rawApiKey: String): Result<Unit> {
        val trimmed = rawApiKey.trim()
        if (trimmed.isEmpty()) {
            removeApiKey(providerKeyId)
            return Result.success(Unit)
        }
        val encryptedPayload = cipherEngine.encrypt(trimmed)
        return if (encryptedPayload != null && encryptedPayload.startsWith("GCM:")) {
            prefs.edit().putString(providerKeyId, encryptedPayload).apply()
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "تعذر تشفير مفتاح API بأمان عبر نظام Android Keystore على هذا الجهاز. لم يتم حفظ المفتاح حفاظاً على أمان بياناتك."
                )
            )
        }
    }

    fun getApiKey(providerKeyId: String): String {
        val stored = prefs.getString(providerKeyId, null) ?: return ""
        if (!stored.startsWith("GCM:")) {
            // Clean up any legacy or non-Keystore entry so plaintext/Base64 never remains on disk
            prefs.edit().remove(providerKeyId).apply()
            return ""
        }
        val decrypted = cipherEngine.decrypt(stored)
        if (decrypted == null) {
            // If encryption key is unavailable (e.g. after device transfer or Keystore reset),
            // clear the unreadable ciphertext safely so the user can configure a fresh key.
            prefs.edit().remove(providerKeyId).apply()
            return ""
        }
        return decrypted
    }

    fun removeApiKey(providerKeyId: String) {
        prefs.edit().remove(providerKeyId).apply()
    }

    fun hasApiKey(providerKeyId: String): Boolean = getApiKey(providerKeyId).isNotBlank()

    fun maskApiKey(rawKey: String): String {
        val clean = rawKey.trim()
        if (clean.isEmpty()) return "غير مُعد"
        if (clean.length <= 8) return "••••••••"
        return clean.take(4) + "••••••••" + clean.takeLast(4)
    }

    companion object {
        private const val PREFS_FILE = "masroofi_secure_keys"

        const val KEY_GEMINI_API = "gemini_api_key"
        const val KEY_OPENROUTER_API = "openrouter_api_key"
    }
}
