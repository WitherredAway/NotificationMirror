package com.notifmirror.mobile

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
 * Key is generated once and stored in SharedPreferences.
 * The same key must be shared with the watch app via Wearable DataClient.
 */
object CryptoHelper {

    private const val PREFS_NAME = "notif_mirror_crypto"
    private const val KEY_AES = "aes_key"
    private const val AES_KEY_SIZE = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    fun getOrCreateKey(context: Context): SecretKey {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_AES, null)
        if (stored != null) {
            val decoded = Base64.decode(stored, Base64.NO_WRAP)
            return SecretKeySpec(decoded, "AES")
        }
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(AES_KEY_SIZE, SecureRandom())
        val key = keyGen.generateKey()
        prefs.edit().putString(KEY_AES, Base64.encodeToString(key.encoded, Base64.NO_WRAP)).apply()
        return key
    }

    fun importKey(context: Context, keyBytes: ByteArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_AES, Base64.encodeToString(keyBytes, Base64.NO_WRAP)).apply()
    }

    fun getKeyBytes(context: Context): ByteArray {
        return getOrCreateKey(context).encoded
    }

    fun encrypt(data: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val encrypted = cipher.doFinal(data)
        // Prepend IV to ciphertext
        return iv + encrypted
    }

    fun decrypt(data: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = data.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = data.copyOfRange(GCM_IV_LENGTH, data.size)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    fun encryptString(plaintext: String, context: Context): String {
        val key = getOrCreateKey(context)
        val encrypted = encrypt(plaintext.toByteArray(Charsets.UTF_8), key)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decryptString(ciphertext: String, context: Context): String {
        val key = getOrCreateKey(context)
        val decoded = Base64.decode(ciphertext, Base64.NO_WRAP)
        val decrypted = decrypt(decoded, key)
        return String(decrypted, Charsets.UTF_8)
    }
}
