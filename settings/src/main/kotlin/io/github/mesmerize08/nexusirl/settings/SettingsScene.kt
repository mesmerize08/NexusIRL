// Port of Moblin/Various/Settings/SettingsScene.swift @97e26d2 (v1 subset: scenes, scene widget
// layout, and the text, image and QR code widgets). Everything else is kept in `extra`.
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SDouble
import io.github.mesmerize08.nexusirl.core.codable.SFloat
import io.github.mesmerize08.nexusirl.core.codable.SUuid
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
import java.util.UUID

private val EMPTY = JsonObject(emptyMap())

/** Upstream `SettingsAlignment`: which corner (or centre) a widget's x/y offsets are measured from. */
@Serializable(with = WidgetAlignment.Serializer::class)
enum class WidgetAlignment(override val rawValue: String) : SwiftRawValueEnum {
    TOP_LEFT("TopLeft"),
    TOP_RIGHT("TopRight"),
    BOTTOM_LEFT("BottomLeft"),
    BOTTOM_RIGHT("BottomRight"),
    TOP_CENTER("TopCenter"),
    BOTTOM_CENTER("BottomCenter"),
    LEFT_CENTER("LeftCenter"),
    RIGHT_CENTER("RightCenter"),
    CENTER("Center"),
    ;

    val isLeft: Boolean
        get() = this == TOP_LEFT || this == BOTTOM_LEFT || this == LEFT_CENTER

    val isHorizontalCenter: Boolean
        get() = this == TOP_CENTER || this == BOTTOM_CENTER || this == CENTER

    val isVerticalCenter: Boolean
        get() = this == LEFT_CENTER || this == RIGHT_CENTER || this == CENTER

    val isTop: Boolean
        get() = this == TOP_LEFT || this == TOP_RIGHT || this == TOP_CENTER

    object Serializer : SwiftRawValueEnumSerializer<WidgetAlignment>("SettingsAlignment", entries, TOP_LEFT)
}

@Serializable(with = HorizontalAlignment.Serializer::class)
enum class HorizontalAlignment(override val rawValue: String) : SwiftRawValueEnum {
    LEADING("Leading"),
    TRAILING("Trailing"),
    CENTER("Center"),
    ;

    object Serializer : SwiftRawValueEnumSerializer<HorizontalAlignment>("SettingsHorizontalAlignment", entries, LEADING)
}

/** Upstream `SettingsWidgetType`. Types Android cannot show yet are kept but not rendered. */
@Serializable(with = WidgetType.Serializer::class)
enum class WidgetType(override val rawValue: String) : SwiftRawValueEnum {
    TEXT("Text"),
    BROWSER("Browser"),
    VIDEO_SOURCE("Video source"),
    IMAGE("Image"),
    ALERTS("Alerts"),
    MAP("Map"),
    SNAPSHOT("Snapshot"),
    CHAT("Chat"),
    CHAT_EMOTE_COMBO("Chat emote combo"),
    SCENE("Scene"),
    SLIDESHOW("Slideshow"),
    VTUBER("VTuber"),
    PNGTUBER("PNGTuber"),
    QR_CODE("QR code"),
    SCOREBOARD("Scoreboard"),
    WHEEL_OF_LUCK("Wheel of luck"),
    BINGO_CARD("Bingo card"),
    CROP("Crop"),
    POMODORO_TIMER("Pomodoro timer"),
    ;

    /** Upstream `hasSize()`: the layout size scales the widget; others draw at their own size. */
    val hasSize: Boolean
        get() = this in setOf(
            IMAGE, BROWSER, CROP, MAP, QR_CODE, VIDEO_SOURCE, VTUBER, PNGTUBER, SNAPSHOT, CHAT_EMOTE_COMBO, SLIDESHOW,
            BINGO_CARD, POMODORO_TIMER, SCOREBOARD,
        )

    // Unknown types decode as text so a newer iOS export still loads (upstream defaults to text).
    object Serializer : SwiftRawValueEnumSerializer<WidgetType>("SettingsWidgetType", entries, TEXT)
}

/** Upstream `RgbColor`: 0..255 channels and an optional 0..1 opacity. */
@Serializable
data class RgbColor(val red: Int, val green: Int, val blue: Int, val opacity: SDouble? = null) {
    /** ARGB for Android graphics. */
    fun argb(): Int = (Math.round((opacity ?: 1.0) * 255).toInt().coerceIn(0, 255) shl 24) or
        (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
}

/** Upstream `SettingsWidgetText` (v1 subset). */
@KeepGeneratedSerializer
@Serializable(with = SettingsWidgetText.Serializer::class)
data class SettingsWidgetText(
    val formatString: String = "{shortTime}",
    val backgroundColor: RgbColor = RgbColor(0, 0, 0, 0.75),
    val clearBackgroundColor: Boolean = false,
    val foregroundColor: RgbColor = RgbColor(255, 255, 255),
    val clearForegroundColor: Boolean = false,
    val fontSize: Int = 30,
    val fontMonospacedDigits: Boolean = false,
    val horizontalAlignment: HorizontalAlignment = HorizontalAlignment.LEADING,
    val widthEnabled: Boolean = false,
    val width: Int = 300,
    val cornerRadius: Int = 10,
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsWidgetText>(generatedSerializer())
}

@KeepGeneratedSerializer
@Serializable(with = SettingsWidgetQrCode.Serializer::class)
data class SettingsWidgetQrCode(
    val message: String = "",
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsWidgetQrCode>(generatedSerializer())
}

/** Upstream `SettingsWidgetBrowser` (v1 subset; mode, speech-to-text and local-only stay in `extra`). */
@KeepGeneratedSerializer
@Serializable(with = SettingsWidgetBrowser.Serializer::class)
data class SettingsWidgetBrowser(
    val url: String = "",
    val width: Int = 500,
    val height: Int = 500,
    /** Upstream `baseFps`, stored under the key "fps". */
    @SerialName("fps")
    val baseFps: SFloat = 5f,
    val styleSheet: String = "",
    /** Whether the page may use the `moblin` JavaScript object (chat and other events). */
    val moblinAccess: Boolean = false,
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsWidgetBrowser>(generatedSerializer())
}

/**
 * Upstream `SettingsWidgetMap`. [size] is the camera distance in metres (stored as "scale").
 * [styleUrl] is Android's addition: a MapLibre style, empty for the default (OpenFreeMap).
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsWidgetMap.Serializer::class)
data class SettingsWidgetMap(
    val northUp: Boolean = false,
    val delay: SDouble = 0.0,
    @SerialName("scale")
    val size: SDouble = 1000.0,
    val styleUrl: String = "",
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsWidgetMap>(generatedSerializer())

    companion object {
        /** OpenFreeMap: free, no key, OpenStreetMap data (docs/PORT_PLAN.md §8.1). */
        const val DEFAULT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    }
}

/** Upstream `SettingsVideoEffectLut`: which LUT (an entry of the colour settings) to apply. */
@KeepGeneratedSerializer
@Serializable(with = SettingsVideoEffectLut.Serializer::class)
data class SettingsVideoEffectLut(
    val lut: SUuid? = null,
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsVideoEffectLut>(generatedSerializer())
}

/**
 * Upstream `SettingsVideoEffect`, an effect on one widget. [type] is upstream's raw value
 * ("lut", "grayScale", "shape", ...), kept as is so effects NexusIRL does not draw survive a
 * round trip; only "lut" is applied, to image widgets.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsVideoEffect.Serializer::class)
data class SettingsVideoEffect(
    val id: SUuid = UUID.randomUUID(),
    val enabled: Boolean = true,
    val type: String = "shape",
    val lut: SettingsVideoEffectLut = SettingsVideoEffectLut(),
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsVideoEffect>(generatedSerializer())

    companion object {
        const val TYPE_LUT = "lut"
    }
}

/**
 * Upstream `SettingsWidget`. Image widgets keep their picture in a file named after the widget id
 * (upstream `imagesStorageDirectory`), not in the settings.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsWidget.Serializer::class)
data class SettingsWidget(
    val name: String = "My widget",
    val id: SUuid = UUID.randomUUID(),
    val type: WidgetType = WidgetType.TEXT,
    val text: SettingsWidgetText = SettingsWidgetText(),
    val qrCode: SettingsWidgetQrCode = SettingsWidgetQrCode(),
    val browser: SettingsWidgetBrowser = SettingsWidgetBrowser(),
    val chat: SettingsWidgetChat = SettingsWidgetChat(),
    val map: SettingsWidgetMap = SettingsWidgetMap(),
    val enabled: Boolean = true,
    val effects: List<SettingsVideoEffect> = emptyList(),
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsWidget>(generatedSerializer())

    /** The LUTs of the enabled "lut" effects, in order. */
    val lutEffectIds: List<UUID>
        get() = effects.filter { it.enabled && it.type == SettingsVideoEffect.TYPE_LUT }.mapNotNull { it.lut.lut }
}

/**
 * Upstream `SettingsSceneWidget`: a widget placed in a scene. [x], [y] and [size] are percentages
 * of the canvas, measured from the [alignment] corner.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsSceneWidget.Serializer::class)
data class SettingsSceneWidget(
    val widgetId: SUuid = UUID.randomUUID(),
    val id: SUuid = UUID.randomUUID(),
    val x: SDouble = 0.0,
    val y: SDouble = 0.0,
    val size: SDouble = 100.0,
    val alignment: WidgetAlignment = WidgetAlignment.TOP_LEFT,
    val positioningLock: Boolean = false,
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsSceneWidget>(generatedSerializer())

    companion object {
        /** Upstream `SettingsSceneWidget(widget:)` default placement per widget type (v1 types). */
        fun defaultFor(widget: SettingsWidget): SettingsSceneWidget = when (widget.type) {
            WidgetType.IMAGE, WidgetType.SLIDESHOW -> SettingsSceneWidget(widgetId = widget.id, size = 30.0)
            WidgetType.MAP, WidgetType.QR_CODE -> SettingsSceneWidget(widgetId = widget.id, size = 23.0)
            WidgetType.CHAT -> SettingsSceneWidget(widgetId = widget.id, alignment = WidgetAlignment.BOTTOM_LEFT)
            else -> SettingsSceneWidget(widgetId = widget.id)
        }
    }
}

/** The camera a scene shows, simplified from upstream `SettingsSceneCameraPosition`. */
enum class SceneCamera { BACK, FRONT, NONE }

/** Upstream `SettingsScene` (v1 subset). */
@KeepGeneratedSerializer
@Serializable(with = SettingsScene.Serializer::class)
data class SettingsScene(
    val name: String = "Scene",
    val id: SUuid = UUID.randomUUID(),
    val enabled: Boolean = true,
    /**
     * Upstream raw value, kept as is so iOS-only positions (multi-camera, ingests, screen) survive
     * a round trip. See [camera] for what Android shows.
     */
    val cameraPosition: String = "Back",
    val widgets: List<SettingsSceneWidget> = emptyList(),
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsScene>(generatedSerializer())

    /** Upstream back positions (incl. multi-camera) use the back camera; ingest sources are not supported yet. */
    val camera: SceneCamera
        get() = when (cameraPosition) {
            "Front" -> SceneCamera.FRONT
            "None" -> SceneCamera.NONE
            else -> SceneCamera.BACK
        }
}
