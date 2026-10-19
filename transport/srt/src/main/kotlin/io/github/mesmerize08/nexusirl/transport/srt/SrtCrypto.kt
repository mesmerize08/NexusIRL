package io.github.mesmerize08.nexusirl.transport.srt

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * SRT payload encryption (not in upstream Moblin's own sender, which has none; added for
 * docs/PORT_PLAN.md §9.5). Implements the libsrt/HaiCrypt scheme from the SRT specification
 * (draft-sharabayko-srt, "Encryption"):
 *
 * - KEK = PBKDF2-HMAC-SHA1(passphrase, last 8 bytes of the 16-byte salt, 2048 iterations).
 * - A random stream encrypting key (SEK) is wrapped with the KEK (RFC 3394 AES key wrap) and sent
 *   in a KMREQ handshake extension.
 * - Each data payload is AES-CTR encrypted with IV = salt[0..13] XOR (packet index << 16), the
 *   low 16 bits being the block counter. Only the even key is used (no key refresh, which libsrt
 *   performs after 2^25 packets by default; streams of that length are out of scope for now).
 */
class SrtCrypto(passphrase: String, val keyLength: Int = 16, random: SecureRandom = SecureRandom()) {
    private val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
    private val streamKey = ByteArray(keyLength).also { random.nextBytes(it) }
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding")
    private val keySpec = SecretKeySpec(streamKey, "AES")

    /** The keying material message for the KMREQ handshake extension. */
    val keyingMaterial: ByteArray

    init {
        require(keyLength == 16 || keyLength == 24 || keyLength == 32) { "Key length must be 16, 24 or 32" }
        require(passphrase.length in 10..79) { "SRT passphrase must be 10 to 79 characters" }
        val kek = pbkdf2(passphrase, salt.copyOfRange(SALT_LENGTH - PBKDF2_SALT_LENGTH, SALT_LENGTH), keyLength)
        keyingMaterial = buildKeyingMaterial(salt, aesKeyWrap(kek, streamKey), keyLength)
    }

    /** Value of the handshake's Encryption Field (key length advertisement). */
    val handshakeEncryptionField: Int
        get() = keyLength / 8

    /** Encrypts [length] bytes at [offset] in place for the packet with [packetIndex]. */
    fun encrypt(data: ByteArray, offset: Int, length: Int, packetIndex: Long) {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(counterIv(salt, packetIndex)))
        val produced = cipher.doFinal(data, offset, length, data, offset)
        check(produced == length)
    }

    companion object {
        const val SALT_LENGTH = 16
        private const val PBKDF2_SALT_LENGTH = 8
        private const val PBKDF2_ITERATIONS = 2048

        /** KK flags in the data packet header's message number word: even key. */
        const val KK_EVEN_KEY = 0x0800_0000L

        /** Handshake extension types. */
        const val EXT_KMREQ = 3
        const val EXT_KMRSP = 4

        /** IV for AES-CTR: first 112 bits of the salt, packet index XORed into bytes 10..13. */
        fun counterIv(salt: ByteArray, packetIndex: Long): ByteArray {
            val iv = ByteArray(16)
            salt.copyInto(iv, 0, 0, 14)
            iv[10] = (iv[10].toInt() xor (packetIndex ushr 24).toInt()).toByte()
            iv[11] = (iv[11].toInt() xor (packetIndex ushr 16).toInt()).toByte()
            iv[12] = (iv[12].toInt() xor (packetIndex ushr 8).toInt()).toByte()
            iv[13] = (iv[13].toInt() xor packetIndex.toInt()).toByte()
            return iv
        }

        fun pbkdf2(passphrase: String, salt: ByteArray, keyLength: Int, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
            val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, keyLength * 8)
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        }

        /** RFC 3394 AES key wrap with the default IV, on plain AES-ECB for portability. */
        fun aesKeyWrap(kek: ByteArray, key: ByteArray): ByteArray {
            require(key.size % 8 == 0 && key.size >= 16)
            val n = key.size / 8
            val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES")) }
            val a = ByteArray(8) { 0xA6.toByte() }
            val r = Array(n) { key.copyOfRange(it * 8, it * 8 + 8) }
            val block = ByteArray(16)
            for (j in 0 until 6) {
                for (i in 0 until n) {
                    a.copyInto(block, 0)
                    r[i].copyInto(block, 8)
                    val b = aes.doFinal(block)
                    val t = (n * j + i + 1).toLong()
                    for (k in 0 until 8) {
                        a[k] = (b[k].toInt() xor (t ushr (56 - 8 * k)).toInt()).toByte()
                    }
                    r[i] = b.copyOfRange(8, 16)
                }
            }
            return r.fold(a.copyOf()) { acc, part -> acc + part }
        }

        /** KM message (SRT spec 3.2.2): version 1, PT=2, sign 0x2029, even key, AES-CTR, SE=2. */
        fun buildKeyingMaterial(salt: ByteArray, wrappedKey: ByteArray, keyLength: Int): ByteArray {
            val header = byteArrayOf(
                0x12, // S=0, V=1, PT=2 (keying material)
                0x20, 0x29, // Sign: "HAI" PnP vendor id
                0x01, // KK = even key
                0, 0, 0, 0, // KEKI
                0x02, // Cipher: AES-CTR
                0x00, // Auth: none
                0x02, // SE: MPEG-TS/SRT
                0x00, // Resv2
                0x00, 0x00, // Resv3
                (SALT_LENGTH / 4).toByte(),
                (keyLength / 4).toByte(),
            )
            return header + salt + wrappedKey
        }
    }
}
