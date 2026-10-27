// Port of Moblin/Various/ChatPost.swift @97e26d2 (the data parts).
package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.settings.RgbColor

/** Where a chat message came from (upstream `Platform`). */
enum class ChatPlatform(val displayName: String) {
    TWITCH("Twitch"),
    KICK("Kick"),
    YOUTUBE("YouTube"),

    /** Only in chat forwarded by a remote control assistant (NexusIRL has no SOOP or OSP chat). */
    SOOP("SOOP"),
    OPEN_STREAMING_PLATFORM("Open Streaming Platform"),
}

/** Upstream `ChatPostEmote`: an emote image, animated and/or still. */
data class ChatEmote(val moving: String?, val still: String?, val name: String? = null) {
    fun url(animated: Boolean): String? = if (animated) moving ?: still else still ?: moving
}

/** Upstream `ChatPostSegment`: a word (with a trailing space), an emote, or a big GIF. */
data class ChatPostSegment(val id: Int, val text: String? = null, val url: ChatEmote? = null, val bigGifUrl: ChatEmote? = null)

/** Upstream `ChatHighlightKind`. */
enum class ChatHighlightKind {
    OTHER,
    REDEMPTION,
    FIRST_MESSAGE,
    NEW_FOLLOWER,
    REPLY,
    MODERATOR,
    REMOTE_CONTROL_ASSISTANT,
    GIGANTIFIED_EMOTE,
    MACRO_NOTIFICATION,
}

/** Upstream `ChatHighlight`. A null [barColor] is upstream's `.clear` (replies). */
data class ChatHighlight(
    val kind: ChatHighlightKind,
    val title: String,
    val segments: List<ChatPostSegment> = emptyList(),
    val barColor: RgbColor? = null,
) {
    /** iOS system colours upstream's `ChatHighlight.make*` use for the bar. */
    object Colors {
        val GREEN = RgbColor(52, 199, 89)
        val YELLOW = RgbColor(255, 204, 0)
        val ORANGE = RgbColor(255, 149, 0)
        val BLUE = RgbColor(0, 122, 255)
        val PURPLE = RgbColor(175, 82, 222)
    }
}

/** One chat message (upstream `ChatPost`). */
data class ChatPost(
    val platform: ChatPlatform,
    val messageId: String?,
    val displayName: String,
    val user: String,
    val userId: String?,
    val userColor: RgbColor?,
    val userBadges: List<String>,
    val segments: List<ChatPostSegment>,
    val isAction: Boolean,
    val isSubscriber: Boolean,
    val isModerator: Boolean,
    val bits: String?,
    val highlight: ChatHighlight?,
    val timestampMillis: Long = System.currentTimeMillis(),
) {
    /** Upstream `ChatPost.text(emoteNames:)`: the message as plain text. */
    fun text(emoteNames: Boolean = true): String = segments.mapNotNull { segment ->
        segment.text?.takeIf { it.isNotEmpty() } ?: if (emoteNames) (segment.url ?: segment.bigGifUrl)?.name?.let { "$it " } else null
    }.joinToString("").trim()
}

/** Receives chat events from a platform connection. Called from network threads. */
/** Twitch alert kinds, as upstream's per-kind alert settings (`SettingsTwitchAlerts`). */
enum class TwitchAlertType { FOLLOW, SUBSCRIPTION, RESUBSCRIPTION, GIFT_SUBSCRIPTION, CHEER, RAID, REWARD }

/**
 * A Twitch alert (EventSub): its kind, who, upstream's text ("just followed!"), the bits of a
 * cheer, and the highlighted chat post for it.
 */
data class TwitchAlert(val type: TwitchAlertType, val user: String, val text: String, val bits: Int?, val post: ChatPost)

interface ChatListener {
    fun onPost(post: ChatPost)

    /** A Twitch alert; by default it goes to chat. Listeners with settings filter it. */
    fun onTwitchAlert(alert: TwitchAlert) = onPost(alert.post)

    fun onDeleteMessage(messageId: String) {}

    fun onDeleteUser(userId: String) {}

    fun onConnectionChanged(platform: ChatPlatform, connected: Boolean) {}
}

/** Upstream `makeChatPostTextSegments`: one segment per word, each with a trailing space. */
fun makeChatPostTextSegments(text: String, startId: Int = 0): List<ChatPostSegment> {
    var id = startId
    return text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { ChatPostSegment(id++, text = "$it ") }
}

/** Upstream `RgbColor.fromHex`: "#RRGGBB" to a colour, null when malformed. */
fun rgbColorFromHex(value: String): RgbColor? {
    val hex = value.removePrefix("#")
    if (hex.length != 6) {
        return null
    }
    val number = hex.toIntOrNull(16) ?: return null
    return RgbColor((number shr 16) and 0xFF, (number shr 8) and 0xFF, number and 0xFF)
}
