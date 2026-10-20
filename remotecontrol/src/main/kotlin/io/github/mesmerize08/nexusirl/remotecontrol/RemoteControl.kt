// Port of Moblin/RemoteControl/RemoteControl.swift @97e26d2 (v1 subset of the requests; the
// others decode as [RemoteControlRequest.Unknown] and are answered with `unknownRequest`). The
// JSON matches Swift's synthesized Codable exactly so iOS assistants and the web remote work.
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.mesmerize08.nexusirl.remotecontrol

import io.github.mesmerize08.nexusirl.core.codable.SData
import io.github.mesmerize08.nexusirl.core.codable.SFloat
import io.github.mesmerize08.nexusirl.core.codable.SUuid
import io.github.mesmerize08.nexusirl.core.codable.SwiftEnumSerializer
import io.github.mesmerize08.nexusirl.core.codable.SwiftJson
import io.github.mesmerize08.nexusirl.core.codable.SwiftKeyedArrayMapSerializer
import io.github.mesmerize08.nexusirl.core.codable.SwiftRawValueEnum
import io.github.mesmerize08.nexusirl.core.codable.SwiftRawValueEnumSerializer
import io.github.mesmerize08.nexusirl.core.codable.SwiftUnitEnumSerializer
import io.github.mesmerize08.nexusirl.core.codable.swiftCase
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

/** Upstream `remoteControlApiVersion`. */
const val REMOTE_CONTROL_API_VERSION = "0.1"

/** Upstream `RemoteControlStartStatusFilter`. */
@Serializable
data class RemoteControlStartStatusFilter(val topRight: Boolean = true)

/** Upstream `RemoteControlFilter`: global effects the assistant can toggle. */
@Serializable(with = RemoteControlFilter.Serializer::class)
enum class RemoteControlFilter(val swiftName: String) {
    PIXELLATE("pixellate"),
    MOVIE("movie"),
    GRAY_SCALE("grayScale"),
    SEPIA("sepia"),
    TRIPLE("triple"),
    TWIN("twin"),
    FOUR_THREE("fourThree"),
    CRT("crt"),
    PINCH("pinch"),
    WHIRLPOOL("whirlpool"),
    POLL("poll"),
    BLUR_FACES("blurFaces"),
    PRIVACY("privacy"),
    BEAUTY("beauty"),
    MOBLIN_IN_MOUTH("moblinInMouth"),
    CAMERA_MAN("cameraMan"),
    ;

    object Serializer : SwiftUnitEnumSerializer<RemoteControlFilter>("RemoteControlFilter", entries, { it.swiftName })
}

/** `[RemoteControlFilter: Bool]`, encoded by Swift as a flat `[key, value, ...]` array. */
object RemoteControlFiltersSerializer :
    SwiftKeyedArrayMapSerializer<RemoteControlFilter, Boolean>(RemoteControlFilter.Serializer, Boolean.serializer())

/** Upstream `RemoteControlRequest` (v1 subset). */
@Serializable(with = RemoteControlRequest.Serializer::class)
sealed interface RemoteControlRequest {
    @Serializable data object GetStatus : RemoteControlRequest

    @Serializable data object GetSettings : RemoteControlRequest

    @Serializable data class SetRecord(val on: Boolean) : RemoteControlRequest

    @Serializable data class SetLive(val on: Boolean) : RemoteControlRequest

    @Serializable data class SetZoom(val x: SFloat) : RemoteControlRequest

    @Serializable data class SetMute(val on: Boolean) : RemoteControlRequest

    @Serializable data class SetStealthMode(val on: Boolean) : RemoteControlRequest

    @Serializable data class SetTorch(val on: Boolean) : RemoteControlRequest

    @Serializable data class SetDebugLogging(val on: Boolean) : RemoteControlRequest

    @Serializable data class SetStream(val id: SUuid) : RemoteControlRequest

    @Serializable data class SetScene(val id: SUuid) : RemoteControlRequest

    @Serializable data class SetMic(val id: String) : RemoteControlRequest

    @Serializable data object ReloadBrowserWidgets : RemoteControlRequest

    @Serializable data object StartPreview : RemoteControlRequest

    @Serializable data object StopPreview : RemoteControlRequest

    @Serializable data class StartStatus(val interval: Int, val filter: RemoteControlStartStatusFilter) : RemoteControlRequest

    @Serializable data object StopStatus : RemoteControlRequest

    @Serializable data class SetFilter(val filter: RemoteControlFilter, val on: Boolean) : RemoteControlRequest

    @Serializable data class ImportSettings(val data: SData) : RemoteControlRequest

    @Serializable data class SendMessage(val text: String) : RemoteControlRequest

    /** Any request Android does not handle yet; answered with `unknownRequest`. */
    @Serializable data object Unknown : RemoteControlRequest

    object Serializer : SwiftEnumSerializer<RemoteControlRequest>(
        "RemoteControlRequest",
        listOf(
            swiftCase("getStatus", GetStatus.serializer()),
            swiftCase("getSettings", GetSettings.serializer()),
            swiftCase("setRecord", SetRecord.serializer()),
            swiftCase("setLive", SetLive.serializer()),
            swiftCase("setZoom", SetZoom.serializer()),
            swiftCase("setMute", SetMute.serializer()),
            swiftCase("setStealthMode", SetStealthMode.serializer()),
            swiftCase("setTorch", SetTorch.serializer()),
            swiftCase("setDebugLogging", SetDebugLogging.serializer()),
            swiftCase("setStream", SetStream.serializer()),
            swiftCase("setScene", SetScene.serializer()),
            swiftCase("setMic", SetMic.serializer()),
            swiftCase("reloadBrowserWidgets", ReloadBrowserWidgets.serializer()),
            swiftCase("startPreview", StartPreview.serializer()),
            swiftCase("stopPreview", StopPreview.serializer()),
            swiftCase("startStatus", StartStatus.serializer()),
            swiftCase("stopStatus", StopStatus.serializer()),
            swiftCase("setFilter", SetFilter.serializer()),
            swiftCase("importSettings", ImportSettings.serializer()),
            swiftCase("sendMessage", SendMessage.serializer()),
            swiftCase("unknown", Unknown.serializer()),
        ),
        fallback = Unknown,
    )
}

/** Upstream `RemoteControlStatusItem`. */
@Serializable
data class RemoteControlStatusItem(val message: String, val ok: Boolean = true)

/** Upstream `RemoteControlStatusGeneralFlame` (thermal state). */
@Serializable(with = RemoteControlStatusGeneralFlame.Serializer::class)
enum class RemoteControlStatusGeneralFlame(override val rawValue: String) : SwiftRawValueEnum {
    WHITE("White"),
    YELLOW("Yellow"),
    RED("Red"),
    ;

    object Serializer : SwiftRawValueEnumSerializer<RemoteControlStatusGeneralFlame>("RemoteControlStatusGeneralFlame", entries, WHITE)
}

/** Upstream `RemoteControlStatusTopRightAudioLevel`. */
@Serializable(with = RemoteControlAudioLevel.Serializer::class)
sealed interface RemoteControlAudioLevel {
    @Serializable data object Muted : RemoteControlAudioLevel

    @Serializable data object Unknown : RemoteControlAudioLevel

    @Serializable data class Value(@SerialName("_0") val value: SFloat) : RemoteControlAudioLevel

    object Serializer : SwiftEnumSerializer<RemoteControlAudioLevel>(
        "RemoteControlStatusTopRightAudioLevel",
        listOf(swiftCase("muted", Muted.serializer()), swiftCase("unknown", Unknown.serializer()), swiftCase("value", Value.serializer())),
        fallback = Unknown,
    )
}

@Serializable
data class RemoteControlAudioInfo(val audioLevel: RemoteControlAudioLevel, val numberOfAudioChannels: Int)

/** Upstream `RemoteControlStatusGeneral`. */
@Serializable
data class RemoteControlStatusGeneral(
    val batteryCharging: Boolean? = null,
    val batteryLevel: Int? = null,
    val flame: RemoteControlStatusGeneralFlame? = null,
    val wiFiSsid: String? = null,
    val isLive: Boolean? = null,
    val isRecording: Boolean? = null,
    val isMuted: Boolean? = null,
)

/** Upstream `RemoteControlStatusTopLeft`. */
@Serializable
data class RemoteControlStatusTopLeft(
    val stream: RemoteControlStatusItem? = null,
    val camera: RemoteControlStatusItem? = null,
    val mic: RemoteControlStatusItem? = null,
    val zoom: RemoteControlStatusItem? = null,
    val obs: RemoteControlStatusItem? = null,
    val events: RemoteControlStatusItem? = null,
    val chat: RemoteControlStatusItem? = null,
    val viewers: RemoteControlStatusItem? = null,
)

/** Upstream `RemoteControlStatusTopRight`. */
@Serializable
data class RemoteControlStatusTopRight(
    val audioInfo: RemoteControlAudioInfo? = null,
    val audioLevel: RemoteControlStatusItem? = null,
    val rtmpServer: RemoteControlStatusItem? = null,
    val remoteControl: RemoteControlStatusItem? = null,
    val gameController: RemoteControlStatusItem? = null,
    val bitrate: RemoteControlStatusItem? = null,
    val uptime: RemoteControlStatusItem? = null,
    val location: RemoteControlStatusItem? = null,
    val srtla: RemoteControlStatusItem? = null,
    val srtlaRtts: RemoteControlStatusItem? = null,
    val recording: RemoteControlStatusItem? = null,
    val replay: RemoteControlStatusItem? = null,
    val browserWidgets: RemoteControlStatusItem? = null,
    val moblink: RemoteControlStatusItem? = null,
    val djiDevices: RemoteControlStatusItem? = null,
    val systemMonitor: RemoteControlStatusItem? = null,
)

@Serializable
data class RemoteControlSettingsStream(val id: SUuid, val name: String)

@Serializable
data class RemoteControlSettingsScene(val id: SUuid, val name: String)

@Serializable
data class RemoteControlSettingsBitratePreset(val id: SUuid, val bitrate: Long)

@Serializable
data class RemoteControlSettingsMic(val id: String, val name: String)

@Serializable
data class RemoteControlSettingsSrtConnectionPriority(val id: SUuid, val name: String, val priority: Int, val enabled: Boolean)

@Serializable
data class RemoteControlSettingsSrt(
    val connectionPrioritiesEnabled: Boolean,
    val connectionPriorities: List<RemoteControlSettingsSrtConnectionPriority>,
)

/** Upstream `RemoteControlSettings`: names and ids only, never URLs or keys. */
@Serializable
data class RemoteControlSettings(
    val streams: List<RemoteControlSettingsStream>,
    val scenes: List<RemoteControlSettingsScene>,
    val bitratePresets: List<RemoteControlSettingsBitratePreset> = emptyList(),
    val mics: List<RemoteControlSettingsMic> = emptyList(),
    val srt: RemoteControlSettingsSrt = RemoteControlSettingsSrt(false, emptyList()),
)

@Serializable
data class RemoteControlZoomPreset(val id: SUuid, val name: String)

/** Upstream `RemoteControlAssistantStreamerState`: only changed fields are sent. */
@Serializable
data class RemoteControlAssistantStreamerState(
    val scene: SUuid? = null,
    val mic: String? = null,
    val bitrate: SUuid? = null,
    val zoom: SFloat? = null,
    val zoomPresets: List<RemoteControlZoomPreset>? = null,
    val zoomPreset: SUuid? = null,
    val debugLogging: Boolean? = null,
    val streaming: Boolean? = null,
    val recording: Boolean? = null,
    val previewStream: Boolean? = null,
    val muted: Boolean? = null,
    val stealthMode: Boolean? = null,
    val torchOn: Boolean? = null,
    val batteryCharging: Boolean? = null,
    @Serializable(with = RemoteControlFiltersSerializer::class)
    val filters: Map<RemoteControlFilter, Boolean>? = null,
)

/** Upstream `RemoteControlResponse` (v1 subset). */
@Serializable(with = RemoteControlResponse.Serializer::class)
sealed interface RemoteControlResponse {
    @Serializable data class GetStatus(
        val general: RemoteControlStatusGeneral? = null,
        val topLeft: RemoteControlStatusTopLeft,
        val topRight: RemoteControlStatusTopRight,
    ) : RemoteControlResponse

    @Serializable data class GetSettings(val data: RemoteControlSettings) : RemoteControlResponse

    object Serializer : SwiftEnumSerializer<RemoteControlResponse>(
        "RemoteControlResponse",
        listOf(swiftCase("getStatus", GetStatus.serializer()), swiftCase("getSettings", GetSettings.serializer())),
    )
}

/** Upstream `RemoteControlEvent` (v1 subset). */
@Serializable(with = RemoteControlEvent.Serializer::class)
sealed interface RemoteControlEvent {
    @Serializable data class State(val data: RemoteControlAssistantStreamerState) : RemoteControlEvent

    @Serializable data class Log(val entry: String) : RemoteControlEvent

    @Serializable data class Status(
        val general: RemoteControlStatusGeneral? = null,
        val topLeft: RemoteControlStatusTopLeft? = null,
        val topRight: RemoteControlStatusTopRight? = null,
    ) : RemoteControlEvent

    object Serializer : SwiftEnumSerializer<RemoteControlEvent>(
        "RemoteControlEvent",
        listOf(swiftCase("state", State.serializer()), swiftCase("log", Log.serializer()), swiftCase("status", Status.serializer())),
    )
}

@Serializable
data class RemoteControlAuthentication(val challenge: String, val salt: String)

/** Upstream `RemoteControlResult`. */
@Serializable(with = RemoteControlResult.Serializer::class)
enum class RemoteControlResult(val swiftName: String) {
    OK("ok"),
    WRONG_PASSWORD("wrongPassword"),
    UNKNOWN_REQUEST("unknownRequest"),
    NOT_IDENTIFIED("notIdentified"),
    ALREADY_IDENTIFIED("alreadyIdentified"),
    ERROR("error"),
    ;

    object Serializer : SwiftUnitEnumSerializer<RemoteControlResult>("RemoteControlResult", entries, { it.swiftName }, ERROR)
}

/** Upstream `RemoteControlMessageToStreamer`: what the assistant sends. */
@Serializable(with = RemoteControlMessageToStreamer.Serializer::class)
sealed interface RemoteControlMessageToStreamer {
    @Serializable data class Hello(val apiVersion: String, val authentication: RemoteControlAuthentication) : RemoteControlMessageToStreamer

    @Serializable data class Identified(val result: RemoteControlResult) : RemoteControlMessageToStreamer

    @Serializable data class Request(val id: Int, val data: RemoteControlRequest) : RemoteControlMessageToStreamer

    @Serializable data object Pong : RemoteControlMessageToStreamer

    object Serializer : SwiftEnumSerializer<RemoteControlMessageToStreamer>(
        "RemoteControlMessageToStreamer",
        listOf(
            swiftCase("hello", Hello.serializer()),
            swiftCase("identified", Identified.serializer()),
            swiftCase("request", Request.serializer()),
            swiftCase("pong", Pong.serializer()),
        ),
    )

    companion object {
        fun fromJson(json: String): RemoteControlMessageToStreamer = SwiftJson.decodeFromString(Serializer, json)
    }
}

/** Upstream `RemoteControlMessageToAssistant`: what the streamer sends. */
@Serializable(with = RemoteControlMessageToAssistant.Serializer::class)
sealed interface RemoteControlMessageToAssistant {
    @Serializable data class Identify(val streamerId: String? = null, val authentication: String) : RemoteControlMessageToAssistant

    @Serializable data class Response(
        val id: Int,
        val result: RemoteControlResult,
        val data: RemoteControlResponse? = null,
    ) : RemoteControlMessageToAssistant

    @Serializable data class Event(val data: RemoteControlEvent) : RemoteControlMessageToAssistant

    @Serializable data class Preview(val preview: SData) : RemoteControlMessageToAssistant

    @Serializable data class TwitchStart(
        val channelName: String? = null,
        val channelId: String,
        val accessToken: String,
    ) : RemoteControlMessageToAssistant

    @Serializable data object Ping : RemoteControlMessageToAssistant

    object Serializer : SwiftEnumSerializer<RemoteControlMessageToAssistant>(
        "RemoteControlMessageToAssistant",
        listOf(
            swiftCase("identify", Identify.serializer()),
            swiftCase("response", Response.serializer()),
            swiftCase("event", Event.serializer()),
            swiftCase("preview", Preview.serializer()),
            swiftCase("twitchStart", TwitchStart.serializer()),
            swiftCase("ping", Ping.serializer()),
        ),
    )

    fun toJson(): String = SwiftJson.encodeToString(Serializer, this)
}
