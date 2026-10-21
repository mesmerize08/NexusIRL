// Port of Moblin/Various/Settings/SettingsRemoteControl.swift @97e26d2 (v1 subset: the streamer
// side; the assistant, web and relay settings stay in `extra`).
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SFloat
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreserving
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreservingSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom

/** Upstream `SettingsRemoteControlStreamer`: this device connecting to an assistant at [url]. */
@KeepGeneratedSerializer
@Serializable(with = SettingsRemoteControlStreamer.Serializer::class)
data class SettingsRemoteControlStreamer(
    val enabled: Boolean = false,
    val name: String = "",
    val url: String = "",
    val previewFps: SFloat = 1f,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsRemoteControlStreamer>(generatedSerializer())
}

/** Upstream `SettingsRemoteControlAssistant`: this device as the assistant, listening on [port]. */
@KeepGeneratedSerializer
@Serializable(with = SettingsRemoteControlAssistant.Serializer::class)
data class SettingsRemoteControlAssistant(
    val enabled: Boolean = false,
    val port: Int = 2345,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsRemoteControlAssistant>(generatedSerializer())
}

/**
 * Upstream `SettingsRemoteControlWeb`: the browser remote control on [port] (HTTP) and port + 1
 * (WebSocket). Android cannot listen below 1024, so upstream's default 80 becomes 8080 there
 * ([androidPort]). [token] is Android's addition (a `{secret:…}` reference, never exported):
 * upstream's web remote control has no login.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsRemoteControlWeb.Serializer::class)
data class SettingsRemoteControlWeb(
    val enabled: Boolean = false,
    val port: Int = 80,
    val deviceName: String = "",
    @SerialName("nexusirlToken")
    val token: String = "",
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsRemoteControlWeb>(generatedSerializer())

    val androidPort: Int
        get() = if (port < 1024) 8080 else port.coerceAtMost(65534)

    companion object {
        /** A 128-bit random access token. */
        fun generateToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Upstream `SettingsRemoteControl`. The [password] is a secret: in `settings.json` it is a
 * `{secret:…}` reference to the secret store (docs/PORT_PLAN.md §9.2), and it defaults to a
 * strong random one (§9.6) instead of upstream's short human-readable string.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsRemoteControl.Serializer::class)
data class SettingsRemoteControl(
    val assistant: SettingsRemoteControlAssistant = SettingsRemoteControlAssistant(),
    val streamer: SettingsRemoteControlStreamer = SettingsRemoteControlStreamer(),
    val web: SettingsRemoteControlWeb = SettingsRemoteControlWeb(),
    val password: String = generatePassword(),
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsRemoteControl>(generatedSerializer())

    companion object {
        private const val ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** 20 characters from an unambiguous alphabet: about 115 bits. */
        fun generatePassword(): String {
            val random = SecureRandom()
            return (1..20).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        }
    }
}
