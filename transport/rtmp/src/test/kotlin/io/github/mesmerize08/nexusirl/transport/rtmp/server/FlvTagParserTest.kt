package io.github.mesmerize08.nexusirl.transport.rtmp.server

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlvTagParserTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun videoFailure(body: ByteArray): String = assertFailsWith<FlvParseException> { FlvTagParser.parseVideo(body) }.reason

    private fun audioFailure(body: ByteArray): String = assertFailsWith<FlvParseException> { FlvTagParser.parseAudio(body) }.reason

    private val payload = bytes(1, 2, 3, 4, 5)

    // Extended header: control, fourCC (4), then the rest.
    private fun extended(control: Int, fourCc: Int, vararg rest: Int) = bytes(
        control,
        (fourCc ushr 24) and 0xFF,
        (fourCc ushr 16) and 0xFF,
        (fourCc ushr 8) and 0xFF,
        fourCc and 0xFF,
        *rest,
    )

    private val hvc1 = 0x6876_6331
    private val avc1 = 0x6176_6331

    @Test
    fun videoBodyUnderTwoBytesIsRejected() {
        assertEquals("Got 0 bytes message, expected >= 2", videoFailure(ByteArray(0)))
        assertEquals("Got 1 bytes message, expected >= 2", videoFailure(bytes(0x17)))
    }

    @Test
    fun legacyUnsupportedCodecIsRejected() {
        assertEquals("Unsupported video codec 2", videoFailure(bytes(0x12, 1)))
    }

    @Test
    fun legacySequenceHeaderCarriesConfigForAvcAndHevc() {
        val avc = assertIs<FlvVideoTag.SequenceHeader>(FlvTagParser.parseVideo(bytes(0x17, 0, 0, 0, 0, 9, 8)))
        assertEquals(IngestVideoCodec.H264, avc.codec)
        assertContentEquals(bytes(9, 8), avc.config)
        val hevc = assertIs<FlvVideoTag.SequenceHeader>(FlvTagParser.parseVideo(bytes(0x1C, 0, 0, 0, 0, 7)))
        assertEquals(IngestVideoCodec.H265, hevc.codec)
    }

    @Test
    fun legacySequenceHeaderUnderFiveBytesIsRejected() {
        assertEquals("Got 4 bytes message, expected >= 5", videoFailure(bytes(0x17, 0, 0, 0)))
    }

    @Test
    fun legacyShortNalPacketIsIgnored() {
        assertIs<FlvVideoTag.Ignored>(FlvTagParser.parseVideo(bytes(0x17, 1, 0, 0, 0, 1, 2, 3, 4)))
    }

    @Test
    fun legacyNalPacketIsAFrame() {
        val frame = assertIs<FlvVideoTag.Frame>(FlvTagParser.parseVideo(bytes(0x17, 1, 0, 0, 40, 1, 2, 3, 4, 5)))
        assertTrue(frame.isKeyFrame)
        assertEquals(40, frame.compositionTimeMs)
        assertContentEquals(payload, frame.data)
        val inter = assertIs<FlvVideoTag.Frame>(FlvTagParser.parseVideo(bytes(0x27, 1, 0, 0, 0, 1, 2, 3, 4, 5)))
        assertEquals(false, inter.isKeyFrame)
    }

    @Test
    fun negativeCompositionTime() {
        val frame = assertIs<FlvVideoTag.Frame>(
            FlvTagParser.parseVideo(bytes(0x27, 1, 0xFF, 0xFF, 0xFE, 1, 2, 3, 4, 5)),
        )
        assertEquals(-2, frame.compositionTimeMs)
    }

    @Test
    fun legacyOtherPacketTypeIsIgnored() {
        assertIs<FlvVideoTag.Ignored>(FlvTagParser.parseVideo(bytes(0x17, 2, 0, 0, 0)))
    }

    @Test
    fun dataIsCopied() {
        val body = bytes(0x17, 1, 0, 0, 0, 1, 2, 3, 4, 5)
        val frame = assertIs<FlvVideoTag.Frame>(FlvTagParser.parseVideo(body))
        body[5] = 99
        assertContentEquals(payload, frame.data)
        val configBody = extended(0x90, hvc1, 1, 2, 3, 4, 5)
        val config = assertIs<FlvVideoTag.SequenceHeader>(FlvTagParser.parseVideo(configBody))
        configBody[5] = 77
        assertContentEquals(payload, config.config)
    }

    @Test
    fun extendedHeaderUnderFiveBytesIsRejected() {
        assertEquals("Got 4 bytes message, expected >= 5", videoFailure(bytes(0x91, 0x68, 0x76, 0x63)))
        assertEquals("Got 2 bytes message, expected >= 5", videoFailure(bytes(0x91, 0)))
    }

    @Test
    fun extendedUnsupportedFrameTypeIsRejected() {
        assertEquals("Unsupported video frame type 3", videoFailure(extended(0xB1, hvc1)))
        assertEquals("Unsupported video frame type 0", videoFailure(extended(0x81, hvc1)))
    }

    @Test
    fun extendedUnsupportedPacketTypeIsRejected() {
        assertEquals("Unsupported video packet type 4", videoFailure(extended(0x94, hvc1)))
        assertEquals("Unsupported video packet type 15", videoFailure(extended(0x9F, hvc1)))
    }

    @Test
    fun extendedAvc1IsRejectedWithUpstreamMessage() {
        assertEquals("Unsupported fourCC avc1.", videoFailure(extended(0x91, avc1)))
    }

    @Test
    fun extendedUnknownFourCcIsRejectedWithItsValue() {
        assertEquals("Unsupported fourCC 1635149105", videoFailure(extended(0x91, 0x6176_6531)))
        assertEquals("Unsupported fourCC 4294967295", videoFailure(extended(0x91, -1)))
    }

    @Test
    fun extendedHevcSequenceStart() {
        val tag = assertIs<FlvVideoTag.SequenceHeader>(FlvTagParser.parseVideo(extended(0x90, hvc1, 1, 2, 3)))
        assertEquals(IngestVideoCodec.H265, tag.codec)
        assertContentEquals(bytes(1, 2, 3), tag.config)
    }

    @Test
    fun extendedHevcShortCodedFramesIsIgnored() {
        assertIs<FlvVideoTag.Ignored>(FlvTagParser.parseVideo(extended(0x91, hvc1, 0, 0, 0, 1)))
    }

    @Test
    fun extendedHevcCodedFramesIsAFrame() {
        val frame = assertIs<FlvVideoTag.Frame>(FlvTagParser.parseVideo(extended(0x91, hvc1, 0, 0, 33, 1, 2, 3, 4, 5)))
        assertTrue(frame.isKeyFrame)
        assertEquals(33, frame.compositionTimeMs)
        assertContentEquals(payload, frame.data)
        val inter = assertIs<FlvVideoTag.Frame>(
            FlvTagParser.parseVideo(extended(0xA1, hvc1, 0xFF, 0xFF, 0xFE, 1, 2, 3, 4, 5)),
        )
        assertEquals(false, inter.isKeyFrame)
        assertEquals(-2, inter.compositionTimeMs)
    }

    @Test
    fun extendedHevcSequenceEnd() {
        assertEquals(FlvVideoTag.SequenceEnd, FlvTagParser.parseVideo(extended(0x92, hvc1)))
    }

    @Test
    fun extendedHevcCodedFramesXHasZeroCompositionTime() {
        val frame = assertIs<FlvVideoTag.Frame>(FlvTagParser.parseVideo(extended(0x93, hvc1, 1, 2, 3, 4, 5)))
        assertTrue(frame.isKeyFrame)
        assertEquals(0, frame.compositionTimeMs)
        assertContentEquals(payload, frame.data)
    }

    @Test
    fun extendedHevcMultitrackIsIgnored() {
        assertIs<FlvVideoTag.Ignored>(FlvTagParser.parseVideo(extended(0x96, hvc1)))
    }

    @Test
    fun audioBodyUnderTwoBytesIsRejected() {
        assertEquals("Got 1 bytes message, expected >= 2", audioFailure(bytes(0xAF)))
        assertEquals("Got 0 bytes message, expected >= 2", audioFailure(ByteArray(0)))
    }

    @Test
    fun audioUnparsableCodecIsRejectedWithControlByte() {
        for (codec in listOf(4, 5, 6, 7, 8, 12, 13)) {
            val control = (codec shl 4) or 0x0F
            assertEquals("Failed to parse audio settings $control", audioFailure(bytes(control, 0)))
        }
    }

    @Test
    fun audioNonAacCodecIsRejectedByName() {
        val names = mapOf(
            0 to "pcm",
            1 to "adpcm",
            2 to "mp3",
            3 to "pcmle",
            9 to "exHeader",
            11 to "speex",
            14 to "mp3_8k",
            15 to "device",
        )
        for ((codec, name) in names) {
            assertEquals(
                "Unsupported audio codec $name. Only AAC is supported.",
                audioFailure(bytes(codec shl 4, 0)),
            )
        }
    }

    @Test
    fun aacSequenceHeaderIsAConfig() {
        val tag = FlvTagParser.parseAudio(bytes(0xAF, 0, 0x12, 0x10))
        assertContentEquals(bytes(0x12, 0x10), assertIs<FlvAudioTag.AacConfig>(tag).audioSpecificConfig)
    }

    @Test
    fun aacRawIsAFrameAndEmptyPayloadIsIgnored() {
        val body = bytes(0xAF, 1, 5, 6, 7)
        val tag = assertIs<FlvAudioTag.AacFrame>(FlvTagParser.parseAudio(body))
        body[2] = 0
        assertContentEquals(bytes(5, 6, 7), tag.data)
        assertIs<FlvAudioTag.Ignored>(FlvTagParser.parseAudio(bytes(0xAF, 1)))
    }

    @Test
    fun aacOtherPacketTypeIsIgnored() {
        assertIs<FlvAudioTag.Ignored>(FlvTagParser.parseAudio(bytes(0xAF, 2, 1)))
    }
}
