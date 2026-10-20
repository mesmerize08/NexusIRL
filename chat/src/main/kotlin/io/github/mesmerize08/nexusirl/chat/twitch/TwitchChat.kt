// Port of the chat client in Moblin/StreamingPlatforms/Twitch/TwitchChat.swift @97e26d2.
package io.github.mesmerize08.nexusirl.chat.twitch

import io.github.mesmerize08.nexusirl.chat.ChatHighlight
import io.github.mesmerize08.nexusirl.chat.ChatHighlightKind
import io.github.mesmerize08.nexusirl.chat.ChatListener
import io.github.mesmerize08.nexusirl.chat.ChatPlatform
import io.github.mesmerize08.nexusirl.chat.ChatPost
import io.github.mesmerize08.nexusirl.chat.EmoteSet
import io.github.mesmerize08.nexusirl.chat.rgbColorFromHex
import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import okhttp3.OkHttpClient

/**
 * Reads a Twitch channel's chat anonymously over IRC-on-WebSocket (no account needed), like
 * upstream. Badges and cheermotes need the Helix API and an account, so they come with login.
 */
class TwitchChat(
    private val channelName: String,
    private val listener: ChatListener,
    private val emoteSet: EmoteSet = EmoteSet(),
    url: String = URL,
    client: OkHttpClient = ReconnectingWebSocket.SHARED_CLIENT,
    /**
     * The channel's numeric id from ROOMSTATE after joining. Anonymous chat has no other way to
     * learn it; it is needed for the channel's third-party emotes.
     */
    private val onRoomId: (String) -> Unit = {},
) {
    private val socket = ReconnectingWebSocket(
        url,
        object : ReconnectingWebSocket.Listener {
            override fun onConnected(socket: ReconnectingWebSocket) {
                socket.send("CAP REQ :twitch.tv/membership")
                socket.send("CAP REQ :twitch.tv/tags")
                socket.send("CAP REQ :twitch.tv/commands")
                socket.send("PASS oauth:SCHMOOPIIE")
                socket.send("NICK justinfan67420")
                socket.send("JOIN #${channelName.lowercase()}")
                listener.onConnectionChanged(ChatPlatform.TWITCH, true)
            }

            override fun onMessage(socket: ReconnectingWebSocket, text: String) {
                for (line in text.split('\r', '\n')) {
                    if (line.isNotEmpty()) {
                        handleLine(line)
                    }
                }
            }

            override fun onDisconnected(socket: ReconnectingWebSocket) {
                listener.onConnectionChanged(ChatPlatform.TWITCH, false)
            }
        },
        client,
    )

    val isConnected: Boolean
        get() = socket.isConnected

    fun start() = socket.start()

    fun stop() = socket.stop()

    /** Handles one IRC line (upstream `handleMessage`); public for tests. */
    fun handleLine(line: String) {
        val message = try {
            TwitchChatMessage.parse(line)
        } catch (e: InvalidTwitchMessageException) {
            return
        }
        when (message.command) {
            TwitchChatCommand.PRIVATE_MESSAGE, TwitchChatCommand.USER_NOTICE -> handleChatMessage(message)
            TwitchChatCommand.CLEAR_MSG -> message.targetMessageId?.let { listener.onDeleteMessage(it) }
            TwitchChatCommand.CLEAR_CHAT -> message.targetUserId?.let { listener.onDeleteUser(it) }
            TwitchChatCommand.PING -> socket.send("PONG ${message.parameters.joinToString(" ")}")
            TwitchChatCommand.ROOM_STATE -> message.roomId?.let(onRoomId)
        }
    }

    private fun handleChatMessage(message: TwitchChatMessage) {
        if (message.parameters.size != 2) {
            return
        }
        var text = message.parameters.last()
        val displayName = message.displayName ?: return
        val user = message.user ?: return
        var announcement = false
        when (message.command) {
            TwitchChatCommand.USER_NOTICE -> {
                if (message.messageId != "announcement") {
                    return
                }
                announcement = true
            }
            else -> Unit
        }
        val isPrivate = message.command == TwitchChatCommand.PRIVATE_MESSAGE
        val isAction = text.startsWith("\u0001ACTION ")
        if (isAction) {
            text = text.drop(8).removeSuffix("\u0001")
        }
        val segments = createTwitchSegments(text, message.emotes, emoteSet, intArrayOf(0))
        val highlight = when {
            announcement -> ChatHighlight(ChatHighlightKind.OTHER, "Announcement", barColor = ChatHighlight.Colors.GREEN)
            isPrivate && message.firstMessage -> ChatHighlight(
                ChatHighlightKind.FIRST_MESSAGE,
                "First time chatter",
                barColor = ChatHighlight.Colors.YELLOW,
            )
            isPrivate && message.isGigantifiedEmote -> ChatHighlight(
                ChatHighlightKind.GIGANTIFIED_EMOTE,
                "Gigantified emote",
                barColor = ChatHighlight.Colors.PURPLE,
            )
            message.replySender != null && message.replyText != null -> ChatHighlight(
                ChatHighlightKind.REPLY,
                "Replying to ${message.replySender}:",
                createTwitchSegments(message.replyText!!, emptyList(), emoteSet, intArrayOf(0)),
            )
            else -> null
        }
        listener.onPost(
            ChatPost(
                platform = ChatPlatform.TWITCH,
                messageId = message.id,
                displayName = displayName,
                user = user,
                userId = message.userId,
                userColor = rgbColorFromHex(message.color ?: ""),
                userBadges = emptyList(),
                segments = segments,
                isAction = isAction,
                isSubscriber = isPrivate && message.subscriber,
                isModerator = isPrivate && message.moderator,
                bits = message.bits,
                highlight = highlight,
            ),
        )
    }

    companion object {
        const val URL = "wss://irc-ws.chat.twitch.tv"
    }
}
