package io.github.mesmerize08.nexusirl.stream

import io.github.mesmerize08.nexusirl.media.pipeline.AudioEncoderFormat
import io.github.mesmerize08.nexusirl.settings.Database
import io.github.mesmerize08.nexusirl.settings.SettingsStream
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

    @Test
    fun audioCodecComesFromTheStream() {
        // Upstream SettingsStreamAudioCodec.toEncoder(), on SRT(LA) and RTMP alike.
        assertEquals(AudioEncoderFormat.AAC, StreamConfig.from(stream("{}"), "srt://h").audioFormat)
        assertEquals(AudioEncoderFormat.OPUS, StreamConfig.from(stream("""{"audioCodec":"OPUS"}"""), "srtla://h").audioFormat)
        assertEquals(AudioEncoderFormat.OPUS, StreamConfig.from(stream("""{"audioCodec":"OPUS"}"""), "rtmp://h/app/k").audioFormat)
    }

    @Test
    fun audioBitrateComesFromTheStream() {
        // Upstream ModelStream.setAudioStreamBitrate: the stream's audioBitrate, 128 kbps by default.
        assertEquals(128_000, StreamConfig.from(stream("{}"), "srt://h").audioBitrate)
        assertEquals(192_000, StreamConfig.from(stream("""{"audioBitrate":192000}"""), "srt://h").audioBitrate)
        assertEquals(64_000, StreamConfig.from(stream("""{"audioCodec":"OPUS","audioBitrate":64000}"""), "rtmp://h/a/k").audioBitrate)
        // Outside upstream's 32-320 kbps picker (a hand-edited or old import): kept within it.
        assertEquals(32_000, StreamConfig.from(stream("""{"audioBitrate":0}"""), "srt://h").audioBitrate)
        assertEquals(320_000, StreamConfig.from(stream("""{"audioBitrate":1000000}"""), "srt://h").audioBitrate)
    }

    @Test
    fun recordingAudioBitrateIsTheRecordingsOwn() {
        // Upstream ModelRecording: recording.audioBitrate, 0 leaves it to the encoder (128 kbps here).
        assertEquals(128_000, StreamConfig.recordingAudioBitrate(null))
        assertEquals(128_000, StreamConfig.recordingAudioBitrate(stream("{}").recording))
        assertEquals(96_000, StreamConfig.recordingAudioBitrate(stream("""{"recording":{"audioBitrate":96000}}""").recording))
        assertEquals(128_000, StreamConfig.recordingAudioBitrate(stream("""{"recording":{"audioBitrate":0}}""").recording))
    }

    @Test
    fun destinationsNeverPrintTheirUrls() {
        val config = StreamConfig.from(
            SettingsStream(url = "rtmp://example.com/app/fakekey-main"),
            "rtmp://example.com/app/fakekey-main",
            listOf(RtmpDestination("YouTube", "rtmp://example.com/live2/fakekey-extra")),
        )
        assertEquals(1, config.rtmpDestinations.size)
        assertFalse("fakekey" in config.toString(), config.toString())
    }
}
