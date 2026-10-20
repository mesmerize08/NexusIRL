// Port of Moblin/Integrations/Emotes/{Emotes,Bttv,Ffz,Seventv}.swift @97e26d2.
package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.chat.net.ReconnectingWebSocket
import io.github.mesmerize08.nexusirl.settings.SettingsStreamChat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Upstream `EmotesPlatform`; [apiName] is how the emote APIs name it. */
enum class EmotesPlatform(val apiName: String) {
    TWITCH("twitch"),
    KICK("kick"),
    YOUTUBE("youtube"),
}

private class NotFoundException : Exception()

private fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)

private fun JsonElement?.text(key: String): String? = (field(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.list(key: String): List<JsonElement>? = field(key) as? JsonArray

/**
 * Fetches a channel's BTTV, FFZ (through BTTV's cache) and 7TV emotes into an [EmoteSet], each
 * provider only when enabled in [settings]. Like upstream, failures are retried after 30 s,
 * doubling up to an hour, until every enabled provider has answered; a channel without emotes on
 * a provider (HTTP 404 on a channel list) is not a failure.
 */
class ThirdPartyEmotes(
    private val platform: EmotesPlatform,
    private val channelId: String,
    private val settings: SettingsStreamChat,
    private val emoteSet: EmoteSet,
    private val client: OkHttpClient = ReconnectingWebSocket.SHARED_CLIENT,
    private val bttvBaseUrl: String = "https://api.betterttv.net",
    private val seventvBaseUrl: String = "https://7tv.io",
) {
    @Volatile
    private var thread: Thread? = null

    private val emotes = HashMap<String, ThirdPartyEmote>()

    fun start() {
        if (!settings.bttvEmotes && !settings.ffzEmotes && !settings.seventvEmotes) {
            return
        }
        val worker = Thread({
            var retrySeconds = 30L
            while (!Thread.currentThread().isInterrupted) {
                if (fetchAll()) {
                    return@Thread
                }
                try {
                    Thread.sleep(retrySeconds * 1000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                retrySeconds = (retrySeconds * 2).coerceAtMost(3600)
            }
        }, "emotes-${platform.apiName}")
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    fun stop() {
        thread?.interrupt()
        thread = null
    }

    /** One round over the enabled providers (upstream merges each into the set as it arrives). Returns whether all succeeded. */
    fun fetchAll(): Boolean {
        var ok = true
        if (settings.bttvEmotes) {
            ok = fetch { bttvGlobal() } and ok
            ok = fetch { bttvChannel() } and ok
        }
        if (settings.ffzEmotes) {
            ok = fetch { ffz("$bttvBaseUrl/3/cached/frankerfacez/emotes/global") } and ok
            if (channelId.isNotEmpty()) {
                ok = fetch { ffz("$bttvBaseUrl/3/cached/frankerfacez/users/${platform.apiName}/$channelId") } and ok
            }
        }
        if (settings.seventvEmotes) {
            ok = fetch { seventvGlobal() } and ok
            ok = fetch { seventvChannel() } and ok
        }
        return ok
    }

    private fun fetch(block: () -> Map<String, ThirdPartyEmote>): Boolean {
        val fetched = try {
            block()
        } catch (e: NotFoundException) {
            emptyMap()
        } catch (e: Exception) {
            return false
        }
        synchronized(emotes) {
            emotes.putAll(fetched)
            emoteSet.set(HashMap(emotes))
        }
        return true
    }

    /** With [notFoundIsEmpty] (channel lists, all FFZ lists), HTTP 404 means "no emotes" as upstream. */
    private fun get(url: String, notFoundIsEmpty: Boolean = true): JsonElement = client.newCall(
        Request.Builder().url(url).build(),
    ).execute().use { response ->
        if (response.code == 404 && notFoundIsEmpty) throw NotFoundException()
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
        Json.parseToJsonElement(response.body!!.string())
    }

    private fun bttvEmotes(list: List<JsonElement>?): Map<String, ThirdPartyEmote> = list.orEmpty().mapNotNull { emote ->
        val id = emote.text("id") ?: return@mapNotNull null
        val code = emote.text("code") ?: return@mapNotNull null
        code to ThirdPartyEmote("https://cdn.betterttv.net/emote/$id/1x")
    }.toMap()

    private fun bttvGlobal(): Map<String, ThirdPartyEmote> = bttvEmotes(
        get("$bttvBaseUrl/3/cached/emotes/global", notFoundIsEmpty = false) as? JsonArray,
    )

    private fun bttvChannel(): Map<String, ThirdPartyEmote> {
        if (channelId.isEmpty()) {
            return emptyMap()
        }
        val channel = get("$bttvBaseUrl/3/cached/users/${platform.apiName}/$channelId")
        // Channel emotes win over shared ones, as upstream adds them last.
        return bttvEmotes(channel.list("sharedEmotes")) + bttvEmotes(channel.list("channelEmotes"))
    }

    private fun ffz(url: String): Map<String, ThirdPartyEmote> = (get(url) as? JsonArray).orEmpty().mapNotNull { emote ->
        val code = emote.text("code") ?: return@mapNotNull null
        val images = emote.field("images")
        val image = images.text("1x") ?: images.text("2x") ?: images.text("4x") ?: return@mapNotNull null
        code to ThirdPartyEmote(image)
    }.toMap()

    private fun seventvEmotes(list: List<JsonElement>?): Map<String, ThirdPartyEmote> {
        if (list.isNullOrEmpty()) {
            // Upstream treats a missing or empty list as an error (it retries).
            throw IllegalStateException("7TV emotes missing")
        }
        return list.mapNotNull { emote ->
            val name = emote.text("name") ?: return@mapNotNull null
            val host = emote.field("data").field("host")
            val hostUrl = host.text("url") ?: return@mapNotNull null
            val file = host.list("files").orEmpty().firstOrNull { it.text("name")?.endsWith(".webp") == true } ?: return@mapNotNull null
            val still = file.text("static_name")?.let { "https:$hostUrl/$it" }
            name to ThirdPartyEmote("https:$hostUrl/${file.text("name")}", still)
        }.toMap()
    }

    private fun seventvGlobal(): Map<String, ThirdPartyEmote> = seventvEmotes(
        get("$seventvBaseUrl/v3/emote-sets/global", notFoundIsEmpty = false).list("emotes"),
    )

    private fun seventvChannel(): Map<String, ThirdPartyEmote> {
        if (channelId.isEmpty()) {
            return emptyMap()
        }
        return seventvEmotes(get("$seventvBaseUrl/v3/users/${platform.apiName}/$channelId").field("emote_set").list("emotes"))
    }
}
