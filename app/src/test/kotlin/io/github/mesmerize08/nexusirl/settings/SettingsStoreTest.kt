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
    fun importAddsMissingQuickButtonsAllOff() {
        val store = SettingsStore(File(directory, "settings.json"), InMemorySecretStore())
        // An export without quick buttons (like the system-test harness sends), and one with mute on.
        store.import("""{"chat":{"botEnabled":true}}""")
        assertTrue(store.current.quickButtons.any { it.type == QuickButtonType.MUTE })
        store.import("""{"quickButtons":[{"name":"Mute","type":"Mute","isOn":true}]}""")
        assertFalse(store.current.quickButtons.single { it.type == QuickButtonType.MUTE }.isOn)
        assertTrue(store.current.quickButtons.any { it.type == QuickButtonType.TORCH })
    }

    @Test
    fun discordWebhooksAndSnapshotSourceAreSecret() {
        val file = File(directory, "settings.json")
        val store = SettingsStore(file, InMemorySecretStore())
        val webhook = "https://discord.com/api/webhooks/123/$key"
        val remote = "http://192.168.1.10/?t=$key"
        store.import(
            """{"streams":[{"name":"IRL","discordSnapshotWebhook":"$webhook","discordChatBotSnapshotWebhook":"$webhook"}],
               "chat":{"botCommandSnapshot":{"source":"Web remote control","webRemoteControlUrl":"$remote"}}}""",
        )
        assertFalse(key in file.readText(), file.readText())
        val stream = store.current.streams.single()
        assertEquals(webhook, store.resolve(stream.discordSnapshotWebhook))
        assertEquals(webhook, store.resolve(stream.discordChatBotSnapshotWebhook))
        assertEquals(remote, store.resolve(store.current.chat.botCommandSnapshot.webRemoteControlUrl))
        assertFalse(key in store.exportJson())
        assertTrue("https://discord.com\"" in store.exportJson())
        assertTrue(webhook in store.exportJson(includeSecrets = true))
        assertTrue(remote in store.exportJson(includeSecrets = true))
    }

    @Test
    fun kickSessionIsSecretAndNeverExported() {
        val file = File(directory, "settings.json")
        val secrets = InMemorySecretStore()
        val store = SettingsStore(file, secrets)
        // As an iOS export with a Kick sign-in would have it (upstream moves it to the Keychain).
        store.import("""{"streams":[{"name":"IRL","kickChannelName":"some_user","kickAccessToken":"$key","kickLoggedIn":true}]}""")
        assertFalse(key in file.readText(), file.readText())
        val stream = store.current.streams.single()
        assertEquals(key, store.kickSecret(stream))
        assertTrue(stream.kickLoggedIn)
        // Not even an export with keys carries the sign-in: the other device signs in itself.
        assertFalse(key in store.exportJson(includeSecrets = true))
        assertFalse("\"kickLoggedIn\":true" in store.exportJson(includeSecrets = true))
        store.update { it.updateStream(it.streams.single().copy(kickAccessToken = "", kickLoggedIn = false)) }
        // Signing out deletes the session from the secret store.
        assertFalse(key in secrets.values())
    }

    @Test
    fun multiStreamingKeysAreSecret() {
        val file = File(directory, "settings.json")
        val store = SettingsStore(file, InMemorySecretStore())
        val destination = "rtmp://a.rtmp.youtube.com/live2/$key"
        store.import(
            """{"streams":[{"name":"IRL","url":"$url",
               "multiStreaming":{"destinations":[{"name":"YouTube","url":"$destination","enabled":true}]}}]}""",
        )
        assertFalse(key in file.readText(), file.readText())
        val saved = store.current.streams.single().multiStreaming.destinations.single()
        assertEquals(listOf("YouTube", true), listOf(saved.name, saved.enabled))
        assertEquals(destination, store.resolve(saved.url))
        assertFalse(key in store.exportJson())
        assertTrue(destination in store.exportJson(includeSecrets = true))
        // Removing the destination removes its key from the secret store.
        store.update { it.updateStream(it.streams.single().copy(multiStreaming = SettingsStreamMultiStreaming())) }
        assertFalse(key in File(directory, "settings.json").readText())
    }

    @Test
    fun remoteControlPasswordIsSecret() {
        val secrets = InMemorySecretStore()
        val file = File(directory, "settings.json")
        val store = SettingsStore(file, secrets)
        // An iOS import with a plaintext password.
        store.import(
            """{"remoteControl":{"password":"$key","server":{"enabled":true,"url":"ws://10.0.0.2:2345"},
                "selectedStreamer":"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11"}}""",
        )
        val onDisk = file.readText()
        assertFalse(key in onDisk, onDisk)
        assertEquals(key, store.remoteControlPassword())
        assertEquals("ws://10.0.0.2:2345", store.current.remoteControl.streamer.url)
        assertTrue("\"selectedStreamer\":\"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11\"" in onDisk, "iOS-only remote control settings are kept")
        assertFalse(key in store.exportJson())
        assertTrue(key in store.exportJson(includeSecrets = true))
        // A fresh install gets a strong random password, also kept out of the file.
        val fresh = SettingsStore(File(directory, "fresh.json"), InMemorySecretStore())
        fresh.update { it }
        assertEquals(20, fresh.remoteControlPassword().length)
        assertFalse(fresh.remoteControlPassword() in File(directory, "fresh.json").readText())
    }

    @Test
    fun remoteControlRelayPathIsSecret() {
        val file = File(directory, "settings.json")
        val store = SettingsStore(file, InMemorySecretStore())
        val bridge = "5f1d2c3b-9a8e-4f70-8c61-2b3a4d5e6f70"
        val url = "wss://relay.example.org/streamer/$bridge"
        store.update { it.copy(remoteControl = it.remoteControl.copy(streamer = it.remoteControl.streamer.copy(url = url))) }
        assertFalse(bridge in file.readText())
        assertEquals(url, store.remoteControlUrl())
        assertFalse(bridge in store.exportJson())
        assertTrue("wss://relay.example.org" in store.exportJson())
        assertTrue(url in store.exportJson(includeSecrets = true))
        assertFalse(bridge in store.redactor().redact("connecting to $url"))
        // A file from before the URL became secret is fixed on load.
        val old = File(directory, "old.json")
        old.writeText("""{"remoteControl":{"streamer":{"enabled":true,"url":"$url"}}}""")
        val reopened = SettingsStore(old, InMemorySecretStore())
        assertFalse(bridge in old.readText())
        assertEquals(url, reopened.remoteControlUrl())
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
    fun webControlTokenStaysOnDevice() {
        val file = File(directory, "settings.json")
        val store = SettingsStore(file, InMemorySecretStore())
        val token = SettingsRemoteControlWeb.generateToken()
        store.update { it.copy(remoteControl = it.remoteControl.copy(web = SettingsRemoteControlWeb(enabled = true, token = token))) }
        assertEquals(32, token.length)
        assertFalse(token in file.readText())
        assertEquals(token, store.webControlToken())
        assertEquals(8080, store.current.remoteControl.web.androidPort, "upstream's port 80 is not allowed on Android")
        // Not exported even with secrets: it is not part of upstream's settings.
        assertFalse(token in store.exportJson(includeSecrets = true))
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
