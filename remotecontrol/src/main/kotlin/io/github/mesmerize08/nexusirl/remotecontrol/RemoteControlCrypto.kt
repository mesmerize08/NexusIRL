// Port of `remoteControlHashPassword` and `RemoteControlEncryption` in
// Moblin/RemoteControl/RemoteControl.swift @97e26d2.
package io.github.mesmerize08.nexusirl.remotecontrol

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

/** Upstream: base64(SHA256(base64(SHA256(password + salt)) + challenge)). */
fun remoteControlHashPassword(challenge: String, salt: String, password: String): String {
    val first = Base64.getEncoder().encodeToString(sha256(password + salt))
    return Base64.getEncoder().encodeToString(sha256(first + challenge))
}

/**
 * Upstream `RemoteControlEncryption`: AES-256-GCM with key SHA256(password), in CryptoKit's
 * "combined" layout (12-byte nonce, ciphertext, 16-byte tag).
 */
class RemoteControlEncryption(password: String) {
    private val key = SecretKeySpec(sha256(password), "AES")
    private val random = SecureRandom()

    fun encrypt(data: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        return nonce + cipher.doFinal(data)
    }

    fun decrypt(data: ByteArray): ByteArray? {
        if (data.size < NONCE_SIZE + TAG_BITS / 8) {
            return null
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 0, NONCE_SIZE))
            cipher.doFinal(data, NONCE_SIZE, data.size - NONCE_SIZE)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val NONCE_SIZE = 12
        const val TAG_BITS = 128
    }
}
