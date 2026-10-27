// Port of RemoteControlChatMessage and RemoteControlChatHighlight (Moblin/RemoteControl/RemoteControl.swift),
// ChatPostSegment, ChatPostEmote and ChatHighlightKind (Moblin/Various/ChatPost.swift), Platform
// (Moblin/StreamingPlatforms/Platform.swift) and RgbColor (Common/Various/CommonUtils.swift) @97e26d2.
package io.github.mesmerize08.nexusirl.remotecontrol

import io.github.mesmerize08.nexusirl.core.codable.SwiftUnitEnumSerializer
import kotlinx.serialization.Serializable

/** Upstream `Platform`. */
@Serializable(with = RemoteControlPlatform.Serializer::class)
enum class RemoteControlPlatform(val swiftName: String) {
    SOOP("soop"),
    KICK("kick"),
    OPEN_STREAMING_PLATFORM("openStreamingPlatform"),
    TWITCH("twitch"),
    YOUTUBE("youTube"),
    ;

    object Serializer : SwiftUnitEnumSerializer<RemoteControlPlatform>("Platform", entries, { it.swiftName }, TWITCH)
}

/** Upstream `ChatHighlightKind`. */
@Serializable(with = RemoteControlChatHighlightKind.Serializer::class)
enum class RemoteControlChatHighlightKind(val swiftName: String) {
    OTHER("other"),
    REDEMPTION("redemption"),
    FIRST_MESSAGE("firstMessage"),
    NEW_FOLLOWER("newFollower"),
    REPLY("reply"),
    MODERATOR("moderator"),
    REMOTE_CONTROL_ASSISTANT("remoteControlAssistant"),
    GIGANTIFIED_EMOTE("gigantifiedEmote"),
    MACRO_NOTIFICATION("macroNotification"),
    ;

    object Serializer : SwiftUnitEnumSerializer<RemoteControlChatHighlightKind>(
        "ChatHighlightKind",
        entries,
        { it.swiftName },
        OTHER,
    )
}

/** Upstream `RgbColor`. */
@Serializable
data class RemoteControlRgbColor(val red: Int, val green: Int, val blue: Int, val opacity: Double? = null)

/** Upstream `ChatPostEmote`: an emote image, animated and/or still. */
@Serializable
data class RemoteControlChatEmote(val moving: String? = null, val still: String? = null, val name: String? = null)

/** Upstream `ChatPostSegment`: a word (with a trailing space), an emote, or a big GIF. */
@Serializable
data class RemoteControlChatSegment(
    val id: Int,
    val text: String? = null,
    val url: RemoteControlChatEmote? = null,
    val bigGifUrl: RemoteControlChatEmote? = null,
)

/** Upstream `RemoteControlChatHighlight`. */
@Serializable
data class RemoteControlChatHighlight(
    val kind: RemoteControlChatHighlightKind,
    val barColor: RemoteControlRgbColor,
    val image: String,
    val titleSegments: List<RemoteControlChatSegment>? = null,
)

/**
 * Upstream `RemoteControlChatMessage`: a chat message an assistant forwards to the streamer (for
 * example when the assistant reads chat for a streamer with a poor connection). [id] increases;
 * the streamer skips ids it has already shown.
 */
@Serializable
data class RemoteControlChatMessage(
    /** Swift's `Int` is 64-bit; assistants use millisecond timestamps. */
    val id: Long,
    val platform: RemoteControlPlatform,
    val messageId: String? = null,
    val displayName: String? = null,
    val user: String? = null,
    val userId: String? = null,
    val userColor: RemoteControlRgbColor? = null,
    val userBadges: List<String> = emptyList(),
    val segments: List<RemoteControlChatSegment> = emptyList(),
    val timestamp: String = "",
    val isAction: Boolean = false,
    val isModerator: Boolean = false,
    val isSubscriber: Boolean = false,
    val isOwner: Boolean = false,
    val bits: String? = null,
    val highlight: RemoteControlChatHighlight? = null,
)
