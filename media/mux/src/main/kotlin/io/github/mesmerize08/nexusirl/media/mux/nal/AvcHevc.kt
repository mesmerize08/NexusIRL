// Port of Moblin/Media/HaishinKit/Mpeg/Avc/*.swift and Hevc/*.swift @97e26d2
// (header, parameter set and timecode SEI coding; CoreMedia format descriptions are not ported).
package io.github.mesmerize08.nexusirl.media.mux.nal

import io.github.mesmerize08.nexusirl.media.mux.MuxException
import java.time.Instant
import java.time.ZoneOffset

object AvcNalUnitType {
    const val UNSPEC = 0
    const val SLICE = 1
    const val IDR = 5
    const val SEI = 6
    const val SPS = 7
    const val PPS = 8
    const val AUD = 9

    fun isPicture(type: Int): Boolean = type == SLICE || type == IDR
}

object HevcNalUnitType {
    const val VPS = 32
    const val SPS = 33
    const val PPS = 34
    const val ACCESS_UNIT_DELIMITER = 35
    const val PREFIX_SEI_NUT = 39
    const val UNSPEC = 0xFF

    fun isPicture(type: Int): Boolean = type <= 31
}

/** A SMPTE-style timecode payload, shared by AVC picture timing and HEVC time code SEI. */
data class SeiTimecode(val hours: Int, val minutes: Int, val seconds: Int, val frame: Long) {
    fun encode(): ByteArray {
        val writer = NalUnitWriter(emulationPrevention = false)
        writer.writeBits(1, 2) // num_clock_ts
        writer.writeBit(true) // clock_timestamp_flag
        writer.writeBit(true) // units_field_based_flag
        writer.writeBits(0, 5) // counting_type
        writer.writeBit(true) // full_timestamp_flag
        writer.writeBit(false) // discontinuity_flag
        writer.writeBit(false) // cnt_dropped_flag
        writer.writeBitsU32(frame, 9)
        writer.writeBits(seconds, 6)
        writer.writeBits(minutes, 6)
        writer.writeBits(hours, 5)
        writer.writeBits(0, 5) // time_offset_length
        writeMoreDataInPayload(writer)
        return writer.data
    }

    /** Clock time today (UTC) for this timecode; like upstream, wrong when close to midnight. */
    fun makeClock(now: Instant = Instant.now()): Pair<Instant, Long> {
        val startOfDay = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
        return startOfDay.plusSeconds(seconds + minutes * 60L + hours * 3600L) to frame
    }

    companion object {
        fun fromClock(clock: Instant, frame: Long): SeiTimecode {
            val time = clock.atZone(ZoneOffset.UTC)
            return SeiTimecode(time.hour, time.minute, time.second, frame)
        }

        /** Returns null on unsupported layouts, like upstream. */
        fun decode(reader: NalUnitReader): SeiTimecode? = try {
            if (reader.readBits(2) != 1 || !reader.readBit()) {
                null
            } else {
                reader.skipBits(1 + 5)
                val fullTimestampFlag = reader.readBit()
                reader.skipBits(1 + 1)
                val frame = reader.readBitsU32(9)
                if (!fullTimestampFlag) {
                    null
                } else {
                    val seconds = reader.readBits(6)
                    val minutes = reader.readBits(6)
                    val hours = reader.readBits(5)
                    SeiTimecode(hours, minutes, seconds, frame)
                }
            }
        } catch (e: MuxException) {
            null
        }
    }
}

private fun encodeSei(writer: NalUnitWriter, payloadType: Int, payload: ByteArray) {
    writer.writeBits(payloadType, 8)
    writer.writeBits(payload.size, 8)
    writer.writeBytes(payload)
    writeRbspTrailingBits(writer)
}

private fun decodeSei(reader: NalUnitReader, expectedType: Int): SeiTimecode {
    val type = reader.readBits(8)
    if (type == 0xFF) {
        throw MuxException("SEI message type too long")
    }
    val length = reader.readBits(8)
    if (length == 0xFF) {
        throw MuxException("SEI message length too long")
    }
    if (type != expectedType) {
        throw MuxException("Unsupported SEI payload type $type")
    }
    return SeiTimecode.decode(reader) ?: throw MuxException("Failed to decode timecode payload")
}

/** H.264 NAL unit with the payloads upstream understands. */
sealed interface AvcNalUnit {
    val type: Int

    fun encode(): ByteArray

    data class Sei(val pictureTiming: SeiTimecode) : AvcNalUnit {
        override val type = AvcNalUnitType.SEI

        override fun encode(): ByteArray {
            val writer = NalUnitWriter()
            encodeHeader(writer, 0, type)
            encodeSei(writer, PICTURE_TIMING, pictureTiming.encode())
            return writer.data
        }
    }

    /** SPS/PPS/other units, kept as raw RBSP. */
    class Raw(override val type: Int, val refIdc: Int, val rbsp: ByteArray) : AvcNalUnit {
        override fun encode(): ByteArray {
            val writer = NalUnitWriter()
            encodeHeader(writer, refIdc, type)
            writer.writeRawBytes(rbsp)
            return writer.data
        }
    }

    companion object {
        const val PICTURE_TIMING = 1

        /** Access unit delimiters with start codes, as written before key frames and other frames. */
        val AUD_10_WITH_START_CODE: ByteArray = NAL_UNIT_START_CODE + byteArrayOf(0x09, 0x10)
        val AUD_30_WITH_START_CODE: ByteArray = NAL_UNIT_START_CODE + byteArrayOf(0x09, 0x30)

        private fun encodeHeader(writer: NalUnitWriter, refIdc: Int, type: Int) {
            writer.writeBit(false)
            writer.writeBits(refIdc, 2)
            writer.writeBits(type, 5)
        }

        fun decode(data: ByteArray, offset: Int = 0): AvcNalUnit? = try {
            val reader = NalUnitReader(data, offset)
            reader.skipBits(1)
            val refIdc = reader.readBits(2)
            val type = reader.readBits(5)
            when (type) {
                AvcNalUnitType.SEI -> Sei(decodeSei(reader, PICTURE_TIMING))
                AvcNalUnitType.SPS, AvcNalUnitType.PPS -> Raw(type, refIdc, reader.readRawBytes())
                else -> Raw(type, refIdc, ByteArray(0))
            }
        } catch (e: MuxException) {
            null
        }
    }
}

/** H.265 NAL unit with the payloads upstream understands. */
sealed interface HevcNalUnit {
    val type: Int
    val temporalIdPlusOne: Int

    fun encode(): ByteArray

    data class PrefixSei(val timeCode: SeiTimecode, override val temporalIdPlusOne: Int = 1) : HevcNalUnit {
        override val type = HevcNalUnitType.PREFIX_SEI_NUT

        override fun encode(): ByteArray {
            val writer = NalUnitWriter()
            encodeHeader(writer, type, temporalIdPlusOne)
            encodeSei(writer, TIME_CODE, timeCode.encode())
            return writer.data
        }
    }

    /** VPS/SPS/PPS/other units, kept as raw RBSP. */
    class Raw(override val type: Int, override val temporalIdPlusOne: Int, val rbsp: ByteArray) : HevcNalUnit {
        override fun encode(): ByteArray {
            val writer = NalUnitWriter()
            encodeHeader(writer, type, temporalIdPlusOne)
            writer.writeRawBytes(rbsp)
            return writer.data
        }
    }

    companion object {
        const val TIME_CODE = 136

        private fun encodeHeader(writer: NalUnitWriter, type: Int, temporalIdPlusOne: Int) {
            writer.writeBit(false)
            writer.writeBits(type, 6)
            writer.writeBits(0, 6) // nuh_layer_id
            writer.writeBits(temporalIdPlusOne, 3)
        }

        fun decode(data: ByteArray, offset: Int = 0): HevcNalUnit? = try {
            val reader = NalUnitReader(data, offset)
            reader.skipBits(1)
            val type = reader.readBits(6)
            reader.readBits(6)
            val temporalIdPlusOne = reader.readBits(3)
            when (type) {
                HevcNalUnitType.PREFIX_SEI_NUT -> PrefixSei(decodeSei(reader, TIME_CODE), temporalIdPlusOne)
                HevcNalUnitType.VPS, HevcNalUnitType.SPS, HevcNalUnitType.PPS ->
                    Raw(type, temporalIdPlusOne, reader.readRawBytes())
                else -> Raw(type, temporalIdPlusOne, ByteArray(0))
            }
        } catch (e: MuxException) {
            null
        }
    }
}
