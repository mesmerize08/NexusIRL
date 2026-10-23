// Port of Moblin/Various/Model/ModelChat.swift, ModelTwitch.swift, ModelKick.swift and
// ModelYouTube.swift (chat parts) @97e26d2.
package io.github.mesmerize08.nexusirl.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.mesmerize08.nexusirl.BuildConfig
import io.github.mesmerize08.nexusirl.chat.kick.KickChat
import io.github.mesmerize08.nexusirl.chat.kick.KickChatIds
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchApi
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchChat
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchEventSub
import io.github.mesmerize08.nexusirl.chat.youtube.YouTubeLiveChat
import io.github.mesmerize08.nexusirl.settings.Database
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.SettingsStreamChat
import io.github.mesmerize08.nexusirl.settings.SettingsTwitchAlerts
import io.github.mesmerize08.nexusirl.stream.StreamController
import io.github.mesmerize08.nexusirl.twitch.TwitchAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Reads the selected stream's Twitch, Kick and YouTube chats (no accounts needed) into one list,
 * newest first, like upstream's `chat` provider. Connections follow the settings: they restart
 * when a channel or the chat switch changes.
 *
 * Call from the main thread.
 */
object ChatController {
    /** Upstream `maximumNumberOfChatMessages`. */
    const val MAXIMUM_NUMBER_OF_MESSAGES = 50
    private const val YOUTUBE_VIDEO_ID_TIMEOUT_MILLIS = 60_000L
    private const val YOUTUBE_VIDEO_ID_RETRY_MILLIS = 5_000L

    private val handler = Handler(Looper.getMainLooper())
    private val mutablePosts = MutableStateFlow<List<ChatPost>>(emptyList())

    /** Newest first. */
    val posts: StateFlow<List<ChatPost>> = mutablePosts

    private val mutableConnected = MutableStateFlow<Map<ChatPlatform, Boolean>>(emptyMap())

    /** Whether each configured platform is connected. */
    val connected: StateFlow<Map<ChatPlatform, Boolean>> = mutableConnected

    private val postListeners = CopyOnWriteArrayList<(ChatPost) -> Unit>()

    private var store: SettingsStore? = null
    private var scope: CoroutineScope? = null
    private var twitch: TwitchChat? = null
    private var kick: KickChat? = null
    private val youTube = mutableListOf<YouTubeLiveChat>()
    private var eventSub: TwitchEventSub? = null
    private var appContext: Context? = null
    private var twitchAccount: Pair<UUID, String>? = null

    private val mutableTwitchViewers = MutableStateFlow<Int?>(null)

    private val mutableTwitchAlertsConnected = MutableStateFlow<Boolean?>(null)

    private val mutableToasts = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Twitch alerts to show as toasts on the streamer's screen (upstream `makeToast`). */
    val toasts: SharedFlow<String> = mutableToasts

    /** Whether Twitch alerts (EventSub) are subscribed; null when not signed in. */
    val twitchAlertsConnected: StateFlow<Boolean?> = mutableTwitchAlertsConnected

    /** Twitch viewers while live and signed in (upstream polls Helix `streams`); null otherwise. */
    val twitchViewers: StateFlow<Int?> = mutableTwitchViewers
    private val emoteLoaders = mutableListOf<ThirdPartyEmotes>()

    /** Increases on every reload so callbacks from old connections are ignored. */
    private var generation = 0

    /** What the connections depend on; a change reconnects. */
    private data class Config(
        val enabled: Boolean,
        val twitchChannelName: String,
        val kickChannelName: String,
        val kickIds: KickChatIds?,
        val youTubeVideoIds: List<String>,
        val youTubeHandle: String,
        val twitchChannelId: String,
        val emotes: SettingsStreamChat,
        /** The stream whose Twitch sign-in is used for alerts, viewers and sending, if signed in. */
        val twitchSignedInStream: UUID?,
    )

    private fun config(database: Database): Config {
        val stream = database.selectedStream
        val kickIds = stream?.kickChannelId?.let { chatroomId ->
            stream.kickChatroomChannelId?.let { KickChatIds(chatroomId, it) }
        }
        return Config(
            enabled = database.chat.enabled,
            twitchChannelName = stream?.twitchChannelName?.trim().orEmpty(),
            kickChannelName = stream?.kickChannelName?.trim().orEmpty(),
            kickIds = kickIds,
            youTubeVideoIds = stream?.youTubeVideoIdList.orEmpty(),
            youTubeHandle = stream?.youTubeHandle?.trim().orEmpty(),
            twitchChannelId = stream?.twitchChannelId?.trim().orEmpty(),
            emotes = stream?.chat ?: SettingsStreamChat(),
            twitchSignedInStream = stream?.takeIf { it.twitchLoggedIn && it.twitchChannelId.isNotBlank() }?.id,
        )
    }

    /** Starts following the settings (idempotent). */
    fun start(context: Context) {
        if (scope != null) {
            return
        }
        val settings = StreamController.settings(context)
        store = settings
        appContext = context.applicationContext
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        newScope.launch {
            settings.database.map(::config).distinctUntilChanged().collect(::reload)
        }
        newScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                removeOldMessages(settings.current)
            }
        }
    }

    /** Called for every new post on the main thread (chat widgets, browser widgets). */
    fun addPostListener(listener: (ChatPost) -> Unit) {
        postListeners += listener
    }

    fun removePostListener(listener: (ChatPost) -> Unit) {
        postListeners -= listener
    }

    /** Upstream `reloadChats`. */
    private fun reload(config: Config) {
        generation += 1
        val current = generation
        twitch?.stop()
        twitch = null
        kick?.stop()
        kick = null
        youTube.forEach { it.stop() }
        youTube.clear()
        emoteLoaders.forEach { it.stop() }
        eventSub?.stop()
        eventSub = null
        twitchAccount = null
        mutableTwitchViewers.value = null
        mutableTwitchAlertsConnected.value = null
        handler.removeCallbacks(viewersTick)
        emoteLoaders.clear()
        mutablePosts.value = emptyList()
        if (!config.enabled) {
            mutableConnected.value = emptyMap()
            return
        }
        val listener = listener(current)
        val connected = mutableMapOf<ChatPlatform, Boolean>()

        // Each platform has its own third-party emotes, loaded once its channel id is known.
        fun emotes(platform: EmotesPlatform, channelId: String, set: EmoteSet) {
            if (generation == current) {
                emoteLoaders += ThirdPartyEmotes(platform, channelId, config.emotes, set).also { it.start() }
            }
        }
        val signedIn = config.twitchSignedInStream
        if (signedIn != null && appContext != null) {
            // Alerts over EventSub with the stream's sign-in (refreshed when it expires).
            val context = appContext!!
            twitchAccount = signedIn to config.twitchChannelId
            // Its own connection state, apart from the anonymous chat connection.
            val alertsListener = object : ChatListener {
                override fun onPost(post: ChatPost) = listener.onPost(post)

                // Upstream: each kind goes to chat and/or a toast, per the stream's settings
                // (read when the alert arrives, so a change needs no reconnect).
                override fun onTwitchAlert(alert: TwitchAlert) {
                    val stream = store?.current?.selectedStream
                    if ((stream?.twitchChatAlerts ?: SettingsTwitchAlerts()).allows(alert)) {
                        listener.onPost(alert.post)
                    }
                    if ((stream?.twitchToastAlerts ?: SettingsTwitchAlerts()).allows(alert)) {
                        mutableToasts.tryEmit("${alert.user} ${alert.text}")
                    }
                }

                override fun onConnectionChanged(platform: ChatPlatform, connected: Boolean) = onMain(current) {
                    mutableTwitchAlertsConnected.value = connected
                }
            }
            mutableTwitchAlertsConnected.value = false
            eventSub = TwitchEventSub(config.twitchChannelId, alertsListener, api = { twitchApi(context, signedIn) }).also { it.start() }
            handler.post(viewersTick)
        }
        if (config.twitchChannelName.isNotEmpty()) {
            connected[ChatPlatform.TWITCH] = false
            val set = EmoteSet()
            // Without a configured id, use the one Twitch sends on joining (first time only).
            var started = config.twitchChannelId.isNotEmpty()
            if (started) {
                emotes(EmotesPlatform.TWITCH, config.twitchChannelId, set)
            }
            twitch = TwitchChat(config.twitchChannelName, listener, set, onRoomId = { roomId ->
                onMain(current) {
                    if (!started) {
                        started = true
                        emotes(EmotesPlatform.TWITCH, roomId, set)
                    }
                }
            }).also { it.start() }
        }
        if (config.kickChannelName.isNotEmpty()) {
            connected[ChatPlatform.KICK] = false
            val set = EmoteSet()
            kick = KickChat(config.kickChannelName, listener, config.kickIds, set, onIds = { ids ->
                // Upstream passes the chatroom id ("kickChannelId") to the emote APIs.
                onMain(current) { emotes(EmotesPlatform.KICK, ids.chatroomId, set) }
            }).also { it.start() }
        }
        if (config.youTubeVideoIds.isNotEmpty()) {
            connected[ChatPlatform.YOUTUBE] = false
            for (videoId in config.youTubeVideoIds) {
                val set = EmoteSet()
                // As upstream, the video id stands in for the channel id.
                emotes(EmotesPlatform.YOUTUBE, videoId, set)
                youTube += YouTubeLiveChat(videoId, listener, set).also { it.start() }
            }
        } else if (config.youTubeHandle.isNotEmpty()) {
            fetchYouTubeVideoId()
        }
        mutableConnected.value = connected
    }

    /**
     * Upstream `tryToFetchYouTubeVideoId` (handle variant): looks up the channel's live video for
     * up to a minute and saves it to the stream, which reconnects the chat. Upstream starts this
     * when going live, since the video only exists then.
     */
    private fun twitchApi(context: Context, streamId: UUID): TwitchApi? =
        TwitchAccount.accessToken(context, streamId)?.let { TwitchApi(BuildConfig.TWITCH_CLIENT_ID, it) }

    /** Upstream `updateTwitchStream`: viewers once a minute, read-only. */
    private val viewersTick = object : Runnable {
        override fun run() {
            val (streamId, userId) = twitchAccount ?: return
            val context = appContext ?: return
            val current = generation
            Thread({
                val viewers = try {
                    twitchApi(context, streamId)?.getStream(userId)?.viewerCount
                } catch (e: Exception) {
                    null
                }
                handler.post {
                    if (generation == current) {
                        mutableTwitchViewers.value = viewers
                    }
                }
            }, "twitch-viewers").apply { isDaemon = true }.start()
            handler.postDelayed(this, 60_000)
        }
    }

    /** Whether messages can be sent to Twitch chat (signed in on the selected stream). */
    val canSendToTwitch: Boolean
        get() = twitchAccount != null

    /**
     * Upstream `sendChatMessage`: posts [text] publicly in the signed-in channel's chat as the
     * signed-in user. [onDone] gets an error message, or null when sent (main thread).
     */
    fun sendTwitchMessage(text: String, onDone: (String?) -> Unit) {
        val (streamId, userId) = twitchAccount ?: return onDone("Sign in to Twitch first")
        val context = appContext ?: return onDone("Not ready")
        val message = text.trim().take(500).ifEmpty { return onDone(null) }
        Thread({
            val error = try {
                val api = twitchApi(context, streamId) ?: throw IllegalStateException("Sign in to Twitch again")
                api.sendChatMessage(broadcasterId = userId, senderId = userId, message = message)
                null
            } catch (e: Exception) {
                e.message ?: "Sending failed"
            }
            handler.post { onDone(error) }
        }, "twitch-send").apply { isDaemon = true }.start()
    }

    fun fetchYouTubeVideoId() {
        val settings = store ?: return
        val stream = settings.current.selectedStream ?: return
        val handle = stream.youTubeHandle.trim()
        if (!settings.current.chat.enabled || handle.isEmpty()) {
            return
        }
        val current = generation
        Thread({
            val deadline = System.currentTimeMillis() + YOUTUBE_VIDEO_ID_TIMEOUT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                val videoId = try {
                    YouTubeLiveChat.fetchVideoId(handle)
                } catch (e: Exception) {
                    null
                }
                if (videoId != null) {
                    handler.post {
                        val latest = settings.current.selectedStream
                        if (latest?.id == stream.id && latest.youTubeVideoIds != videoId) {
                            settings.update { it.updateStream(latest.copy(youTubeVideoIds = videoId)) }
                        }
                    }
                    return@Thread
                }
                if (generation != current) {
                    return@Thread
                }
                try {
                    Thread.sleep(YOUTUBE_VIDEO_ID_RETRY_MILLIS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }, "youtube-video-id").apply { isDaemon = true }.start()
    }

    private fun listener(current: Int) = object : ChatListener {
        override fun onPost(post: ChatPost) = onMain(current) {
            mutablePosts.value = (listOf(post) + mutablePosts.value).take(MAXIMUM_NUMBER_OF_MESSAGES)
            postListeners.forEach { it(post) }
        }

        override fun onDeleteMessage(messageId: String) = onMain(current) {
            mutablePosts.value = mutablePosts.value.filterNot { it.messageId == messageId }
        }

        override fun onDeleteUser(userId: String) = onMain(current) {
            mutablePosts.value = mutablePosts.value.filterNot { it.userId == userId }
        }

        override fun onConnectionChanged(platform: ChatPlatform, connected: Boolean) = onMain(current) {
            mutableConnected.value = mutableConnected.value + (platform to connected)
        }
    }

    private fun onMain(current: Int, block: () -> Unit) {
        handler.post {
            if (generation == current) {
                block()
            }
        }
    }

    /** Upstream `removeOldChatMessages`. */
    private fun removeOldMessages(database: Database) {
        if (!database.chat.maximumAgeEnabled) {
            return
        }
        val oldest = System.currentTimeMillis() - database.chat.maximumAge * 1000L
        val posts = mutablePosts.value
        if (posts.any { it.timestampMillis < oldest }) {
            mutablePosts.value = posts.filter { it.timestampMillis >= oldest }
        }
    }
}
