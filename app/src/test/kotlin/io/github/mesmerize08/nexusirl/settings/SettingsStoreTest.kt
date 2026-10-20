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

    // Obviously fake, low-entropy value so secret scanners (gitleaks) do not flag the test.
    private val key = "fakekey-aaaaaaaaaaaa"
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
        assertEquals(listOf("Back"), Database.fromJson(file.readText()).scenes.map { it.name }, "scenes are kept")
        assertEquals("srt://example.com:4000?streamid=$key", store.resolveUrl(store.current.selectedStream!!))
    }

    @Test
    fun remoteControlPasswordIsSecret() {
        val secrets = InMemorySecretStore()
        val file = File(directory, "settings.json")
        val store = SettingsStore(file, secrets)
        // An iOS import with a plaintext password.
        store.import(
            """{"remoteControl":{"password":"$key","streamer":{"enabled":true,"url":"ws://10.0.0.2:2345"},"web":{"enabled":false}}}""",
        )
        val onDisk = file.readText()
        assertFalse(key in onDisk, onDisk)
        assertEquals(key, store.remoteControlPassword())
        assertEquals("ws://10.0.0.2:2345", store.current.remoteControl.streamer.url)
        assertTrue("\"web\":{\"enabled\":false}" in onDisk, "iOS-only remote control settings are kept")
        assertFalse(key in store.exportJson())
        assertTrue(key in store.exportJson(includeSecrets = true))
        // A fresh install gets a strong random password, also kept out of the file.
        val fresh = SettingsStore(File(directory, "fresh.json"), InMemorySecretStore())
        fresh.update { it }
        assertEquals(20, fresh.remoteControlPassword().length)
        assertFalse(fresh.remoteControlPassword() in File(directory, "fresh.json").readText())
    }

    @Test
    fun moblinkPasswordIsSecret() {
        val file = File(directory, "settings.json")
        val store = SettingsStore(file, InMemorySecretStore())
        store.import("""{"moblink":{"password":"$key","client":{"enabled":true,"name":"Relay","url":"//1.2.3.4:5678","manual":true}}}""")
        assertFalse(key in file.readText())
        assertEquals(key, store.moblinkPassword())
        assertEquals("Relay", store.current.moblink.relay.name)
        // Upstream drops relay URLs that are not WebSocket URLs.
        assertEquals("", store.current.moblink.relay.validUrl)
        assertFalse(key in store.exportJson())
        assertTrue(key in store.exportJson(includeSecrets = true))
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
        store.update { it.updateStream(stream.copy(url = "rtmp://live.twitch.tv/app/fakekey-bbbbbbbbbbbb")) }
        val password = store.remoteControlPassword()
        assertEquals(listOf("/app/fakekey-bbbbbbbbbbbb"), secrets.values().filter { it != password })
        store.update { it.removeStream(stream.id) }
        assertEquals(listOf(password), secrets.values().toList())
    }

    @Test
    fun brokenFileFallsBackToDefaultsAndIsKept() {
        val file = File(directory, "settings.json").apply { writeText("{not json") }
        val store = SettingsStore(file, InMemorySecretStore())
        assertTrue(store.current.streams.isEmpty())
        assertTrue(File(directory, "settings.json.broken").exists())
    }
}
