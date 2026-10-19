// Port of Moblin/Media/HaishinKit/Mpeg/MpegTsAudioConfig.swift, Adts.swift, Opus.swift,
// Avc/MpegTsVideoConfigAvc.swift and Hevc/MpegTsVideoConfigHevc.swift @97e26d2
package io.github.mesmerize08.nexusirl.media.mux.ts

import io.github.mesmerize08.nexusirl.media.mux.ByteReader
import io.github.mesmerize08.nexusirl.media.mux.MuxException
import io.github.mesmerize08.nexusirl.media.mux.nal.HevcNalUnitType

/** MPEG-4 audio sampling frequency index. */
enum class SamplingFrequency(val index: Int, val sampleRate: Int) {
    HZ96000(0, 96000),
    HZ88200(1, 88200),
    HZ64000(2, 64000),
    HZ48000(3, 48000),
    HZ44100(4, 44100),
    HZ32000(5, 32000),
    HZ24000(6, 24000),
    HZ22050(7, 22050),
    HZ16000(8, 16000),
    HZ12000(9, 12000),
    HZ11025(10, 11025),
    HZ8000(11, 8000),
    HZ7350(12, 7350),
    ;

    companion object {
        /** Unknown rates map to 44.1 kHz, like upstream. */
        fun fromSampleRate(sampleRate: Int): SamplingFrequency = entries.firstOrNull { it.sampleRate == sampleRate } ?: HZ44100

        fun fromIndex(index: Int): SamplingFrequency? = entries.firstOrNull { it.index == index }
    }
}

/** Audio stream configuration. [objectType] uses upstream's numbering (2 = AAC-LC, 10 = Opus). */
data class MpegTsAudioConfig(val objectType: Int, val frequency: SamplingFrequency, val channels: Int) {
    val isOpus: Boolean
        get() = objectType == OBJECT_TYPE_OPUS

    /** Two-byte AudioSpecificConfig (also used as the FLV/RTMP AAC sequence header). */
    fun encode(): ByteArray = byteArrayOf(
        ((objectType shl 3) or (frequency.index ushr 1)).toByte(),
        (((frequency.index and 0x1) shl 7) or ((channels and 0xF) shl 3)).toByte(),
    )

    companion object {
        const val OBJECT_TYPE_AAC_LC = 2
        const val OBJECT_TYPE_OPUS = 10

        fun aacLc(sampleRate: Int, channels: Int) =
            MpegTsAudioConfig(OBJECT_TYPE_AAC_LC, SamplingFrequency.fromSampleRate(sampleRate), channels)

        fun opus(sampleRate: Int, channels: Int) =
            MpegTsAudioConfig(OBJECT_TYPE_OPUS, SamplingFrequency.fromSampleRate(sampleRate), channels)

        /** Parses a two-byte AudioSpecificConfig (e.g. MediaCodec `csd-0`). */
        fun decode(data: ByteArray): MpegTsAudioConfig? {
            if (data.size < 2) {
                return null
            }
            val b0 = data[0].toInt() and 0xFF
            val b1 = data[1].toInt() and 0xFF
            val objectType = b0 ushr 3
            val frequency = SamplingFrequency.fromIndex(((b0 and 0b111) shl 1) or (b1 ushr 7)) ?: return null
            val channels = (b1 and 0b0111_1000) ushr 3
            if (objectType > 10 || channels > 7) {
                return null
            }
            return MpegTsAudioConfig(objectType, frequency, channels)
        }
    }
}

object AdtsHeader {
    const val SIZE = 7

    fun encode(type: Int, frequency: Int, channels: Int, length: Int): ByteArray {
        val fullSize = SIZE + length
        return byteArrayOf(
            0xFF.toByte(),
            0xF9.toByte(),
            (((type - 1) shl 6) or (frequency shl 2) or (channels ushr 2)).toByte(),
            (((channels and 3) shl 6) or (fullSize ushr 11)).toByte(),
            ((fullSize and 0x7FF) ushr 3).toByte(),
            (((fullSize and 7) shl 5) + 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    /** Returns the frame length (header included) of the ADTS frame at [offset], or null. */
    fun frameLength(data: ByteArray, offset: Int = 0): Int? {
        if (data.size - offset < SIZE) {
            return null
        }
        val length = ((data[offset + 3].toInt() and 0b11) shl 11) or
            ((data[offset + 4].toInt() and 0xFF) shl 3) or
            ((data[offset + 5].toInt() and 0xFF) ushr 5)
        return if (length >= SIZE) length else null
    }
}

/** The Opus control header used in MPEG-TS (ETSI TS 102 366 style length coding). */
object OpusHeader {
    fun encode(length: Int): ByteArray {
        val header = ArrayList<Byte>(2 + length / 255 + 1)
        val prefix = 0x3FF shl 5
        header.add((prefix ushr 8).toByte())
        header.add(prefix.toByte())
        var remaining = length
        while (remaining >= 0) {
            header.add((if (remaining < 255) remaining else 255).toByte())
            remaining -= 255
        }
        return header.toByteArray()
    }

    /** Returns (payload length, header length), or null. */
    fun decode(data: ByteArray): Pair<Int, Int>? {
        val reader = ByteReader(data)
        var length = 0
        return try {
            reader.readUInt16()
            while (true) {
                val value = reader.readUInt8()
                length += value
                if (value < 255) {
                    return length to reader.position
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (e: MuxException) {
            null
        }
    }
}

/** Video parameter sets as raw NAL units (no start codes). */
sealed interface MpegTsVideoConfig {
    data class Avc(val sequenceParameterSet: ByteArray?, val pictureParameterSet: ByteArray?) : MpegTsVideoConfig {
        companion object {
            /** Parses an `avcC` (ISO/IEC 14496-15) record. */
            fun fromAvcC(avcC: ByteArray): Avc {
                var sps: ByteArray? = null
                var pps: ByteArray? = null
                try {
                    val reader = ByteReader(avcC)
                    reader.skipBytes(5)
                    val numberOfSps = reader.readUInt8() and 0x1F
                    repeat(numberOfSps) { sps = reader.readBytes(reader.readUInt16()) }
                    val numberOfPps = reader.readUInt8()
                    repeat(numberOfPps) { pps = reader.readBytes(reader.readUInt16()) }
                } catch (e: MuxException) {
                    // Keep what was parsed, like upstream.
                }
                return Avc(sps, pps)
            }
        }
    }

    data class Hevc(
        val videoParameterSet: ByteArray?,
        val sequenceParameterSet: ByteArray?,
        val pictureParameterSet: ByteArray?,
    ) : MpegTsVideoConfig {
        companion object {
            /** Parses an `hvcC` (ISO/IEC 14496-15 8.3.3.1.2) record. */
            fun fromHvcC(hvcC: ByteArray): Hevc {
                var vps: ByteArray? = null
                var sps: ByteArray? = null
                var pps: ByteArray? = null
                try {
                    val reader = ByteReader(hvcC)
                    reader.skipBytes(22)
                    val numberOfArrays = reader.readUInt8()
                    repeat(numberOfArrays) {
                        val nalUnitType = reader.readUInt8() and 0b0011_1111
                        val numNalus = reader.readUInt16()
                        repeat(numNalus) {
                            val data = reader.readBytes(reader.readUInt16())
                            when (nalUnitType) {
                                HevcNalUnitType.VPS -> vps = data
                                HevcNalUnitType.SPS -> sps = data
                                HevcNalUnitType.PPS -> pps = data
                            }
                        }
                    }
                } catch (e: MuxException) {
                    // Keep what was parsed, like upstream.
                }
                return Hevc(vps, sps, pps)
            }
        }
    }
}
