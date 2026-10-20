// Port of Moblin/StreamingPlatforms/Kick/KickPusher.swift and the channel lookup in KickApi.swift
// @97e26d2 (chat only; alerts such as subscriptions come later).
package io.github.mesmerize08.nexusirl.chat.kick

import io.github.mesmerize08.nexusirl.chat.ChatEmote
import io.github.mesmerize08.nexusirl.chat.ChatHighlight
import io.github.mesmerize08.nexusirl.chat.ChatHighlightKind
import io.github.mesmerize08.nexusirl.chat.ChatListener
import io.github.mesmerize08.nexusirl.chat.ChatPlatform
import io.github.mesmerize08.nexusirl.chat.ChatPost
import io.github.mesmerize08.nexusirl.chat.ChatPostSegment
import io.github.mesmerize08.nexusirl.chat.EmoteSet
import io.github.mesmerize08.nexusirl.chat.net.ReconnectingWebSocket
import io.github.mesmerize08.nexusirl.chat.rgbColorFromHex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class KickBadge(val type: String, val count: Int? = null)

@Serializable
private data class KickIdentity(val color: String = "", val badges: List<KickBadge> = emptyList())

@Serializable
private data class KickSender(val id: Long? = null, val username: String, val identity: KickIdentity = KickIdentity())

@Serializable
private data class KickOriginalSender(val username: String)

@Serializable
private data class KickOriginalMessage(val content: String)

@Serializable
private data class KickMetadata(val original_sender: KickOriginalSender? = null, val original_message: KickOriginalMessage? = null)

@Serializable
private data class KickChatMessageEvent(
    val id: String? = null,
    val type: String? = null,
    val content: String,
    val sender: KickSender,
    val metadata: KickMetadata? = null,
)

@Serializable
private data class KickMessageId(val id: String)

@Serializable
private data class KickMessageDeletedEvent(val message: KickMessageId)

@Serializable
private data class KickUser(val id: Long)

@Serializable
private data class KickUserBannedEvent(val user: KickUser)

@Serializable
private data class KickChatroom(val id: Long, val channel_id: Long)

@Serializable
private data class KickChannel(val slug: String, val chatroom: KickChatroom)

/** The ids Kick's Pusher channels are named after (upstream `kickChannelId`, `kickChatroomChannelId`). */
data class KickChatIds(val chatroomId: String, val channelId: String)

private val emotePattern = Regex("""\[emote:(\d+):([^\]]+)]""")

/** Upstream `createKickSegments`: `[emote:<id>:<name>]` tokens become Kick emote images. */
fun createKickSegments(message: String, emoteSet: EmoteSet, id: IntArray): List<ChatPostSegment> {
    val segments = mutableListOf<ChatPostSegment>()
    var start = 0
    for (match in emotePattern.findAll(message)) {
        segments += emoteSet.createSegments(message.substring(start, match.range.first), id)
        val url = "https://files.kick.com/emotes/${match.groupValues[1]}/fullsize"
        segments += ChatPostSegment(id[0]++, url = ChatEmote(url, url, match.groupValues[2]))
        start = match.range.last + 1
    }
    if (start < message.length) {
        segments += emoteSet.createSegments(message.substring(start), id)
    }
    return segments
}

/**
 * Reads a Kick channel's chat through Kick's public Pusher socket (no account needed). The
 * chatroom ids come from settings or, when missing, from Kick's public channel API.
 */
class KickChat(
    private val channelName: String,
    private val listener: ChatListener,
    private var ids: KickChatIds? = null,
    private val emoteSet: EmoteSet = EmoteSet(),
    private val client: OkHttpClient = ReconnectingWebSocket.SHARED_CLIENT,
    private val pusherUrl: String = PUSHER_URL,
    private val apiBaseUrl: String = "https://kick.com/api/v1/channels/",
) {
    private var socket: ReconnectingWebSocket? = null

    @Volatile
    private var running = false

    val isConnected: Boolean
        get() = socket?.isConnected == true

    /** Starts on a background thread (the id lookup is a network call). */
    fun start() {
        running = true
        Thread({
            val resolved = ids ?: lookUpIds()
            if (resolved == null || !running) {
                return@Thread
            }
            ids = resolved
            val socket = ReconnectingWebSocket(
                pusherUrl,
                object : ReconnectingWebSocket.Listener {
                    override fun onConnected(socket: ReconnectingWebSocket) {
                        // Upstream subscribes to all of these; chat arrives on the chatroom ones.
                        for (channel in listOf(
                            "chatrooms.${resolved.chatroomId}.v2",
                            "chatroom_${resolved.chatroomId}",
                            "chatrooms.${resolved.chatroomId}",
                            "predictions-channel-${resolved.chatroomId}",
                            "channel_${resolved.channelId}",
                        )) {
                            socket.send("""{"event":"pusher:subscribe","data":{"auth":"","channel":"$channel"}}""")
                        }
                        listener.onConnectionChanged(ChatPlatform.KICK, true)
                    }

                    override fun onMessage(socket: ReconnectingWebSocket, text: String) = handleMessage(text)

                    override fun onDisconnected(socket: ReconnectingWebSocket) {
                        listener.onConnectionChanged(ChatPlatform.KICK, false)
                    }
                },
                client,
            )
            synchronized(this) {
                if (running) {
                    this.socket = socket
                    socket.start()
                }
            }
        }, "kick-chat").start()
    }

    fun stop() {
        running = false
        synchronized(this) {
            socket?.stop()
            socket = null
        }
    }

    /** Upstream `getKickChannelInfo`: the slug as entered, then with "_" replaced by "-". */
    private fun lookUpIds(): KickChatIds? {
        for (slug in listOf(channelName, channelName.replace("_", "-")).distinct()) {
            try {
                client.newCall(
                    Request.Builder().url(apiBaseUrl + slug).header("Accept", "application/json").build(),
                ).execute().use { response ->
                    if (response.isSuccessful) {
                        val channel = json.decodeFromString(KickChannel.serializer(), response.body!!.string())
                        return KickChatIds(channel.chatroom.id.toString(), channel.chatroom.channel_id.toString())
                    }
                }
            } catch (e: Exception) {
                // Try the next slug.
            }
        }
        return null
    }

    /** Handles one Pusher message (upstream `handleMessage`); public for tests. */
    fun handleMessage(text: String) {
        try {
            val envelope = json.parseToJsonElement(text) as? JsonObject ?: return
            val type = envelope["event"]?.jsonPrimitive?.content ?: return
            val data = envelope["data"]?.jsonPrimitive?.content ?: return
            when (type) {
                "App\\Events\\ChatMessageEvent" -> handleChatMessage(json.decodeFromString(KickChatMessageEvent.serializer(), data))
                "App\\Events\\MessageDeletedEvent" ->
                    listener.onDeleteMessage(json.decodeFromString(KickMessageDeletedEvent.serializer(), data).message.id)
                "App\\Events\\UserBannedEvent" ->
                    listener.onDeleteUser(json.decodeFromString(KickUserBannedEvent.serializer(), data).user.id.toString())
            }
        } catch (e: Exception) {
            // Malformed or unexpected message; upstream logs and ignores it.
        }
    }

    private fun handleChatMessage(event: KickChatMessageEvent) {
        val badges = event.sender.identity.badges.map { it.type }
        val original = event.metadata
        val highlight = if (event.type == "reply" && original?.original_sender != null && original.original_message != null) {
            ChatHighlight(
                ChatHighlightKind.REPLY,
                "Replying to ${original.original_sender.username}:",
                createKickSegments(original.original_message.content, emoteSet, intArrayOf(0)),
            )
        } else {
            null
        }
        listener.onPost(
            ChatPost(
                platform = ChatPlatform.KICK,
                messageId = event.id,
                displayName = event.sender.username,
                user = event.sender.username,
                userId = event.sender.id?.toString(),
                userColor = rgbColorFromHex(event.sender.identity.color),
                userBadges = emptyList(),
                segments = createKickSegments(event.content, emoteSet, intArrayOf(0)),
                isAction = false,
                isSubscriber = "subscriber" in badges,
                isModerator = "moderator" in badges,
                bits = null,
                highlight = highlight,
            ),
        )
    }

    companion object {
        /** Kick's public Pusher app key and cluster (from Kick's web client, as upstream uses it). */
        const val PUSHER_URL = "wss://ws-us2.pusher.com/app/34bf7a0ff419a2a775b9?protocol=7&client=js&version=7.6.0&flash=false"
    }
}
