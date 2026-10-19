// Port of Moblin/Various/Settings/Settings.swift `Database` @97e26d2 (v1 subset).
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SwiftJson
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreserving
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreservingSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * The settings document. Upstream keeps the whole app configuration in one forgiving JSON object
 * with no version field: every key has a default and unknown keys are ignored. Android models the
 * parts it uses and keeps the rest in [extra], so an iOS export round-trips unchanged.
 */
@KeepGeneratedSerializer
@Serializable(with = Database.Serializer::class)
data class Database(
    val streams: List<SettingsStream> = emptyList(),
    val tapToFocus: Boolean = false,
    val portrait: Boolean = false,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<Database>(generatedSerializer())

    /** The stream that Go Live uses (upstream `stream`: the enabled one, else the first). */
    val selectedStream: SettingsStream?
        get() = streams.firstOrNull { it.enabled } ?: streams.firstOrNull()

    /** Selects [id] as the only enabled stream. */
    fun selectStream(id: java.util.UUID): Database = copy(streams = streams.map { it.copy(enabled = it.id == id) })

    fun updateStream(stream: SettingsStream): Database = copy(streams = streams.map { if (it.id == stream.id) stream else it })

    fun addStream(stream: SettingsStream): Database {
        val added = copy(streams = streams + stream)
        return if (streams.isEmpty()) added.selectStream(stream.id) else added
    }

    fun removeStream(id: java.util.UUID): Database {
        val removed = copy(streams = streams.filterNot { it.id == id })
        val selected = removed.selectedStream
        return if (selected != null && removed.streams.none { it.enabled }) removed.selectStream(selected.id) else removed
    }

    fun toJson(): String = SwiftJson.encodeToString(Serializer, this)

    companion object {
        /** Decodes a settings document; malformed input throws [kotlinx.serialization.SerializationException]. */
        fun fromJson(json: String): Database = SwiftJson.decodeFromString(Serializer, json)
    }
}
