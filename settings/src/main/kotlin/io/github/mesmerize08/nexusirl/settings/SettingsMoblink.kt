// Port of Moblin/Various/Settings/SettingsMoblink.swift @97e26d2.
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SwiftPreserving
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreservingSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom

/** Upstream `SettingsMoblinkStreamer` (this device as a streamer; not on Android yet). */
@KeepGeneratedSerializer
@Serializable(with = SettingsMoblinkStreamer.Serializer::class)
data class SettingsMoblinkStreamer(
    val enabled: Boolean = false,
    val port: Int = 7777,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsMoblinkStreamer>(generatedSerializer())
}

/**
 * Upstream `SettingsMoblinkRelay`: this device lends its mobile data to a streamer, found on the
 * Wi-Fi automatically, or at [url] when [manual]. A [url] that is not ws:// or wss:// is dropped
 * on load, as upstream.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsMoblinkRelay.Serializer::class)
data class SettingsMoblinkRelay(
    val enabled: Boolean = false,
    val name: String = randomRelayName(),
    val url: String = "",
    val manual: Boolean = false,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsMoblinkRelay>(generatedSerializer())

    /** The URL if it is a usable WebSocket URL, else empty (upstream `isValidWebSocketUrl`). */
    val validUrl: String
        get() = url.takeIf { isValidWebSocketUrl(it) }.orEmpty()

    companion object {
        fun isValidWebSocketUrl(url: String): Boolean {
            val scheme = url.substringBefore("://", "").lowercase()
            val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?')
            return (scheme == "ws" || scheme == "wss") && host.isNotEmpty()
        }

        private fun randomRelayName(): String = "Android-" + (1..4).map { "0123456789ABCDEF"[SecureRandom().nextInt(16)] }.joinToString("")
    }
}

/**
 * Upstream `SettingsMoblink`, stored under upstream's keys "server" and "client". The [password]
 * is a secret (a `{secret:…}` reference on disk). Upstream defaults it to "1234"; Android starts
 * empty so the user enters the streamer's password instead of relying on a guessable default.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsMoblink.Serializer::class)
data class SettingsMoblink(
    @SerialName("server")
    val streamer: SettingsMoblinkStreamer = SettingsMoblinkStreamer(),
    @SerialName("client")
    val relay: SettingsMoblinkRelay = SettingsMoblinkRelay(),
    val password: String = "",
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsMoblink>(generatedSerializer())
}
