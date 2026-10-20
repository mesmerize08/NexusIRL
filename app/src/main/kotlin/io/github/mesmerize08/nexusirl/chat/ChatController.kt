// Port of Moblin/Various/Model/ModelChat.swift, ModelTwitch.swift, ModelKick.swift and
// ModelYouTube.swift (chat parts) @97e26d2.
package io.github.mesmerize08.nexusirl.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.mesmerize08.nexusirl.chat.kick.KickChat
import io.github.mesmerize08.nexusirl.chat.kick.KickChatIds
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchChat
import io.github.mesmerize08.nexusirl.chat.youtube.YouTubeLiveChat
import io.github.mesmerize08.nexusirl.settings.Database
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.stream.StreamController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
        )
    }

    /** Starts following the settings (idempotent). */
    fun start(context: Context) {
        if (scope != null) {
            return
        }
        val settings = StreamController.settings(context)
        store = settings
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
        mutablePosts.value = emptyList()
        if (!config.enabled) {
            mutableConnected.value = emptyMap()
            return
        }
        val listener = listener(current)
        val connected = mutableMapOf<ChatPlatform, Boolean>()
        if (config.twitchChannelName.isNotEmpty()) {
            connected[ChatPlatform.TWITCH] = false
            twitch = TwitchChat(config.twitchChannelName, listener).also { it.start() }
        }
        if (config.kickChannelName.isNotEmpty()) {
            connected[ChatPlatform.KICK] = false
            kick = KickChat(config.kickChannelName, listener, config.kickIds).also { it.start() }
        }
        if (config.youTubeVideoIds.isNotEmpty()) {
            connected[ChatPlatform.YOUTUBE] = false
            for (videoId in config.youTubeVideoIds) {
                youTube += YouTubeLiveChat(videoId, listener).also { it.start() }
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
