// Port of Moblin/Various/MoblinSettingsUrl.swift and the stream parts of
// Moblin/Various/Model/ModelSettingsUrl.swift @97e26d2.
package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SwiftJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLDecoder

@Serializable
data class MoblinSettingsUrlStreamVideo(
    val resolution: StreamResolution? = null,
    val fps: Int? = null,
    val bitrate: Long? = null,
    val codec: StreamCodec? = null,
    val bFrames: Boolean? = null,
    val maxKeyFrameInterval: Int? = null,
)

@Serializable
data class MoblinSettingsUrlStreamAudio(val bitrate: Int? = null)

@Serializable
data class MoblinSettingsSrt(val latency: Int? = null, val adaptiveBitrateEnabled: Boolean? = null)

@Serializable
data class MoblinSettingsUrlStreamTwitch(val channelName: String, val channelId: String)

@Serializable
data class MoblinSettingsUrlStreamKick(val channelName: String)

@Serializable
data class MoblinSettingsUrlStream(
    val name: String,
    val url: String,
    val selected: Boolean? = null,
    val video: MoblinSettingsUrlStreamVideo? = null,
    val audio: MoblinSettingsUrlStreamAudio? = null,
    val srt: MoblinSettingsSrt? = null,
    val obs: JsonObject? = null,
    val twitch: MoblinSettingsUrlStreamTwitch? = null,
    val kick: MoblinSettingsUrlStreamKick? = null,
)

/**
 * A `moblin://?<json>` settings link (upstream `MoblinSettingsUrl`), used by stream providers to
 * set up the app from a link or QR code. Every link is untrusted input: [parse] validates it like
 * upstream and [apply] never starts streaming (docs/PORT_PLAN.md §9.7, threat T7).
 */
@Serializable
data class MoblinSettingsUrl(
    val streams: List<MoblinSettingsUrlStream>? = null,
    val quickButtons: JsonObject? = null,
    val webBrowser: JsonObject? = null,
    val remoteControl: JsonObject? = null,
    val moblinCloud: JsonObject? = null,
) {
    class InvalidSettingsUrlException(message: String) : Exception(message)

    /** Parts of the link the Android app does not apply yet, for the confirmation dialog. */
    val ignoredParts: List<String>
        get() = buildList {
            if (streams.orEmpty().any { it.obs != null }) add("OBS WebSocket")
            if (quickButtons != null) add("quick buttons")
            if (webBrowser != null) add("web browser")
            if (remoteControl != null) add("remote control")
            // Moblin Cloud is a service run by the upstream author; this app never uses it (§8.1).
            if (moblinCloud != null) add("Moblin Cloud")
        }

    /** Names of streams in the link that already exist (upstream `findCollidingStreamNames`). */
    fun collidingStreamNames(database: Database): Set<String> =
        streams.orEmpty().map { it.name }.filter { name -> database.streams.any { it.name == name } }.toSet()

    /**
     * Port of upstream `handleSettingsUrlsDefaultStreams`: adds the link's streams (or, with
     * [replaceCollisions], updates streams with the same name) and selects the one marked
     * `selected`. Values outside upstream's accepted ranges are ignored, as upstream does.
     */
    fun apply(database: Database, replaceCollisions: Boolean): Database {
        var result = database
        var newSelected: SettingsStream? = null
        for (linkStream in streams.orEmpty()) {
            val existing = if (replaceCollisions) result.streams.firstOrNull { it.name == linkStream.name } else null
            var target = existing ?: SettingsStream(name = makeUniqueName(linkStream.name, result.streams.map { it.name }))
            if (existing != null && existing.enabled && newSelected == null) {
                newSelected = existing
            }
            target = target.copy(url = linkStream.url.trim())
            linkStream.video?.let { video ->
                video.resolution?.let { target = target.copy(resolution = it) }
                video.fps?.takeIf { it in STREAM_FPS_CHOICES }?.let { target = target.copy(fps = it) }
                video.bitrate?.takeIf { it in 50_000..50_000_000 }?.let { target = target.copy(bitrate = it) }
                video.codec?.let { target = target.copy(codec = it) }
                video.bFrames?.let { target = target.copy(bFrames = it) }
                video.maxKeyFrameInterval?.takeIf { it in 0..10 }?.let { target = target.copy(maxKeyFrameInterval = it) }
            }
            linkStream.audio?.bitrate?.takeIf { isValidAudioBitrate(it) }?.let { target = target.copy(audioBitrate = it) }
            linkStream.srt?.let { srt ->
                srt.latency?.let { target = target.copy(srt = target.srt.copy(latency = it)) }
                srt.adaptiveBitrateEnabled?.let { target = target.copy(srt = target.srt.copy(adaptiveBitrateEnabled = it)) }
            }
            // Channel names are not secret; they are kept for the chat features of later phases.
            val extra = target.extra.toMutableMap()
            linkStream.twitch?.let {
                extra["twitchChannelName"] = JsonPrimitive(it.channelName.trim())
                extra["twitchChannelId"] = JsonPrimitive(it.channelId.trim())
            }
            linkStream.kick?.let { extra["kickChannelName"] = JsonPrimitive(it.channelName.trim()) }
            target = target.copy(extra = JsonObject(extra))
            result = if (existing != null) result.updateStream(target) else result.copy(streams = result.streams + target)
            if (linkStream.selected == true) {
                newSelected = target
            }
        }
        newSelected?.let { result = result.selectStream(it.id) }
        if (result.streams.none { it.enabled }) {
            result.streams.firstOrNull()?.let { result = result.selectStream(it.id) }
        }
        return result
    }

    companion object {
        /**
         * Parses a `moblin://?<json>` link (upstream `handleSettingsUrlWithConfirmation` and
         * `MoblinSettingsUrl.fromString`). Throws [InvalidSettingsUrlException] with a message
         * for the user.
         */
        fun parse(link: String): MoblinSettingsUrl {
            val schemeEnd = link.indexOf(':')
            if (schemeEnd < 0 || !link.substring(0, schemeEnd).equals("moblin", ignoreCase = true)) {
                throw InvalidSettingsUrlException("Not a moblin:// link")
            }
            val rest = link.substring(schemeEnd + 1).removePrefix("//")
            val queryStart = rest.indexOf('?')
            if (queryStart < 0) {
                throw InvalidSettingsUrlException("Custom URL query is missing")
            }
            if (rest.substring(0, queryStart).trim('/').isNotEmpty()) {
                throw InvalidSettingsUrlException("Custom URL path is not empty")
            }
            val rawQuery = rest.substring(queryStart + 1)
            // Links are usually percent-encoded; raw JSON is accepted too, like iOS.
            val query = if (rawQuery.trimStart().startsWith("{")) rawQuery else URLDecoder.decode(rawQuery.replace("+", "%2B"), "UTF-8")
            return fromJson(query)
        }

        fun fromJson(query: String): MoblinSettingsUrl {
            val settings = try {
                SwiftJson.decodeFromString(serializer(), query)
            } catch (e: Exception) {
                throw InvalidSettingsUrlException("Malformed settings: ${e.message?.lineSequence()?.firstOrNull() ?: "invalid JSON"}")
            }
            for (stream in settings.streams.orEmpty()) {
                StreamUrls.validate(StreamUrls.clean(stream.url))?.let { throw InvalidSettingsUrlException("${stream.name}: $it") }
                stream.srt?.latency?.let {
                    if (it < 0) throw InvalidSettingsUrlException("Negative SRT latency")
                    if (it > 65535) throw InvalidSettingsUrlException("Too big SRT latency")
                }
            }
            return settings
        }

        /** Upstream `isValidAudioBitrate`. */
        fun isValidAudioBitrate(bitrate: Int): Boolean = bitrate in 32_000..320_000 && bitrate % 32_000 == 0

        /** Upstream `makeUniqueName`. */
        fun makeUniqueName(name: String, existingNames: List<String>): String {
            if (name !in existingNames) {
                return name
            }
            var number = 1
            while ("$name $number" in existingNames) {
                number += 1
            }
            return "$name $number"
        }
    }
}
