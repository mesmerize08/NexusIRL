// Port of Moblin/Media/HaishinKit/Mpeg/MpegTsPacket.swift and MpegTsAdaptationField.swift @97e26d2
package io.github.mesmerize08.nexusirl.media.mux.ts

import io.github.mesmerize08.nexusirl.media.mux.ByteReader
import io.github.mesmerize08.nexusirl.media.mux.ByteWriter
import io.github.mesmerize08.nexusirl.media.mux.MuxException

/** An MPEG transport stream packet (always 188 bytes when encoded). */
class MpegTsPacket(var id: Int) {
    var payloadUnitStartIndicator = false
    var continuityCounter = 0
    var adaptationField: MpegTsAdaptationField? = null
    var payload: ByteArray = EMPTY
    var payloadOffset = 0
    var payloadLength = 0

    fun setPayload(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        payload = data
        payloadOffset = offset
        payloadLength = length
    }

    fun maximumPayloadSize(): Int = SIZE - FIXED_HEADER_SIZE - (adaptationField?.calcLength() ?: 0)

    fun setAdaptationFieldStuffing(size: Int) {
        adaptationField?.setStuffing(size)
    }

    /** Writes the 4-byte fixed header into [buffer] at [offset]. */
    fun encodeFixedHeaderInto(buffer: ByteArray, offset: Int) {
        buffer[offset] = SYNC_BYTE.toByte()
        buffer[offset + 1] = ((if (payloadUnitStartIndicator) 0x40 else 0) or (id ushr 8)).toByte()
        buffer[offset + 2] = (id and 0xFF).toByte()
        buffer[offset + 3] = ((if (adaptationField != null) 0x20 else 0) or 0x10 or continuityCounter).toByte()
    }

    /** Encodes into [buffer] at [offset] and returns the number of bytes written. */
    fun encodeInto(buffer: ByteArray, offset: Int): Int {
        encodeFixedHeaderInto(buffer, offset)
        var position = offset + FIXED_HEADER_SIZE
        adaptationField?.let {
            val encoded = it.encode()
            encoded.copyInto(buffer, position)
            position += encoded.size
        }
        payload.copyInto(buffer, position, payloadOffset, payloadOffset + payloadLength)
        position += payloadLength
        return position - offset
    }

    fun encode(): ByteArray {
        val size = FIXED_HEADER_SIZE + (adaptationField?.calcLength() ?: 0) + payloadLength
        val buffer = ByteArray(size)
        encodeInto(buffer, 0)
        return buffer
    }

    companion object {
        const val SIZE = 188
        const val FIXED_HEADER_SIZE = 4
        const val SYNC_BYTE = 0x47
        private val EMPTY = ByteArray(0)

        fun decode(reader: ByteReader): MpegTsPacket {
            val startPosition = reader.position
            if (reader.readUInt8() != SYNC_BYTE) {
                throw MuxException("Invalid sync byte")
            }
            var byte = reader.readUInt8()
            val payloadUnitStartIndicator = (byte and 0x40) == 0x40
            var id = (byte and 0x1F) shl 8
            id = id or reader.readUInt8()
            val packet = MpegTsPacket(id)
            packet.payloadUnitStartIndicator = payloadUnitStartIndicator
            byte = reader.readUInt8()
            packet.continuityCounter = byte and 0xF
            if ((byte and 0x20) == 0x20) {
                val length = reader.readUInt8()
                if (length > 0) {
                    packet.adaptationField = MpegTsAdaptationField.decode(reader, length)
                }
            }
            if ((byte and 0x10) == 0x10) {
                packet.setPayload(reader.readBytes(SIZE - (reader.position - startPosition)))
            }
            return packet
        }
    }
}

class MpegTsAdaptationField {
    var randomAccessIndicator = false
    var programClockReference: ByteArray? = null
    var stuffingBytes: ByteArray? = null

    fun calcLength(): Int = FIXED_SECTION_SIZE + (programClockReference?.size ?: 0) + (stuffingBytes?.size ?: 0)

    fun setStuffing(size: Int) {
        stuffingBytes = ByteArray(size) { 0xFF.toByte() }
    }

    fun encode(): ByteArray {
        var flags = 0
        if (randomAccessIndicator) {
            flags = flags or 0x40
        }
        if (programClockReference != null) {
            flags = flags or 0x10
        }
        val writer = ByteWriter()
        writer.writeUInt8(calcLength() - 1)
        writer.writeUInt8(flags)
        programClockReference?.let { writer.writeBytes(it) }
        stuffingBytes?.let { writer.writeBytes(it) }
        return writer.toByteArray()
    }

    companion object {
        const val FIXED_SECTION_SIZE = 2

        fun decode(reader: ByteReader, length: Int): MpegTsAdaptationField {
            val startPosition = reader.position
            val field = MpegTsAdaptationField()
            val byte = reader.readUInt8()
            field.randomAccessIndicator = (byte and 0x40) == 0x40
            if ((byte and 0x10) == 0x10) {
                field.programClockReference = reader.readBytes(6)
            }
            val stuffingCount = (startPosition + length) - reader.position
            if (stuffingCount > 0) {
                reader.skipBytes(stuffingCount)
            }
            return field
        }
    }
}

/** 33-bit PES timestamp coding (PTS/DTS). */
object TsTimestamp {
    const val RESOLUTION = 90_000.0
    const val DATA_SIZE = 5

    fun encode(value: Long, marker: Int): ByteArray {
        val b = value and 0x1_FFFF_FFFFL
        return byteArrayOf(
            ((b ushr 29).toInt() or 0x01 or marker).toByte(),
            (b ushr 22).toInt().toByte(),
            ((b ushr 14).toInt() or 0x01).toByte(),
            (b ushr 7).toInt().toByte(),
            ((b shl 1).toInt() or 0x01).toByte(),
        )
    }

    fun decode(data: ByteArray, offset: Int = 0): Long? {
        if (data.size < offset + DATA_SIZE) {
            return null
        }
        fun at(i: Int): Long = (data[offset + i].toLong() and 0xFF)
        return ((at(0) and 0x0E) shl 29) or
            (at(1) shl 22) or
            ((at(2) and 0xFE) shl 14) or
            (at(3) shl 7) or
            ((at(4) and 0xFE) ushr 1)
    }
}

/** Program clock reference coding, bit-exact with upstream (including its reserved-bit handling). */
object TsProgramClockReference {
    fun encode(base: Long, extension: Int): ByteArray {
        val encoded = ByteArray(6)
        encoded[0] = (base ushr 25).toInt().toByte()
        encoded[1] = (base ushr 17).toInt().toByte()
        encoded[2] = (base ushr 9).toInt().toByte()
        encoded[3] = (base ushr 1).toInt().toByte()
        // Upstream sets byte 4 to 0xFF, puts the base LSB in bit 7 and always clears bit 0.
        var byte4 = 0xFF
        byte4 = if ((base and 1L) == 1L) byte4 or 0x80 else byte4 and 0x7F
        byte4 = byte4 and 0xFE
        encoded[4] = byte4.toByte()
        encoded[5] = extension.toByte()
        return encoded
    }
}
