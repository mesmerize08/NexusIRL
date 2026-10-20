package io.github.mesmerize08.nexusirl.settings

import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoblinSettingsUrlTest {
    private val json = """
        {"streams":[{"name":"IRL","url":"srtla://example.com:5000?streamid=fakekey-aaaaaaaa","selected":true,
          "video":{"resolution":"1280x720","fps":60,"bitrate":8000000,"codec":"H.264/AVC","maxKeyFrameInterval":4},
          "audio":{"bitrate":160000},"srt":{"latency":2500,"adaptiveBitrateEnabled":false},
          "twitch":{"channelName":" someone ","channelId":"123"},"obs":{"webSocketUrl":"ws://10.0.0.2:4455","webSocketPassword":"x"}}],
         "remoteControl":{"password":"x"},"moblinCloud":{"enabled":true,"key":"k"}}
    """.trimIndent()

    @Test
    fun parsesEncodedAndRawLinks() {
        val encoded = "moblin://?" + URLEncoder.encode(json, "UTF-8").replace("+", "%20")
        assertEquals(MoblinSettingsUrl.parse("moblin://?$json"), MoblinSettingsUrl.parse(encoded))
        assertEquals("IRL", MoblinSettingsUrl.parse(encoded).streams!!.single().name)
    }

    @Test
    fun rejectsBadLinksLikeUpstream() {
        fun message(link: String) =
            assertFailsWith<MoblinSettingsUrl.InvalidSettingsUrlException> { MoblinSettingsUrl.parse(link) }.message!!
        assertEquals("Custom URL query is missing", message("moblin://"))
        assertEquals("Custom URL path is not empty", message("moblin://foo?{}"))
        assertEquals("Not a moblin:// link", message("https://example.com/?{}"))
        assertTrue(message("moblin://?{not json").startsWith("Malformed settings"))
        assertEquals("A: SRT(LA) port number missing", message("""moblin://?{"streams":[{"name":"A","url":"srt://host"}]}"""))
        assertEquals("A: RTMP stream key missing", message("""moblin://?{"streams":[{"name":"A","url":"rtmp://host/"}]}"""))
        assertEquals("Negative SRT latency", message("""moblin://?{"streams":[{"name":"A","url":"srt://h:1","srt":{"latency":-1}}]}"""))
        assertEquals("A: RIST is not supported on Android yet", message("""moblin://?{"streams":[{"name":"A","url":"rist://h:1"}]}"""))
    }

    @Test
    fun appliesStreamsLikeUpstream() {
        val link = MoblinSettingsUrl.fromJson(json)
        val database = link.apply(Database().addStream(SettingsStream(name = "Old")), replaceCollisions = false)
        assertEquals(listOf("Old", "IRL"), database.streams.map { it.name })
        val irl = database.selectedStream!!
        assertEquals("IRL", irl.name)
        assertEquals(StreamResolution.R1280X720, irl.resolution)
        assertEquals(60, irl.fps)
        assertEquals(8_000_000, irl.bitrate)
        assertEquals(StreamCodec.H264_AVC, irl.codec)
        assertEquals(4, irl.maxKeyFrameInterval)
        assertEquals(160_000, irl.audioBitrate)
        assertEquals(2500, irl.srt.latency)
        assertEquals(false, irl.srt.adaptiveBitrateEnabled)
        assertEquals("\"someone\"", irl.extra["twitchChannelName"].toString())
        // OBS (holds a password), remote control and Moblin Cloud are not applied.
        assertNull(irl.extra["obsWebSocketPassword"])
        assertEquals(listOf("OBS WebSocket", "remote control", "Moblin Cloud"), link.ignoredParts)
    }

    @Test
    fun outOfRangeValuesAreIgnored() {
        val link = MoblinSettingsUrl.fromJson(
            """{"streams":[{"name":"A","url":"srt://h:1",""" +
                """"video":{"fps":37,"bitrate":10,"maxKeyFrameInterval":99},"audio":{"bitrate":100000}}]}""",
        )
        val stream = link.apply(Database(), replaceCollisions = false).streams.single()
        val defaults = SettingsStream()
        assertEquals(defaults.fps, stream.fps)
        assertEquals(defaults.bitrate, stream.bitrate)
        assertEquals(defaults.maxKeyFrameInterval, stream.maxKeyFrameInterval)
        assertEquals(defaults.audioBitrate, stream.audioBitrate)
        assertTrue(stream.enabled, "the only stream is selected")
    }

    @Test
    fun nameCollisions() {
        val existing = Database().addStream(SettingsStream(name = "IRL", url = "srt://old:1"))
        val link = MoblinSettingsUrl.fromJson("""{"streams":[{"name":"IRL","url":"srt://new:2"}]}""")
        assertEquals(setOf("IRL"), link.collidingStreamNames(existing))

        val added = link.apply(existing, replaceCollisions = false)
        assertEquals(listOf("IRL", "IRL 1"), added.streams.map { it.name })

        val replaced = link.apply(existing, replaceCollisions = true)
        assertEquals(listOf("srt://new:2"), replaced.streams.map { it.url })
        assertEquals(existing.streams.single().id, replaced.streams.single().id)
    }

    @Test
    fun streamUrlValidation() {
        assertNull(StreamUrls.validate("rtmp://live.twitch.tv/app/key"))
        assertNull(StreamUrls.validate("rtmps://host:443/app/key"))
        assertNull(StreamUrls.validate("srt://host:4000?streamid=x"))
        assertEquals("Host missing", StreamUrls.validate("srt://:4000"))
        assertEquals("Unsupported scheme http", StreamUrls.validate("http://host/"))
        assertEquals("Host missing", StreamUrls.validate("host:4000"))
        assertEquals("srt://Host:1?a=b", StreamUrls.clean(" SRT://Host:1?a=b "))
    }
}
