package io.github.mesmerize08.nexusirl.core.codable

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A settings object that keeps the JSON keys it does not model.
 *
 * The Android app models only part of Moblin's settings document (upstream
 * `Various/Settings/Settings.swift`). Without this, importing an iOS export and exporting it again
 * would silently drop every iOS-only setting. [extra] holds those keys untouched.
 *
 * Declare [extra] as a `@Transient` constructor property of a data class, not in the class body:
 * `copy()` only carries constructor properties, so a body property would be lost on every edit.
 */
interface SwiftPreserving {
    var extra: JsonObject
}

/**
 * Wraps a generated serializer (see `@KeepGeneratedSerializer`) so unknown keys go to
 * [SwiftPreserving.extra] on decode and are written back on encode. Modelled keys win on encode.
 */
open class SwiftPreservingSerializer<T : SwiftPreserving>(private val inner: KSerializer<T>) : KSerializer<T> {
    override val descriptor: SerialDescriptor = inner.descriptor

    private val knownKeys: Set<String> by lazy {
        (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
    }

    override fun deserialize(decoder: Decoder): T {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("${descriptor.serialName} supports JSON only")
        val element = jsonDecoder.decodeJsonElement().jsonObject
        val value = jsonDecoder.json.decodeFromJsonElement(inner, element)
        value.extra = JsonObject(element.filterKeys { it !in knownKeys })
        return value
    }

    override fun serialize(encoder: Encoder, value: T) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("${descriptor.serialName} supports JSON only")
        val modelled = jsonEncoder.json.encodeToJsonElement(inner, value).jsonObject
        jsonEncoder.encodeJsonElement(JsonObject(value.extra + modelled))
    }
}
