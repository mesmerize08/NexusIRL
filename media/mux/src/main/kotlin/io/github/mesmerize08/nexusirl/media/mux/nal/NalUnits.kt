// Port of Moblin/Media/HaishinKit/Mpeg/NalUnitReader.swift, NalUnitWriter.swift and
// NalUnitStream.swift @97e26d2
package io.github.mesmerize08.nexusirl.media.mux.nal

import io.github.mesmerize08.nexusirl.media.mux.MuxException
import java.io.ByteArrayOutputStream

const val NAL_UNIT_EMULATION_PREVENTION_BYTE = 0x03

val NAL_UNIT_START_CODE: ByteArray = byteArrayOf(0x00, 0x00, 0x00, 0x01)

/** MSB-first bit reader that skips emulation prevention bytes (`00 00 03`). */
class NalUnitReader(private val data: ByteArray, offset: Int = 0) {
    private var byteOffset = offset
    private var bitOffset = 7

    fun available(): Int = 8 * (data.size - byteOffset) - (7 - bitOffset)

    fun readRawBytes(): ByteArray {
        checkOutOfData()
        if (bitOffset != 7) {
            throw MuxException("Cannot read remaining bytes when not at byte boundary")
        }
        return data.copyOfRange(byteOffset, data.size)
    }

    fun readBit(): Boolean {
        checkOutOfData()
        val value = (data[byteOffset].toInt() shr bitOffset) and 1 == 1
        bitOffset -= 1
        if (bitOffset == -1) {
            bitOffset = 7
            byteOffset += 1
            if (available() > 0 &&
                byteOffset >= 2 &&
                data[byteOffset - 2].toInt() == 0 &&
                data[byteOffset - 1].toInt() == 0 &&
                data[byteOffset].toInt() == NAL_UNIT_EMULATION_PREVENTION_BYTE
            ) {
                byteOffset += 1
            }
        }
        return value
    }

    fun skipBits(count: Int) {
        repeat(count) { readBit() }
    }

    fun readBits(count: Int): Int {
        var value = 0
        repeat(count) {
            value = (value shl 1) or (if (readBit()) 1 else 0)
        }
        return value and 0xFF
    }

    fun readBitsU32(count: Int): Long {
        var value = 0L
        repeat(count) {
            value = (value shl 1) or (if (readBit()) 1L else 0L)
        }
        return value and 0xFFFF_FFFFL
    }

    private fun checkOutOfData() {
        if (byteOffset >= data.size) {
            throw MuxException("Out of data")
        }
    }
}

/** MSB-first bit writer that inserts emulation prevention bytes when enabled. */
class NalUnitWriter(private val emulationPrevention: Boolean = true) {
    private var bytes = ByteArray(16)
    private var size = 0
    var bitOffset = 0
        private set

    val data: ByteArray
        get() = bytes.copyOf(size)

    private fun append(value: Int) {
        if (size == bytes.size) {
            bytes = bytes.copyOf(bytes.size * 2)
        }
        bytes[size++] = value.toByte()
    }

    fun writeBit(value: Boolean) {
        if (bitOffset == 0) {
            append(0)
        }
        if (value) {
            bytes[size - 1] = (bytes[size - 1].toInt() or (1 shl (7 - bitOffset))).toByte()
        }
        bitOffset = (bitOffset + 1) % 8
        if (bitOffset == 0) {
            insertEmulationPreventionByteIfNeeded()
        }
    }

    private fun insertEmulationPreventionByteIfNeeded() {
        if (!emulationPrevention ||
            size < 3 ||
            (bytes[size - 1].toInt() and 0xFF) > NAL_UNIT_EMULATION_PREVENTION_BYTE ||
            bytes[size - 2].toInt() != 0 ||
            bytes[size - 3].toInt() != 0
        ) {
            return
        }
        val last = bytes[size - 1]
        bytes[size - 1] = NAL_UNIT_EMULATION_PREVENTION_BYTE.toByte()
        append(last.toInt())
    }

    fun writeBits(value: Int, count: Int) {
        for (i in 0 until count) {
            writeBit((value shr (count - i - 1)) and 1 == 1)
        }
    }

    fun writeBitsU32(value: Long, count: Int) {
        for (i in 0 until count) {
            writeBit((value shr (count - i - 1)) and 1L == 1L)
        }
    }

    fun writeBytes(data: ByteArray) {
        for (value in data) {
            writeBits(value.toInt() and 0xFF, 8)
        }
    }

    fun writeRawBytes(data: ByteArray) {
        for (value in data) {
            append(value.toInt())
        }
    }
}

/** Writes `rbsp_trailing_bits()`. */
fun writeRbspTrailingBits(writer: NalUnitWriter) {
    writer.writeBit(true)
    while (writer.bitOffset != 0) {
        writer.writeBit(false)
    }
}

/** Pads an SEI payload to a byte boundary with `1 0 0 ...` (upstream `writeMoreDataInPayload`). */
fun writeMoreDataInPayload(writer: NalUnitWriter) {
    var padding = true
    while (writer.bitOffset != 0) {
        writer.writeBit(padding)
        padding = false
    }
}

/** Location of one NAL unit inside an Annex-B byte stream. */
data class NalUnitInfo(val startCodeOffset: Int, val startCodeLength: Int, val dataLength: Int) {
    fun dataOffset(): Int = startCodeOffset + startCodeLength
}

/** Finds all NAL units in an Annex-B byte stream (3- or 4-byte start codes), in stream order. */
fun getNalUnits(data: ByteArray): List<NalUnitInfo> {
    val nalUnits = ArrayList<NalUnitInfo>()
    var lastIndexOf = data.size - 1
    var index = lastIndexOf - 2
    while (index >= 0) {
        if ((data[index].toInt() and 0xFF) > 1) {
            index -= 3
            continue
        }
        if (data[index + 2].toInt() != 1 || data[index + 1].toInt() != 0 || data[index].toInt() != 0) {
            index -= 1
            continue
        }
        val startCodeLength = if (index - 1 >= 0 && data[index - 1].toInt() == 0) 4 else 3
        val dataLength = lastIndexOf - index - 2
        if (dataLength <= 0) {
            index -= 1
            continue
        }
        val startCodeIndex = index + 3 - startCodeLength
        nalUnits.add(NalUnitInfo(startCodeIndex, startCodeLength, dataLength))
        lastIndexOf = startCodeIndex - 1
        index = lastIndexOf
    }
    nalUnits.reverse()
    return nalUnits
}

/** Converts a 4-byte length-prefixed (AVCC/HVCC) access unit to Annex-B in place. */
fun addNalUnitStartCodes(data: ByteArray) {
    var index = 0
    while (index + 3 < data.size) {
        val length = ((data[index].toInt() and 0xFF) shl 24) or
            ((data[index + 1].toInt() and 0xFF) shl 16) or
            ((data[index + 2].toInt() and 0xFF) shl 8) or
            (data[index + 3].toInt() and 0xFF)
        NAL_UNIT_START_CODE.copyInto(data, index)
        index += length + 4
    }
}

/** Converts an Annex-B access unit to 4-byte length-prefixed (AVCC/HVCC) form, e.g. for FLV/RTMP. */
fun annexBToLengthPrefixed(data: ByteArray): ByteArray {
    val nalUnits = getNalUnits(data)
    val output = ByteArrayOutputStream(data.size + nalUnits.size)
    for (nalUnit in nalUnits) {
        val length = nalUnit.dataLength
        output.write(length ushr 24)
        output.write(length ushr 16)
        output.write(length ushr 8)
        output.write(length)
        output.write(data, nalUnit.dataOffset(), length)
    }
    return output.toByteArray()
}
