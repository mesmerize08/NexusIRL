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

    /** Who may import settings remotely is decided on this phone only: imports and exports never change it. */
    @Test
    fun allowRemoteImportStaysOnDevice() {
        val store = SettingsStore(File(directory, "settings.json"), InMemorySecretStore())
        assertFalse(store.current.remoteControl.streamer.allowSettingsImport, "off by default")
        store.import("""{"remoteControl":{"server":{"nexusirlAllowSettingsImport":true}}}""")
        assertFalse(store.current.remoteControl.streamer.allowSettingsImport)

        store.update {
            it.copy(remoteControl = it.remoteControl.copy(streamer = it.remoteControl.streamer.copy(allowSettingsImport = true)))
        }
        assertFalse("nexusirlAllowSettingsImport\":true" in store.exportJson(includeSecrets = true))
        store.import("{}")
        assertTrue(store.current.remoteControl.streamer.allowSettingsImport, "an import keeps this phone's choice")
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
    fun obsPasswordAndUrlAreSecret() {
        val file = File(directory, "settings.json")
        val store = SettingsStore(file, InMemorySecretStore())
        val obsUrl = "ws://user:$key@10.0.0.5:4455"
        store.update {
            it.addStream(SettingsStream(name = "S", obsWebSocketEnabled = true, obsWebSocketUrl = obsUrl, obsWebSocketPassword = key))
        }
        assertFalse(key in file.readText())
        val stream = store.current.selectedStream!!
        assertEquals(key, store.obsWebSocketPassword(stream))
        assertEquals(obsUrl, store.obsWebSocketUrl(stream))
        assertFalse(key in store.exportJson())
        assertTrue("\"obsWebSocketUrl\":\"ws://10.0.0.5:4455\"" in store.exportJson())
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

    /** Every credential in upstream's settings, each with its own marker, in modelled fields and in `extra`. */
    private val credentials = listOf(
        "obs-pass", "obs-url-pass", "rtirl-push", "golive-hook", "osp-key", "chat-ai", "chat-tts", "alert-ai", "alert-tts",
        "browser-token", "tesla-key", "cloud-key", "wifi-pass", "srtla-relay-pass", "dji-wifi", "dji-rtmp", "gopro-wifi",
        "gopro-server", "gopro-custom", "rtmp-ingest", "whip-ingest", "srtla-ingest", "srt-ingest", "rist-ingest",
        "rtsp-ingest", "whep-ingest", "deeplink-url", "deeplink-obs", "saved-url", "client-bridge", "streamer-bridge",
        "map-style",
    ).associateWith { "fake-$it-aaaa" }

    private fun marker(name: String) = credentials.getValue(name)

    // Shaped like an iOS Moblin export (upstream Various/Settings/*.swift keys).
    private val iosSettings by lazy {
        """{"streams":[{"name":"IRL","url":"rtmp://live.example.com/app/$key",
             "obsWebSocketUrl":"ws://obs:${marker("obs-url-pass")}@10.0.0.5:4455","obsWebSocketPassword":"${marker("obs-pass")}",
             "realtimeIrlBaseUrl":"https://rtirl.com/api","realtimeIrlPushKey":"${marker("rtirl-push")}",
             "goLiveNotificationDiscordWebhookUrl":"https://discord.com/api/webhooks/1/${marker("golive-hook")}",
             "openStreamingPlatformUrl":"rtmp://osp.example.com/live/${marker("osp-key")}",
             "discordSnapshotWebhookOnlyWhenLive":true,"twitchChannelName":"benign_channel"}],
           "chat":{"botCommandAi":{"baseUrl":"https://generativelanguage.googleapis.com/v1beta/openai","apiKey":"${marker("chat-ai")}"},
             "ttsMonster":{"apiToken":"${marker("chat-tts")}"}},
           "widgets":[
             {"name":"Alerts","type":"Alerts","alerts":{"ai":{"apiKey":"${marker("alert-ai")}"},
               "ttsMonster":{"apiToken":"${marker("alert-tts")}"}}},
             {"name":"Overlay","type":"Browser",
               "browser":{"url":"https://overlay.example.com/w?token=${marker("browser-token")}","width":500}},
             {"name":"Map","type":"Map","map":{"styleUrl":"https://maps.example.com/style.json?key=${marker("map-style")}"}}],
           "tesla":{"vin":"BENIGNVIN123","privateKey":"${marker("tesla-key")}"},
           "moblinCloud":{"enabled":true,"key":"${marker("cloud-key")}"},
           "keyboard":{"keys":[{"key":"F5","press":"benign"}]},
           "savedWifiNetworks":[{"ssid":"BenignHome","password":"${marker("wifi-pass")}"}],
           "srtlaRelay":{"password":"${marker("srtla-relay-pass")}"},
           "djiDevices":{"devices":[{"wifiSsid":"BenignDji","wifiPassword":"${marker("dji-wifi")}",
             "customRtmpUrl":"rtmp://192.168.1.2:1935/live/${marker("dji-rtmp")}"}]},
           "goPro":{"wifiCredentials":[{"name":"Benign","ssid":"BenignGoPro","password":"${marker("gopro-wifi")}"}],
             "rtmpUrls":[{"name":"Benign","serverUrl":"rtmp://1.2.3.4/live/${marker("gopro-server")}",
               "customUrl":"rtmp://1.2.3.4/live/${marker("gopro-custom")}"}],
             "selectedRtmpUrl":"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A12"},
           "rtmpServer":{"enabled":true,"port":1935,"streams":[{"name":"Cam","streamKey":"${marker("rtmp-ingest")}","latency":2000}]},
           "whipServer":{"streams":[{"name":"Cam","streamKey":"${marker("whip-ingest")}"}]},
           "srtlaServer":{"streams":[{"name":"Cam","streamId":"${marker("srtla-ingest")}"}]},
           "srtClient":{"streams":[{"name":"Cam","url":"srt://10.0.0.9:4000?streamid=${marker("srt-ingest")}"}]},
           "ristServer":{"streams":[{"name":"Cam","url":"rist://10.0.0.9:6000?secret=${marker("rist-ingest")}"}]},
           "rtspClient":{"streams":[{"name":"Cam","url":"rtsp://user:${marker("rtsp-ingest")}@10.0.0.9:554/live"}]},
           "whepClient":{"streams":[{"name":"Cam","url":"https://whep.example.com/play/${marker("whep-ingest")}"}]},
           "deepLinkCreator":{"streams":[{"name":"Link","url":"srt://example.com:4000?streamid=${marker("deeplink-url")}",
             "obs":{"webSocketUrl":"ws://10.0.0.5:4455","webSocketPassword":"${marker("deeplink-obs")}"}}]},
           "remoteControl":{"password":"$key","server":{"enabled":true,"url":"ws://10.0.0.2:2345",
               "savedUrls":[{"name":"Home","url":"wss://relay.example.org/streamer/${marker("saved-url")}"}]},
             "client":{"relay":{"baseUrl":"wss://relay.example.net/remote-control-relay","bridgeId":"${marker("client-bridge")}"}},
             "streamers":[{"name":"Phone","relay":{"bridgeId":"${marker("streamer-bridge")}"}}]},
           "moblink":{"client":{"enabled":true,"url":"ws://10.0.0.3:7777/benign-path"}},
           "alertsTokenCount":5,"benignNested":{"list":["one","two"],"flag":false}}"""
    }

    /** Values that look like credentials by name or shape but are not, and must stay exactly as they are. */
    private val benign = listOf(
        "\"twitchChannelName\":\"benign_channel\"",
        "\"baseUrl\":\"https://generativelanguage.googleapis.com/v1beta/openai\"",
        "\"realtimeIrlBaseUrl\":\"https://rtirl.com/api\"",
        "\"baseUrl\":\"wss://relay.example.net/remote-control-relay\"",
        "\"vin\":\"BENIGNVIN123\"",
        "\"key\":\"F5\"",
        "\"ssid\":\"BenignHome\"",
        "\"selectedRtmpUrl\":\"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A12\"",
        "\"discordSnapshotWebhookOnlyWhenLive\":true",
        "\"alertsTokenCount\":5",
        "\"benignNested\":{\"list\":[\"one\",\"two\"],\"flag\":false}",
        "\"url\":\"ws://10.0.0.3:7777/benign-path\"",
    )

    @Test
    fun everyUpstreamCredentialIsProtectedOnDiskAndInExports() {
        val file = File(directory, "settings.json")
        val store = SettingsStore(file, InMemorySecretStore())
        store.import(iosSettings)

        val onDisk = file.readText()
        val withoutKeys = store.exportJson()
        val withKeys = store.exportJson(includeSecrets = true)
        for ((name, value) in credentials) {
            assertFalse(value in onDisk, "$name is in settings.json")
            assertFalse(value in withoutKeys, "$name is in the export without keys")
            assertTrue(value in withKeys, "$name is missing from the export with keys")
        }
        assertFalse("{secret:" in withoutKeys, withoutKeys)
        assertFalse("{secret:" in withKeys, withKeys)
        for (value in benign) {
            assertTrue(value in onDisk, "$value changed in settings.json: $onDisk")
            assertTrue(value in withoutKeys, "$value changed in the export without keys: $withoutKeys")
            assertTrue(value in withKeys, "$value changed in the export with keys")
        }
        // URLs keep their public part, as stream URLs do.
        assertTrue("\"customRtmpUrl\":\"rtmp://192.168.1.2:1935\"" in withoutKeys, withoutKeys)
        assertTrue("\"obsWebSocketUrl\":\"ws://10.0.0.5:4455\"" in withoutKeys, withoutKeys)
        assertTrue("\"obsWebSocketUrl\":\"ws://obs:${marker("obs-url-pass")}@10.0.0.5:4455\"" in withKeys, withKeys)
        assertTrue("\"url\":\"rtsp://user:${marker("rtsp-ingest")}@10.0.0.9:554/live\"" in withKeys, withKeys)
        // Diagnostics mask every stored secret, so these too, even outside a URL or JSON.
        val whole = listOf(
            "obs-pass", "rtirl-push", "chat-ai", "chat-tts", "alert-ai", "alert-tts", "tesla-key", "cloud-key", "wifi-pass",
            "srtla-relay-pass", "dji-wifi", "gopro-wifi", "rtmp-ingest", "whip-ingest", "srtla-ingest", "deeplink-obs",
            "client-bridge", "streamer-bridge",
        )
        val log = store.redactor().redact(whole.joinToString(" ") { marker(it) })
        for (name in whole) {
            assertFalse(marker(name) in log, "$name is not redacted")
        }
    }

    @Test
    fun protectedWidgetUrlsResolveForUse() {
        val store = SettingsStore(File(directory, "settings.json"), InMemorySecretStore())
        store.import(iosSettings)
        val browser = store.current.widgets.single { it.type == WidgetType.BROWSER }.browser
        assertEquals("https://overlay.example.com/w?token=${marker("browser-token")}", store.resolveOrPublicPart(browser.url))
        val map = store.current.widgets.single { it.type == WidgetType.MAP }.map
        assertEquals("https://maps.example.com/style.json?key=${marker("map-style")}", store.resolveOrPublicPart(map.styleUrl))
        assertEquals("ws://10.0.0.3:7777/benign-path", store.current.moblink.relay.url, "the Moblink relay URL is used as is")
    }

    @Test
    fun secretsReferencedFromExtraAreKept() {
        val secrets = InMemorySecretStore()
        val file = File(directory, "settings.json")
        val store = SettingsStore(file, secrets)
        store.import(iosSettings)
        store.update { it.copy(tapToFocus = !it.tapToFocus) }
        val withKeys = SettingsStore(file, secrets).exportJson(includeSecrets = true)
        for ((name, value) in credentials) {
            assertTrue(value in withKeys, "$name was lost")
        }
    }

    @Test
    fun plainCredentialsFromOlderVersionsAreProtectedOnLoad() {
        val file = File(directory, "settings.json").apply { writeText(iosSettings) }
        val store = SettingsStore(file, InMemorySecretStore())
        val onDisk = file.readText()
        for ((name, value) in credentials) {
            assertFalse(value in onDisk, "$name is still in settings.json")
        }
        val withKeys = store.exportJson(includeSecrets = true)
        for ((name, value) in credentials) {
            assertTrue(value in withKeys, "$name was lost")
        }
    }

    @Test
    fun brokenFileFallsBackToDefaultsAndIsKept() {
        val file = File(directory, "settings.json").apply { writeText("{not json") }
        val store = SettingsStore(file, InMemorySecretStore())
        assertTrue(store.current.streams.isEmpty())
        assertTrue(File(directory, "settings.json.broken").exists())
    }
}
