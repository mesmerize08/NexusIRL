// Port of ChatHighlight(remoteControl:) (Moblin/Various/ChatPost.swift) and the message mapping in
// remoteControlStreamerChatMessages (Moblin/Various/Model/ModelRemoteControl.swift) @97e26d2.
package io.github.mesmerize08.nexusirl.remote

import io.github.mesmerize08.nexusirl.chat.ChatEmote
import io.github.mesmerize08.nexusirl.chat.ChatHighlight
import io.github.mesmerize08.nexusirl.chat.ChatHighlightKind
import io.github.mesmerize08.nexusirl.chat.ChatPlatform
import io.github.mesmerize08.nexusirl.chat.ChatPost
import io.github.mesmerize08.nexusirl.chat.ChatPostSegment
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlChatEmote
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlChatHighlightKind
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlChatMessage
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlChatSegment
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlPlatform
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlRgbColor
import io.github.mesmerize08.nexusirl.settings.RgbColor

/** A forwarded chat message as a post, received now (as upstream's `timestampTime: .now`). */
fun RemoteControlChatMessage.toChatPost(): ChatPost = ChatPost(
    platform = platform.toChatPlatform(),
    messageId = messageId,
    displayName = displayName ?: user.orEmpty(),
    user = user ?: displayName.orEmpty(),
    userId = userId,
    userColor = userColor?.toRgbColor(),
    userBadges = userBadges,
    segments = segments.map { it.toSegment() },
    isAction = isAction,
    isSubscriber = isSubscriber,
    isModerator = isModerator,
    bits = bits,
    highlight = highlight?.let { highlight ->
        val titleSegments = highlight.titleSegments.orEmpty().map { it.toSegment() }
        ChatHighlight(
            kind = highlight.kind.toKind(),
            title = titleSegments.mapNotNull { it.text }.joinToString("").trim(),
            segments = titleSegments,
            barColor = highlight.barColor.toRgbColor(),
        )
    },
    isOwner = isOwner,
)

private fun RemoteControlPlatform.toChatPlatform() = when (this) {
    RemoteControlPlatform.SOOP -> ChatPlatform.SOOP
    RemoteControlPlatform.KICK -> ChatPlatform.KICK
    RemoteControlPlatform.OPEN_STREAMING_PLATFORM -> ChatPlatform.OPEN_STREAMING_PLATFORM
    RemoteControlPlatform.TWITCH -> ChatPlatform.TWITCH
    RemoteControlPlatform.YOUTUBE -> ChatPlatform.YOUTUBE
}

private fun RemoteControlChatHighlightKind.toKind() = when (this) {
    RemoteControlChatHighlightKind.OTHER -> ChatHighlightKind.OTHER
    RemoteControlChatHighlightKind.REDEMPTION -> ChatHighlightKind.REDEMPTION
    RemoteControlChatHighlightKind.FIRST_MESSAGE -> ChatHighlightKind.FIRST_MESSAGE
    RemoteControlChatHighlightKind.NEW_FOLLOWER -> ChatHighlightKind.NEW_FOLLOWER
    RemoteControlChatHighlightKind.REPLY -> ChatHighlightKind.REPLY
    RemoteControlChatHighlightKind.MODERATOR -> ChatHighlightKind.MODERATOR
    RemoteControlChatHighlightKind.REMOTE_CONTROL_ASSISTANT -> ChatHighlightKind.REMOTE_CONTROL_ASSISTANT
    RemoteControlChatHighlightKind.GIGANTIFIED_EMOTE -> ChatHighlightKind.GIGANTIFIED_EMOTE
    RemoteControlChatHighlightKind.MACRO_NOTIFICATION -> ChatHighlightKind.MACRO_NOTIFICATION
}

private fun RemoteControlChatSegment.toSegment() = ChatPostSegment(id, text, url?.toEmote(), bigGifUrl?.toEmote())

private fun RemoteControlChatEmote.toEmote() = ChatEmote(moving, still, name)

private fun RemoteControlRgbColor.toRgbColor() = RgbColor(red, green, blue, opacity)
