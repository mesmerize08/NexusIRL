package io.github.mesmerize08.nexusirl.settings

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncryptedSettingsTest {
    private val archive = SettingsArchive.write("""{"streams":[{"name":"Twitch","url":"rtmp://live.example.com/app/key"}]}""")
    private val passphrase = "correct horse battery".toCharArray()

    // Few iterations keep the tests fast; the format stores the count.
    private fun encrypt() = EncryptedSettings.encrypt(archive, passphrase, iterations = 1_000)

    @Test
    fun roundTrip() {
        val encrypted = encrypt()
        assertTrue(EncryptedSettings.isEncrypted(encrypted))
        assertFalse(EncryptedSettings.isEncrypted(archive))
        assertFalse("rtmp://live.example.com" in encrypted.toString(Charsets.ISO_8859_1), "no plain text")
        assertContentEquals(archive, EncryptedSettings.decrypt(encrypted, passphrase))
        assertTrue("Twitch" in SettingsArchive.readSettingsJson(EncryptedSettings.decrypt(encrypted, passphrase)))
    }

    @Test
    fun wrongPassphraseOrTamperingFails() {
        val encrypted = encrypt()
        assertFailsWith<EncryptedSettings.DecryptionException> { EncryptedSettings.decrypt(encrypted, "wrong passphrase!".toCharArray()) }
        // Flipping a bit in the salt (header) or the ciphertext is detected.
        for (index in listOf(10, encrypted.size - 1)) {
            val tampered = encrypted.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertFailsWith<EncryptedSettings.DecryptionException> { EncryptedSettings.decrypt(tampered, passphrase) }
        }
    }

    @Test
    fun refusesHugeIterationCounts() {
        val encrypted = encrypt()
        // Iterations are bytes 5..8: set them to Int.MAX_VALUE.
        val hostile = encrypted.copyOf().also { for (i in 5..8) it[i] = if (i == 5) 0x7F else 0xFF.toByte() }
        assertFailsWith<EncryptedSettings.DecryptionException> { EncryptedSettings.decrypt(hostile, passphrase) }
    }

    @Test
    fun shortPassphrasesAreRefused() {
        assertFailsWith<IllegalArgumentException> { EncryptedSettings.encrypt(archive, "short".toCharArray()) }
    }

    @Test
    fun defaultIterationsAreTheRecommendedCount() {
        assertTrue(EncryptedSettings.ITERATIONS >= 600_000)
    }
}
