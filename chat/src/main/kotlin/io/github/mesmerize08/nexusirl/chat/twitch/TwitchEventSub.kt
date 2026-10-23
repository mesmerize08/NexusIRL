// Port of Moblin/StreamingPlatforms/Twitch/TwitchEventSub.swift and the alert texts in
// Moblin/Various/Model/ModelTwitch.swift @97e26d2 (v1 subset: follows, subscriptions, resubs, gift
// subs, cheers, raids and channel points, shown as chat alerts).
package io.github.mesmerize08.nexusirl.chat.twitch

import io.github.mesmerize08.nexusirl.chat.ChatHighlight
import io.github.mesmerize08.nexusirl.chat.ChatHighlightKind
import io.github.mesmerize08.nexusirl.chat.ChatListener
import io.github.mesmerize08.nexusirl.chat.ChatPlatform
import io.github.mesmerize08.nexusirl.chat.ChatPost
import io.github.mesmerize08.nexusirl.chat.TwitchAlert
import io.github.mesmerize08.nexusirl.chat.TwitchAlertType
import io.github.mesmerize08.nexusirl.chat.makeChatPostTextSegments
import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import io.github.mesmerize08.nexusirl.settings.RgbColor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)

private fun JsonElement?.text(key: String): String? = (field(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.number(key: String): Int? = (field(key) as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonElement?.bool(key: String): Boolean = (field(key) as? JsonPrimitive)?.content == "true"

/** Upstream `twitchTierAsNumber`: "1000" is tier 1, and so on. */
private fun tier(value: String?): Int = (value?.toIntOrNull() ?: 1000) / 1000

/** An EventSub subscription (type, version, condition). */
data class TwitchEventSubSubscription(val type: String, val version: Int, val condition: Map<String, String>)

/**
 * Twitch alerts over EventSub WebSocket: after the welcome, subscribes to the channel's events via
 * Helix within the 10 seconds Twitch allows, then turns notifications into highlighted chat posts.
 * Reconnects after 2 s, doubling up to 120 s on failures (upstream). Follows `session_reconnect`
 * and treats a missed keepalive as a lost connection.
 */
class TwitchEventSub(
    private val userId: String,
    private val listener: ChatListener,
    private val api: () -> TwitchApi?,
    private val client: OkHttpClient = ReconnectingWebSocket.SHARED_CLIENT,
    private val url: String = "wss://eventsub.wss.twitch.tv/ws",
    /** Called when Twitch rejects the sign-in (the app can refresh or ask to sign in again). */
    private val onUnauthorized: () -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "twitch-eventsub").apply { isDaemon = true } }
    private var socket: WebSocket? = null
    private var generation = 0
    private var running = false
    private var reconnectDelaySeconds = INITIAL_DELAY_SECONDS
    private var keepaliveTimeout: ScheduledFuture<*>? = null
    private var keepaliveSeconds = 10L

    /** Set on `session_reconnect`: the next welcome keeps the existing subscriptions. */
    private var reconnecting = false

    @Volatile
    var isConnected = false
        private set

    /** Upstream `makeSubscriptions` for the alerts NexusIRL shows (and the scopes it asks for). */
    val subscriptions: List<TwitchEventSubSubscription> = run {
        val broadcaster = mapOf("broadcaster_user_id" to userId)
        listOf(
            TwitchEventSubSubscription("channel.follow", 2, broadcaster + ("moderator_user_id" to userId)),
            TwitchEventSubSubscription("channel.subscribe", 1, broadcaster),
            TwitchEventSubSubscription("channel.subscription.message", 1, broadcaster),
            TwitchEventSubSubscription("channel.subscription.gift", 1, broadcaster),
            TwitchEventSubSubscription("channel.cheer", 1, broadcaster),
            TwitchEventSubSubscription("channel.raid", 1, mapOf("to_broadcaster_user_id" to userId)),
            TwitchEventSubSubscription("channel.channel_points_custom_reward_redemption.add", 1, broadcaster),
        )
    }

    fun start() = executor.execute {
        running = true
        // Upstream waits 2 seconds before the first connect.
        executor.schedule({ connect(url) }, INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
    }

    fun stop() {
        executor.execute {
            running = false
            disconnect()
        }
        executor.shutdown()
    }

    private fun connect(target: String) {
        if (!running) {
            return
        }
        val current = ++generation
        socket = client.newWebSocket(
            Request.Builder().url(target).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = onExecutor(current) { handleMessage(text) }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onExecutor(current) { reconnectLater() }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onExecutor(current) { reconnectLater() }
            },
        )
    }

    private fun onExecutor(connection: Int, block: () -> Unit) {
        try {
            executor.execute {
                if (connection == generation && running) {
                    block()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Stopped.
        }
    }

    private fun disconnect() {
        generation++
        keepaliveTimeout?.cancel(false)
        socket?.cancel()
        socket = null
        setConnected(false)
    }

    private fun setConnected(connected: Boolean) {
        if (isConnected != connected) {
            isConnected = connected
            listener.onConnectionChanged(ChatPlatform.TWITCH, connected)
        }
    }

    /** Upstream `reconnectLater`. */
    private fun reconnectLater() {
        disconnect()
        reconnecting = false
        if (!running) {
            return
        }
        executor.schedule({ connect(url) }, reconnectDelaySeconds, TimeUnit.SECONDS)
        reconnectDelaySeconds = (reconnectDelaySeconds * 2).coerceAtMost(MAXIMUM_DELAY_SECONDS)
    }

    private fun restartKeepalive() {
        keepaliveTimeout?.cancel(false)
        val current = generation
        // Twitch sends something at least every keepalive interval; allow some slack.
        keepaliveTimeout = executor.schedule({ if (current == generation) reconnectLater() }, keepaliveSeconds + 5, TimeUnit.SECONDS)
    }

    /** Handles one EventSub message; public for tests (call on any thread when not started). */
    fun handleMessage(text: String) {
        val message = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return
        }
        val metadata = message.field("metadata")
        val payload = message.field("payload")
        restartKeepaliveIfRunning()
        when (metadata.text("message_type")) {
            "session_welcome" -> {
                val session = payload.field("session")
                keepaliveSeconds = session.number("keepalive_timeout_seconds")?.toLong() ?: keepaliveSeconds
                val sessionId = session.text("id") ?: return
                if (reconnecting) {
                    reconnecting = false
                    setConnected(true)
                } else {
                    subscribe(sessionId)
                }
            }
            "session_reconnect" -> {
                // Connect to the new URL; Twitch moves the subscriptions there.
                val reconnectUrl = payload.field("session").text("reconnect_url") ?: return
                if (running) {
                    disconnect()
                    reconnecting = true
                    connect(reconnectUrl)
                }
            }
            "notification" -> handleNotification(metadata.text("subscription_type"), payload.field("event"))
        }
    }

    private fun restartKeepaliveIfRunning() {
        if (running) {
            restartKeepalive()
        }
    }

    private fun subscribe(sessionId: String) {
        val twitchApi = api() ?: return reconnectLater()
        try {
            for (subscription in subscriptions) {
                twitchApi.createEventSubSubscription(subscription.type, subscription.version, subscription.condition, sessionId)
            }
            reconnectDelaySeconds = INITIAL_DELAY_SECONDS
            setConnected(true)
        } catch (e: TwitchUnauthorizedException) {
            onUnauthorized()
            reconnectLater()
        } catch (e: Exception) {
            reconnectLater()
        }
    }

    private fun alert(
        type: TwitchAlertType,
        user: String,
        text: String,
        title: String,
        color: RgbColor,
        kind: ChatHighlightKind = ChatHighlightKind.OTHER,
        bits: Int? = null,
    ) {
        val post = ChatPost(
            platform = ChatPlatform.TWITCH,
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
            highlight = ChatHighlight(kind, title, barColor = color),
        )
        listener.onTwitchAlert(TwitchAlert(type, user, text, bits, post))
    }

    /** Upstream `joinTwitchAlertText`: the alert text, then the viewer's message if any. */
    private fun withMessage(text: String, event: JsonElement?): String {
        val message = event.field("message").text("text") ?: event.text("message")
        return if (message.isNullOrBlank()) text else "$text $message"
    }

    private fun handleNotification(type: String?, event: JsonElement?) {
        when (type) {
            "channel.follow" -> alert(
                TwitchAlertType.FOLLOW,
                event.text("user_name") ?: return,
                "just followed!",
                "New follower",
                PINK,
                ChatHighlightKind.NEW_FOLLOWER,
            )
            "channel.subscribe" -> if (!event.bool("is_gift")) {
                alert(
                    TwitchAlertType.SUBSCRIPTION,
                    event.text("user_name") ?: return,
                    "just subscribed tier ${tier(event.text("tier"))}!",
                    "New subscriber",
                    CYAN,
                )
            }
            "channel.subscription.message" -> {
                val months = event.number("cumulative_months") ?: 1
                val streak = event.number("streak_months")
                val text = if (streak != null) {
                    "just resubscribed tier ${tier(event.text("tier"))} for $months months, $streak in a row!"
                } else {
                    "just resubscribed tier ${tier(event.text("tier"))} for $months months!"
                }
                alert(TwitchAlertType.RESUBSCRIPTION, event.text("user_name") ?: return, withMessage(text, event), "New resubscribe", CYAN)
            }
            "channel.subscription.gift" -> {
                val user = if (event.bool("is_anonymous")) null else event.text("user_name")
                val text = "just gifted ${event.number("total") ?: 1} tier ${tier(event.text("tier"))} subscriptions!"
                alert(TwitchAlertType.GIFT_SUBSCRIPTION, user ?: "Anonymous", text, "Gift subscriptions", CYAN)
            }
            "channel.cheer" -> {
                val user = if (event.bool("is_anonymous")) null else event.text("user_name")
                val bits = event.number("bits") ?: 0
                alert(TwitchAlertType.CHEER, user ?: "Anonymous", withMessage("cheered $bits bits!", event), "Cheer", GREEN, bits = bits)
            }
            "channel.raid" -> alert(
                TwitchAlertType.RAID,
                event.text("from_broadcaster_user_name") ?: return,
                "raided with a party of ${event.number("viewers") ?: 0}!",
                "Raid",
                PINK,
            )
            "channel.channel_points_custom_reward_redemption.add" -> {
                val reward = event.field("reward").text("title") ?: return
                val input = event.text("user_input")
                alert(
                    TwitchAlertType.REWARD,
                    event.text("user_name") ?: return,
                    if (input.isNullOrBlank()) "redeemed $reward!" else "redeemed $reward! $input",
                    "Reward redemption",
                    BLUE,
                    ChatHighlightKind.REDEMPTION,
                )
            }
        }
    }

    private companion object {
        const val INITIAL_DELAY_SECONDS = 2L
        const val MAXIMUM_DELAY_SECONDS = 120L

        // iOS system colours upstream uses for these alerts.
        val PINK = RgbColor(255, 45, 85)
        val CYAN = RgbColor(50, 173, 230)
        val GREEN = ChatHighlight.Colors.GREEN
        val BLUE = ChatHighlight.Colors.BLUE
    }
}
