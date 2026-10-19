package io.github.mesmerize08.nexusirl.core.secrets

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AeadSecretStoreTest {
    private fun newAead(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun tempFile(): File = Files.createTempDirectory("secrets").resolve("secrets.properties").toFile()

    @Test
    fun persistsEncryptedAndReloads() {
        val file = tempFile()
        val aead = newAead()
        val ref = AeadSecretStore(file, aead).put("live_123456_AbCdEf")
        assertFalse("live_123456_AbCdEf" in file.readText(), "plaintext secret written to disk")
        assertEquals("live_123456_AbCdEf", AeadSecretStore(file, aead).get(ref))
    }

    @Test
    fun wrongKeyCannotReadSecrets() {
        val file = tempFile()
        val ref = AeadSecretStore(file, newAead()).put("hunter2hunter2")
        assertNull(AeadSecretStore(file, newAead()).get(ref))
    }

    @Test
    fun removeDeletesFromDisk() {
        val file = tempFile()
        val aead = newAead()
        val store = AeadSecretStore(file, aead)
        val ref = store.put("value1234")
        store.remove(ref)
        assertNull(AeadSecretStore(file, aead).get(ref))
    }

    @Test
    fun placeholdersResolveInMemoryOnly() {
        val store = InMemorySecretStore()
        val ref = store.put("live_key")
        val stored = "rtmp://live.twitch.tv/app/${ref.placeholder}"
        assertEquals(listOf(ref), SecretRef.findAll(stored))
        assertEquals("rtmp://live.twitch.tv/app/live_key", SecretRef.resolve(stored, store))
        store.remove(ref)
        assertFailsWith<MissingSecretException> { SecretRef.resolve(stored, store) }
    }
}
