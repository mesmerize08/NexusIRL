package io.github.mesmerize08.nexusirl.core.codable

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlin.reflect.KClass

/** One case of a Swift enum with (optional) associated values. */
class SwiftEnumCase<T : Any>(
    val name: String,
    val type: KClass<out T>,
    val serializer: KSerializer<out T>,
)

/** Creates a [SwiftEnumCase] for [SwiftEnumSerializer]. */
inline fun <reified C : Any> swiftCase(name: String, serializer: KSerializer<C>): SwiftEnumCase<C> =
    SwiftEnumCase(name, C::class, serializer)

/**
 * Serializer for a Swift enum whose cases may carry associated values, as encoded by Swift's
 * synthesized `Codable` conformance:
 *
 * - `case ok` encodes as `{"ok":{}}`
 * - `case setZoom(x: Float)` encodes as `{"setZoom":{"x":2}}`
 * - unlabeled values `case foo(Int)` encode as `{"foo":{"_0":1}}` (use `@SerialName("_0")`)
 *
 * Model the enum as a sealed interface with one `@Serializable` data class or data object per
 * case. Decoding requires exactly one key, like Swift. An unknown case decodes to [fallback] when
 * one is given, which keeps newer iOS peers from breaking older Android builds.
 */
abstract class SwiftEnumSerializer<T : Any>(
    serialName: String,
    cases: List<SwiftEnumCase<out T>>,
    private val fallback: T? = null,
) : KSerializer<T> {
    private val casesByName = cases.associateBy { it.name }
    private val casesByType = cases.associateBy { it.type }

    init {
        require(casesByName.size == cases.size) { "Duplicate case name in $serialName" }
        require(casesByType.size == cases.size) { "Duplicate case type in $serialName" }
    }

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName)

    override fun serialize(encoder: Encoder, value: T) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("${descriptor.serialName} supports JSON only")
        val case = casesByType[value::class]
            ?: throw SerializationException("${value::class} is not a case of ${descriptor.serialName}")

        @Suppress("UNCHECKED_CAST")
        val payload = jsonEncoder.json.encodeToJsonElement(case.serializer as KSerializer<T>, value)
        jsonEncoder.encodeJsonElement(JsonObject(mapOf(case.name to payload)))
    }

    override fun deserialize(decoder: Decoder): T {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("${descriptor.serialName} supports JSON only")
        val element = jsonDecoder.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("${descriptor.serialName}: expected an object")
        if (element.size != 1) {
            throw SerializationException(
                "${descriptor.serialName}: expected exactly one case key, got ${element.keys}",
            )
        }
        val (name, payload) = element.entries.first()
        val case = casesByName[name]
            ?: return fallback
                ?: throw SerializationException("${descriptor.serialName}: unknown case '$name'")
        return jsonDecoder.json.decodeFromJsonElement(case.serializer, payload)
    }
}

/** A Swift enum with `String` raw values, e.g. `enum MoblinkThermalState: String { case white }`. */
interface SwiftRawValueEnum {
    val rawValue: String
}

/**
 * Serializer for a Swift `String` raw-value enum. Unknown raw values decode to [fallback] when
 * given (Moblin's settings decoder does the same), otherwise decoding fails like Swift's.
 */
abstract class SwiftRawValueEnumSerializer<E>(
    serialName: String,
    entries: List<E>,
    private val fallback: E? = null,
) : KSerializer<E> where E : Enum<E>, E : SwiftRawValueEnum {
    private val byRawValue = entries.associateBy { it.rawValue }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) {
        encoder.encodeString(value.rawValue)
    }

    override fun deserialize(decoder: Decoder): E {
        val raw = decoder.decodeString()
        return byRawValue[raw] ?: fallback
            ?: throw SerializationException("${descriptor.serialName}: unknown raw value '$raw'")
    }
}

/**
 * A Swift enum without associated or raw values, e.g. `enum RemoteControlFilter { case grayScale }`,
 * which Swift encodes as `{"grayScale":{}}`. [caseName] gives each Kotlin constant its Swift case
 * name; unknown cases decode to [fallback] when given.
 */
abstract class SwiftUnitEnumSerializer<E : Enum<E>>(
    serialName: String,
    entries: List<E>,
    private val caseName: (E) -> String,
    private val fallback: E? = null,
) : KSerializer<E> {
    private val byName = entries.associateBy(caseName)

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName)

    override fun serialize(encoder: Encoder, value: E) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("${descriptor.serialName} supports JSON only")
        jsonEncoder.encodeJsonElement(JsonObject(mapOf(caseName(value) to JsonObject(emptyMap()))))
    }

    override fun deserialize(decoder: Decoder): E {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("${descriptor.serialName} supports JSON only")
        val element = jsonDecoder.decodeJsonElement() as? JsonObject
        val name = element?.keys?.singleOrNull()
            ?: throw SerializationException("${descriptor.serialName}: expected exactly one case key")
        return byName[name] ?: fallback ?: throw SerializationException("${descriptor.serialName}: unknown case '$name'")
    }
}
