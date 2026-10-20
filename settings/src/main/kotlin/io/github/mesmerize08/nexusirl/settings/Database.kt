// Port of Moblin/Various/Settings/Settings.swift `Database` @97e26d2 (v1 subset).
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SFloat
import io.github.mesmerize08.nexusirl.core.codable.SUuid
import io.github.mesmerize08.nexusirl.core.codable.SwiftJson
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreserving
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreservingSerializer
import io.github.mesmerize08.nexusirl.core.codable.SwiftRawValueEnum
import io.github.mesmerize08.nexusirl.core.codable.SwiftRawValueEnumSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/** Upstream `SettingsVideoStabilizationMode`. Android maps every mode except Off to "on". */
@Serializable(with = VideoStabilizationMode.Serializer::class)
enum class VideoStabilizationMode(override val rawValue: String) : SwiftRawValueEnum {
    OFF("Off"),
    STANDARD("Standard"),
    CINEMATIC("Cinematic"),
    CINEMATIC_EXTENDED_ENHANCED("Cinematic extended enhanced"),
    ;

    object Serializer : SwiftRawValueEnumSerializer<VideoStabilizationMode>("SettingsVideoStabilizationMode", entries, OFF)
}

/** Upstream `SettingsSceneSwitchTransition`: what the stream shows while a new camera starts. */
@Serializable(with = SceneSwitchTransition.Serializer::class)
enum class SceneSwitchTransition(override val rawValue: String) : SwiftRawValueEnum {
    BLUR("Blur"),
    FREEZE("Freeze"),
    BLUR_AND_ZOOM("Blur & zoom"),
    ;

    object Serializer : SwiftRawValueEnumSerializer<SceneSwitchTransition>("SettingsSceneSwitchTransition", entries, BLUR)
}

/** Upstream `SettingsColorLut`: a LUT and whether it is applied. [type] is "bundled", "disk" or "diskCube". */
@KeepGeneratedSerializer
@Serializable(with = SettingsColorLut.Serializer::class)
data class SettingsColorLut(
    val id: SUuid = java.util.UUID.randomUUID(),
    val type: String = "bundled",
    val name: String = "",
    val enabled: Boolean = false,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsColorLut>(generatedSerializer())
}

/** Upstream `SettingsColor` (v1 subset: the LUT lists; colour space stays in `extra`). */
@KeepGeneratedSerializer
@Serializable(with = SettingsColor.Serializer::class)
data class SettingsColor(
    val bundledLuts: List<SettingsColorLut> = emptyList(),
    val diskLuts: List<SettingsColorLut> = emptyList(),
    val diskLutsPng: List<SettingsColorLut> = emptyList(),
    val diskLutsCube: List<SettingsColorLut> = emptyList(),
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsColor>(generatedSerializer())

    /** Upstream `allLuts()`: the order LUTs are applied in. */
    val allLuts: List<SettingsColorLut>
        get() = bundledLuts + diskLutsCube + diskLutsPng

    companion object {
        /** Upstream `allBundledLuts` (files in the app's assets under luts/<name>.png). */
        val BUNDLED_LUT_NAMES = listOf("Apple Log To Rec 709", "Moblin Meme")
    }
}

/**
 * The settings document. Upstream keeps the whole app configuration in one forgiving JSON object
 * with no version field: every key has a default and unknown keys are ignored. Android models the
 * parts it uses and keeps the rest in [extra], so an iOS export round-trips unchanged.
 */
@KeepGeneratedSerializer
@Serializable(with = Database.Serializer::class)
data class Database(
    val streams: List<SettingsStream> = emptyList(),
    val scenes: List<SettingsScene> = emptyList(),
    val widgets: List<SettingsWidget> = emptyList(),
    val tapToFocus: Boolean = false,
    val portrait: Boolean = false,
    val videoStabilizationMode: VideoStabilizationMode = VideoStabilizationMode.OFF,
    val pixellateStrength: SFloat = 0.3f,
    val whirlpoolAngle: SFloat = (Math.PI / 2).toFloat(),
    val pinchScale: SFloat = 0.5f,
    val color: SettingsColor = SettingsColor(),
    val chat: SettingsChat = SettingsChat(),
    /** Upstream `quickButtons`, stored under "globalButtons" ("quickButtons" holds their layout settings). */
    @SerialName("globalButtons")
    val quickButtons: List<SettingsQuickButton> = emptyList(),
    val sceneSwitchTransition: SceneSwitchTransition = SceneSwitchTransition.BLUR,
    /** Upstream: restart the camera, and so show the transition, on every scene switch. */
    val forceSceneSwitchTransition: Boolean = false,
    val location: SettingsLocation = SettingsLocation(),
    val remoteControl: SettingsRemoteControl = SettingsRemoteControl(),
    val moblink: SettingsMoblink = SettingsMoblink(),
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
