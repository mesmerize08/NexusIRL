// Port of Moblin/Media/RtmpServer/RtmpServerChunkStream.swift @97e26d2 (processMessageAudio,
// processMessageVideo and calcCompositionTime, parsing only: decoding stays with the caller).
package io.github.mesmerize08.nexusirl.transport.rtmp.server

import io.github.mesmerize08.nexusirl.transport.rtmp.Flv

enum class IngestVideoCodec { H264, H265 }

// The tags holding bytes compare them by content: later stages compare sequence headers to see a
// format change.
sealed interface FlvVideoTag {
    data class SequenceHeader(val codec: IngestVideoCodec, val config: ByteArray) : FlvVideoTag {
        override fun equals(other: Any?): Boolean = other is SequenceHeader && codec == other.codec && config.contentEquals(other.config)

        override fun hashCode(): Int = 31 * codec.hashCode() + config.contentHashCode()
    }

    /** [data] is length-prefixed NAL units, as sent. */
    data class Frame(val isKeyFrame: Boolean, val compositionTimeMs: Int, val data: ByteArray) : FlvVideoTag {
        override fun equals(other: Any?): Boolean = other is Frame &&
            isKeyFrame == other.isKeyFrame &&
            compositionTimeMs == other.compositionTimeMs &&
            data.contentEquals(other.data)

        override fun hashCode(): Int = 31 * (31 * isKeyFrame.hashCode() + compositionTimeMs) + data.contentHashCode()
    }

    data object SequenceEnd : FlvVideoTag

    /** Not an error: upstream logs and drops it. */
    data class Ignored(val reason: String) : FlvVideoTag
}

sealed interface FlvAudioTag {
    data class AacConfig(val audioSpecificConfig: ByteArray) : FlvAudioTag {
        override fun equals(other: Any?): Boolean = other is AacConfig && audioSpecificConfig.contentEquals(other.audioSpecificConfig)

        override fun hashCode(): Int = audioSpecificConfig.contentHashCode()
    }

    data class AacFrame(val data: ByteArray) : FlvAudioTag {
        override fun equals(other: Any?): Boolean = other is AacFrame && data.contentEquals(other.data)

        override fun hashCode(): Int = data.contentHashCode()
    }

    data class Ignored(val reason: String) : FlvAudioTag
}

/** A failure ends the session with [reason] (upstream `stopInternal(reason:)`). */
class FlvParseException(val reason: String) : Exception(reason)

/**
 * Turns an audio or video message body from an untrusted publisher into a typed tag or a
 * [FlvParseException]. Every index is length-checked first. Returned arrays are copies.
 */
object FlvTagParser {
    private const val VIDEO_HEADER_SIZE = 5

    // Upstream FlvAudioCodec case names for the codecs that are known but not AAC.
    private val audioCodecNames = mapOf(
        0 to "pcm",
        1 to "adpcm",
        2 to "mp3",
        3 to "pcmle",
        Flv.AUDIO_CODEC_EX_HEADER to "exHeader",
        11 to "speex",
        14 to "mp3_8k",
        15 to "device",
    )

    fun parseVideo(body: ByteArray): FlvVideoTag {
        requireSize(body, 2)
        val control = body[0].toInt() and 0xFF
        return if (control and Flv.EXTENDED_VIDEO_HEADER == Flv.EXTENDED_VIDEO_HEADER) {
            parseExtendedVideo(body, control)
        } else {
            parseLegacyVideo(body, control)
        }
    }

    private fun parseLegacyVideo(body: ByteArray, control: Int): FlvVideoTag {
        val codec = when (control and 0xF) {
            Flv.VIDEO_CODEC_AVC -> IngestVideoCodec.H264
            Flv.VIDEO_CODEC_HEVC -> IngestVideoCodec.H265
            else -> throw FlvParseException("Unsupported video codec ${control and 0xF}")
        }
        return when (val packetType = body[1].toInt() and 0xFF) {
            Flv.AVC_PACKET_TYPE_SEQ -> {
                requireSize(body, VIDEO_HEADER_SIZE)
                FlvVideoTag.SequenceHeader(codec, body.copyOfRange(VIDEO_HEADER_SIZE, body.size))
            }

            Flv.AVC_PACKET_TYPE_NAL -> {
                if (body.size <= 9) {
                    FlvVideoTag.Ignored("Dropping short packet")
                } else {
                    FlvVideoTag.Frame(
                        isKeyFrame = frameType(control) == Flv.FRAME_TYPE_KEY,
                        compositionTimeMs = compositionTime(body, 2),
                        data = body.copyOfRange(VIDEO_HEADER_SIZE, body.size),
                    )
                }
            }

            else -> FlvVideoTag.Ignored("Unsupported video $codec packet type $packetType")
        }
    }

    private fun parseExtendedVideo(body: ByteArray, control: Int): FlvVideoTag {
        requireSize(body, VIDEO_HEADER_SIZE)
        val frameType = frameType(control)
        if (frameType != Flv.FRAME_TYPE_KEY && frameType != Flv.FRAME_TYPE_INTER) {
            throw FlvParseException("Unsupported video frame type $frameType")
        }
        val packetType = control and 0b1111
        if (packetType !in supportedExtendedPacketTypes) {
            throw FlvParseException("Unsupported video packet type $packetType")
        }
        val fourCc = ((body[1].toLong() and 0xFF) shl 24) or
            ((body[2].toLong() and 0xFF) shl 16) or
            ((body[3].toLong() and 0xFF) shl 8) or
            (body[4].toLong() and 0xFF)
        when (fourCc) {
            Flv.FOURCC_HVC1 -> Unit
            Flv.FOURCC_AVC1 -> throw FlvParseException("Unsupported fourCC avc1.")
            else -> throw FlvParseException("Unsupported fourCC $fourCc")
        }
        val isKeyFrame = frameType == Flv.FRAME_TYPE_KEY
        return when (packetType) {
            Flv.VIDEO_PACKET_TYPE_SEQUENCE_START ->
                FlvVideoTag.SequenceHeader(IngestVideoCodec.H265, body.copyOfRange(VIDEO_HEADER_SIZE, body.size))

            Flv.VIDEO_PACKET_TYPE_CODED_FRAMES ->
                if (body.size <= 9) {
                    FlvVideoTag.Ignored("Dropping short packet")
                } else {
                    FlvVideoTag.Frame(isKeyFrame, compositionTime(body, 5), body.copyOfRange(8, body.size))
                }

            Flv.VIDEO_PACKET_TYPE_SEQUENCE_END -> FlvVideoTag.SequenceEnd

            Flv.VIDEO_PACKET_TYPE_CODED_FRAMES_X ->
                FlvVideoTag.Frame(isKeyFrame, 0, body.copyOfRange(VIDEO_HEADER_SIZE, body.size))

            else -> FlvVideoTag.Ignored("Unsupported video packet type $packetType")
        }
    }

    fun parseAudio(body: ByteArray): FlvAudioTag {
        requireSize(body, 2)
        val control = body[0].toInt() and 0xFF
        val codec = control shr 4
        if (codec != Flv.AUDIO_CODEC_AAC) {
            val name = audioCodecNames[codec] ?: throw FlvParseException("Failed to parse audio settings $control")
            throw FlvParseException("Unsupported audio codec $name. Only AAC is supported.")
        }
        return when (body[1].toInt() and 0xFF) {
            Flv.AAC_PACKET_TYPE_SEQ -> FlvAudioTag.AacConfig(body.copyOfRange(2, body.size))

            Flv.AAC_PACKET_TYPE_RAW ->
                if (body.size > 2) {
                    FlvAudioTag.AacFrame(body.copyOfRange(2, body.size))
                } else {
                    FlvAudioTag.Ignored("Empty AAC frame")
                }

            else -> FlvAudioTag.Ignored("Unsupported AAC packet type ${body[1].toInt() and 0xFF}")
        }
    }

    private val supportedExtendedPacketTypes = setOf(
        Flv.VIDEO_PACKET_TYPE_SEQUENCE_START,
        Flv.VIDEO_PACKET_TYPE_CODED_FRAMES,
        Flv.VIDEO_PACKET_TYPE_SEQUENCE_END,
        Flv.VIDEO_PACKET_TYPE_CODED_FRAMES_X,
        Flv.VIDEO_PACKET_TYPE_MULTITRACK,
    )

    private fun frameType(control: Int) = (control shr 4) and 0b111

    private fun requireSize(body: ByteArray, minimum: Int) {
        if (body.size < minimum) {
            throw FlvParseException("Got ${body.size} bytes message, expected >= $minimum")
        }
    }

    /** Signed 24-bit big-endian at [offset]; the caller has checked the length. */
    private fun compositionTime(body: ByteArray, offset: Int): Int {
        val value = ((body[offset].toInt() and 0xFF) shl 16) or
            ((body[offset + 1].toInt() and 0xFF) shl 8) or
            (body[offset + 2].toInt() and 0xFF)
        return (value shl 8) shr 8
    }
}
