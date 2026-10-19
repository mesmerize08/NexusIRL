package io.github.mesmerize08.nexusirl.core.codable

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal

/**
 * Formats a floating point number the way Swift's `JSONEncoder` does: integral values have no
 * fractional part (`1`, not `1.0`), other values use the shortest round-trip representation
 * without an exponent in the common range.
 *
 * [shortest] is the shortest round-trip decimal string for the value's original precision
 * (`Float.toString()` for floats, so `0.1f` stays `0.1`).
 */
fun formatSwiftNumber(value: Double, shortest: String = value.toString()): String {
    if (!value.isFinite()) {
        throw SerializationException("Swift JSONEncoder cannot encode non-finite number $value")
    }
    if (value == 0.0) {
        return "0"
    }
    val magnitude = Math.abs(value)
    if (value == Math.rint(value) && magnitude < 1e15) {
        return value.toLong().toString()
    }
    if (magnitude >= 1e-4 && magnitude < 1e15) {
        return BigDecimal(shortest).stripTrailingZeros().toPlainString()
    }
    return shortest.lowercase()
}

@OptIn(ExperimentalSerializationApi::class)
private inline fun Encoder.encodeSwiftNumber(text: String, fallback: () -> Unit) {
    if (this is JsonEncoder) {
        encodeJsonElement(JsonUnquotedLiteral(text))
    } else {
        fallback()
    }
}

object SwiftDoubleSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.mesmerize08.nexusirl.SwiftDouble", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Double) {
        encoder.encodeSwiftNumber(formatSwiftNumber(value)) { encoder.encodeDouble(value) }
    }

    override fun deserialize(decoder: Decoder): Double = decoder.decodeDouble()
}

object SwiftFloatSerializer : KSerializer<Float> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.mesmerize08.nexusirl.SwiftFloat", PrimitiveKind.FLOAT)

    override fun serialize(encoder: Encoder, value: Float) {
        encoder.encodeSwiftNumber(formatSwiftNumber(value.toDouble(), value.toString())) {
            encoder.encodeFloat(value)
        }
    }

    override fun deserialize(decoder: Decoder): Float = decoder.decodeFloat()
}

/** Swift `Double`. Use instead of `Double` in wire types so integral values encode as `1`. */
typealias SDouble = @Serializable(with = SwiftDoubleSerializer::class) Double

/** Swift `Float`. Use instead of `Float` in wire types so integral values encode as `1`. */
typealias SFloat = @Serializable(with = SwiftFloatSerializer::class) Float
