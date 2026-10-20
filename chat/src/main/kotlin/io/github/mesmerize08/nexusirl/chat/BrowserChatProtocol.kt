// Port of the chat parts of Moblin/VideoEffects/Browser/BrowserEffectServer.swift @97e26d2: the
// JSON that moblin.js pages send and receive (Swift Codable enum encoding).
package io.github.mesmerize08.nexusirl.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A page's `moblin.subscribe({chat: {prefix}})`; with a [prefix], only messages starting with it are sent. */
data class BrowserChatSubscription(val prefix: String?)

object BrowserChatProtocol {
    /**
     * Upstream `MessageToMoblin.subscribe(topic: .chat(prefix:))` from a page, or null for any
     * other message (ping, publish, speech-to-text) or malformed input.
     */
    fun parseChatSubscription(message: String): BrowserChatSubscription? = try {
        val root = Json.parseToJsonElement(message) as? JsonObject
        val topic = (root?.get("subscribe") as? JsonObject)?.get("topic") as? JsonObject
        val chat = topic?.get("chat") as? JsonObject
        chat?.let { BrowserChatSubscription((it["prefix"] as? JsonPrimitive)?.takeIf { prefix -> prefix.isString }?.content) }
    } catch (e: Exception) {
        null
    }

    /** Upstream `sendChatMessage`: whether [post] goes to a page with [subscription]. */
    fun matches(subscription: BrowserChatSubscription, post: ChatPost): Boolean {
        val prefix = subscription.prefix ?: return true
        return post.segments.firstOrNull()?.text?.startsWith(prefix) == true
    }

    /** Upstream `MessageToBrowser.message(data: .chat(message:))` as JSON (nil fields left out, as Swift does). */
    fun encodeChatMessage(post: ChatPost): String = buildJsonObject {
        put(
            "message",
            buildJsonObject {
                put(
                    "data",
                    buildJsonObject {
                        put(
                            "chat",
                            buildJsonObject {
                                put(
                                    "message",
                                    buildJsonObject {
                                        put("user", post.user)
                                        put("segments", buildJsonArray { post.segments.forEach { add(encodeSegment(it)) } })
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }.toString()

    private fun encodeSegment(segment: ChatPostSegment) = buildJsonObject {
        put("id", segment.id)
        segment.text?.let { put("text", it) }
        segment.url?.let { put("url", encodeEmote(it)) }
        segment.bigGifUrl?.let { put("bigGifUrl", encodeEmote(it)) }
    }

    private fun encodeEmote(emote: ChatEmote) = buildJsonObject {
        emote.moving?.let { put("moving", it) }
        emote.still?.let { put("still", it) }
        emote.name?.let { put("name", it) }
    }
}
