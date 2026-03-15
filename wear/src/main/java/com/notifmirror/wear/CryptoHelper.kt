package com.notifmirror.wear

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption helper for notification data.
 * Key is received from phone app via Wearable DataClient and stored locally.
 */
object CryptoHelper {

    private const val PREFS_NAME = "notif_mirror_crypto"
    private const val KEY_AES = "aes_key"
    private const val AES_KEY_SIZE = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    fun getKey(context: Context): SecretKey? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_AES, null) ?: return null
        val decoded = Base64.decode(stored, Base64.NO_WRAP)
        return SecretKeySpec(decoded, "AES")
    }

    fun importKey(context: Context, keyBytes: ByteArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_AES, Base64.encodeToString(keyBytes, Base64.NO_WRAP)).apply()
    }

    fun hasKey(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AES, null) != null
    }

    fun decrypt(data: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = data.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = data.copyOfRange(GCM_IV_LENGTH, data.size)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    fun encrypt(data: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val encrypted = cipher.doFinal(data)
        return iv + encrypted
    }

    fun encryptString(plaintext: String, context: Context): String? {
        val key = getKey(context) ?: return null
        val encrypted = encrypt(plaintext.toByteArray(Charsets.UTF_8), key)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decryptString(ciphertext: String, context: Context): String? {
        val key = getKey(context) ?: return null
        val decoded = Base64.decode(ciphertext, Base64.NO_WRAP)
        val decrypted = decrypt(decoded, key)
        return String(decrypted, Charsets.UTF_8)
    }
}
