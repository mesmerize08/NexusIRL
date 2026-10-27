package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SwiftJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {
    // Shaped like an iOS export (upstream Settings.swift / SettingsStream.swift key names), with
    // iOS-only settings Android does not model.
    private val iosJson = """
        {
          "streams": [
            {
              "name": "Twitch", "id": "0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11", "enabled": false,
              "url": "rtmp://live.twitch.tv/app/live_123_abc",
              "twitchChannelName": "someone", "obsWebSocketUrl": "ws://10.0.0.2:4455",
              "resolution": "1280x720", "fps": 60, "bitrate": 6000000, "bitrateRateControl": "CBR",
              "codec": "H.264/AVC", "rtmp": {"adaptiveBitrateEnabled": false}, "recording": {"videoCodec": "H.265/HEVC"}
            },
            {
              "name": "IRL", "id": "5E0F7D6C-2B1A-4C3D-8E9F-0A1B2C3D4E5F", "enabled": true,
              "url": "srtla://example.com:5000?streamid=publish/live/key&passphrase=0123456789",
              "codec": "H.265/HEVC",
              "srt": {"latency": 2000, "adaptiveBitrateEnabled": true, "implementation": "Moblin",
                      "adaptiveBitrate": {"algorithm": {"fastIrl": {}}, "fastIrlSettings": {"packetsInFlight": 150, "minimumBitrate": 500}},
                      "connectionPriorities": {"enabled": true, "priorities": []}}
            }
          ],
          "scenes": [{"name": "Back", "id": "8C7D4E5F-1A2B-4C3D-9E8F-7A6B5C4D3E2F"}],
          "remoteControl": {"password": "hunter2-not-real"},
          "tapToFocus": true,
          "unknownFutureSetting": [1, 2.5, "x"]
        }
    """.trimIndent()

    @Test
    fun sceneSwitchTransition() {
        assertEquals(SceneSwitchTransition.BLUR, Database.fromJson("{}").sceneSwitchTransition)
        val database = Database.fromJson("""{"sceneSwitchTransition": "Blur & zoom", "forceSceneSwitchTransition": true}""")
        assertEquals(SceneSwitchTransition.BLUR_AND_ZOOM, database.sceneSwitchTransition)
        assertTrue(database.forceSceneSwitchTransition)
        assertTrue(database.toJson().contains("\"Blur & zoom\""))
        assertEquals(SceneSwitchTransition.BLUR, Database.fromJson("""{"sceneSwitchTransition": "Spin"}""").sceneSwitchTransition)
    }

    @Test
    fun chatSettings() {
        val database = Database.fromJson(
            """{"chat": {"fontSize": 25, "height": 0.5, "usernameColor": {"red": 1, "green": 2, "blue": 3}, "botEnabled": true},
               "streams": [{"twitchChannelName": "a", "kickChannelName": "b", "kickChannelId": "12", "youTubeVideoIds": "x,y",
                            "chat": {"bttvEmotes": true}}]}""",
        )
        assertEquals(25f, database.chat.fontSize)
        assertEquals(0.5, database.chat.height)
        assertEquals(RgbColor(1, 2, 3), database.chat.usernameColor)
        assertTrue(database.chat.enabled)
        val stream = database.streams.single()
        assertEquals(listOf("a", "b", "12"), listOf(stream.twitchChannelName, stream.kickChannelName, stream.kickChannelId))
        assertEquals(null, stream.kickChatroomChannelId)
        assertEquals(listOf("x", "y"), stream.youTubeVideoIdList)
        assertTrue(stream.chat.bttvEmotes)
        val json = SwiftJson.parseToJsonElement(database.toJson()).jsonObject
        assertEquals("true", json["chat"]!!.jsonObject["botEnabled"].toString())
        assertFalse("kickChatroomChannelId" in json["streams"]!!.jsonArray[0].jsonObject)
    }

    @Test
    fun widgetEffects() {
        val lut = UUID.randomUUID()
        val database = Database.fromJson(
            """{"widgets": [{"type": "image", "effects": [
                 {"type": "lut", "enabled": true, "lut": {"lut": "$lut"}},
                 {"type": "lut", "enabled": false, "lut": {"lut": "${UUID.randomUUID()}"}},
                 {"type": "shape", "shape": {"cornerRadius": 0.5}}]}]}""",
        )
        val widget = database.widgets.single()
        assertEquals(listOf(lut), widget.lutEffectIds)
        val effects = SwiftJson.parseToJsonElement(database.toJson()).jsonObject["widgets"]!!.jsonArray[0].jsonObject["effects"]!!.jsonArray
        assertEquals("0.5", effects[2].jsonObject["shape"]!!.jsonObject["cornerRadius"].toString())
    }

    @Test
    fun chatBotSettings() {
        // As the upstream harness's chat suite sets them up, with an iOS-only "migrated" key.
        val database = Database.fromJson(
            """{"chat": {"botEnabled": true,
                         "botCommandPermissions": {"scene": {"moderatorsEnabled": true}, "migrated": true},
                         "aliases": [{"alias": "!screen", "replacement": "!moblin scene Screen"}]}}""",
        )
        val chat = database.chat
        assertTrue(chat.botEnabled)
        assertTrue(chat.botCommandPermissions.scene.moderatorsEnabled)
        assertTrue(chat.botCommandPermissions.audio.moderatorsEnabled)
        assertFalse(chat.botCommandPermissions.audio.othersEnabled)
        assertEquals(null, chat.botCommandPermissions.audio.cooldown)
        assertEquals(listOf(SettingsChatBotAlias("!screen", "!moblin scene Screen")), chat.aliases)
        val json = SwiftJson.parseToJsonElement(database.toJson()).jsonObject["chat"]!!.jsonObject
        assertEquals("true", json["botCommandPermissions"]!!.jsonObject["migrated"].toString())
        assertFalse("cooldown" in json["botCommandPermissions"]!!.jsonObject["audio"]!!.jsonObject)
        // A new install: scene, stream and macro commands are off for moderators, as upstream.
        val defaults = Database.fromJson("{}").chat.botCommandPermissions
        assertEquals(
            listOf(false, false, false, true),
            listOf(defaults.scene, defaults.stream, defaults.macro, defaults.zoom).map {
                it.moderatorsEnabled
            },
        )
    }

    @Test
    fun decodesAnIosExport() {
        val database = Database.fromJson(iosJson)
        assertEquals(2, database.streams.size)
        val twitch = database.streams[0]
        assertEquals(StreamResolution.R1280X720, twitch.resolution)
        assertEquals(60, twitch.fps)
        assertEquals(6_000_000, twitch.bitrate)
        assertEquals(StreamRateControl.CBR, twitch.rateControl)
        assertEquals(StreamCodec.H264_AVC, twitch.codec)
        assertFalse(twitch.rtmp.adaptiveBitrateEnabled)
        val irl = database.selectedStream!!
        assertEquals("IRL", irl.name)
        assertEquals(2000, irl.srt.latency)
        assertEquals(SrtAdaptiveBitrateAlgorithm.FastIrl, irl.srt.adaptiveBitrate.algorithm)
        assertEquals(150, irl.srt.adaptiveBitrate.fastIrlSettings.packetsInFlight)
        assertTrue(database.tapToFocus)
    }

    @Test
    fun remoteControlAndYouTubeUseUpstreamKeys() {
        // Upstream SettingsRemoteControl: "server" is the streamer side, "client" the assistant.
        val ios = """
            {"remoteControl": {"server": {"enabled": true, "url": "ws://192.168.1.5:2345", "reliableChatAndEvents": true},
                               "client": {"enabled": true, "port": 2346}, "hasMigratedAssistant": true},
             "streams": [{"name": "YouTube", "youTubeVideoId": "abc,def"}]}
        """
        val database = Database.fromJson(ios)
        assertTrue(database.remoteControl.streamer.enabled)
        assertEquals("ws://192.168.1.5:2345", database.remoteControl.streamer.url)
        assertEquals(2346, database.remoteControl.assistant.port)
        assertEquals(listOf("abc", "def"), database.streams.single().youTubeVideoIdList)
        val exported = SwiftJson.parseToJsonElement(database.toJson()).jsonObject
        val remoteControl = exported["remoteControl"]!!.jsonObject
        assertEquals(setOf("client", "server", "web", "password", "hasMigratedAssistant"), remoteControl.keys)
        assertEquals("true", remoteControl["server"]!!.jsonObject["reliableChatAndEvents"].toString())
        val stream = exported["streams"]!!.jsonArray.single().jsonObject
        assertEquals("\"abc,def\"", stream["youTubeVideoId"].toString())
        assertFalse("youTubeVideoIds" in stream)
    }

    @Test
    fun settingsWrittenByEarlierAndroidVersionsStillLoad() {
        // Up to 0.2.0 the app wrote "streamer", "assistant" and "youTubeVideoIds".
        val old = """
            {"remoteControl": {"streamer": {"enabled": true, "url": "ws://10.0.0.2:2345"}, "assistant": {"port": 2399}},
             "streams": [{"name": "Old", "youTubeVideoIds": "xyz"}]}
        """
        val database = Database.fromJson(old)
        assertEquals("ws://10.0.0.2:2345", database.remoteControl.streamer.url)
        assertEquals(2399, database.remoteControl.assistant.port)
        assertEquals(listOf("xyz"), database.streams.single().youTubeVideoIdList)
        val exported = database.toJson()
        assertFalse("\"streamer\"" in exported || "\"assistant\"" in exported || "youTubeVideoIds" in exported, exported)
    }

    @Test
    fun iosOnlySettingsSurviveARoundTrip() {
        val original = SwiftJson.parseToJsonElement(iosJson)
        val roundTripped = SwiftJson.parseToJsonElement(Database.fromJson(iosJson).toJson())
        // Every original key and value is still there (Android adds its defaults alongside).
        assertContainsAll(original, roundTripped, "$")
    }

    @Test
    fun editsKeepIosOnlySettings() {
        val database = Database.fromJson(iosJson)
        val edited = database.updateStream(database.streams[0].copy(name = "Twitch main", fps = 30))
        val stream = SwiftJson.parseToJsonElement(edited.toJson()).jsonObject["streams"]!!.jsonArray[0].jsonObject
        assertEquals("\"Twitch main\"", stream["name"].toString())
        assertEquals("30", stream["fps"].toString())
        assertEquals("\"someone\"", stream["twitchChannelName"].toString())
        assertEquals("\"H.265/HEVC\"", stream["recording"]!!.jsonObject["videoCodec"].toString())
    }

    @Test
    fun defaultsMatchUpstream() {
        val stream = Database.fromJson("""{"streams":[{}]}""").streams.single()
        assertEquals("My stream", stream.name)
        assertEquals(DEFAULT_STREAM_URL, stream.url)
        assertEquals(StreamResolution.R1920X1080, stream.resolution)
        assertEquals(30, stream.fps)
        assertEquals(5_000_000, stream.bitrate)
        assertEquals(StreamCodec.H265_HEVC, stream.codec)
        assertEquals(3000, stream.srt.latency)
        assertEquals(SrtAdaptiveBitrateAlgorithm.Belabox, stream.srt.adaptiveBitrate.algorithm)
        assertEquals(2, stream.maxKeyFrameInterval)
        assertEquals(StreamCodec.H265_HEVC, stream.recording.videoCodec)
        assertEquals(0, stream.recording.videoBitrate)
        assertEquals(128_000, stream.recording.audioBitrate)
    }

    @Test
    fun unknownRawValuesFallBackLikeUpstream() {
        val stream = Database.fromJson(
            """{"streams":[{"codec":"AV1","resolution":"7680x4320","srt":{"adaptiveBitrate":{"algorithm":{"quantum":{}}}}}]}""",
        ).streams.single()
        assertEquals(StreamCodec.H264_AVC, stream.codec)
        assertEquals(StreamResolution.R1920X1080, stream.resolution)
        assertEquals(SrtAdaptiveBitrateAlgorithm.Belabox, stream.srt.adaptiveBitrate.algorithm)
    }

    @Test
    fun numbersEncodeLikeSwift() {
        val json = Database(streams = listOf(SettingsStream(id = UUID.fromString("5E0F7D6C-2B1A-4C3D-8E9F-0A1B2C3D4E5F")))).toJson()
        assertTrue("\"minimumBitrate\":250}" in json, json)
        assertTrue("\"rttDiffHighDecreaseFactor\":0.9," in json, json)
        assertTrue("\"id\":\"5E0F7D6C-2B1A-4C3D-8E9F-0A1B2C3D4E5F\"" in json, json)
        assertTrue("\"algorithm\":{\"belabox\":{}}" in json, json)
    }

    @Test
    fun streamSelection() {
        val a = SettingsStream(name = "A")
        val b = SettingsStream(name = "B")
        var database = Database().addStream(a).addStream(b)
        assertEquals("A", database.selectedStream!!.name)
        database = database.selectStream(b.id)
        assertEquals("B", database.selectedStream!!.name)
        database = database.removeStream(b.id)
        assertEquals("A", database.selectedStream!!.name)
        assertTrue(database.streams.single().enabled)
    }

    @Test
    fun streamUrlSecrets() {
        assertEquals("rtmp://live.twitch.tv" to "/app/live_123_abc", StreamUrlSecrets.split("rtmp://live.twitch.tv/app/live_123_abc"))
        assertEquals("srt://example.com:5000", StreamUrlSecrets.display("srt://example.com:5000?streamid=x&passphrase=y"))
        assertEquals("rtmps://host", StreamUrlSecrets.display("rtmps://user:pass@host/app/key"))
        assertEquals("rtmp://host:1935", StreamUrlSecrets.display("rtmp://host:1935{secret:00000000-0000-0000-0000-000000000001}"))
        assertEquals("srt://example.com:4000" to "", StreamUrlSecrets.split("srt://example.com:4000"))
        val stored = mutableListOf<String>()
        val protected = StreamUrlSecrets.protect("rtmp://live.twitch.tv/app/live_123_abc") {
            stored += it
            "{secret:00000000-0000-0000-0000-000000000001}"
        }
        assertEquals("rtmp://live.twitch.tv{secret:00000000-0000-0000-0000-000000000001}", protected)
        assertEquals(listOf("/app/live_123_abc"), stored)
        assertEquals(protected, StreamUrlSecrets.protect(protected) { error("must not store twice") })
        assertTrue(StreamUrlSecrets.isValid("srtla://example.com:5000?streamid=a"))
        assertFalse(StreamUrlSecrets.isValid("http://example.com"))
        assertFalse(StreamUrlSecrets.isValid("srt://"))
    }

    @Test
    fun archiveRoundTripAndPlainJson() {
        val json = Database.fromJson(iosJson).toJson()
        assertEquals(json, SettingsArchive.readSettingsJson(SettingsArchive.write(json)))
        assertEquals("{\"streams\":[]}", SettingsArchive.readSettingsJson(" {\"streams\":[]}\n".toByteArray()))
        assertFailsWith<SettingsArchive.InvalidArchiveException> { SettingsArchive.readSettingsJson("hello".toByteArray()) }
    }

    @Test
    fun scenesAndWidgets() {
        val json = """
            {"scenes":[{"name":"Triple","id":"8C7D4E5F-1A2B-4C3D-9E8F-7A6B5C4D3E2F","cameraPosition":"Back triple",
              "widgets":[{"widgetId":"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11","id":"5E0F7D6C-2B1A-4C3D-8E9F-0A1B2C3D4E5F",
                          "x":2.5,"y":3,"size":30,"alignment":"BottomRight","positioningLock":false}],"fillFrame":true}],
             "widgets":[{"name":"Clock","id":"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11","type":"Text",
                         "text":{"formatString":"{time}","fontSize":40,"backgroundColor":{"red":0,"green":0,"blue":0,"opacity":0.5},"timers":[]}},
                        {"name":"Wheel","type":"Wheel of luck","wheelOfLuck":{"options":[]}}]}
        """.trimIndent()
        val database = Database.fromJson(json)
        val scene = database.scenes.single()
        assertEquals(SceneCamera.BACK, scene.camera)
        assertEquals(WidgetAlignment.BOTTOM_RIGHT, scene.widgets.single().alignment)
        assertEquals(2.5, scene.widgets.single().x)
        assertEquals(40, database.widgets[0].text.fontSize)
        assertEquals(0x80000000.toInt(), database.widgets[0].text.backgroundColor.argb())
        assertEquals(WidgetType.WHEEL_OF_LUCK, database.widgets[1].type)
        // iOS-only values (camera position, wheel of luck, timers, fillFrame) survive a round trip.
        assertContainsAll(SwiftJson.parseToJsonElement(json), SwiftJson.parseToJsonElement(database.toJson()), "$")
    }

    private fun assertContainsAll(
        expected: kotlinx.serialization.json.JsonElement,
        actual: kotlinx.serialization.json.JsonElement,
        path: String,
    ) {
        when (expected) {
            is kotlinx.serialization.json.JsonObject -> {
                val actualObject = actual.jsonObject
                for ((key, value) in expected) {
                    val actualValue = actualObject[key] ?: throw AssertionError("$path.$key missing")
                    assertContainsAll(value, actualValue, "$path.$key")
                }
            }
            is kotlinx.serialization.json.JsonArray -> {
                val actualArray = actual.jsonArray
                assertEquals(expected.size, actualArray.size, "$path length")
                expected.forEachIndexed { index, value -> assertContainsAll(value, actualArray[index], "$path[$index]") }
            }
            else -> assertEquals(
                expected.toString().toDoubleOrNull() ?: expected.toString(),
                actual.toString().toDoubleOrNull() ?: actual.toString(),
                path,
            )
        }
    }
}
