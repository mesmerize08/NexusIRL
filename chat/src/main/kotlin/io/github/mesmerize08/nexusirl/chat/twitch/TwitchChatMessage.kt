// Port of the IRC parsing in Moblin/StreamingPlatforms/Twitch/TwitchChat.swift @97e26d2.
package io.github.mesmerize08.nexusirl.chat.twitch

import io.github.mesmerize08.nexusirl.chat.ChatEmote
import io.github.mesmerize08.nexusirl.chat.ChatPostSegment
import io.github.mesmerize08.nexusirl.chat.EmoteSet

enum class TwitchChatCommand(val rawValue: String) {
    PING("PING"),
    PRIVATE_MESSAGE("PRIVMSG"),
    USER_NOTICE("USERNOTICE"),
    CLEAR_CHAT("CLEARCHAT"),
    CLEAR_MSG("CLEARMSG"),
    ROOM_STATE("ROOMSTATE"),
}

class InvalidTwitchMessageException(message: String) : Exception(message)

/** An emote or GIF at code point positions [first]..[last] of the message (upstream `ChatMessageEmote`). */
data class TwitchMessageEmote(val url: String, val stillUrl: String?, val first: Int, val last: Int, val isGif: Boolean = false)

/** Upstream `makeTwitchEmoteUrls`. */
fun makeTwitchEmoteUrls(id: String): Pair<String, String> =
    "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/3.0" to "https://static-cdn.jtvnw.net/emoticons/v2/$id/static/dark/3.0"

/** Upstream `TwitchChatMessage`: one IRC line with Twitch tags. */
class TwitchChatMessage private constructor(val command: TwitchChatCommand, val parameters: List<String>) {
    var displayName: String? = null
    var user: String? = null
    var userId: String? = null
    var color: String? = null
    val emotes = mutableListOf<TwitchMessageEmote>()
    var badges: List<String> = emptyList()
    var messageId: String? = null
    var id: String? = null
    var firstMessage = false
    var subscriber = false
    var moderator = false
    var bits: String? = null
    var replySender: String? = null
    var replyText: String? = null
    var targetMessageId: String? = null
    var targetUserId: String? = null
    var sourceRoomId: String? = null
    var roomId: String? = null

    val isGigantifiedEmote: Boolean
        get() = messageId == "gigantified-emote-message"

    companion object {
        fun parse(line: String): TwitchChatMessage {
            var parts = line.split(" ")
            val tags = mutableListOf<Pair<String, String>>()
            if (parts.firstOrNull()?.startsWith("@") == true) {
                for (tag in parts[0].substring(1).split(";")) {
                    tagNameAndValue(tag)?.let { tags += it }
                }
                parts = parts.drop(1)
            }
            var sourceUser: String? = null
            if (parts.firstOrNull()?.startsWith(":") == true) {
                val source = parts[0].substring(1)
                if ('!' in source) {
                    sourceUser = source.substringBefore('!')
                }
                parts = parts.drop(1)
            }
            val commandText = parts.firstOrNull()?.takeIf { it.isNotEmpty() }
                ?: throw InvalidTwitchMessageException("Missing command: $line")
            val command = TwitchChatCommand.entries.firstOrNull { it.rawValue == commandText }
                ?: throw InvalidTwitchMessageException("Invalid command: $commandText")
            val message = TwitchChatMessage(command, parseParameters(parts.drop(1)))
            // Upstream applies tags first, then the prefix's user overrides "login".
            for ((name, value) in tags) {
                message.applyTag(name, value)
            }
            sourceUser?.let { message.user = it }
            return message
        }

        private fun parseParameters(parts: List<String>): List<String> {
            val parameters = mutableListOf<String>()
            for ((index, part) in parts.withIndex()) {
                if (!part.startsWith(":")) {
                    parameters += part
                    continue
                }
                return parameters + parts.subList(index, parts.size).joinToString(" ").removePrefix(":")
            }
            return parameters
        }

        private fun tagNameAndValue(specifier: String): Pair<String, String>? {
            val equals = specifier.indexOf('=')
            if (equals < 0) {
                return null
            }
            val value = specifier.substring(equals + 1)
            if (value.isEmpty()) {
                return null
            }
            return specifier.substring(0, equals) to unescapeTagValue(value)
        }

        /** IRCv3 tag unescaping: \: ; \s space, \r, \n, \\ backslash, other \x is x, trailing \ dropped. */
        fun unescapeTagValue(value: String): String {
            if ('\\' !in value) {
                return value
            }
            val result = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                if (char != '\\' || index >= value.length) {
                    if (char != '\\') {
                        result.append(char)
                    }
                    continue
                }
                when (val escaped = value[index++]) {
                    ':' -> result.append(';')
                    's' -> result.append(' ')
                    'r' -> result.append('\r')
                    'n' -> result.append('\n')
                    else -> result.append(escaped)
                }
            }
            return result.toString()
        }

        private fun parseRange(text: String): Pair<Int, Int>? {
            val bounds = text.split("-")
            if (bounds.size != 2) {
                return null
            }
            val first = bounds[0].toIntOrNull() ?: return null
            val last = bounds[1].toIntOrNull() ?: return null
            return if (first <= last) first to last else null
        }

        private fun parseEmotes(value: String): List<TwitchMessageEmote> = value.split("/").flatMap { definition ->
            val parts = definition.split(":")
            if (parts.size != 2) {
                return@flatMap emptyList()
            }
            val (moving, still) = makeTwitchEmoteUrls(parts[0])
            parts[1].split(",").mapNotNull { range -> parseRange(range)?.let { TwitchMessageEmote(moving, still, it.first, it.second) } }
        }

        /** Upstream `parseGif` with `lowResolutionGiphyUrl` (100.gif for giphy.com). */
        private fun parseGif(value: String): TwitchMessageEmote? {
            val parts = value.split("|", limit = 3)
            if (parts.size != 3) {
                return null
            }
            val range = parseRange(parts[0]) ?: return null
            val url = parts[2]
            val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?')
            val lowResolution = if (host.endsWith("giphy.com")) {
                val path = url.substringBefore('?')
                val query = url.substringAfter('?', "")
                path.substringBeforeLast('/') + "/100.gif" + if (query.isNotEmpty()) "?$query" else ""
            } else {
                url
            }
            return TwitchMessageEmote(lowResolution, null, range.first, range.second, isGif = true)
        }
    }

    private fun applyTag(name: String, value: String) {
        when (name) {
            "display-name" -> displayName = value
            "user-id" -> userId = value
            "login" -> user = value
            "color" -> color = value
            "emotes" -> emotes += parseEmotes(value)
            "gifs" -> parseGif(value)?.let { emotes += it }
            "badges" -> badges = value.split(",")
            "msg-id" -> messageId = value
            "id" -> id = value
            "first-msg" -> firstMessage = value == "1"
            "subscriber" -> subscriber = value == "1"
            "mod" -> moderator = value == "1"
            "bits" -> bits = value
            "reply-parent-display-name" -> replySender = value
            "reply-parent-msg-body" -> replyText = value
            "target-msg-id" -> targetMessageId = value
            "target-user-id" -> targetUserId = value
            "source-room-id" -> sourceRoomId = value
            "room-id" -> roomId = value
        }
    }
}

/**
 * Upstream `createTwitchSegments(text:emotes:...)`: Twitch emotes by code point range; the text
 * between them goes through the third-party [emoteSet].
 */
fun createTwitchSegments(text: String, emotes: List<TwitchMessageEmote>, emoteSet: EmoteSet, id: IntArray): List<ChatPostSegment> {
    val segments = mutableListOf<ChatPostSegment>()
    val codePoints = text.codePoints().toArray()
    var startOffset = 0
    fun substring(from: Int, to: Int) = String(codePoints, from, to - from)
    for (emote in emotes.sortedBy { it.first }) {
        if (emote.last >= codePoints.size) {
            break
        }
        if (emote.first < startOffset) {
            continue
        }
        if (emote.first > startOffset) {
            segments += emoteSet.createSegments(substring(startOffset, emote.first), id)
        }
        val name = substring(emote.first, emote.last + 1)
        segments += if (emote.isGif) {
            ChatPostSegment(id[0]++, bigGifUrl = ChatEmote(emote.url, null, name.trim('[', ']')))
        } else {
            ChatPostSegment(id[0]++, url = ChatEmote(emote.url, emote.stillUrl, name))
        }
        segments += ChatPostSegment(id[0]++, text = "")
        startOffset = emote.last + 1
    }
    if (startOffset < codePoints.size) {
        segments += emoteSet.createSegments(substring(startOffset, codePoints.size), id)
    }
    return segments
}
