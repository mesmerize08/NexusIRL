// Port of Moblin/StreamingPlatforms/YouTube/YouTubeLiveChat.swift @97e26d2.
package io.github.mesmerize08.nexusirl.chat.youtube

import io.github.mesmerize08.nexusirl.chat.ChatEmote
import io.github.mesmerize08.nexusirl.chat.ChatHighlight
import io.github.mesmerize08.nexusirl.chat.ChatHighlightKind
import io.github.mesmerize08.nexusirl.chat.ChatListener
import io.github.mesmerize08.nexusirl.chat.ChatPlatform
import io.github.mesmerize08.nexusirl.chat.ChatPost
import io.github.mesmerize08.nexusirl.chat.ChatPostSegment
import io.github.mesmerize08.nexusirl.chat.EmoteSet
import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import io.github.mesmerize08.nexusirl.settings.RgbColor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class YouTubeChatException(message: String) : Exception(message)

private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:124.0) Gecko/20100101 Firefox/124.0"
private const val MINIMUM_POLL_DELAY_MILLIS = 200
private const val MAXIMUM_POLL_DELAY_MILLIS = 3000

private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

private fun JsonElement?.array(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

private fun JsonElement?.string(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Reads a YouTube live stream's chat by polling the web client's `get_live_chat` endpoint (no
 * account needed), like upstream: an initial continuation from the popout page, then polling
 * every 200 ms to 3 s depending on chat activity; on errors it starts over after 5 s.
 */
class YouTubeLiveChat(
    private val videoId: String,
    private val listener: ChatListener,
    private val emoteSet: EmoteSet = EmoteSet(),
    private val client: OkHttpClient = ReconnectingWebSocket.SHARED_CLIENT,
    private val baseUrl: String = "https://www.youtube.com",
) {
    private var continuation = ""
    private var delayMillis = 2000

    @Volatile
    private var thread: Thread? = null

    @Volatile
    var isConnected = false
        private set

    fun start() {
        val worker = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    getInitialContinuation()
                    setConnected(true)
                    while (!Thread.currentThread().isInterrupted) {
                        handleGetLiveChat(post("$baseUrl/youtubei/v1/live_chat/get_live_chat?prettyPrint=false", makeGetLiveChatBody()))
                        Thread.sleep(delayMillis.toLong())
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    // Upstream retries the whole sequence after 5 seconds.
                }
                setConnected(false)
                try {
                    Thread.sleep(5000)
                } catch (e: InterruptedException) {
                    break
                }
            }
            setConnected(false)
        }, "youtube-chat")
        thread = worker
        worker.isDaemon = true
        worker.start()
    }

    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun setConnected(connected: Boolean) {
        if (isConnected != connected) {
            isConnected = connected
            listener.onConnectionChanged(ChatPlatform.YOUTUBE, connected)
        }
    }

    private fun getInitialContinuation() {
        val body = get("$baseUrl/live_chat?is_popout=1&v=$videoId")
        continuation = Regex(""""continuation":"([^"]+)"""").find(body)?.groupValues?.get(1)
            ?: throw YouTubeChatException("No continuation")
    }

    private fun makeGetLiveChatBody(): String =
        """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20210128.02.00"}},"continuation":"$continuation"}"""

    private fun get(url: String): String =
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
            if (!response.isSuccessful) throw YouTubeChatException("HTTP ${response.code}")
            response.body!!.string()
        }

    private fun post(url: String, body: String): String = client.newCall(
        Request.Builder().url(url).header("User-Agent", USER_AGENT).post(body.toRequestBody("application/json".toMediaType())).build(),
    ).execute().use { response ->
        if (!response.isSuccessful) throw YouTubeChatException("HTTP ${response.code}")
        response.body!!.string()
    }

    /** Handles one `get_live_chat` response (upstream `handleGetLiveChat`); public for tests. */
    fun handleGetLiveChat(data: String) {
        val root = try {
            Json.parseToJsonElement(data)
        } catch (e: Exception) {
            throw YouTubeChatException("Invalid JSON")
        }
        val liveChat = root.obj("continuationContents").obj("liveChatContinuation")
            ?: throw YouTubeChatException("No live chat continuation")
        var numberOfMessages = 0
        for (action in liveChat.array("actions").orEmpty()) {
            val item = action.obj("addChatItemAction").obj("item") ?: continue
            item.obj("liveChatTextMessageRenderer")?.let { numberOfMessages += handleChatDescription(it, null, null) }
            item.obj("liveChatPaidMessageRenderer")?.let {
                numberOfMessages += handleChatDescription(it, purchaseText(it, "Super Chat"), superChat)
            }
            item.obj("liveChatPaidStickerRenderer")?.let {
                numberOfMessages += handleChatDescription(it, purchaseText(it, "Super Sticker"), superSticker)
            }
            item.obj("liveChatMembershipItemRenderer")?.let { numberOfMessages += handleChatDescription(it, null, member) }
            item.obj("liveChatSponsorshipsGiftPurchaseAnnouncementRenderer").obj("header").obj("liveChatSponsorshipsHeaderRenderer")?.let {
                numberOfMessages += handleGiftPurchase(it)
            }
            item.obj("liveChatSponsorshipsGiftRedemptionAnnouncementRenderer")?.let {
                numberOfMessages += handleChatDescription(it, null, giftedMemberships)
            }
            item.obj("giftMessageViewModel")?.let { numberOfMessages += handleGiftMessage(it) }
        }
        continuation = liveChat.array("continuations")?.firstOrNull().obj("invalidationContinuationData").string("continuation")
            ?: throw YouTubeChatException("Continuation missing")
        updateDelay(numberOfMessages)
    }

    /** Upstream `updateDelayMs`: poll faster when chat is busy. */
    private fun updateDelay(numberOfMessages: Int) {
        delayMillis = if (numberOfMessages > 0) delayMillis * 5 / numberOfMessages else MAXIMUM_POLL_DELAY_MILLIS
        delayMillis = delayMillis.coerceIn(MINIMUM_POLL_DELAY_MILLIS, MAXIMUM_POLL_DELAY_MILLIS)
    }

    // Upstream `ChatHighlight.makePaidMessage()` and friends.
    private val superChat = highlight("Super Chat", ChatHighlight.Colors.ORANGE)
    private val superSticker = highlight("Super Sticker", ChatHighlight.Colors.GREEN)
    private val member = highlight("Member", ChatHighlight.Colors.BLUE)
    private val giftedMemberships = highlight("Gifted Memberships", ChatHighlight.Colors.BLUE)
    private val jewels = highlight("Jewels", ChatHighlight.Colors.BLUE)

    private fun highlight(title: String, color: RgbColor) = ChatHighlight(ChatHighlightKind.OTHER, title, barColor = color)

    private fun purchaseText(description: JsonObject, what: String): String =
        description.obj("purchaseAmountText").string("simpleText")?.let { "sent a $it $what!" } ?: "sent a $what!"

    private fun runsToSegments(runs: JsonArray?, id: IntArray, emojis: Boolean): List<ChatPostSegment> {
        val segments = mutableListOf<ChatPostSegment>()
        for (run in runs.orEmpty()) {
            run.string("text")?.let { segments += emoteSet.createSegments(it, id) }
            if (!emojis) {
                continue
            }
            val emoji = run.obj("emoji") ?: continue
            val url = emoji.obj("image").array("thumbnails")?.firstOrNull().string("url") ?: continue
            val name = (emoji.array("shortcuts")?.firstOrNull() as? JsonPrimitive)?.content?.trim(':')
            segments += ChatPostSegment(id[0]++, url = ChatEmote(url, url, name))
        }
        return segments
    }

    private fun roles(badges: JsonArray?): Pair<Boolean, Boolean> {
        val types = badges.orEmpty().mapNotNull { it.obj("liveChatAuthorBadgeRenderer").obj("icon").string("iconType") }
        return ("OWNER" in types) to ("MODERATOR" in types)
    }

    private fun handleChatDescription(description: JsonObject, text: String?, highlight: ChatHighlight?): Int {
        val id = intArrayOf(0)
        val segments = mutableListOf<ChatPostSegment>()
        text?.let { segments += emoteSet.createSegments(it, id) }
        segments += runsToSegments(description.obj("headerSubtext").array("runs"), id, emojis = true)
        segments += runsToSegments(description.obj("message").array("runs"), id, emojis = true)
        if (segments.isEmpty() && highlight == null) {
            return 0
        }
        val (isOwner, isModerator) = roles(description.array("authorBadges"))
        emit(
            description.obj("authorName").string("simpleText") ?: return 0,
            description.string("authorExternalChannelId"),
            segments,
            isOwner,
            isModerator,
            highlight,
        )
        return 1
    }

    private fun handleGiftPurchase(header: JsonObject): Int {
        val segments = runsToSegments(header.obj("primaryText").array("runs"), intArrayOf(0), emojis = false)
        if (segments.isEmpty()) {
            return 0
        }
        val (isOwner, isModerator) = roles(header.array("authorBadges"))
        emit(
            header.obj("authorName").string("simpleText") ?: return 0,
            header.string("authorExternalChannelId"),
            segments,
            isOwner,
            isModerator,
            giftedMemberships,
        )
        return 1
    }

    private fun handleGiftMessage(model: JsonObject): Int {
        val user = model.obj("authorName").string("content") ?: return 0
        val text = model.obj("text").string("content") ?: return 0
        emit(user, null, emoteSet.createSegments(text, intArrayOf(0)), false, false, jewels)
        return 1
    }

    private fun emit(
        user: String,
        userId: String?,
        segments: List<ChatPostSegment>,
        isOwner: Boolean,
        isModerator: Boolean,
        highlight: ChatHighlight?,
    ) {
        listener.onPost(
            ChatPost(
                platform = ChatPlatform.YOUTUBE,
                messageId = null,
                displayName = user,
                user = user,
                userId = userId,
                userColor = null,
                userBadges = emptyList(),
                segments = segments,
                isAction = false,
                isSubscriber = false,
                isModerator = isModerator || isOwner,
                bits = null,
                highlight = highlight,
                isOwner = isOwner,
            ),
        )
    }

    companion object {
        /** Upstream `fetchYouTubeVideoId`: the live video of a channel handle such as "@someone". */
        fun fetchVideoId(
            handle: String,
            client: OkHttpClient = ReconnectingWebSocket.SHARED_CLIENT,
            baseUrl: String = "https://www.youtube.com",
        ): String {
            val html = client.newCall(
                Request.Builder().url("$baseUrl/$handle/live").header("User-Agent", USER_AGENT).header("Cookie", "CONSENT=YES+1").build(),
            ).execute().use { response ->
                if (!response.isSuccessful) throw YouTubeChatException("HTTP ${response.code}")
                response.body!!.string()
            }
            for (pattern in listOf(
                Regex("""<link rel="shortlinkUrl" href="https://youtu\.be/([^"]+)""""),
                Regex("""<link rel='shortlinkUrl' href='https://youtu\.be/([^']+)'"""),
                Regex("""shortlinkUrl[^>]*href=["']https://youtu\.be/([^"']+)"""),
            )) {
                pattern.find(html)?.let { return it.groupValues[1] }
            }
            throw YouTubeChatException("Video id not found")
        }
    }
}
