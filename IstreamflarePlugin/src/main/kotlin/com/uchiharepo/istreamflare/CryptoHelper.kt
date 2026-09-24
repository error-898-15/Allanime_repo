package com.uchiharepo.istreamflare

import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {
    private const val PASSWORD = "iSf#2024$Xk9@mNpQrStUvWxYz1234Ab"
    private const val SALT = "iStreamFlareSalt"
    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256

    private val secretKeySpec: SecretKeySpec by lazy {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(
            PASSWORD.toCharArray(),
            SALT.toByteArray(StandardCharsets.UTF_8),
            ITERATIONS,
            KEY_LENGTH
        )
        val secret = factory.generateSecret(spec)
        SecretKeySpec(secret.encoded ?: ByteArray(0), "AES")
    }

    private fun decodeBase64(str: String): ByteArray {
        return try {
            Base64.decode(str, Base64.DEFAULT)
        } catch (e: Throwable) {
            java.util.Base64.getDecoder().decode(str)
        }
    }

    /**
     * Decrypts AES/GCM/NoPadding encrypted Base64 string from iStreamFlare API
     * Payload structure: [0..11: IV (12 bytes)][12..27: Auth Tag (16 bytes)][28..end: Ciphertext]
     */
    fun decrypt(encryptedBase64: String): String {
        val raw = decodeBase64(encryptedBase64)
        if (raw.size < 28) {
            throw IllegalArgumentException("Invalid encrypted payload size: ${raw.size}")
        }
        val iv = raw.copyOfRange(0, 12)
        val tag = raw.copyOfRange(12, 28)
        val cipherText = raw.copyOfRange(28, raw.size)

        // Android JCE Cipher requires Ciphertext + Auth Tag sequence
        val combined = ByteArray(cipherText.size + tag.size)
        System.arraycopy(cipherText, 0, combined, 0, cipherText.size)
        System.arraycopy(tag, 0, combined, cipherText.size, tag.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, GCMParameterSpec(128, iv))
        val decryptedBytes = cipher.doFinal(combined)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }
}
