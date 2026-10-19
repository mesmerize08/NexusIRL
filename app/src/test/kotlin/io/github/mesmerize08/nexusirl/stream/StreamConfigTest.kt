package io.github.mesmerize08.nexusirl.stream

import io.github.mesmerize08.nexusirl.settings.Database
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamConfigTest {
    private fun stream(json: String) = Database.fromJson("""{"streams":[$json]}""").streams.single()

    @Test
    fun videoSettingsComeFromTheStream() {
        val config = StreamConfig.from(
            stream("""{"resolution":"1280x720","fps":60,"bitrate":6000000,"codec":"H.264/AVC","maxKeyFrameInterval":4}"""),
            "srt://host:4000",
        )
        assertEquals(1280, config.video.width)
        assertEquals(720, config.video.height)
        assertEquals(60, config.video.fps)
        assertEquals(6_000_000, config.video.bitrate)
        assertFalse(config.video.hevc)
        assertEquals(4, config.video.keyFrameIntervalSeconds)
        assertFalse("srt://host" in config.toString(), "the URL may hold a key and must not be printed")
    }

    @Test
    fun srtAdaptiveBitrateFollowsUpstreamMapping() {
        // Upstream Model.updateAdaptiveBitrateSrt: settings are kbps, algorithms take bps.
        val fast = StreamConfig.from(
            stream(
                """{"srt":{"latency":2000,"adaptiveBitrate":{"algorithm":{"fastIrl":{}},""" +
                    """"fastIrlSettings":{"packetsInFlight":150,"minimumBitrate":500}}}}""",
            ),
            "srt://h",
        )
        assertEquals(2000, fast.srtLatency)
        assertEquals(AdaptiveBitrateSettings.FAST.copy(packetsInFlight = 150, minimumBitrate = 500_000), fast.srtAdaptiveBitrate!!.settings)
        assertFalse(fast.srtAdaptiveBitrate!!.belabox)

        val belabox = StreamConfig.from(stream("""{"srt":{"adaptiveBitrate":{"belaboxSettings":{"minimumBitrate":300}}}}"""), "srt://h")
        assertTrue(belabox.srtAdaptiveBitrate!!.belabox)
        assertEquals(300_000, belabox.srtAdaptiveBitrate!!.settings.minimumBitrate)

        val custom = StreamConfig.from(
            stream(
                """{"srt":{"adaptiveBitrate":{"algorithm":{"customIrl":{}},"customSettings":{"packetsInFlight":300,""" +
                    """"pifDiffIncreaseFactor":50,"rttDiffHighDecreaseFactor":0.8,"rttDiffHighAllowedSpike":70,""" +
                    """"rttDiffHighMinimumDecrease":200,"minimumBitrate":400}}}}""",
            ),
            "srt://h",
        )
        assertEquals(AdaptiveBitrateSettings(300, 0.8f.toDouble(), 70.0, 200_000, 50_000, 400_000), custom.srtAdaptiveBitrate!!.settings)

        assertEquals(
            AdaptiveBitrateSettings.SLOW,
            StreamConfig.from(
                stream("""{"srt":{"adaptiveBitrate":{"algorithm":{"slowIrl":{}}}}}"""),
                "srt://h",
            ).srtAdaptiveBitrate!!.settings,
        )
        assertNull(StreamConfig.from(stream("""{"srt":{"adaptiveBitrateEnabled":false}}"""), "srt://h").srtAdaptiveBitrate)
    }
}
