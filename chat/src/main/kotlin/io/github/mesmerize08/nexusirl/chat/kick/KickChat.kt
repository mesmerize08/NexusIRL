// Port of Moblin/StreamingPlatforms/Kick/KickPusher.swift (chat and alerts) and the channel lookup in
// KickApi.swift @97e26d2; the alert texts are upstream's from Moblin/Various/Model/ModelKick.swift.
package io.github.mesmerize08.nexusirl.chat.kick

import io.github.mesmerize08.nexusirl.chat.ChatEmote
import io.github.mesmerize08.nexusirl.chat.ChatHighlight
import io.github.mesmerize08.nexusirl.chat.ChatHighlightKind
import io.github.mesmerize08.nexusirl.chat.ChatListener
import io.github.mesmerize08.nexusirl.chat.ChatPlatform
import io.github.mesmerize08.nexusirl.chat.ChatPost
import io.github.mesmerize08.nexusirl.chat.ChatPostSegment
import io.github.mesmerize08.nexusirl.chat.EmoteSet
import io.github.mesmerize08.nexusirl.chat.KickAlert
import io.github.mesmerize08.nexusirl.chat.KickAlertType
import io.github.mesmerize08.nexusirl.chat.makeChatPostTextSegments
import io.github.mesmerize08.nexusirl.chat.rgbColorFromHex
import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import io.github.mesmerize08.nexusirl.settings.RgbColor
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
private data class KickUser(val id: Long, val username: String = "")

@Serializable
private data class KickUserBannedEvent(val user: KickUser, val permanent: Boolean = false)

@Serializable
private data class KickSubscriptionEvent(val username: String, val months: Int)

@Serializable
private data class KickGiftedSubscriptionsEvent(val gifted_usernames: List<String>, val gifter_username: String, val gifter_total: Int)

@Serializable
private data class KickRewardRedeemedEvent(val reward_title: String, val username: String, val user_input: String = "")

@Serializable
private data class KickStreamHostEvent(val host_username: String, val number_viewers: Int)

@Serializable
private data class KickKicksSender(val username: String)

@Serializable
private data class KickKicksGift(val name: String, val amount: Int)

@Serializable
private data class KickKicksGiftedEvent(val message: String = "", val sender: KickKicksSender, val gift: KickKicksGift)

private val CYAN = RgbColor(50, 173, 230)
private val RED = RgbColor(255, 59, 48)

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
    /** The ids once known (from settings or the lookup); third-party emotes need them. */
    private val onIds: (KickChatIds) -> Unit = {},
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
            onIds(resolved)
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
                "App\\Events\\UserBannedEvent" -> handleUserBanned(json.decodeFromString(KickUserBannedEvent.serializer(), data))
                "App\\Events\\SubscriptionEvent" -> json.decodeFromString(KickSubscriptionEvent.serializer(), data).let {
                    val text = "just subscribed! They've been subscribed for ${it.months} months!"
                    alert(KickAlertType.SUBSCRIPTION, it.username, text, "New subscriber", CYAN, toast = "🎉 ${it.username} $text")
                }
                "GiftedSubscriptionsEvent" -> json.decodeFromString(KickGiftedSubscriptionsEvent.serializer(), data).let {
                    val text = "just gifted ${it.gifted_usernames.size} subscription(s)! They've gifted ${it.gifter_total} in total!"
                    alert(
                        KickAlertType.GIFTED_SUBSCRIPTIONS,
                        it.gifter_username,
                        text,
                        "Gift subscriptions",
                        CYAN,
                        toast = "🎁 ${it.gifter_username} $text",
                    )
                }
                "RewardRedeemedEvent" -> json.decodeFromString(KickRewardRedeemedEvent.serializer(), data).let {
                    val base = "redeemed ${it.reward_title}"
                    val text = if (it.user_input.isEmpty()) base else "$base: ${it.user_input}"
                    alert(
                        KickAlertType.REWARD,
                        it.username,
                        text,
                        "Reward Redeemed",
                        ChatHighlight.Colors.GREEN,
                        toast = "🎁 ${it.username} $text",
                    )
                }
                "App\\Events\\StreamHostEvent" -> json.decodeFromString(KickStreamHostEvent.serializer(), data).let {
                    val text = "is now hosting with ${it.number_viewers} viewers!"
                    alert(
                        KickAlertType.HOST,
                        it.host_username,
                        text,
                        "Host",
                        ChatHighlight.Colors.ORANGE,
                        toast = "📺 ${it.host_username} $text",
                    )
                }
                "KicksGifted" -> json.decodeFromString(KickKicksGiftedEvent.serializer(), data).let {
                    val text = "sent ${it.gift.name} 💎 ${it.gift.amount}"
                    val message = if (it.message.isEmpty()) text else "$text ${it.message}"
                    alert(
                        KickAlertType.KICKS,
                        it.sender.username,
                        message,
                        "Kicks",
                        ChatHighlight.Colors.GREEN,
                        toast = "${it.sender.username} $message",
                        kicks = it.gift.amount,
                    )
                }
            }
        } catch (e: Exception) {
            // Malformed or unexpected message; upstream logs and ignores it.
        }
    }

    /** Upstream: the user's messages go, and chat shows who was banned or timed out (no toast). */
    private fun handleUserBanned(event: KickUserBannedEvent) {
        listener.onDeleteUser(event.user.id.toString())
        if (event.user.username.isNotEmpty()) {
            val (text, title) = if (event.permanent) {
                "was banned from chat!" to "User banned"
            } else {
                "was timed out from chat!" to "User timed out"
            }
            alert(KickAlertType.BAN, event.user.username, text, title, RED, toast = null)
        }
    }

    /** Upstream `appendKickChatAlertMessage`, with the toast text for the listener to filter. */
    private fun alert(type: KickAlertType, user: String, text: String, title: String, color: RgbColor, toast: String?, kicks: Int? = null) {
        val post = ChatPost(
            platform = ChatPlatform.KICK,
            messageId = null,
            displayName = user,
            user = user,
            userId = null,
            userColor = null,
            userBadges = emptyList(),
            segments = makeChatPostTextSegments(text),
            isAction = false,
            isSubscriber = false,
            isModerator = false,
            bits = null,
            highlight = ChatHighlight(ChatHighlightKind.OTHER, title, barColor = color),
        )
        listener.onKickAlert(KickAlert(type, kicks, toast, post))
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
