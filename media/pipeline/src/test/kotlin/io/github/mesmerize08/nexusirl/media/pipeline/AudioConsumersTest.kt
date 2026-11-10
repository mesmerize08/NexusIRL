package io.github.mesmerize08.nexusirl.media.pipeline

import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioConsumersTest {
    private class RecordingSink : MediaSink {
        val configs = ArrayList<MpegTsAudioConfig>()
        val frames = ArrayList<String>()

        override fun setAudioConfig(config: MpegTsAudioConfig, sampleRate: Int) {
            configs += config
        }

        override fun setVideoConfig(config: MpegTsVideoConfig) {}

        override fun writeAudio(frame: ByteArray, presentationTimeStamp: MediaTime) {
            frames += String(frame)
        }

        override fun writeVideo(accessUnit: ByteArray, presentationTimeStamp: MediaTime, decodeTimeStamp: MediaTime?, isSync: Boolean) {}
    }

    /** Stands in for the MediaCodec encoders: one output sink per running format. */
    private val running = LinkedHashMap<AudioEncoderKey, MediaSink>()
    private val events = ArrayList<String>()
    private var failNextStart = false
    private var noOpus = false
    private val consumers = AudioConsumers(
        startEncoder = { key, sink ->
            events += "start ${key.format}" + if (key.bitrate == BITRATE) "" else " ${key.bitrate}"
            if (failNextStart) {
                failNextStart = false
                null
            } else {
                // Like AudioPipeline: a phone without an Opus encoder gets AAC instead.
                val actual = if (key.format == AudioEncoderFormat.OPUS && noOpus) AudioEncoderFormat.AAC else key.format
                running[key] = sink
                sink.setAudioConfig(actual.config(48_000, 1), 48_000)
                AudioEncoderKey(actual, actual.bitrate(key.bitrate, channels = 1))
            }
        },
        stopEncoder = { key ->
            events += "stop ${key.format}" + if (key.bitrate == BITRATE) "" else " ${key.bitrate}"
            running.remove(key)
        },
    )

    private fun encode(format: AudioEncoderFormat, text: String, bitrate: Int = BITRATE) =
        running.getValue(AudioEncoderKey(format, bitrate)).writeAudio(text.toByteArray(), MediaTime(0, 1))

    private fun AudioConsumers.add(consumer: MediaSink, format: AudioEncoderFormat) = add(consumer, format, BITRATE)

    @Test
    fun aStreamAndARecordingWithDifferentBitratesGetAnEncoderEach() {
        // Upstream's stream encoder uses the stream's audio bitrate, its recorder the recording's.
        val stream = RecordingSink()
        val recording = RecordingSink()
        consumers.add(stream, AudioEncoderFormat.AAC, 160_000)
        consumers.add(recording, AudioEncoderFormat.AAC, BITRATE)
        assertEquals(listOf("start AAC 160000", "start AAC"), events)
        encode(AudioEncoderFormat.AAC, "stream", 160_000)
        encode(AudioEncoderFormat.AAC, "recording")
        assertEquals(listOf("stream"), stream.frames)
        assertEquals(listOf("recording"), recording.frames)
        consumers.remove(stream)
        assertEquals("stop AAC 160000", events.last())
    }

    @Test
    fun consumersOfOneFormatAndBitrateShareAnEncoder() {
        val stream = RecordingSink()
        val recording = RecordingSink()
        consumers.add(stream, AudioEncoderFormat.AAC, 96_000)
        consumers.add(recording, AudioEncoderFormat.AAC, 96_000)
        assertEquals(listOf("start AAC 96000"), events)
        consumers.remove(stream)
        assertEquals(1, events.size)
        consumers.remove(recording)
        assertEquals("stop AAC 96000", events.last())
    }

    @Test
    fun consumersLearnWhenOpusFellBackToAac() {
        noOpus = true
        val stream = RecordingSink()
        val second = RecordingSink()
        assertEquals(AudioEncoderKey(AudioEncoderFormat.AAC, BITRATE), consumers.add(stream, AudioEncoderFormat.OPUS))
        // A consumer joining the running encoder is told too.
        assertEquals(AudioEncoderFormat.AAC, consumers.add(second, AudioEncoderFormat.OPUS)?.format)
        assertEquals(listOf("start OPUS"), events)
        // The muxers get the AAC config, not Opus.
        assertFalse(stream.configs.single().isOpus)
        assertFalse(second.configs.single().isOpus)
    }

    @Test
    fun anAacRecordingJoinsTheAacEncoderAnOpusStreamFellBackTo() {
        noOpus = true
        val stream = RecordingSink()
        val recording = RecordingSink()
        consumers.add(stream, AudioEncoderFormat.OPUS)
        assertEquals(AudioEncoderFormat.AAC, consumers.add(recording, AudioEncoderFormat.AAC)?.format)
        // One AAC encoder, not two identical ones.
        assertEquals(listOf("start OPUS"), events)
        encode(AudioEncoderFormat.OPUS, "aac")
        assertEquals(listOf("aac"), recording.frames)
        consumers.remove(stream)
        assertEquals(1, events.size, "the recording still uses it")
        consumers.remove(recording)
        assertEquals("stop OPUS", events.last())
        assertTrue(consumers.isEmpty)
        assertFalse(consumers.hasEncoder)
    }

    @Test
    fun theEncoderBitrateStaysWithinWhatTheCodecCanDo() {
        // AAC-LC carries at most 6144 bits per channel per 1024 frames: 288 kbps mono at 48 kHz.
        assertEquals(256_000, AudioEncoderFormat.AAC.bitrate(320_000, channels = 1))
        assertEquals(320_000, AudioEncoderFormat.AAC.bitrate(320_000, channels = 2))
        assertEquals(128_000, AudioEncoderFormat.AAC.bitrate(128_000, channels = 1))
        assertEquals(320_000, AudioEncoderFormat.OPUS.bitrate(320_000, channels = 1))
        // Upstream AudioEncoder.setBitrate: clamped to the encoder's applicable bitrates.
        assertEquals(192_000, AudioEncoderFormat.AAC.bitrate(256_000, channels = 1, supported = 8_000..192_000))
        assertEquals(64_000, AudioEncoderFormat.OPUS.bitrate(32_000, channels = 1, supported = 64_000..510_000))
    }

    @Test
    fun anEncoderThatFailedToStartIsTriedAgainForTheNextConsumer() {
        failNextStart = true
        val stream = RecordingSink()
        assertNull(consumers.add(stream, AudioEncoderFormat.AAC))
        assertFalse(consumers.hasEncoder)
        val recording = RecordingSink()
        consumers.add(recording, AudioEncoderFormat.AAC)
        assertEquals(listOf("start AAC", "start AAC"), events)
        assertTrue(consumers.hasEncoder)
        encode(AudioEncoderFormat.AAC, "aac")
        assertEquals(listOf("aac"), recording.frames)
        // Removing the consumer whose encoder never started stops nothing.
        consumers.remove(stream)
        assertEquals(listOf("start AAC", "start AAC"), events)
    }

    @Test
    fun recordingAlongsideAnOpusStreamGetsItsOwnAacEncoder() {
        val stream = RecordingSink()
        val recording = RecordingSink()
        consumers.add(stream, AudioEncoderFormat.OPUS)
        consumers.add(recording, AudioEncoderFormat.AAC)
        assertEquals(listOf("start OPUS", "start AAC"), events)
        encode(AudioEncoderFormat.OPUS, "opus")
        encode(AudioEncoderFormat.AAC, "aac")
        assertEquals(listOf("opus"), stream.frames)
        assertEquals(listOf("aac"), recording.frames)
        assertTrue(stream.configs.single().isOpus)
        assertFalse(recording.configs.single().isOpus)
    }

    @Test
    fun consumersOfOneFormatShareAnEncoder() {
        val stream = RecordingSink()
        val recording = RecordingSink()
        consumers.add(stream, AudioEncoderFormat.AAC)
        consumers.add(recording, AudioEncoderFormat.AAC)
        assertEquals(listOf("start AAC"), events)
        // The late consumer still gets the format, as the FanOutSink replays it.
        assertEquals(1, recording.configs.size)
        encode(AudioEncoderFormat.AAC, "x")
        assertEquals(listOf("x"), stream.frames)
        assertEquals(listOf("x"), recording.frames)
    }

    @Test
    fun anEncoderStopsWithItsLastConsumer() {
        val stream = RecordingSink()
        val recording = RecordingSink()
        consumers.add(stream, AudioEncoderFormat.OPUS)
        consumers.add(recording, AudioEncoderFormat.AAC)
        consumers.remove(recording)
        assertEquals("stop AAC", events.last())
        assertFalse(consumers.isEmpty)
        consumers.remove(stream)
        assertEquals("stop OPUS", events.last())
        assertTrue(consumers.isEmpty)
        // Removing again is harmless.
        consumers.remove(stream)
        assertEquals(4, events.size)
    }

    @Test
    fun formatsHaveNamesForTheStreamer() {
        assertEquals(listOf("AAC", "Opus"), AudioEncoderFormat.entries.map { it.displayName })
    }

    @Test
    fun opusFramesAre20Milliseconds() {
        // Upstream AudioEncoder.samplesPerBuffer: 1024 for AAC, 960 for Opus.
        assertEquals(1024, AudioEncoderFormat.AAC.framesPerChunk)
        assertEquals(960, AudioEncoderFormat.OPUS.framesPerChunk)
        assertTrue(AudioEncoderFormat.OPUS.config(48_000, 2).isOpus)
        assertEquals(2, AudioEncoderFormat.AAC.config(48_000, 2).channels)
    }

    private companion object {
        const val BITRATE = 128_000
    }
}
