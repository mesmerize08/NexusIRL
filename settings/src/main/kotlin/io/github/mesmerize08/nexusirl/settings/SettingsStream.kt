// Port of Moblin/Various/Settings/SettingsStream.swift @97e26d2 (v1 subset).
//
// Keys, raw values and defaults match upstream's Codable conformance so iOS settings import as-is.
// Everything not modelled here is kept in `extra` and written back unchanged (SwiftPreserving).
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SFloat
import io.github.mesmerize08.nexusirl.core.codable.SUuid
import io.github.mesmerize08.nexusirl.core.codable.SwiftEnumSerializer
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreserving
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreservingSerializer
import io.github.mesmerize08.nexusirl.core.codable.SwiftRawValueEnum
import io.github.mesmerize08.nexusirl.core.codable.SwiftRawValueEnumSerializer
import io.github.mesmerize08.nexusirl.core.codable.swiftCase
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** Upstream `defaultStreamUrl`. */
const val DEFAULT_STREAM_URL = "srt://my_public_ip:4000"

/** Upstream `defaultSrtLatency` (ms). */
const val DEFAULT_SRT_LATENCY = 3000

/** Upstream `fpss`. */
val STREAM_FPS_CHOICES = listOf(120, 100, 60, 50, 30, 25, 15)

private val EMPTY = JsonObject(emptyMap())

@Serializable(with = StreamCodec.Serializer::class)
enum class StreamCodec(override val rawValue: String, val shortName: String) : SwiftRawValueEnum {
    H265_HEVC("H.265/HEVC", "H.265"),
    H264_AVC("H.264/AVC", "H.264"),
    ;

    // Upstream decodes unknown raw values as H.264.
    object Serializer : SwiftRawValueEnumSerializer<StreamCodec>("SettingsStreamCodec", entries, H264_AVC)
}

@Serializable(with = StreamH264Profile.Serializer::class)
enum class StreamH264Profile(override val rawValue: String) : SwiftRawValueEnum {
    BASELINE("Baseline"),
    MAIN("Main"),
    HIGH("High"),
    ;

    object Serializer : SwiftRawValueEnumSerializer<StreamH264Profile>("SettingsStreamH264Profile", entries, MAIN)
}

@Serializable(with = StreamRateControl.Serializer::class)
enum class StreamRateControl(override val rawValue: String) : SwiftRawValueEnum {
    ABR("ABR"),
    CBR("CBR"),
    VBR("VBR"),
    ;

    object Serializer : SwiftRawValueEnumSerializer<StreamRateControl>("SettingsStreamRateControl", entries, ABR)
}

@Serializable(with = StreamResolution.Serializer::class)
enum class StreamResolution(override val rawValue: String, val width: Int, val height: Int, val shortName: String) :
    SwiftRawValueEnum {
    R4032X3024("4032x3024", 4032, 3024, "3024p (4:3)"),
    R3840X2160("3840x2160", 3840, 2160, "4K"),
    R2560X1440("2560x1440", 2560, 1440, "1440p"),
    R1920X1440("1920x1440", 1920, 1440, "1440p (4:3)"),
    R1920X1080("1920x1080", 1920, 1080, "1080p"),
    R1664X936("1664x936", 1664, 936, "936p"),
    R1280X720("1280x720", 1280, 720, "720p"),
    R1024X768("1024x768", 1024, 768, "768p (4:3)"),
    R960X540("960x540", 960, 540, "540p"),
    R854X480("854x480", 854, 480, "480p"),
    R640X360("640x360", 640, 360, "360p"),
    R426X240("426x240", 426, 240, "240p"),
    ;

    object Serializer : SwiftRawValueEnumSerializer<StreamResolution>("SettingsStreamResolution", entries, R1920X1080)
}

@Serializable(with = StreamAudioCodec.Serializer::class)
enum class StreamAudioCodec(override val rawValue: String) : SwiftRawValueEnum {
    AAC("AAC"),
    OPUS("OPUS"),
    ;

    object Serializer : SwiftRawValueEnumSerializer<StreamAudioCodec>("SettingsStreamAudioCodec", entries, AAC)
}

/** Upstream `SettingsStreamSrtAdaptiveBitrateAlgorithm`: a payload-less Swift enum, `{"belabox":{}}`. */
@Serializable(with = SrtAdaptiveBitrateAlgorithm.Serializer::class)
sealed interface SrtAdaptiveBitrateAlgorithm {
    @Serializable
    @SerialName("belabox")
    data object Belabox : SrtAdaptiveBitrateAlgorithm

    @Serializable
    @SerialName("fastIrl")
    data object FastIrl : SrtAdaptiveBitrateAlgorithm

    @Serializable
    @SerialName("slowIrl")
    data object SlowIrl : SrtAdaptiveBitrateAlgorithm

    @Serializable
    @SerialName("customIrl")
    data object CustomIrl : SrtAdaptiveBitrateAlgorithm

    // Upstream decodes anything unrecognised as belabox.
    object Serializer : SwiftEnumSerializer<SrtAdaptiveBitrateAlgorithm>(
        "SettingsStreamSrtAdaptiveBitrateAlgorithm",
        listOf(
            swiftCase("belabox", Belabox.serializer()),
            swiftCase("fastIrl", FastIrl.serializer()),
            swiftCase("slowIrl", SlowIrl.serializer()),
            swiftCase("customIrl", CustomIrl.serializer()),
        ),
        Belabox,
    )
}

@KeepGeneratedSerializer
@Serializable(with = SrtAdaptiveBitrateFastIrlSettings.Serializer::class)
data class SrtAdaptiveBitrateFastIrlSettings(
    val packetsInFlight: Int = 200,
    val minimumBitrate: SFloat = 250f,
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SrtAdaptiveBitrateFastIrlSettings>(generatedSerializer())
}

@KeepGeneratedSerializer
@Serializable(with = SrtAdaptiveBitrateCustomSettings.Serializer::class)
data class SrtAdaptiveBitrateCustomSettings(
    val packetsInFlight: Int = 200,
    val pifDiffIncreaseFactor: SFloat = 100f,
    val rttDiffHighDecreaseFactor: SFloat = 0.9f,
    val rttDiffHighAllowedSpike: SFloat = 50f,
    val rttDiffHighMinimumDecrease: SFloat = 250f,
    val minimumBitrate: SFloat = 250f,
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SrtAdaptiveBitrateCustomSettings>(generatedSerializer())
}

@KeepGeneratedSerializer
@Serializable(with = SrtAdaptiveBitrateBelaboxSettings.Serializer::class)
data class SrtAdaptiveBitrateBelaboxSettings(
    val minimumBitrate: SFloat = 250f,
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SrtAdaptiveBitrateBelaboxSettings>(generatedSerializer())
}

@KeepGeneratedSerializer
@Serializable(with = SrtAdaptiveBitrate.Serializer::class)
data class SrtAdaptiveBitrate(
    val algorithm: SrtAdaptiveBitrateAlgorithm = SrtAdaptiveBitrateAlgorithm.Belabox,
    val fastIrlSettings: SrtAdaptiveBitrateFastIrlSettings = SrtAdaptiveBitrateFastIrlSettings(),
    val customSettings: SrtAdaptiveBitrateCustomSettings = SrtAdaptiveBitrateCustomSettings(),
    val belaboxSettings: SrtAdaptiveBitrateBelaboxSettings = SrtAdaptiveBitrateBelaboxSettings(),
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SrtAdaptiveBitrate>(generatedSerializer())
}

/** Upstream `SettingsStreamSrt` (v1 subset; implementation, DNS strategy etc. stay in `extra`). */
@KeepGeneratedSerializer
@Serializable(with = SettingsStreamSrt.Serializer::class)
data class SettingsStreamSrt(
    val latency: Int = DEFAULT_SRT_LATENCY,
    val adaptiveBitrateEnabled: Boolean = true,
    val adaptiveBitrate: SrtAdaptiveBitrate = SrtAdaptiveBitrate(),
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsStreamSrt>(generatedSerializer())
}

@KeepGeneratedSerializer
@Serializable(with = SettingsStreamRtmp.Serializer::class)
data class SettingsStreamRtmp(
    val adaptiveBitrateEnabled: Boolean = true,
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsStreamRtmp>(generatedSerializer())
}

/**
 * Upstream `SettingsStreamRecording` (v1 subset; resolution override, auto start/stop and the
 * recording path stay in `extra`). Zero means "auto" for the bitrates and key frame interval.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsStreamRecording.Serializer::class)
data class SettingsStreamRecording(
    val videoCodec: StreamCodec = StreamCodec.H265_HEVC,
    val videoBitrate: Long = 0,
    val maxKeyFrameInterval: Int = 0,
    val audioBitrate: Long = 128_000,
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsStreamRecording>(generatedSerializer())
}

/** Upstream `SettingsStreamChat` (v1 subset): third-party emote providers. */
@KeepGeneratedSerializer
@Serializable(with = SettingsStreamChat.Serializer::class)
data class SettingsStreamChat(
    val bttvEmotes: Boolean = false,
    val ffzEmotes: Boolean = false,
    val seventvEmotes: Boolean = false,
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsStreamChat>(generatedSerializer())
}

/**
 * Upstream `SettingsStream` (v1 subset). The selected stream is the one with [enabled] set, as
 * upstream. Upstream's key names are kept even where the Swift property differs
 * (`bitrateRateControl` for `rateControl`).
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsStream.Serializer::class)
data class SettingsStream(
    val name: String = "My stream",
    val id: SUuid = UUID.randomUUID(),
    val enabled: Boolean = false,
    val url: String = DEFAULT_STREAM_URL,
    val resolution: StreamResolution = StreamResolution.R1920X1080,
    val fps: Int = 30,
    val bitrate: Long = 5_000_000,
    @SerialName("bitrateRateControl")
    val rateControl: StreamRateControl = StreamRateControl.ABR,
    val codec: StreamCodec = StreamCodec.H265_HEVC,
    val h264Profile: StreamH264Profile = StreamH264Profile.MAIN,
    val bFrames: Boolean = false,
    val adaptiveBitrate: Boolean = true,
    val srt: SettingsStreamSrt = SettingsStreamSrt(),
    val rtmp: SettingsStreamRtmp = SettingsStreamRtmp(),
    val maxKeyFrameInterval: Int = 2,
    val audioCodec: StreamAudioCodec = StreamAudioCodec.AAC,
    val audioBitrate: Int = 128_000,
    val recording: SettingsStreamRecording = SettingsStreamRecording(),
    val portrait: Boolean = false,
    val twitchChannelName: String = "",
    val twitchChannelId: String = "",
    /** A `{secret:…}` reference to the Twitch tokens (upstream stores the token itself here). */
    val twitchAccessToken: String = "",
    val twitchLoggedIn: Boolean = false,
    val kickChannelName: String = "",
    val kickChannelId: String? = null,
    val kickChatroomChannelId: String? = null,
    /** Comma separated, as upstream, whose key is singular although the property is plural. */
    @SerialName("youTubeVideoId")
    val youTubeVideoIds: String = "",
    val youTubeHandle: String = "",
    val chat: SettingsStreamChat = SettingsStreamChat(),
    val twitchChatAlerts: SettingsTwitchAlerts = SettingsTwitchAlerts(),
    val twitchToastAlerts: SettingsTwitchAlerts = SettingsTwitchAlerts(),
    @Transient override var extra: JsonObject = EMPTY,
) : SwiftPreserving {
    // Up to 0.2.0 the Android app wrote "youTubeVideoIds", which iOS does not read.
    object Serializer : SwiftPreservingSerializer<SettingsStream>(
        generatedSerializer(),
        renamedKeys = mapOf("youTubeVideoIds" to "youTubeVideoId"),
    )

    /** Canvas width, swapped with the height for portrait streams (upstream `dimensions()`). */
    val canvasWidth: Int
        get() = if (portrait) resolution.height else resolution.width

    val canvasHeight: Int
        get() = if (portrait) resolution.width else resolution.height

    /** Upstream `getYouTubeVideoIds`. */
    val youTubeVideoIdList: List<String>
        get() = youTubeVideoIds.split(",").filter { it.isNotEmpty() }
}
