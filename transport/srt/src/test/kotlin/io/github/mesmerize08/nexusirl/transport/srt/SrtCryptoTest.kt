package io.github.mesmerize08.nexusirl.transport.srt

import io.github.mesmerize08.nexusirl.core.clock.ManualSerialExecutor
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class SrtCryptoTest {
    @Test
    fun aesKeyWrapRfc3394Vectors() {
        // RFC 3394 4.1: 128-bit key data with a 128-bit KEK.
        assertContentEquals(
            hex("1FA68B0A8112B447AEF34BD8FB5A7B829D3E862371D2CFE5"),
            SrtCrypto.aesKeyWrap(hex("000102030405060708090A0B0C0D0E0F"), hex("00112233445566778899AABBCCDDEEFF")),
        )
        // RFC 3394 4.6: 256-bit key data with a 256-bit KEK.
        assertContentEquals(
            hex("28C9F404C4B810F4CBCCB35CFB87F8263F5786E2D80ED326CBC7F0E71A99F43BFB988B9B7A02DD21"),
            SrtCrypto.aesKeyWrap(
                hex("000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F"),
                hex("00112233445566778899AABBCCDDEEFF000102030405060708090A0B0C0D0E0F"),
            ),
        )
    }

    @Test
    fun pbkdf2HmacSha1Rfc6070Vector() {
        assertContentEquals(
            hex("4b007901b765489abead49d926f721d065a429c1"),
            SrtCrypto.pbkdf2("password", "salt".toByteArray(), 20, iterations = 4096),
        )
    }

    @Test
    fun counterIvXorsPacketIndexIntoSaltBytes10To13() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val iv = SrtCrypto.counterIv(salt, 0x0102_0304L)
        assertContentEquals(salt.copyOf(10), iv.copyOf(10))
        assertContentEquals(intArrayOf(11 xor 1, 12 xor 2, 13 xor 3, 14 xor 4).map { it.toByte() }.toByteArray(), iv.copyOfRange(10, 14))
        assertContentEquals(byteArrayOf(0, 0), iv.copyOfRange(14, 16)) // Block counter starts at 0.
    }

    @Test
    fun keyingMaterialLayout() {
        val crypto = SrtCrypto("correct horse battery", keyLength = 32)
        val km = crypto.keyingMaterial
        assertEquals(16 + 16 + 32 + 8, km.size)
        assertContentEquals(hex("12202901000000000200020000000408"), km.copyOf(16))
        assertEquals(4, crypto.handshakeEncryptionField)
    }

    @Test
    fun rejectsInvalidParameters() {
        assertFailsWith<IllegalArgumentException> { SrtCrypto("short") }
        assertFailsWith<IllegalArgumentException> { SrtCrypto("long enough passphrase", keyLength = 20) }
    }

    @Test
    fun encryptionIsAesCtrWithTheWrappedKey() {
        // Deterministic randomness: the first 16 bytes are the salt, the next 16 the stream key.
        val random = object : SecureRandom() {
            private var counter = 0

            override fun nextBytes(bytes: ByteArray) {
                for (i in bytes.indices) {
                    bytes[i] = (counter++).toByte()
                }
            }
        }
        val crypto = SrtCrypto("correct horse battery", random = random)
        val salt = ByteArray(16) { it.toByte() }
        val streamKey = ByteArray(16) { (16 + it).toByte() }
        val plaintext = ByteArray(1316) { (it * 7).toByte() }
        val data = plaintext.copyOf()
        crypto.encrypt(data, 0, data.size, 4242)
        val expected = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(streamKey, "AES"), IvParameterSpec(SrtCrypto.counterIv(salt, 4242)))
            doFinal(plaintext)
        }
        assertContentEquals(expected, data)
        // The wrapped key in the KM message is the stream key wrapped with the PBKDF2 KEK.
        val kek = SrtCrypto.pbkdf2("correct horse battery", salt.copyOfRange(8, 16), 16)
        assertContentEquals(SrtCrypto.aesKeyWrap(kek, streamKey), crypto.keyingMaterial.copyOfRange(32, 32 + 24))
    }
}

class SrtSenderEncryptionTest {
    private class Recorder : SrtSender.Listener {
        val packets = ArrayList<ByteArray>()
        var connected = 0
        var disconnected = 0

        override fun onConnected() {
            connected++
        }

        override fun onDisconnected() {
            disconnected++
        }

        override fun onOutput(packet: ByteArray) {
            packets.add(packet)
        }
    }

    private val inductionResponse = hex(
        "80000000000000000000000000000000000000040000000200000fe6000005dc" +
            "00002000000000012ab1f77c000000000100007f000000000000000000000000",
    )

    /** Conclusion response header without extensions; [extensions] are appended. */
    private fun conclusionResponse(extensions: ByteArray) = hex(
        "800000000000000000000000000000000000000500000005000000000000" +
            "05dc00002000ffffffff2ab1f77c000000000100007f0000000000000000" +
            "00000000",
    ) + extensions

    private fun kmrsp(content: ByteArray): ByteArray =
        byteArrayOf(0, SrtCrypto.EXT_KMRSP.toByte(), 0, (content.size / 4).toByte()) + content

    private fun connect(crypto: SrtCrypto, response: (ByteArray) -> ByteArray): Triple<ManualSerialExecutor, Recorder, SrtSender> {
        val executor = ManualSerialExecutor()
        val recorder = Recorder()
        val sender = SrtSender("enc", 2000, false, executor, executor.clock, recorder, crypto)
        sender.start()
        executor.runPending()
        sender.input(inductionResponse)
        val conclusion = recorder.packets.last()
        sender.input(conclusionResponse(response(conclusion)))
        return Triple(executor, recorder, sender)
    }

    @Test
    fun conclusionCarriesKmreqAndPayloadsAreEncrypted() {
        val crypto = SrtCrypto("correct horse battery")
        val (executor, recorder, sender) = connect(crypto) { kmrsp(crypto.keyingMaterial) }
        val conclusion = recorder.packets[1]
        assertEquals(2, conclusion.getUInt16Be(20)) // Encryption field: AES-128.
        assertEquals(7, conclusion.getUInt16Be(22)) // HSREQ | KMREQ | CONFIG.
        val kmOffset = conclusion.size - crypto.keyingMaterial.size
        assertEquals(SrtCrypto.EXT_KMREQ, conclusion.getUInt16Be(kmOffset - 4))
        assertContentEquals(crypto.keyingMaterial, conclusion.copyOfRange(kmOffset, conclusion.size))
        assertEquals(1, recorder.connected)

        recorder.packets.clear()
        executor.advanceMillis(10)
        val plaintext = ByteArray(1316) { if (it % 188 == 0) 0x47 else 0x11 }
        sender.enqueue(sender.newDataPacket(plaintext), executor.clock.nanos)
        sender.send(executor.clock.nanos)
        val packet = recorder.packets.single()
        assertEquals(0xE800_0001L, packet.getUInt32Be(4)) // KK = even key.
        assertFalse(packet.copyOfRange(16, packet.size).contentEquals(plaintext))
        // Retransmission resends identical ciphertext (only the R bit changes).
        val sequenceNumber = packet.getUInt32Be(0)
        recorder.packets.clear()
        executor.advanceMillis(10)
        val nak = ByteArray(20)
        nak.setUInt16Be(0x8000 or SrtPacketType.NAK)
        nak.setUInt32Be(sequenceNumber, 16)
        sender.input(nak)
        val retransmitted = recorder.packets.single()
        assertEquals(0xEC00_0001L, retransmitted.getUInt32Be(4))
        assertContentEquals(packet.copyOfRange(16, packet.size), retransmitted.copyOfRange(16, retransmitted.size))
    }

    @Test
    fun wrongPassphraseStatusDisconnectsWithReason() {
        val crypto = SrtCrypto("correct horse battery")
        val (_, recorder, sender) = connect(crypto) { kmrsp(byteArrayOf(0, 0, 0, 4)) }
        assertEquals(0, recorder.connected)
        assertEquals(1, recorder.disconnected)
        assertTrue("wrong passphrase" in sender.disconnectReason!!)
    }

    @Test
    fun missingKmrspDisconnects() {
        val crypto = SrtCrypto("correct horse battery")
        val (_, recorder, sender) = connect(crypto) { ByteArray(0) }
        assertEquals(1, recorder.disconnected)
        assertTrue("did not accept" in sender.disconnectReason!!)
    }

    @Test
    fun handshakeRejectionIsReported() {
        val executor = ManualSerialExecutor()
        val recorder = Recorder()
        val sender = SrtSender("enc", 2000, false, executor, executor.clock, recorder, SrtCrypto("correct horse battery"))
        sender.start()
        executor.runPending()
        sender.input(inductionResponse)
        val rejection = conclusionResponse(ByteArray(0))
        rejection.setUInt32Be(1010, 36) // Handshake type: rejection SRT_REJ_BADSECRET.
        sender.input(rejection)
        assertEquals(1, recorder.disconnected)
        assertEquals("SRT rejected: wrong passphrase", sender.disconnectReason)
    }
}
