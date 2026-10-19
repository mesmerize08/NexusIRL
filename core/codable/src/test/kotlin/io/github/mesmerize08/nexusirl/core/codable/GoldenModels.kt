package io.github.mesmerize08.nexusirl.core.codable

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

// Minimal Kotlin mirrors of upstream Swift types, used only to check that :core:codable reproduces
// Swift's synthesized Codable JSON byte-for-byte. The full protocol types live in
// :remotecontrol and :moblink.
//
// Sources (upstream @97e26d2):
//   Moblin/RemoteControl/RemoteControl.swift
//   Moblin/Moblink/MoblinkProtocol.swift
//   Moblin/Various/Managers/GForceManager.swift

@Serializable(with = GimbalMotion.Serializer::class)
sealed interface GimbalMotion {
    @Serializable data object Kapow : GimbalMotion

    @Serializable data object Nod : GimbalMotion

    object Serializer : SwiftEnumSerializer<GimbalMotion>(
        "GimbalMotion",
        listOf(swiftCase("kapow", Kapow.serializer()), swiftCase("nod", Nod.serializer())),
    )
}

@Serializable(with = MusicCommand.Serializer::class)
sealed interface MusicCommand {
    @Serializable data object Play : MusicCommand

    @Serializable data class Add(val title: String, val user: String? = null, val requestId: String? = null) : MusicCommand

    @Serializable data class Find(val title: String) : MusicCommand

    @Serializable data class Remove(val id: String) : MusicCommand

    object Serializer : SwiftEnumSerializer<MusicCommand>(
        "RemoteControlMusicCommand",
        listOf(
            swiftCase("play", Play.serializer()),
            swiftCase("add", Add.serializer()),
            swiftCase("find", Find.serializer()),
            swiftCase("remove", Remove.serializer()),
        ),
    )
}

@Serializable(with = Request.Serializer::class)
sealed interface Request {
    @Serializable data class SetGimbalTracking(val on: Boolean) : Request

    @Serializable data class SetGimbalMovement(val x: SFloat, val y: SFloat) : Request

    @Serializable data class AnimateGimbal(val motion: GimbalMotion) : Request

    @Serializable data object SaveGimbalPreset : Request

    @Serializable data class SendMessage(val text: String) : Request

    @Serializable data class Music(val command: MusicCommand) : Request

    object Serializer : SwiftEnumSerializer<Request>(
        "RemoteControlRequest",
        listOf(
            swiftCase("setGimbalTracking", SetGimbalTracking.serializer()),
            swiftCase("setGimbalMovement", SetGimbalMovement.serializer()),
            swiftCase("animateGimbal", AnimateGimbal.serializer()),
            swiftCase("saveGimbalPreset", SaveGimbalPreset.serializer()),
            swiftCase("sendMessage", SendMessage.serializer()),
            swiftCase("music", Music.serializer()),
        ),
    )
}

@Serializable
data class MusicFoundSong(val id: String, val title: String, val artist: String? = null, val duration: Int? = null)

@Serializable(with = Response.Serializer::class)
sealed interface Response {
    @Serializable data class MusicFind(val song: MusicFoundSong? = null) : Response

    object Serializer : SwiftEnumSerializer<Response>(
        "RemoteControlResponse",
        listOf(swiftCase("musicFind", MusicFind.serializer())),
    )
}

@Serializable
data class GForce(val now: SDouble, val recentMax: SDouble, val max: SDouble)

object HeartRatesSerializer : SwiftNullableValueMapSerializer<Int>(Int.serializer())

@Serializable
data class Stats(
    val date: SDate,
    val timeZone: String,
    val speed: SDouble,
    val averageSpeed: SDouble,
    val altitude: SDouble,
    val latitude: SDouble? = null,
    val longitude: SDouble? = null,
    val distance: SDouble,
    val splitDistance: SDouble,
    val slopePercent: SDouble,
    val altitudeAscent: SDouble,
    val altitudeDescent: SDouble,
    val splitAltitudeAscent: SDouble,
    val splitAltitudeDescent: SDouble,
    val temperature: SDouble? = null,
    val feelsLikeTemperature: SDouble? = null,
    val windSpeed: SDouble? = null,
    val windGust: SDouble? = null,
    val country: String? = null,
    val countryFlag: String? = null,
    val state: String? = null,
    val area: String? = null,
    val city: String? = null,
    val neighborhood: String? = null,
    @Serializable(with = HeartRatesSerializer::class)
    val heartRates: Map<String, Int?>,
    val activeEnergyBurned: Int? = null,
    val workoutDistance: Int? = null,
    val power: Int? = null,
    val stepCount: Int? = null,
    val cyclingPower: Int,
    val cyclingCadence: Int,
    val cyclingSpeed: SDouble,
    val gForce: GForce? = null,
)

@Serializable(with = MoblinkThermalState.Serializer::class)
enum class MoblinkThermalState(override val rawValue: String) : SwiftRawValueEnum {
    WHITE("white"),
    YELLOW("yellow"),
    RED("red"),
    ;

    object Serializer : SwiftRawValueEnumSerializer<MoblinkThermalState>(
        "MoblinkThermalState",
        entries,
    )
}

@Serializable(with = MoblinkResult.Serializer::class)
sealed interface MoblinkResult {
    @Serializable data object Ok : MoblinkResult

    @Serializable data object WrongPassword : MoblinkResult

    object Serializer : SwiftEnumSerializer<MoblinkResult>(
        "MoblinkResult",
        listOf(swiftCase("ok", Ok.serializer()), swiftCase("wrongPassword", WrongPassword.serializer())),
    )
}

@Serializable(with = MoblinkResponse.Serializer::class)
sealed interface MoblinkResponse {
    @Serializable data class StartTunnel(val port: Int) : MoblinkResponse

    @Serializable
    data class Status(val batteryPercentage: Int? = null, val thermalState: MoblinkThermalState? = null) :
        MoblinkResponse

    object Serializer : SwiftEnumSerializer<MoblinkResponse>(
        "MoblinkResponse",
        listOf(swiftCase("startTunnel", StartTunnel.serializer()), swiftCase("status", Status.serializer())),
    )
}

@Serializable(with = MoblinkMessageToStreamer.Serializer::class)
sealed interface MoblinkMessageToStreamer {
    @Serializable data class Identify(val id: SUuid, val name: String, val authentication: String) :
        MoblinkMessageToStreamer

    @Serializable data class Response(val id: Int, val result: MoblinkResult, val data: MoblinkResponse? = null) :
        MoblinkMessageToStreamer

    object Serializer : SwiftEnumSerializer<MoblinkMessageToStreamer>(
        "MoblinkMessageToStreamer",
        listOf(swiftCase("identify", Identify.serializer()), swiftCase("response", Response.serializer())),
    )
}
