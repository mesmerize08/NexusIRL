// Port of Moblin/Various/Settings/SettingsAudio.swift @97e26d2 (v1 subset: the other audio
// settings stay in `extra`).
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SwiftPreserving
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreservingSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * Upstream `SettingsAudio`. Built-in microphones record mono unless [preferStereoMic] is on,
 * like upstream.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsAudio.Serializer::class)
data class SettingsAudio(val preferStereoMic: Boolean = false, @Transient override var extra: JsonObject = JsonObject(emptyMap())) :
    SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsAudio>(generatedSerializer())
}

/**
 * Upstream `SettingsMics` (subset: the mic list, "all", stays in `extra`). [defaultMic] is the
 * last mic chosen, used again at start when connected; with [autoSwitch], a newly connected
 * higher-ranked mic takes over. Ids are this phone's own: an iOS id never matches one here.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsMics.Serializer::class)
data class SettingsMics(
    val autoSwitch: Boolean = true,
    val defaultMic: String = "",
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsMics>(generatedSerializer())
}

/**
 * Upstream `SettingsDebug` (subset). [bluetoothOutputOnly], on by default as upstream, leaves
 * Bluetooth mics out: Bluetooth stays an audio output.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsDebug.Serializer::class)
data class SettingsDebug(val bluetoothOutputOnly: Boolean = true, @Transient override var extra: JsonObject = JsonObject(emptyMap())) :
    SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsDebug>(generatedSerializer())
}
