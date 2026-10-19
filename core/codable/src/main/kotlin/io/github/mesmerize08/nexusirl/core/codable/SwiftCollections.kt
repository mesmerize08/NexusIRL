package io.github.mesmerize08.nexusirl.core.codable

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * A Swift `Dictionary` whose key type is neither `String` nor `Int` (for example an enum with
 * associated values). Swift encodes such a dictionary as a flat array `[key, value, key, value]`,
 * e.g. remote control `filters: [RemoteControlFilter: Bool]`.
 */
open class SwiftKeyedArrayMapSerializer<K, V>(
    private val keySerializer: KSerializer<K>,
    private val valueSerializer: KSerializer<V>,
) : KSerializer<Map<K, V>> {
    override val descriptor: SerialDescriptor = ListSerializer(keySerializer).descriptor

    override fun serialize(encoder: Encoder, value: Map<K, V>) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("SwiftKeyedArrayMapSerializer supports JSON only")
        val json = jsonEncoder.json
        val elements = ArrayList<JsonElement>(value.size * 2)
        for ((key, item) in value) {
            elements.add(json.encodeToJsonElement(keySerializer, key))
            elements.add(json.encodeToJsonElement(valueSerializer, item))
        }
        jsonEncoder.encodeJsonElement(JsonArray(elements))
    }

    override fun deserialize(decoder: Decoder): Map<K, V> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("SwiftKeyedArrayMapSerializer supports JSON only")
        val array = jsonDecoder.decodeJsonElement() as? JsonArray
            ?: throw SerializationException("Expected a [key, value, ...] array")
        if (array.size % 2 != 0) {
            throw SerializationException("Keyed array has an odd number of elements")
        }
        val json = jsonDecoder.json
        val result = LinkedHashMap<K, V>(array.size / 2)
        for (i in array.indices step 2) {
            result[json.decodeFromJsonElement(keySerializer, array[i])] =
                json.decodeFromJsonElement(valueSerializer, array[i + 1])
        }
        return result
    }
}

/**
 * A Swift `[String: V?]`. Swift writes `null` for nil values inside dictionaries even though nil
 * properties are omitted, so this serializer always emits explicit nulls for map values.
 */
open class SwiftNullableValueMapSerializer<V : Any>(
    private val valueSerializer: KSerializer<V>,
) : KSerializer<Map<String, V?>> {
    override val descriptor: SerialDescriptor =
        MapSerializer(String.serializer(), valueSerializer).descriptor

    override fun serialize(encoder: Encoder, value: Map<String, V?>) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("SwiftNullableValueMapSerializer supports JSON only")
        val json = jsonEncoder.json
        jsonEncoder.encodeJsonElement(
            JsonObject(
                value.mapValues { (_, item) ->
                    if (item == null) JsonNull else json.encodeToJsonElement(valueSerializer, item)
                },
            ),
        )
    }

    override fun deserialize(decoder: Decoder): Map<String, V?> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("SwiftNullableValueMapSerializer supports JSON only")
        val element = jsonDecoder.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("Expected an object")
        return element.mapValues { (_, item) ->
            if (item is JsonNull) null else jsonDecoder.json.decodeFromJsonElement(valueSerializer, item)
        }
    }
}
