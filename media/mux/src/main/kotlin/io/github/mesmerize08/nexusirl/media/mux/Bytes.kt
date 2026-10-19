// Port of Moblin/Media/HaishinKit/Util/ByteReader.swift and ByteWriter.swift @97e26d2
package io.github.mesmerize08.nexusirl.media.mux

import java.io.ByteArrayOutputStream

/** Thrown when parsing runs out of data or finds malformed input. */
class MuxException(message: String) : Exception(message)

/** Big-endian reader over a byte array, mirroring upstream `ByteReader`. */
class ByteReader(val data: ByteArray, var position: Int = 0) {
    val bytesAvailable: Int
        get() = data.size - position

    private fun require(count: Int) {
        if (count < 0 || bytesAvailable < count) {
            throw MuxException("End of data")
        }
    }

    fun readUInt8(): Int {
        require(1)
        return data[position++].toInt() and 0xFF
    }

    fun readUInt16(): Int {
        require(2)
        return (readUInt8() shl 8) or readUInt8()
    }

    fun readUInt16Le(): Int = readUInt8() or (readUInt8() shl 8)

    fun readUInt24(): Int = (readUInt8() shl 16) or (readUInt8() shl 8) or readUInt8()

    fun readUInt24Le(): Int = readUInt8() or (readUInt8() shl 8) or (readUInt8() shl 16)

    fun readUInt32(): Long {
        require(4)
        return (readUInt16().toLong() shl 16) or readUInt16().toLong()
    }

    fun readUInt32Le(): Long = readUInt16Le().toLong() or (readUInt16Le().toLong() shl 16)

    fun readUInt64(): Long = (readUInt32() shl 32) or readUInt32()

    /** Big-endian IEEE 754 double (AMF0 number). */
    fun readDouble(): Double = java.lang.Double.longBitsToDouble(readUInt64())

    fun readUtf8Bytes(length: Int): String {
        val bytes = readBytes(length)
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw MuxException("Invalid UTF-8")
        }
    }

    fun readBytes(length: Int): ByteArray {
        require(length)
        val result = data.copyOfRange(position, position + length)
        position += length
        return result
    }

    fun skipBytes(length: Int) {
        require(length)
        position += length
    }
}

/** Big-endian append-only writer, mirroring upstream `ByteWriter`. */
class ByteWriter(initialCapacity: Int = 64) {
    private val stream = object : ByteArrayOutputStream(initialCapacity) {
        fun buffer(): ByteArray = buf
    }

    val size: Int
        get() = stream.size()

    fun writeUInt8(value: Int) {
        stream.write(value and 0xFF)
    }

    fun writeUInt16(value: Int) {
        writeUInt8(value ushr 8)
        writeUInt8(value)
    }

    fun writeUInt16Le(value: Int) {
        writeUInt8(value)
        writeUInt8(value ushr 8)
    }

    fun writeUInt24(value: Int) {
        writeUInt8(value ushr 16)
        writeUInt8(value ushr 8)
        writeUInt8(value)
    }

    fun writeUInt24Le(value: Int) {
        writeUInt8(value)
        writeUInt8(value ushr 8)
        writeUInt8(value ushr 16)
    }

    fun writeUInt32(value: Long) {
        writeUInt16((value ushr 16).toInt())
        writeUInt16(value.toInt())
    }

    fun writeUInt32Le(value: Long) {
        writeUInt16Le(value.toInt())
        writeUInt16Le((value ushr 16).toInt())
    }

    fun writeUInt64(value: Long) {
        writeUInt32(value ushr 32)
        writeUInt32(value and 0xFFFF_FFFFL)
    }

    fun writeInt32(value: Int) = writeUInt32(value.toLong())

    /** Big-endian IEEE 754 double (AMF0 number). */
    fun writeDouble(value: Double) = writeUInt64(java.lang.Double.doubleToRawLongBits(value))

    fun writeUtf8Bytes(value: String) = writeBytes(value.toByteArray(Charsets.UTF_8))

    fun writeBytes(value: ByteArray, offset: Int = 0, length: Int = value.size - offset) {
        stream.write(value, offset, length)
    }

    fun toByteArray(): ByteArray = stream.toByteArray()
}

/** Splits into chunks of at most [size] bytes (upstream `Data.chunks`). */
fun ByteArray.chunks(size: Int): List<ByteArray> {
    require(size > 0)
    val result = ArrayList<ByteArray>((this.size + size - 1) / size)
    var offset = 0
    while (offset < this.size) {
        val end = minOf(offset + size, this.size)
        result.add(copyOfRange(offset, end))
        offset = end
    }
    return result
}
