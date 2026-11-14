// Port of Moblin/Various/Settings/SettingsIngests.swift @97e26d2 (the RTMP server settings),
// Common/Various/Validate.swift (`isValidIngestLatency`, `isValidPort`), Moblin/Various/Utils/Utils.swift
// (`randomHumanString`) and Common/Various/CommonUtils.swift (`rtmpCamera`).
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SUuid
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreserving
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreservingSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Upstream `SettingsRtmpServerStream`: one publisher the RTMP server accepts. The [streamKey] is
 * a secret (the credential store takes it out of `settings.json`).
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsRtmpServerStream.Serializer::class)
data class SettingsRtmpServerStream(
    val id: SUuid = UUID.randomUUID(),
    val name: String = "My stream",
    val streamKey: String = "",
    /** Buffer in milliseconds. */
    val latency: Int = 2000,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsRtmpServerStream>(generatedSerializer())

    /** Upstream `rtmpCamera(name:)`: how the stream is named as a camera. */
    val camera: String get() = "$name (RTMP)"

    val latencySeconds: Double get() = latency / 1000.0
}

/** Upstream `SettingsRtmpServer`. An import never turns it [enabled] (see SettingsStore.import). */
@KeepGeneratedSerializer
@Serializable(with = SettingsRtmpServer.Serializer::class)
data class SettingsRtmpServer(
    val enabled: Boolean = false,
    val port: Int = 1935,
    val streams: List<SettingsRtmpServerStream> = emptyList(),
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsRtmpServer>(generatedSerializer())
}

/** Upstream `isValidIngestLatency` and `isValidPort`: an error message, or null when valid. */
object IngestValidators {
    fun latency(value: String): String? {
        val latency = value.toIntOrNull() ?: return "Not a number"
        if (latency < 5) return "Too small"
        if (latency > 10000) return "Too big"
        return null
    }

    fun port(value: String): String? {
        // Upstream parses a UInt: a sign or a value past its range is "Not a number".
        val port = value.takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'9' } }?.toULongOrNull()
            ?: return "Not a number"
        if (port == 0uL) return "Too small"
        if (port > 65535uL) return "Too big"
        return null
    }
}

private val secureRandom = SecureRandom()

/** Upstream `randomHumanString`: 15 random bytes in Base64 without `+`, `/` and `=`. */
fun randomHumanString(): String {
    val bytes = ByteArray(15).also { secureRandom.nextBytes(it) }
    return Base64.getEncoder().encodeToString(bytes).replace(Regex("[+/=]"), "")
}
