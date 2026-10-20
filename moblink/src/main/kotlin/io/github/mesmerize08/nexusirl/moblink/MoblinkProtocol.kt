// Port of Moblin/Moblink/MoblinkProtocol.swift @97e26d2. Byte-exact Swift Codable JSON, so an
// Android relay works with iOS Moblin streamers and the other way round.
package io.github.mesmerize08.nexusirl.moblink

import io.github.mesmerize08.nexusirl.core.codable.SUuid
import io.github.mesmerize08.nexusirl.core.codable.SwiftEnumSerializer
import io.github.mesmerize08.nexusirl.core.codable.SwiftJson
import io.github.mesmerize08.nexusirl.core.codable.SwiftRawValueEnum
import io.github.mesmerize08.nexusirl.core.codable.SwiftRawValueEnumSerializer
import io.github.mesmerize08.nexusirl.core.codable.SwiftUnitEnumSerializer
import io.github.mesmerize08.nexusirl.core.codable.swiftCase
import kotlinx.serialization.Serializable

/** Upstream `moblinkBonjourType`: streamers advertise this service with a TXT record `name`. */
const val MOBLINK_SERVICE_TYPE = "_moblink._tcp"

/** Upstream `DefaultTcpPorts.moblinkStreamer`. */
const val MOBLINK_DEFAULT_PORT = 7777

/** Upstream `MoblinkThermalState`. */
@Serializable(with = MoblinkThermalState.Serializer::class)
enum class MoblinkThermalState(override val rawValue: String) : SwiftRawValueEnum {
    WHITE("white"),
    YELLOW("yellow"),
    RED("red"),
    ;

    object Serializer : SwiftRawValueEnumSerializer<MoblinkThermalState>("MoblinkThermalState", entries, WHITE)
}

/** Upstream `MoblinkRequest`. */
@Serializable(with = MoblinkRequest.Serializer::class)
sealed interface MoblinkRequest {
    @Serializable data class StartTunnel(val address: String, val port: Int) : MoblinkRequest

    @Serializable data object Status : MoblinkRequest

    /** A request this relay does not know; answered with `unknownRequest`. */
    @Serializable data object Unknown : MoblinkRequest

    object Serializer : SwiftEnumSerializer<MoblinkRequest>(
        "MoblinkRequest",
        listOf(
            swiftCase("startTunnel", StartTunnel.serializer()),
            swiftCase("status", Status.serializer()),
            swiftCase("unknown", Unknown.serializer()),
        ),
        fallback = Unknown,
    )
}

/** Upstream `MoblinkResponse`. */
@Serializable(with = MoblinkResponse.Serializer::class)
sealed interface MoblinkResponse {
    @Serializable data class StartTunnel(val port: Int) : MoblinkResponse

    @Serializable data class Status(val batteryPercentage: Int? = null, val thermalState: MoblinkThermalState? = null) : MoblinkResponse

    object Serializer : SwiftEnumSerializer<MoblinkResponse>(
        "MoblinkResponse",
        listOf(swiftCase("startTunnel", StartTunnel.serializer()), swiftCase("status", Status.serializer())),
    )
}

@Serializable
data class MoblinkAuthentication(val challenge: String, val salt: String)

/** Upstream `MoblinkResult`. */
@Serializable(with = MoblinkResult.Serializer::class)
enum class MoblinkResult(val swiftName: String) {
    OK("ok"),
    WRONG_PASSWORD("wrongPassword"),
    UNKNOWN_REQUEST("unknownRequest"),
    NOT_IDENTIFIED("notIdentified"),
    ALREADY_IDENTIFIED("alreadyIdentified"),
    ;

    object Serializer : SwiftUnitEnumSerializer<MoblinkResult>("MoblinkResult", entries, { it.swiftName }, NOT_IDENTIFIED)
}

/** Upstream `MoblinkMessageToRelay`: what the streamer sends. */
@Serializable(with = MoblinkMessageToRelay.Serializer::class)
sealed interface MoblinkMessageToRelay {
    @Serializable data class Hello(val apiVersion: String, val authentication: MoblinkAuthentication) : MoblinkMessageToRelay

    @Serializable data class Identified(val result: MoblinkResult) : MoblinkMessageToRelay

    @Serializable data class Request(val id: Int, val data: MoblinkRequest) : MoblinkMessageToRelay

    object Serializer : SwiftEnumSerializer<MoblinkMessageToRelay>(
        "MoblinkMessageToRelay",
        listOf(
            swiftCase("hello", Hello.serializer()),
            swiftCase("identified", Identified.serializer()),
            swiftCase("request", Request.serializer()),
        ),
    )

    fun toJson(): String = SwiftJson.encodeToString(Serializer, this)

    companion object {
        fun fromJson(json: String): MoblinkMessageToRelay = SwiftJson.decodeFromString(Serializer, json)
    }
}

/** Upstream `MoblinkMessageToStreamer`: what the relay sends. */
@Serializable(with = MoblinkMessageToStreamer.Serializer::class)
sealed interface MoblinkMessageToStreamer {
    @Serializable data class Identify(val id: SUuid, val name: String, val authentication: String) : MoblinkMessageToStreamer

    @Serializable data class Response(val id: Int, val result: MoblinkResult, val data: MoblinkResponse? = null) : MoblinkMessageToStreamer

    object Serializer : SwiftEnumSerializer<MoblinkMessageToStreamer>(
        "MoblinkMessageToStreamer",
        listOf(swiftCase("identify", Identify.serializer()), swiftCase("response", Response.serializer())),
    )

    fun toJson(): String = SwiftJson.encodeToString(Serializer, this)

    companion object {
        fun fromJson(json: String): MoblinkMessageToStreamer = SwiftJson.decodeFromString(Serializer, json)
    }
}
