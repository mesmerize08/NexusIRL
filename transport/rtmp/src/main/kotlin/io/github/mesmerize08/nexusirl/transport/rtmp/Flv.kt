// Port of Moblin/Media/HaishinKit/Flv/Flv.swift and the FLV tag builders in RtmpStream.swift
// @97e26d2, plus avcC/hvcC builders: Android's MediaCodec provides raw parameter sets where
// VideoToolbox provides ready-made records.
package io.github.mesmerize08.nexusirl.transport.rtmp

import io.github.mesmerize08.nexusirl.media.mux.ByteWriter
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig

object Flv {
    const val AAC_PACKET_TYPE_SEQ = 0
    const val AAC_PACKET_TYPE_RAW = 1
    const val OPUS_PACKET_TYPE_SEQUENCE_START = 0
    const val OPUS_PACKET_TYPE_CODED_FRAMES = 1
    const val AVC_PACKET_TYPE_SEQ = 0
    const val AVC_PACKET_TYPE_NAL = 1
    const val FRAME_TYPE_KEY = 1
    const val FRAME_TYPE_INTER = 2
    const val VIDEO_CODEC_AVC = 7
    const val AUDIO_CODEC_EX_HEADER = 9
    const val AUDIO_CODEC_AAC = 10
    const val VIDEO_PACKET_TYPE_SEQUENCE_START = 0
    const val VIDEO_PACKET_TYPE_CODED_FRAMES = 1
    const val FOURCC_AVC1 = 0x6176_6331L
    const val FOURCC_HVC1 = 0x6876_6331L
    const val EXTENDED_VIDEO_HEADER = 0b1000_0000

    /** Chunk stream ids used for audio and video messages (upstream `FlvTagType.streamId`). */
    const val AUDIO_CHUNK_STREAM_ID = 8
    const val VIDEO_CHUNK_STREAM_ID = 9

    /** AAC, 44 kHz, 16-bit, stereo: FLV mandates these flags for AAC regardless of the real format. */
    const val AAC_SOUND_FLAGS = (AUDIO_CODEC_AAC shl 4) or (3 shl 2) or (1 shl 1) or 1
    const val OPUS_SEQUENCE_START_FLAGS = (AUDIO_CODEC_EX_HEADER shl 4) or OPUS_PACKET_TYPE_SEQUENCE_START

    fun avcVideoTagHeader(frameType: Int, avcPacketType: Int): ByteArray = byteArrayOf(
        ((frameType shl 4) or VIDEO_CODEC_AVC).toByte(),
        avcPacketType.toByte(),
    )

    /** Enhanced RTMP extended header for HEVC: `0x80 | frameType << 4 | packetType`, FourCC `hvc1`. */
    fun hevcExtendedTagHeader(frameType: Int, videoPacketType: Int): ByteArray {
        val writer = ByteWriter(5)
        writer.writeUInt8(EXTENDED_VIDEO_HEADER or (frameType shl 4) or videoPacketType)
        writer.writeUInt32(FOURCC_HVC1)
        return writer.toByteArray()
    }

    fun aacSequenceHeader(config: MpegTsAudioConfig): ByteArray =
        byteArrayOf(AAC_SOUND_FLAGS.toByte(), AAC_PACKET_TYPE_SEQ.toByte()) + config.encode()

    /** Enhanced RTMP Opus sequence start: ex-header, FourCC "Opus", then an OpusHead. */
    fun opusSequenceHeader(channels: Int, sampleRate: Int): ByteArray {
        val writer = ByteWriter(32)
        writer.writeUInt8(OPUS_SEQUENCE_START_FLAGS)
        writer.writeUtf8Bytes("Opus")
        writer.writeUtf8Bytes("OpusHead")
        writer.writeUInt8(1)
        writer.writeUInt8(channels)
        writer.writeUInt16(0)
        writer.writeUInt32(sampleRate.toLong())
        writer.writeUInt16(0)
        writer.writeUInt8(0)
        return writer.toByteArray()
    }

    fun aacFrameHeader(): ByteArray = byteArrayOf(AAC_SOUND_FLAGS.toByte(), AAC_PACKET_TYPE_RAW.toByte())

    fun opusFrameHeader(): ByteArray =
        byteArrayOf(((AUDIO_CODEC_EX_HEADER shl 4) or OPUS_PACKET_TYPE_CODED_FRAMES).toByte()) + "Opus".toByteArray()

    /** Builds an AVCDecoderConfigurationRecord (`avcC`) from raw SPS and PPS NAL units. */
    fun makeAvcC(sps: ByteArray, pps: ByteArray): ByteArray {
        require(sps.size >= 4) { "SPS too short" }
        val writer = ByteWriter(16 + sps.size + pps.size)
        writer.writeUInt8(1) // configurationVersion
        writer.writeUInt8(sps[1].toInt()) // AVCProfileIndication
        writer.writeUInt8(sps[2].toInt()) // profile_compatibility
        writer.writeUInt8(sps[3].toInt()) // AVCLevelIndication
        writer.writeUInt8(0xFF) // reserved + lengthSizeMinusOne = 3
        writer.writeUInt8(0xE1) // reserved + numOfSequenceParameterSets = 1
        writer.writeUInt16(sps.size)
        writer.writeBytes(sps)
        writer.writeUInt8(1) // numOfPictureParameterSets
        writer.writeUInt16(pps.size)
        writer.writeBytes(pps)
        return writer.toByteArray()
    }

    /**
     * Builds an HEVCDecoderConfigurationRecord (`hvcC`, ISO/IEC 14496-15 8.3.3.1.2) from raw VPS,
     * SPS and PPS NAL units. The general profile/tier/level fields come from the SPS
     * profile_tier_level (after removing emulation prevention bytes); other fields use the
     * conventional "unspecified" defaults.
     */
    fun makeHvcC(vps: ByteArray, sps: ByteArray, pps: ByteArray): ByteArray {
        val rbsp = removeEmulationPrevention(sps)
        require(rbsp.size >= 15) { "SPS too short" }
        // rbsp[0..1] NAL header, rbsp[2] = vps_id(4) | max_sub_layers_minus1(3) | temporal_id_nesting(1),
        // rbsp[3..14] = general profile_tier_level (12 bytes).
        val maxSubLayersMinus1 = (rbsp[2].toInt() ushr 1) and 0x7
        val temporalIdNested = rbsp[2].toInt() and 0x1
        val writer = ByteWriter(64 + vps.size + sps.size + pps.size)
        writer.writeUInt8(1) // configurationVersion
        writer.writeUInt8(rbsp[3].toInt()) // general_profile_space, tier_flag, profile_idc
        writer.writeBytes(rbsp, 4, 4) // general_profile_compatibility_flags
        writer.writeBytes(rbsp, 8, 6) // general_constraint_indicator_flags
        writer.writeUInt8(rbsp[14].toInt()) // general_level_idc
        writer.writeUInt16(0xF000) // reserved + min_spatial_segmentation_idc = 0
        writer.writeUInt8(0xFC) // reserved + parallelismType = 0
        writer.writeUInt8(0xFD) // reserved + chromaFormat = 1 (4:2:0)
        writer.writeUInt8(0xF8) // reserved + bitDepthLumaMinus8 = 0
        writer.writeUInt8(0xF8) // reserved + bitDepthChromaMinus8 = 0
        writer.writeUInt16(0) // avgFrameRate
        // constantFrameRate(2) = 0, numTemporalLayers(3), temporalIdNested(1), lengthSizeMinusOne(2) = 3
        writer.writeUInt8(((maxSubLayersMinus1 + 1) shl 3) or (temporalIdNested shl 2) or 3)
        writer.writeUInt8(3) // numOfArrays
        for ((type, nal) in listOf(32 to vps, 33 to sps, 34 to pps)) {
            writer.writeUInt8(0x80 or type) // array_completeness = 1, NAL unit type
            writer.writeUInt16(1)
            writer.writeUInt16(nal.size)
            writer.writeBytes(nal)
        }
        return writer.toByteArray()
    }

    fun removeEmulationPrevention(nal: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (byte in nal) {
            val value = byte.toInt() and 0xFF
            if (zeros >= 2 && value == 0x03) {
                zeros = 0
                continue
            }
            output.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return output.toByteArray()
    }
}
