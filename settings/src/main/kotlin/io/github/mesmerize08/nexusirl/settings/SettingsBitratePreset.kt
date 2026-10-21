// Port of SettingsBitratePreset and addDefaultBitratePresets in Moblin/Various/Settings/Settings.swift @97e26d2.
package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SUuid
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreserving
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreservingSerializer
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** A bitrate to switch the stream to while live (upstream bitrate quick button and remote control). */
@KeepGeneratedSerializer
@Serializable(with = SettingsBitratePreset.Serializer::class)
data class SettingsBitratePreset(
    val id: SUuid = UUID.randomUUID(),
    /** Bits per second. */
    val bitrate: Long = 5_000_000,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsBitratePreset>(generatedSerializer())

    companion object {
        /** Upstream `addDefaultBitratePresets`, used when there are none. */
        fun defaults(): List<SettingsBitratePreset> =
            listOf(15, 12, 9, 7, 6, 5, 4, 3, 2, 1).map { SettingsBitratePreset(bitrate = it * 1_000_000L) }
    }
}
