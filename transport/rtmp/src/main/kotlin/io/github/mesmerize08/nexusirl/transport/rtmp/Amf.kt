// Port of Moblin/Media/HaishinKit/Rtmp/Amf/Amf.swift @97e26d2
package io.github.mesmerize08.nexusirl.transport.rtmp

import io.github.mesmerize08.nexusirl.media.mux.ByteReader
import io.github.mesmerize08.nexusirl.media.mux.ByteWriter
import io.github.mesmerize08.nexusirl.media.mux.MuxException
import java.time.Instant

/** AMF0 object: an insertion-ordered key/value map (servers see keys in the order written). */
typealias AsObject = Map<String, AsValue>

/** An AMF0 value (upstream `AsValue`). */
sealed interface AsValue {
    data class Number(val value: Double) : AsValue

    data class Bool(val value: Boolean) : AsValue

    data class Str(val value: String) : AsValue

    data class Object(val value: AsObject) : AsValue

    data object Null : AsValue

    data object Undefined : AsValue

    data object Reference : AsValue

    data class EcmaArray(val items: AsObject) : AsValue

    data class StrictArray(val items: List<AsValue>) : AsValue

    /** Milliseconds-precision date. */
    data class Date(val value: Instant) : AsValue

    data object Unsupported : AsValue

    data class XmlDocument(val data: String) : AsValue

    data class TypedObject(val type: String, val value: AsObject) : AsValue

    data object Avmplush : AsValue
}

class AmfException(message: String) : Exception(message)

private object Amf0Type {
    const val NUMBER = 0x00
    const val BOOL = 0x01
    const val STRING = 0x02
    const val OBJECT = 0x03
    const val NULL = 0x05
    const val UNDEFINED = 0x06
    const val REFERENCE = 0x07
    const val ECMA_ARRAY = 0x08
    const val OBJECT_END = 0x09
    const val STRICT_ARRAY = 0x0A
    const val DATE = 0x0B
    const val LONG_STRING = 0x0C
    const val UNSUPPORTED = 0x0D
    const val XML_DOCUMENT = 0x0F
    const val TYPED_OBJECT = 0x10
    const val AVMPLUSH = 0x11
}

class Amf0Encoder {
    private val writer = ByteWriter()

    val data: ByteArray
        get() = writer.toByteArray()

    fun writeUInt8(value: Int) = writer.writeUInt8(value)

    fun encode(value: AsValue) {
        when (value) {
            is AsValue.Number -> {
                writer.writeUInt8(Amf0Type.NUMBER)
                writer.writeDouble(value.value)
            }
            is AsValue.Date -> {
                writer.writeUInt8(Amf0Type.DATE)
                writer.writeDouble(value.value.toEpochMilli().toDouble())
                writer.writeUInt16(0)
            }
            is AsValue.Str -> encodeString(value.value)
            is AsValue.Bool -> {
                writer.writeUInt8(Amf0Type.BOOL)
                writer.writeUInt8(if (value.value) 1 else 0)
            }
            // Upstream's encoder writes nothing for ECMA arrays (never sent by the client).
            is AsValue.EcmaArray -> Unit
            is AsValue.Object -> {
                writer.writeUInt8(Amf0Type.OBJECT)
                for ((key, item) in value.value) {
                    encodeShortString(key)
                    encode(item)
                }
                encodeShortString("")
                writer.writeUInt8(Amf0Type.OBJECT_END)
            }
            AsValue.Null -> writer.writeUInt8(Amf0Type.NULL)
            else -> writer.writeUInt8(Amf0Type.UNDEFINED)
        }
    }

    private fun encodeString(value: String) {
        val data = value.toByteArray(Charsets.UTF_8)
        if (data.size > 0xFFFF) {
            writer.writeUInt8(Amf0Type.LONG_STRING)
            writer.writeUInt32(data.size.toLong())
        } else {
            writer.writeUInt8(Amf0Type.STRING)
            writer.writeUInt16(data.size)
        }
        writer.writeBytes(data)
    }

    private fun encodeShortString(value: String) {
        val data = value.toByteArray(Charsets.UTF_8)
        writer.writeUInt16(data.size)
        writer.writeBytes(data)
    }
}

class Amf0Decoder(data: ByteArray) {
    private val reader = ByteReader(data)
    private var depth = 0

    var position: Int
        get() = reader.position
        set(value) {
            reader.position = value
        }

    val bytesAvailable: Int
        get() = reader.bytesAvailable

    fun decode(): AsValue {
        if (depth >= 32) {
            throw AmfException("Too deep")
        }
        depth += 1
        try {
            return when (val type = readType()) {
                Amf0Type.NUMBER -> AsValue.Number(reader.readDouble())
                Amf0Type.BOOL -> AsValue.Bool(reader.readUInt8() == 0x01)
                Amf0Type.STRING -> AsValue.Str(decodeStringValue())
                Amf0Type.OBJECT -> AsValue.Object(decodeObjectValue())
                Amf0Type.NULL -> AsValue.Null
                Amf0Type.UNDEFINED -> AsValue.Undefined
                Amf0Type.REFERENCE -> AsValue.Reference
                Amf0Type.ECMA_ARRAY -> {
                    // The length is always 0 from DigitalOcean, so it cannot be used.
                    readNumberOfArrayElements()
                    AsValue.EcmaArray(decodeObjectValue())
                }
                Amf0Type.STRICT_ARRAY -> {
                    val count = readNumberOfArrayElements()
                    AsValue.StrictArray(List(count) { decode() })
                }
                Amf0Type.DATE -> {
                    val millis = reader.readDouble()
                    reader.readUInt16()
                    AsValue.Date(Instant.ofEpochMilli(millis.toLong()))
                }
                Amf0Type.LONG_STRING -> AsValue.Str(decodeLongStringValue())
                Amf0Type.UNSUPPORTED -> AsValue.Unsupported
                Amf0Type.XML_DOCUMENT -> AsValue.XmlDocument(decodeLongStringValue())
                Amf0Type.TYPED_OBJECT -> {
                    val typeName = decodeStringValue()
                    AsValue.TypedObject(typeName, decodeObjectValue())
                }
                Amf0Type.AVMPLUSH -> AsValue.Avmplush
                Amf0Type.OBJECT_END -> throw AmfException("Unexpected object end")
                else -> throw AmfException("Not AMF0 type $type")
            }
        } finally {
            depth -= 1
        }
    }

    /** Decodes a number and truncates it to an integer; non-finite or huge values are rejected. */
    fun decodeInt(): Long {
        if (readType() != Amf0Type.NUMBER) {
            throw AmfException("Not number")
        }
        val value = reader.readDouble()
        if (!value.isFinite() || value >= 9.223372036854776E18 || value < -9.223372036854776E18) {
            throw AmfException("Not number")
        }
        return value.toLong()
    }

    fun decodeString(): String = when (readType()) {
        Amf0Type.STRING -> decodeStringValue()
        Amf0Type.LONG_STRING -> decodeLongStringValue()
        else -> throw AmfException("Not string")
    }

    fun decodeObject(): AsObject = when (readType()) {
        Amf0Type.NULL -> emptyMap()
        Amf0Type.OBJECT -> decodeObjectValue()
        else -> throw AmfException("Not object")
    }

    private fun decodeObjectValue(): AsObject {
        val result = LinkedHashMap<String, AsValue>()
        while (true) {
            val key = decodeStringValue()
            if (key.isEmpty()) {
                break
            }
            result[key] = decode()
        }
        if (reader.readUInt8() != Amf0Type.OBJECT_END) {
            throw AmfException("Not object end")
        }
        return result
    }

    private fun readNumberOfArrayElements(): Int {
        val count = reader.readUInt32()
        if (count >= 128) {
            throw AmfException("Array too big")
        }
        return count.toInt()
    }

    private fun decodeStringValue(): String = reader.readUtf8Bytes(reader.readUInt16())

    private fun decodeLongStringValue(): String {
        val length = reader.readUInt32()
        if (length > Int.MAX_VALUE) {
            throw MuxException("String too long")
        }
        return reader.readUtf8Bytes(length.toInt())
    }

    private fun readType(): Int = reader.readUInt8()
}
