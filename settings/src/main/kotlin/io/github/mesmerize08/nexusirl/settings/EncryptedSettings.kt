package io.github.mesmerize08.nexusirl.settings

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Settings exports with stream keys, encrypted with a passphrase (docs/PORT_PLAN.md §9.7, Android
 * only: iOS Moblin cannot read them).
 *
 * Format: `NXSE`, version (1 byte), PBKDF2 iterations (4 bytes, big-endian), salt (16 bytes),
 * GCM nonce (12 bytes), then the `.moblinSettings` archive encrypted with AES-256-GCM. The header
 * is authenticated as associated data, so changing any of it fails decryption. The key is
 * PBKDF2-HMAC-SHA256 of the passphrase.
 */
object EncryptedSettings {
    const val FILE_EXTENSION = "nexusirlSettings"
    const val MINIMUM_PASSPHRASE_LENGTH = 10

    /** OWASP's 2023 recommendation for PBKDF2-HMAC-SHA256. */
    const val ITERATIONS = 600_000

    /** Refuse files that would make the phone spin for minutes. */
    private const val MAXIMUM_ITERATIONS = 10_000_000
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte())
    private const val VERSION: Byte = 1
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val HEADER_SIZE = 4 + 1 + 4 + SALT_SIZE + NONCE_SIZE
    private const val TAG_BITS = 128

    /** Wrong passphrase, a damaged file, or an unsupported version. */
    class DecryptionException(message: String) : Exception(message)

    fun isEncrypted(data: ByteArray): Boolean = data.size >= MAGIC.size && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /** Encrypts [archive]. Slow (key derivation): call off the main thread. */
    fun encrypt(archive: ByteArray, passphrase: CharArray, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): ByteArray {
        require(passphrase.size >= MINIMUM_PASSPHRASE_LENGTH) { "Passphrase too short" }
        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER_SIZE).put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(nonce).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(archive)
    }

    /** Returns the archive inside [data]. Slow (key derivation): call off the main thread. */
    fun decrypt(data: ByteArray, passphrase: CharArray): ByteArray {
        if (!isEncrypted(data) || data.size < HEADER_SIZE + TAG_BITS / 8) {
            throw DecryptionException("Not an encrypted settings file")
        }
        val buffer = ByteBuffer.wrap(data)
        buffer.position(MAGIC.size)
        if (buffer.get() != VERSION) {
            throw DecryptionException("Unsupported encrypted settings version")
        }
        val iterations = buffer.getInt()
        if (iterations !in 1..MAXIMUM_ITERATIONS) {
            throw DecryptionException("Unsupported key derivation")
        }
        val salt = ByteArray(SALT_SIZE).also { buffer.get(it) }
        val nonce = ByteArray(NONCE_SIZE).also { buffer.get(it) }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(data, 0, HEADER_SIZE)
            cipher.doFinal(data, HEADER_SIZE, data.size - HEADER_SIZE)
        } catch (e: GeneralSecurityException) {
            throw DecryptionException("Wrong passphrase or damaged file")
        }
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
