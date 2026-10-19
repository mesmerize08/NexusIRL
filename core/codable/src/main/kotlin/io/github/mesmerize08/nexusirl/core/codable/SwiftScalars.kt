package io.github.mesmerize08.nexusirl.core.codable

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** Swift `UUID`: encoded as an uppercase canonical string, decoded case-insensitively. */
object SwiftUuidSerializer : KSerializer<UUID> {
    private val uuidPattern =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.mesmerize08.nexusirl.SwiftUuid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString().uppercase(Locale.ROOT))
    }

    override fun deserialize(decoder: Decoder): UUID {
        val text = decoder.decodeString()
        if (!uuidPattern.matches(text)) {
            throw SerializationException("Invalid UUID string: $text")
        }
        return UUID.fromString(text)
    }
}

/** Seconds between the Unix epoch and Apple's reference date, 2001-01-01T00:00:00Z. */
const val APPLE_REFERENCE_DATE_OFFSET_SECONDS: Long = 978_307_200L

/**
 * Swift `Date` with the default `JSONEncoder.DateEncodingStrategy.deferredToDate`: a number of
 * seconds since 2001-01-01T00:00:00Z (not the Unix epoch).
 */
object AppleDateSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.mesmerize08.nexusirl.AppleDate", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Instant) {
        SwiftDoubleSerializer.serialize(encoder, toAppleSeconds(value))
    }

    override fun deserialize(decoder: Decoder): Instant = fromAppleSeconds(decoder.decodeDouble())

    fun toAppleSeconds(instant: Instant): Double =
        (instant.epochSecond - APPLE_REFERENCE_DATE_OFFSET_SECONDS) + instant.nano / 1e9

    fun fromAppleSeconds(seconds: Double): Instant {
        val whole = Math.floor(seconds)
        val nanos = Math.round((seconds - whole) * 1e9)
        return Instant.ofEpochSecond(whole.toLong() + APPLE_REFERENCE_DATE_OFFSET_SECONDS, nanos)
    }
}

/** Swift `Data`: a standard (padded, no line breaks) base64 string. */
object SwiftDataSerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.mesmerize08.nexusirl.SwiftData", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray {
        val text = decoder.decodeString()
        return try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw SerializationException("Invalid base64 data: ${e.message}")
        }
    }
}

/** Swift `UUID`. */
typealias SUuid = @Serializable(with = SwiftUuidSerializer::class) UUID

/** Swift `Date`. */
typealias SDate = @Serializable(with = AppleDateSerializer::class) Instant

/** Swift `Data`. */
typealias SData = @Serializable(with = SwiftDataSerializer::class) ByteArray
