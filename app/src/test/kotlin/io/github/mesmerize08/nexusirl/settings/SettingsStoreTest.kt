package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.secrets.InMemorySecretStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStoreTest {
    @TempDir
    lateinit var directory: File

    private val key = "live_123456_abcdefghijklmnop"
    private val url = "rtmp://live.twitch.tv/app/$key"

    @Test
    fun streamKeysNeverReachTheSettingsFile() {
        val secrets = InMemorySecretStore()
        val file = File(directory, "settings.json")
        val store = SettingsStore(file, secrets)
        store.update { it.addStream(SettingsStream(name = "Twitch", url = url)) }

        val onDisk = file.readText()
        assertFalse(key in onDisk, onDisk)
        assertTrue("rtmp://live.twitch.tv{secret:" in onDisk, onDisk)
        assertEquals(url, store.resolveUrl(store.current.selectedStream!!))

        // A fresh store reads the same settings back and still resolves the key.
        val reloaded = SettingsStore(file, secrets)
        assertEquals(url, reloaded.resolveUrl(reloaded.current.selectedStream!!))
    }

    @Test
    fun importMovesSecretsOutOfTheDocument() {
        val secrets = InMemorySecretStore()
        val file = File(directory, "settings.json")
        val store = SettingsStore(file, secrets)
        store.import(
            """{"streams":[{"name":"IRL","enabled":true,"url":"srt://example.com:4000?streamid=$key"}],"scenes":[{"name":"Back"}]}""",
        )

        assertFalse(key in file.readText())
        assertTrue("\"scenes\":[{\"name\":\"Back\"}]" in file.readText(), "iOS-only settings are kept")
        assertEquals("srt://example.com:4000?streamid=$key", store.resolveUrl(store.current.selectedStream!!))
    }

    @Test
    fun exportWithoutSecretsHasNoKeys() {
        val store = SettingsStore(File(directory, "settings.json"), InMemorySecretStore())
        store.update { it.addStream(SettingsStream(name = "Twitch", url = url)) }

        val withoutSecrets = store.exportJson()
        assertFalse(key in withoutSecrets)
        assertFalse("{secret:" in withoutSecrets, "placeholders are Android-only and must not leak into exports")
        assertTrue("\"url\":\"rtmp://live.twitch.tv\"" in withoutSecrets, withoutSecrets)
        assertTrue(key in store.exportJson(includeSecrets = true))
    }

    @Test
    fun secretsOfRemovedOrChangedStreamsAreDeleted() {
        val secrets = InMemorySecretStore()
        val store = SettingsStore(File(directory, "settings.json"), secrets)
        store.update { it.addStream(SettingsStream(name = "A", url = url)) }
        val stream = store.current.streams.single()
        store.update { it.updateStream(stream.copy(url = "rtmp://live.twitch.tv/app/other_key_0987654321")) }
        assertEquals(listOf("/app/other_key_0987654321"), secrets.values().toList())
        store.update { it.removeStream(stream.id) }
        assertTrue(secrets.values().isEmpty())
    }

    @Test
    fun brokenFileFallsBackToDefaultsAndIsKept() {
        val file = File(directory, "settings.json").apply { writeText("{not json") }
        val store = SettingsStore(file, InMemorySecretStore())
        assertTrue(store.current.streams.isEmpty())
        assertTrue(File(directory, "settings.json.broken").exists())
    }
}
