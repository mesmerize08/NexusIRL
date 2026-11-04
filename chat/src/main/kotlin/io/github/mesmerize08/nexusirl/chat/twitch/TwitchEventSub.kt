// Port of Moblin/StreamingPlatforms/Twitch/TwitchEventSub.swift and the alert texts in
// Moblin/Various/Model/ModelTwitch.swift @97e26d2: follows, subscriptions, resubs, gift subs,
// upgrades, watch streaks and shared chat alerts, cheers, raids and channel points as chat alerts;
// hype trains, polls and predictions as banners and chat lines; shoutouts as chat lines; ad breaks
// as toasts.
package io.github.mesmerize08.nexusirl.chat.twitch

import io.github.mesmerize08.nexusirl.chat.ChatEmote
import io.github.mesmerize08.nexusirl.chat.ChatHighlight
import io.github.mesmerize08.nexusirl.chat.ChatHighlightKind
import io.github.mesmerize08.nexusirl.chat.ChatListener
import io.github.mesmerize08.nexusirl.chat.ChatPlatform
import io.github.mesmerize08.nexusirl.chat.ChatPost
import io.github.mesmerize08.nexusirl.chat.ChatPostSegment
import io.github.mesmerize08.nexusirl.chat.TwitchAlert
import io.github.mesmerize08.nexusirl.chat.TwitchAlertType
import io.github.mesmerize08.nexusirl.chat.makeChatPostTextSegments
import io.github.mesmerize08.nexusirl.chat.rgbColorFromHex
import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import io.github.mesmerize08.nexusirl.settings.RgbColor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)

private fun JsonElement?.text(key: String): String? = (field(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.number(key: String): Int? = (field(key) as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonElement?.bool(key: String): Boolean = (field(key) as? JsonPrimitive)?.content == "true"

/** The object at [key]; null when missing or JSON null. */
private fun JsonElement?.obj(key: String): JsonObject? = field(key) as? JsonObject

private fun JsonElement?.array(key: String): List<JsonElement> = (field(key) as? JsonArray).orEmpty()

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
    /**
     * Called with the subscription types Twitch refused (403) because the sign-in predates the
     * permissions they need; signing in again grants them.
     */
    private val onMissingPermissions: (List<String>) -> Unit = {},
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

    /** The last poll, for the message an archived poll keeps. On the executor (or a test's thread). */
    private var poll: TwitchPoll? = null

    private val broadcaster = mapOf("broadcaster_user_id" to userId)

    /**
     * Upstream `makeSubscriptions`, in its order and versions, less `channel.moderate` and raids
     * from this channel: upstream uses them only for its outgoing raid banner, which NexusIRL has
     * not ported, and channel.moderate needs eight moderator scopes.
     */
    val subscriptions: List<TwitchEventSubSubscription> = run {
        val broadcasterAndModerator = broadcaster + ("moderator_user_id" to userId)
        listOf(
            TwitchEventSubSubscription("channel.follow", 2, broadcasterAndModerator),
            TwitchEventSubSubscription(CHAT_NOTIFICATION, 1, broadcaster + ("user_id" to userId)),
            TwitchEventSubSubscription("channel.channel_points_custom_reward_redemption.add", 1, broadcaster),
            TwitchEventSubSubscription("channel.raid", 1, mapOf("to_broadcaster_user_id" to userId)),
            TwitchEventSubSubscription("channel.cheer", 1, broadcaster),
            TwitchEventSubSubscription("channel.hype_train.begin", 2, broadcaster),
            TwitchEventSubSubscription("channel.hype_train.progress", 2, broadcaster),
            TwitchEventSubSubscription("channel.hype_train.end", 2, broadcaster),
            TwitchEventSubSubscription("channel.ad_break.begin", 1, broadcaster),
            TwitchEventSubSubscription("channel.poll.begin", 1, broadcaster),
            TwitchEventSubSubscription("channel.poll.progress", 1, broadcaster),
            TwitchEventSubSubscription("channel.poll.end", 1, broadcaster),
            TwitchEventSubSubscription("channel.prediction.begin", 1, broadcaster),
            TwitchEventSubSubscription("channel.prediction.progress", 1, broadcaster),
            TwitchEventSubSubscription("channel.prediction.lock", 1, broadcaster),
            TwitchEventSubSubscription("channel.prediction.end", 1, broadcaster),
            TwitchEventSubSubscription("channel.shoutout.create", 1, broadcasterAndModerator),
        )
    }

    /**
     * NexusIRL 0.2's subscription alerts (scope channel:read:subscriptions), for sign-ins from
     * before user:read:chat was asked for, which Twitch refuses channel.chat.notification.
     */
    private val legacySubscriptions = listOf(
        TwitchEventSubSubscription("channel.subscribe", 1, broadcaster),
        TwitchEventSubSubscription("channel.subscription.message", 1, broadcaster),
        TwitchEventSubSubscription("channel.subscription.gift", 1, broadcaster),
    )

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

    /**
     * Upstream `remoteControlStreamerTwitchEventSubNotification`: an EventSub message a remote
     * control assistant received for this channel. Only notifications are taken; session
     * messages belong to the assistant's own connection.
     */
    fun handleForwardedMessage(text: String) {
        try {
            executor.execute {
                val message = try {
                    json.parseToJsonElement(text)
                } catch (e: Exception) {
                    return@execute
                }
                val metadata = message.field("metadata")
                if (metadata.text("message_type") == "notification") {
                    handleNotification(metadata.text("subscription_type"), message.field("payload").field("event"))
                }
            }
        } catch (e: RejectedExecutionException) {
            // Stopped (the chat settings changed); the notification is dropped, as upstream.
        }
    }

    private fun restartKeepaliveIfRunning() {
        if (running) {
            restartKeepalive()
        }
    }

    private fun subscribe(sessionId: String) {
        val twitchApi = api() ?: return reconnectLater()

        // Upstream treats a refusal as signed out; here the other subscriptions keep working.
        fun create(subscription: TwitchEventSubSubscription): Boolean = try {
            twitchApi.createEventSubSubscription(subscription.type, subscription.version, subscription.condition, sessionId)
            true
        } catch (e: TwitchForbiddenException) {
            false
        } catch (e: TwitchMissingScopeException) {
            false
        }
        try {
            val refused = mutableListOf<String>()
            for (subscription in subscriptions) {
                if (!create(subscription)) {
                    refused += subscription.type
                    if (subscription.type == CHAT_NOTIFICATION) {
                        legacySubscriptions.forEach(::create)
                    }
                }
            }
            if (refused.isNotEmpty()) {
                onMissingPermissions(refused)
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

    /** Upstream `appendTwitchChatAlertMessage`: a highlighted chat post. */
    private fun post(
        user: String,
        segments: List<ChatPostSegment>,
        title: String,
        color: RgbColor,
        kind: ChatHighlightKind = ChatHighlightKind.OTHER,
        userColor: RgbColor? = null,
    ) = ChatPost(
        platform = ChatPlatform.TWITCH,
        messageId = null,
        displayName = user,
        user = user,
        userId = null,
        userColor = userColor,
        userBadges = emptyList(),
        segments = segments,
        isAction = false,
        isSubscriber = false,
        isModerator = false,
        bits = null,
        highlight = ChatHighlight(kind, title, barColor = color),
    )

    private fun alert(
        type: TwitchAlertType,
        user: String,
        text: String,
        title: String,
        color: RgbColor,
        kind: ChatHighlightKind = ChatHighlightKind.OTHER,
        bits: Int? = null,
    ) {
        listener.onTwitchAlert(TwitchAlert(type, user, text, bits, post(user, makeChatPostTextSegments(text), title, color, kind)))
    }

    /** Upstream `joinTwitchAlertText`: the alert text, then the viewer's message if any. */
    private fun withMessage(text: String, event: JsonElement?): String {
        val message = event.field("message").text("text") ?: event.text("message")
        return if (message.isNullOrBlank()) text else "$text $message"
    }

    /** A chat line by the channel itself (hype trains, polls, predictions). */
    private fun channelLine(event: JsonElement?, text: String, title: String, color: RgbColor) {
        val user = event.text("broadcaster_user_name").orEmpty()
        listener.onPost(post(user, makeChatPostTextSegments(text), title, color))
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
            CHAT_NOTIFICATION -> handleChatNotification(event)
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
                val text = resubText(event.number("cumulative_months") ?: 1, event.number("streak_months"), event.text("tier"))
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
            "channel.hype_train.begin", "channel.hype_train.progress" -> {
                listener.onTwitchBanner(
                    TwitchHypeTrain(
                        level = event.number("level") ?: 1,
                        progress = event.number("progress") ?: 0,
                        goal = event.number("goal") ?: 1,
                        expiresAtMillis = parseTwitchTimestamp(event.text("expires_at")),
                    ),
                )
                if (type == "channel.hype_train.begin") {
                    channelLine(event, "started a hype train!", "Hype train started", PURPLE)
                }
            }
            "channel.hype_train.end" -> {
                val level = event.number("level") ?: 1
                listener.onTwitchBanner(TwitchHypeTrain(level, progress = 1, goal = 1, expiresAtMillis = null, ended = true))
                channelLine(event, "ended the hype train at level $level!", "Hype train ended", PURPLE)
            }
            "channel.ad_break.begin" -> {
                val kind = if (event.bool("is_automatic")) "automatic" else "manual"
                listener.onToast("${formatShortDuration(event.number("duration_seconds") ?: 0)} $kind commercial starting")
            }
            "channel.poll.begin", "channel.poll.progress" -> {
                updatePoll(event, TwitchPollState.ONGOING, "")
                if (type == "channel.poll.begin") {
                    channelLine(event, "started a poll: ${event.text("title").orEmpty()}", "Poll started", INDIGO)
                }
            }
            "channel.poll.end" -> {
                val title = event.text("title").orEmpty()
                if (event.text("status") == "archived") {
                    // Upstream: the banner keeps its last message and chat says nothing.
                    updatePoll(event, TwitchPollState.COMPLETED, poll?.message(System.currentTimeMillis()).orEmpty())
                    return
                }
                val winner = updatePoll(event, TwitchPollState.COMPLETED, "Poll ended").choices.maxByOrNull { it.votes }
                val text = if (winner != null) "ended the poll: $title Winner: ${winner.title}" else "ended the poll: $title"
                channelLine(event, text, "Poll ended", INDIGO)
            }
            "channel.prediction.begin", "channel.prediction.progress" -> {
                updatePrediction(event, TwitchPredictionState.ONGOING)
                if (type == "channel.prediction.begin") {
                    channelLine(event, "started a prediction: ${event.text("title").orEmpty()}", "Prediction started", MINT)
                }
            }
            "channel.prediction.lock" -> updatePrediction(event, TwitchPredictionState.LOCKED)
            "channel.prediction.end" -> {
                val title = event.text("title").orEmpty()
                val winner = updatePrediction(event, TwitchPredictionState.COMPLETED).outcomes.firstOrNull { it.winner }
                val text = if (winner !=
                    null
                ) {
                    "ended the prediction: $title Outcome: ${winner.title}"
                } else {
                    "cancelled the prediction: $title"
                }
                channelLine(event, text, "Prediction ended", MINT)
            }
            "channel.shoutout.create" -> {
                val user = event.text("moderator_user_name") ?: return
                val text = "gave a shoutout to ${event.text("to_broadcaster_user_name").orEmpty()}!"
                listener.onPost(post(user, makeChatPostTextSegments(text), "Shoutout sent", ORANGE))
            }
        }
    }

    /** Upstream `updateTwitchPoll`. */
    private fun updatePoll(event: JsonElement?, state: TwitchPollState, endedMessage: String): TwitchPoll {
        val choices = event.array("choices").map {
            TwitchPollChoice(
                it.text("id").orEmpty(),
                it.text("title").orEmpty(),
                it.number("votes") ?: 0,
            )
        }
        val updated = TwitchPoll(event.text("title").orEmpty(), choices, state, parseTwitchTimestamp(event.text("ends_at")), endedMessage)
        poll = updated
        listener.onTwitchBanner(updated)
        return updated
    }

    /** Upstream `updateTwitchPrediction`. */
    private fun updatePrediction(event: JsonElement?, state: TwitchPredictionState): TwitchPrediction {
        val winningId = event.text("winning_outcome_id")
        val outcomes = event.array("outcomes").map {
            val id = it.text("id").orEmpty()
            TwitchPredictionOutcome(
                id = id,
                title = it.text("title").orEmpty(),
                color = it.text("color").orEmpty(),
                users = it.number("users") ?: 0,
                channelPoints = it.number("channel_points") ?: 0,
                winner = id == winningId,
            )
        }
        val updated = TwitchPrediction(event.text("title").orEmpty(), outcomes, state, parseTwitchTimestamp(event.text("locks_at")))
        listener.onTwitchBanner(updated)
        return updated
    }

    private fun resubText(months: Int, streak: Int?, tier: String?): String = if (streak != null) {
        "just resubscribed tier ${tier(tier)} for $months months, $streak in a row!"
    } else {
        "just resubscribed tier ${tier(tier)} for $months months!"
    }

    /**
     * Upstream `handleNotificationChannelChatNotification`: subscriptions, gifts, upgrades and
     * watch streaks in this channel, and the same plus raids from shared chat partners
     * (`shared_chat_*`, when `source_broadcaster_user_id` is set).
     */
    private fun handleChatNotification(event: JsonElement?) {
        val notice = event.text("notice_type") ?: return
        val sharedChat = event.text("source_broadcaster_user_id") != null && event.text("source_broadcaster_user_name") != null
        val anonymous = event.bool("chatter_is_anonymous")
        val user = event.text("chatter_user_name").orEmpty()
        val userColor = if (anonymous) null else rgbColorFromHex(event.text("color").orEmpty())

        // Upstream `makeTwitchAlertSegments(text:fragments:)`: the alert text, then the viewer's message.
        fun chatAlert(type: TwitchAlertType, user: String, text: String, title: String, color: RgbColor, streak: Int? = null) {
            val textSegments = makeChatPostTextSegments(text)
            val segments = textSegments + fragmentSegments(event, startId = textSegments.size)
            val post = post(user, segments, title, color, userColor = userColor)
            listener.onTwitchAlert(TwitchAlert(type, user, withMessage(text, event), null, post, streak, sharedChat))
        }

        // Upstream `event.sub ?? event.shared_chat_sub` and so on.
        fun details(name: String) = event.obj(name) ?: event.obj("shared_chat_$name")
        when (notice.removePrefix("shared_chat_")) {
            "sub" -> {
                val sub = details("sub") ?: return
                val text = if (sub.bool(
                        "is_prime",
                    )
                ) {
                    "just subscribed with Prime!"
                } else {
                    "just subscribed tier ${tier(sub.text("sub_tier"))}!"
                }
                chatAlert(TwitchAlertType.SUBSCRIPTION, user, text, "New subscriber", CYAN)
            }
            "resub" -> {
                val resub = details("resub") ?: return
                val text = resubText(resub.number("cumulative_months") ?: 1, resub.number("streak_months"), resub.text("sub_tier"))
                chatAlert(TwitchAlertType.RESUBSCRIPTION, user, text, "New resubscribe", CYAN)
            }
            "sub_gift" -> {
                val gift = details("sub_gift") ?: return
                // Part of a community gift, which has its own notice with the total.
                if (gift.text("community_gift_id") != null) {
                    return
                }
                val text = "just gifted 1 tier ${tier(gift.text("sub_tier"))} subscriptions!"
                chatAlert(TwitchAlertType.GIFT_SUBSCRIPTION, if (anonymous) "Anonymous" else user, text, "Gift subscriptions", CYAN)
            }
            "community_sub_gift" -> {
                val gift = details("community_sub_gift") ?: return
                val text = "just gifted ${gift.number("total") ?: 1} tier ${tier(gift.text("sub_tier"))} subscriptions!"
                chatAlert(TwitchAlertType.GIFT_SUBSCRIPTION, if (anonymous) "Anonymous" else user, text, "Gift subscriptions", CYAN)
            }
            "prime_paid_upgrade" -> {
                val upgrade = details("prime_paid_upgrade") ?: return
                val text = "just converted their Prime subscription to tier ${tier(upgrade.text("sub_tier"))}!"
                chatAlert(TwitchAlertType.SUBSCRIPTION, user, text, "New subscriber", CYAN)
            }
            "gift_paid_upgrade" -> chatAlert(
                TwitchAlertType.SUBSCRIPTION,
                user,
                "just continued their gift subscription!",
                "New subscriber",
                CYAN,
            )
            // Raids into this channel come from channel.raid; only a partner's raid is shown here.
            "raid" -> if (notice == "shared_chat_raid") {
                val raid = event.obj("shared_chat_raid") ?: return
                val raider = raid.text("user_name") ?: return
                chatAlert(TwitchAlertType.RAID, raider, "raided with a party of ${raid.number("viewer_count") ?: 0}!", "Raid", PINK)
            }
            "watch_streak" -> if (notice == "watch_streak") {
                val streak = event.obj("watch_streak")?.number("streak_count") ?: return
                chatAlert(TwitchAlertType.WATCH_STREAK, user, "just watched $streak streams in a row!", "Watch streak", ORANGE, streak)
            }
        }
    }

    /** Upstream `createTwitchSegments(fragments:)`: Twitch emotes as images, other text word by word. */
    private fun fragmentSegments(event: JsonElement?, startId: Int): List<ChatPostSegment> {
        var id = startId
        return event.field("message").array("fragments").flatMap { fragment ->
            val text = fragment.text("text").orEmpty()
            val emoteId = fragment.field("emote").text("id")
            if (fragment.text("type") == "emote" && emoteId != null) {
                val (moving, still) = makeTwitchEmoteUrls(emoteId)
                listOf(ChatPostSegment(id++, url = ChatEmote(moving, still, text)), ChatPostSegment(id++, text = ""))
            } else {
                makeChatPostTextSegments(text, id).also { id += it.size }
            }
        }
    }

    private companion object {
        const val INITIAL_DELAY_SECONDS = 2L
        const val MAXIMUM_DELAY_SECONDS = 120L
        const val CHAT_NOTIFICATION = "channel.chat.notification"

        // iOS system colours upstream uses for these alerts.
        val PINK = RgbColor(255, 45, 85)
        val CYAN = RgbColor(50, 173, 230)
        val GREEN = ChatHighlight.Colors.GREEN
        val BLUE = ChatHighlight.Colors.BLUE
        val ORANGE = ChatHighlight.Colors.ORANGE
        val PURPLE = ChatHighlight.Colors.PURPLE
        val INDIGO = RgbColor(88, 86, 214)
        val MINT = RgbColor(0, 199, 190)
    }
}
