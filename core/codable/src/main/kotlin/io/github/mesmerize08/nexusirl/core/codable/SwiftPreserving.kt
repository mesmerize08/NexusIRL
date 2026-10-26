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
 * [renamedKeys] maps keys this app wrote in earlier versions to the key now used (upstream's),
 * so settings saved before a rename still load.
 */
open class SwiftPreservingSerializer<T : SwiftPreserving>(
    private val inner: KSerializer<T>,
    private val renamedKeys: Map<String, String> = emptyMap(),
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = inner.descriptor

    private val knownKeys: Set<String> by lazy {
        (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
    }

    override fun deserialize(decoder: Decoder): T {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("${descriptor.serialName} supports JSON only")
        val element = renameKeys(jsonDecoder.decodeJsonElement().jsonObject, renamedKeys)
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

/** Moves each old key's value to its new key; when both are present, the new key wins. */
fun renameKeys(element: JsonObject, renamedKeys: Map<String, String>): JsonObject {
    if (renamedKeys.keys.none { it in element }) {
        return element
    }
    val result = LinkedHashMap(element)
    for ((old, new) in renamedKeys) {
        val value = result.remove(old) ?: continue
        result.putIfAbsent(new, value)
    }
    return JsonObject(result)
}
