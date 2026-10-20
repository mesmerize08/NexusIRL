// Port of Moblin/Various/Settings/SettingsQuickButtons.swift and the quick button defaults in
// Settings.swift (`addMissingQuickButtons`) @97e26d2. Generated from upstream's lists; keys,
// raw values, icons (SF Symbol names, kept for iOS round trips) and pages match upstream.
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.mesmerize08.nexusirl.settings

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

/** Upstream `SettingsQuickButtonType`; [displayName] is upstream's `toString()`. */
@Serializable(with = QuickButtonType.Serializer::class)
enum class QuickButtonType(override val rawValue: String, val displayName: String) : SwiftRawValueEnum {
    UNKNOWN("Unknown", "Unknown"),
    TORCH("Torch", "Torch"),
    MUTE("Mute", "Mute"),
    BITRATE("Bitrate", "Bitrate"),
    MIC("Mic", "Mic"),
    CHAT("Chat", "Chat"),
    BLACK_SCREEN("Black screen", "Stealth mode"),
    RECORD("Record", "Record"),
    IMAGE("Image", "Camera"),
    MOVIE("Movie", "Movie"),
    GRAY_SCALE("Gray scale", "Gray scale"),
    SEPIA("Sepia", "Sepia"),
    TRIPLE("Triple", "Triple"),
    TWIN("Twin", "Twin"),
    PIXELLATE("Pixellate", "Pixellate"),
    STREAM("Stream", "Switch stream"),
    GRID("Grid", "Grid"),
    CAMERA_LEVEL("Camera level", "Camera level"),
    OBS("OBS", "OBS"),
    REMOTE("Remote", "Remote"),
    DRAW("Draw", "Draw"),
    LOCAL_OVERLAYS("Local overlays", "Local overlays"),
    BROWSER("Browser", "Browser"),
    CAMERA_PREVIEW("Camera preview", "Camera preview"),
    FOUR_THREE("4:3", "4:3"),
    CRT("CRT", "CRT"),
    POLL("Poll", "Poll"),
    SNAPSHOT("Snapshot", "Snapshot"),
    WIDGETS("Widgets", "Scene widgets"),
    LUTS("LUTs", "LUTs"),
    WORKOUT("Workout", "Workout"),
    MODERATION("Moderation", "Moderation"),
    PREDEFINED_MESSAGES("Predefined messages", "Predefined messages"),
    SKIP_CURRENT_TTS("Skip current TTS", "Skip current TTS"),
    STREAM_MARKER("Stream marker", "Stream marker"),
    RELOAD_BROWSER_WIDGETS("Reload browser widgets", "Reload browser widgets"),
    INTERACTIVE_CHAT("Interactive chat", "Scrollable chat"),
    LOCK_SCREEN("Lock screen", "Lock screen"),
    DJI_DEVICES("DJI devices", "DJI devices"),
    PORTRAIT("Portrait", "Portrait"),
    GO_PRO("GoPro", "GoPro"),
    REPLAY("Replay", "Replay"),
    CONNECTION_PRIORITIES("Connection priorities", "Connection priorities"),
    INSTANT_REPLAY("Instant replay", "Instant replay"),
    PINCH("Pinch", "Pinch"),
    WHIRLPOOL("Whirlpool", "Whirlpool"),
    AUTO_SCENE_SWITCHER("Auto scene switcher", "Auto scene switcher"),
    PAUSE_TTS("Pause TTS", "Pause TTS"),
    LIVE("Live", "Stream"),
    NAVIGATION("Navigation", "Navigation"),
    BLUR_FACES("Blur faces", "Blur faces"),
    BLUR_TEXT("Blur text", "Blur text"),
    PRIVACY("Privacy", "Blur background"),
    MOBLIN_IN_MOUTH("Moblin in mouth", "Moblin in mouth"),
    GLASSES("Glasses", "Glasses"),
    SPARKLE("Sparkle", "Sparkle"),
    BEAUTY("Beauty filter", "Beauty"),
    CAMERA_MAN("Camera man", "Camera man"),
    VIDEO_PREVIEW("Video preview", "Video preview"),
    INTERACTIVE_BROWSER_WIDGETS("Interactive browser widgets", "Interactive browser widgets"),
    MACROS("Macros", "Macros"),
    GIMBAL_TRACKING("Gimbal tracking", "Gimbal tracking"),
    PREVIEW_STREAM("Preview stream", "Preview stream"),
    PHOTO_SHOOT("Photo shoot", "Photo shoot"),
    ;

    object Serializer : SwiftRawValueEnumSerializer<QuickButtonType>("SettingsQuickButtonType", entries, UNKNOWN)
}

/** Upstream `defaultQuickButtonColor`. */
val DEFAULT_QUICK_BUTTON_COLOR = RgbColor(255 / 4, 255 / 4, 255 / 4)

/** Upstream `SettingsQuickButton`. The icons are SF Symbol names; Android maps them to its own icons. */
@KeepGeneratedSerializer
@Serializable(with = SettingsQuickButton.Serializer::class)
data class SettingsQuickButton(
    val name: String = "",
    val id: SUuid = UUID.randomUUID(),
    val type: QuickButtonType = QuickButtonType.UNKNOWN,
    @SerialName("systemImageNameOn")
    val imageOn: String = "",
    @SerialName("systemImageNameOff")
    val imageOff: String = "",
    val isOn: Boolean = false,
    val enabled: Boolean = true,
    val backgroundColor: RgbColor = DEFAULT_QUICK_BUTTON_COLOR,
    val page: Int = 1,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsQuickButton>(generatedSerializer())
}

/** One upstream default button: type, icons and page (iOS 17 pages). */
private class QuickButtonDefault(val type: QuickButtonType, val imageOn: String, val imageOff: String, val page: Int)

/** Upstream `addMissingQuickButtonsPageOne/Two/Three`, in order. */
private val DEFAULT_QUICK_BUTTONS = listOf(
    QuickButtonDefault(QuickButtonType.TORCH, "flashlight.on.fill", "flashlight.off.fill", 1),
    QuickButtonDefault(QuickButtonType.MUTE, "mic.slash", "mic", 1),
    QuickButtonDefault(QuickButtonType.LIVE, "dot.radiowaves.left.and.right", "dot.radiowaves.left.and.right", 1),
    QuickButtonDefault(QuickButtonType.MIC, "music.mic", "music.mic", 1),
    QuickButtonDefault(QuickButtonType.RECORD, "record.circle.fill", "record.circle", 1),
    QuickButtonDefault(QuickButtonType.SNAPSHOT, "camera.aperture", "camera.aperture", 1),
    QuickButtonDefault(QuickButtonType.WIDGETS, "photo.on.rectangle.fill", "photo.on.rectangle", 1),
    QuickButtonDefault(QuickButtonType.LOCAL_OVERLAYS, "square.stack.3d.up.slash.fill", "square.stack.3d.up.slash", 1),
    QuickButtonDefault(QuickButtonType.BLACK_SCREEN, "sunset.fill", "sunset", 1),
    QuickButtonDefault(QuickButtonType.CHAT, "message.fill", "message", 1),
    QuickButtonDefault(QuickButtonType.BITRATE, "speedometer", "speedometer", 1),
    QuickButtonDefault(QuickButtonType.BROWSER, "globe", "globe", 1),
    QuickButtonDefault(QuickButtonType.DRAW, "pencil.line", "pencil.line", 2),
    QuickButtonDefault(QuickButtonType.POLL, "chart.bar.xaxis", "chart.bar.xaxis", 2),
    QuickButtonDefault(QuickButtonType.PINCH, "hand.pinch.fill", "hand.pinch", 2),
    QuickButtonDefault(QuickButtonType.WHIRLPOOL, "tornado", "tornado", 2),
    QuickButtonDefault(QuickButtonType.BLUR_FACES, "face.dashed", "face.dashed", 2),
    QuickButtonDefault(QuickButtonType.PRIVACY, "circle.rectangle.dashed", "circle.rectangle.dashed", 2),
    QuickButtonDefault(QuickButtonType.BLUR_TEXT, "text.redaction", "text.redaction", 2),
    QuickButtonDefault(QuickButtonType.GLASSES, "sunglasses", "sunglasses", 2),
    QuickButtonDefault(QuickButtonType.SPARKLE, "eye", "eye", 2),
    QuickButtonDefault(QuickButtonType.MOVIE, "film.fill", "film", 2),
    QuickButtonDefault(QuickButtonType.FOUR_THREE, "square.fill", "square", 2),
    QuickButtonDefault(QuickButtonType.CRT, "tv", "tv", 2),
    QuickButtonDefault(QuickButtonType.PIXELLATE, "squareshape.split.2x2", "squareshape.split.2x2", 2),
    QuickButtonDefault(QuickButtonType.GRAY_SCALE, "moon.fill", "moon", 2),
    QuickButtonDefault(QuickButtonType.SEPIA, "moonphase.waxing.crescent.inverse", "moonphase.waning.crescent", 2),
    QuickButtonDefault(QuickButtonType.TRIPLE, "person.3.fill", "person.3", 2),
    QuickButtonDefault(QuickButtonType.TWIN, "person.2.fill", "person.2", 2),
    QuickButtonDefault(QuickButtonType.MOBLIN_IN_MOUTH, "mouth", "mouth", 2),
    QuickButtonDefault(QuickButtonType.CAMERA_MAN, "video.fill", "video", 2),
    QuickButtonDefault(QuickButtonType.BEAUTY, "wand.and.stars", "wand.and.stars", 2),
    QuickButtonDefault(QuickButtonType.LUTS, "camera.filters", "camera.filters", 2),
    QuickButtonDefault(QuickButtonType.OBS, "xserve", "xserve", 3),
    QuickButtonDefault(QuickButtonType.REMOTE, "appletvremote.gen1.fill", "appletvremote.gen1", 3),
    QuickButtonDefault(QuickButtonType.REPLAY, "play.fill", "play", 3),
    QuickButtonDefault(QuickButtonType.INSTANT_REPLAY, "memories", "memories", 3),
    QuickButtonDefault(QuickButtonType.DJI_DEVICES, "appletvremote.gen1.fill", "appletvremote.gen1", 3),
    QuickButtonDefault(QuickButtonType.GO_PRO, "appletvremote.gen1.fill", "appletvremote.gen1", 3),
    QuickButtonDefault(QuickButtonType.AUTO_SCENE_SWITCHER, "autostartstop", "autostartstop", 3),
    QuickButtonDefault(QuickButtonType.LOCK_SCREEN, "lock.fill", "lock", 3),
    QuickButtonDefault(QuickButtonType.IMAGE, "camera.fill", "camera", 3),
    QuickButtonDefault(QuickButtonType.CAMERA_PREVIEW, "camera.rotate.fill", "camera.rotate", 3),
    QuickButtonDefault(QuickButtonType.STREAM, "arrow.left.arrow.right", "arrow.left.arrow.right", 3),
    QuickButtonDefault(QuickButtonType.GRID, "grid", "grid", 3),
    QuickButtonDefault(QuickButtonType.CAMERA_LEVEL, "level.fill", "level", 3),
    QuickButtonDefault(QuickButtonType.WORKOUT, "figure.run", "figure.run", 3),
    QuickButtonDefault(QuickButtonType.SKIP_CURRENT_TTS, "waveform.slash", "waveform.slash", 3),
    QuickButtonDefault(QuickButtonType.PAUSE_TTS, "waveform.badge.xmark", "waveform.badge.xmark", 3),
    QuickButtonDefault(QuickButtonType.MODERATION, "shield", "shield", 3),
    QuickButtonDefault(QuickButtonType.PREDEFINED_MESSAGES, "list.bullet", "list.bullet", 3),
    QuickButtonDefault(QuickButtonType.STREAM_MARKER, "bookmark.fill", "bookmark", 3),
    QuickButtonDefault(QuickButtonType.NAVIGATION, "arrow.trianglehead.turn.up.right.circle", "arrow.trianglehead.turn.up.right.circle", 3),
    QuickButtonDefault(QuickButtonType.RELOAD_BROWSER_WIDGETS, "arrow.clockwise", "arrow.clockwise", 3),
    QuickButtonDefault(QuickButtonType.PORTRAIT, "rectangle.portrait.rotate", "rectangle.portrait.rotate", 3),
    QuickButtonDefault(QuickButtonType.CONNECTION_PRIORITIES, "phone.connection.fill", "phone.connection", 3),
    QuickButtonDefault(QuickButtonType.VIDEO_PREVIEW, "person.2.crop.square.stack", "person.2.crop.square.stack", 3),
    QuickButtonDefault(QuickButtonType.INTERACTIVE_BROWSER_WIDGETS, "hand.tap.fill", "hand.tap", 3),
    QuickButtonDefault(QuickButtonType.MACROS, "increase.indent", "increase.indent", 3),
    QuickButtonDefault(QuickButtonType.PREVIEW_STREAM, "video.circle.fill", "video.circle", 3),
    QuickButtonDefault(QuickButtonType.PHOTO_SHOOT, "person.crop.square.badge.camera.fill", "person.crop.square.badge.camera", 3),
)

/**
 * Upstream `addMissingQuickButtons` plus the `isOn` reset in `Database` loading: every known
 * button exists once (existing ones keep their place, colour and page but get upstream's name and
 * icons), unknown buttons are dropped, and all buttons start off.
 */
fun addMissingQuickButtons(buttons: List<SettingsQuickButton>): List<SettingsQuickButton> {
    val result = buttons.filter { it.type != QuickButtonType.UNKNOWN }.toMutableList()
    for (default in DEFAULT_QUICK_BUTTONS) {
        val index = result.indexOfFirst { it.type == default.type }
        if (index >= 0) {
            result[index] = result[index].copy(name = default.type.displayName, imageOn = default.imageOn, imageOff = default.imageOff)
        } else {
            result += SettingsQuickButton(
                name = default.type.displayName,
                type = default.type,
                imageOn = default.imageOn,
                imageOff = default.imageOff,
                page = default.page,
            )
        }
    }
    // Upstream keeps these few on across restarts.
    val keepState = setOf(QuickButtonType.INTERACTIVE_CHAT, QuickButtonType.CAMERA_PREVIEW, QuickButtonType.INTERACTIVE_BROWSER_WIDGETS)
    return result.map { if (it.isOn && it.type !in keepState) it.copy(isOn = false) else it }
}
