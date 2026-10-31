// Port of Moblin/Various/Model/ModelChatBot.swift and the chat bot queue in appendChatMessage
// (Moblin/Various/Model/ModelChat.swift) @97e26d2, for the commands NexusIRL can run.
package io.github.mesmerize08.nexusirl.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.mesmerize08.nexusirl.chat.bot.ChatBotCommand
import io.github.mesmerize08.nexusirl.chat.bot.ChatBotDecision
import io.github.mesmerize08.nexusirl.chat.bot.ChatBotFilterArgument
import io.github.mesmerize08.nexusirl.chat.bot.ChatBotMainArgument
import io.github.mesmerize08.nexusirl.chat.bot.ChatBotMessage
import io.github.mesmerize08.nexusirl.chat.bot.ChatBotOnOffArgument
import io.github.mesmerize08.nexusirl.chat.bot.ChatBotPermissionCheck
import io.github.mesmerize08.nexusirl.chat.bot.ChatBotStreamArgument
import io.github.mesmerize08.nexusirl.chat.bot.ChatBotTorchArgument
import io.github.mesmerize08.nexusirl.kick.KickChannel
import io.github.mesmerize08.nexusirl.settings.ChatBotSnapshotSource
import io.github.mesmerize08.nexusirl.settings.QuickButtonType
import io.github.mesmerize08.nexusirl.settings.SettingsChatBotPermissionsCommand
import io.github.mesmerize08.nexusirl.stream.Snapshots
import io.github.mesmerize08.nexusirl.stream.StreamController
import io.github.mesmerize08.nexusirl.twitch.TwitchChannel

/**
 * Runs "!moblin" commands (and aliases) from chat: scene, mute, unmute, zoom, torch, filter,
 * snapshot, stream and help. Commands that need features NexusIRL does not have (text to speech,
 * OBS, Tesla, widgets and so on) are ignored, as upstream ignores commands it cannot run.
 *
 * Main thread.
 */
object ChatBot {
    /** Upstream runs one queued command per 100 ms tick and queues at most 25 (moderators always). */
    private const val TICK_MILLIS = 100L
    private const val MAXIMUM_QUEUED = 25
    const val HELP_URL = "https://mesmerize08.github.io/NexusIRL/chat-bot.html"

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<ChatBotMessage>()
    private val permissionCheck = ChatBotPermissionCheck(SystemClock::elapsedRealtime)
    private var appContext: Context? = null

    fun start(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Upstream `appendChatMessage`: queues [post] when the bot is on and it looks like a command.
     * [live] is false for a history batch, whose commands were meant for earlier.
     */
    fun onPost(post: ChatPost, live: Boolean) {
        val context = appContext ?: return
        if (!live || !StreamController.settings(context).current.chat.botEnabled) {
            return
        }
        if (post.segments.firstOrNull()?.text?.trim()?.startsWith("!") != true) {
            return
        }
        if (queue.size >= MAXIMUM_QUEUED && !post.isModerator) {
            return
        }
        queue += ChatBotMessage(
            platform = post.platform,
            user = post.user,
            isOwner = post.isOwner,
            isModerator = post.isModerator,
            isSubscriber = post.isSubscriber,
            userId = post.userId,
            segments = post.segments,
        )
        if (queue.size == 1) {
            handler.post(tick)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val message = queue.removeFirstOrNull() ?: return
            appContext?.let { handle(it, message) }
            if (queue.isNotEmpty()) {
                handler.postDelayed(this, TICK_MILLIS)
            }
        }
    }

    private fun handle(context: Context, message: ChatBotMessage) {
        val database = StreamController.settings(context).current
        val command = ChatBotCommand.parse(message, database.chat.aliases) ?: return
        val permissions = database.chat.botCommandPermissions
        when (command.popFirstArgument(ChatBotMainArgument.entries.toTypedArray())) {
            ChatBotMainArgument.HELP -> reply(message, "NexusIRL chat bot help: $HELP_URL")
            ChatBotMainArgument.SNAPSHOT -> snapshot(context, command, permissions.snapshot)
            ChatBotMainArgument.MUTE -> ifAllowed(context, "audio", permissions.audio, command) { setMuted(context, true) }
            ChatBotMainArgument.UNMUTE -> ifAllowed(context, "audio", permissions.audio, command) { setMuted(context, false) }
            ChatBotMainArgument.FILTER -> ifAllowed(context, "filter", permissions.filter, command) { filter(context, command) }
            ChatBotMainArgument.ZOOM -> zoom(context, command, permissions.zoom)
            ChatBotMainArgument.SCENE -> {
                val name = command.popFirst() ?: return
                ifAllowed(context, "scene", permissions.scene, command) { selectSceneByName(context, name) }
            }
            ChatBotMainArgument.STREAM -> ifAllowed(context, "stream", permissions.stream, command) { stream(context, command) }
            ChatBotMainArgument.TORCH -> ifAllowed(context, "torch", permissions.torch, command) { torch(context, command) }
            else -> Unit
        }
    }

    /** Upstream `executeIfUserAllowedToUseChatBot`, with its default answers when asked to send them. */
    private fun ifAllowed(
        context: Context,
        key: String,
        permissions: SettingsChatBotPermissionsCommand,
        command: ChatBotCommand,
        onNotAllowed: (() -> Unit)? = null,
        onAllowed: () -> Unit,
    ) {
        val stream = StreamController.settings(context).current.selectedStream
        val decision = permissionCheck.check(
            key,
            permissions,
            command.message,
            twitchChannelName = stream?.twitchChannelName?.trim().orEmpty(),
            kickChannelName = stream?.kickChannelName?.trim().orEmpty(),
        )
        val user = command.user()
        when (decision) {
            ChatBotDecision.Allowed -> onAllowed()
            ChatBotDecision.NotAllowed -> when {
                onNotAllowed != null -> onNotAllowed()
                permissions.sendChatMessages && user != null ->
                    reply(command.message, "$user Sorry, you are not allowed to use this chat bot command 😢")
            }
            is ChatBotDecision.Cooldown -> if (permissions.sendChatMessages && user != null) {
                reply(
                    command.message,
                    "$user Sorry, but this chat bot command is on cooldown for ${decision.secondsLeft} seconds. 😢",
                )
            }
        }
    }

    /** Upstream `sendChatBotReply`: public, in the chat the command came from, when signed in there. */
    private fun reply(message: ChatBotMessage, text: String) {
        when (message.platform) {
            ChatPlatform.TWITCH -> if (ChatController.canSendToTwitch) ChatController.sendTwitchMessage(text) {}
            ChatPlatform.KICK -> if (ChatController.canReplyOnKick) ChatController.sendKickMessage(text) {}
            else -> {}
        }
    }

    private fun toast(text: String) = ChatController.showToast("Chat bot: $text")

    private fun setMuted(context: Context, muted: Boolean) {
        if (StreamController.isQuickButtonOn(context, QuickButtonType.MUTE) == muted) {
            return
        }
        toast(if (muted) "Muting audio" else "Unmuting audio")
        StreamController.setQuickButton(context, QuickButtonType.MUTE, muted)
    }

    /** Upstream `selectSceneByName`: the first enabled scene with that name, any case. */
    private fun selectSceneByName(context: Context, name: String) {
        val scene = StreamController.settings(context).current.scenes.firstOrNull {
            it.enabled && it.name.equals(name, ignoreCase = true)
        } ?: return
        if (scene.id != StreamController.selectedSceneId.value) {
            StreamController.selectScene(context, scene.id)
        }
    }

    private fun zoom(context: Context, command: ChatBotCommand, permissions: SettingsChatBotPermissionsCommand) {
        ifAllowed(context, "zoom", permissions, command) {
            val x = command.rest().toFloatOrNull()?.takeIf { it.isFinite() }
            if (x != null) {
                StreamController.setZoom(x)
            } else if (permissions.sendChatMessages) {
                reply(command.message, "Sorry, zoom x must be a number.")
            }
        }
    }

    private fun torch(context: Context, command: ChatBotCommand) {
        // Android has no torch brightness on most phones, so "level" is ignored.
        when (command.popFirstArgument(ChatBotTorchArgument.entries.toTypedArray())) {
            ChatBotTorchArgument.ON -> StreamController.setQuickButton(context, QuickButtonType.TORCH, true)
            ChatBotTorchArgument.OFF -> StreamController.setQuickButton(context, QuickButtonType.TORCH, false)
            else -> Unit
        }
    }

    private fun filter(context: Context, command: ChatBotCommand) {
        val filter = command.popFirstArgument(ChatBotFilterArgument.entries.toTypedArray()) ?: return
        val state = command.popFirstArgument(ChatBotOnOffArgument.entries.toTypedArray()) ?: return
        val type = when (filter) {
            ChatBotFilterArgument.GRAYSCALE -> QuickButtonType.GRAY_SCALE
            ChatBotFilterArgument.SEPIA -> QuickButtonType.SEPIA
            ChatBotFilterArgument.PIXELLATE -> QuickButtonType.PIXELLATE
            ChatBotFilterArgument.FOUR_THREE -> QuickButtonType.FOUR_THREE
            ChatBotFilterArgument.WHIRLPOOL -> QuickButtonType.WHIRLPOOL
            ChatBotFilterArgument.PINCH -> QuickButtonType.PINCH
            // Movie, triple and twin are not ported.
            else -> return
        }
        StreamController.setQuickButton(context, type, state == ChatBotOnOffArgument.ON)
    }

    /**
     * Upstream's snapshot command: from this phone or another phone's web remote control, saved
     * and uploaded to the stream's chat bot Discord webhook with "Snapshot taken by <user>.".
     * The countdown variant with a message (`!moblin snapshot <message>`) takes it at once.
     */
    private fun snapshot(context: Context, command: ChatBotCommand, permissions: SettingsChatBotPermissionsCommand) {
        val user = command.user()
        ifAllowed(context, "snapshot", permissions, command, onNotAllowed = {
            if (permissions.sendChatMessages && user != null) {
                reply(command.message, " $user, you are not allowed to take snapshots, sorry. 😢")
            }
        }) {
            if (user != null && permissions.sendChatMessages) {
                reply(command.message, "$user, thanks for bringing our photo album to life. 🎉")
            }
            val message = user?.let { "Snapshot taken by $it." }
            val database = StreamController.settings(context).current
            when (database.chat.botCommandSnapshot.effectiveSource(StreamController.isLive)) {
                ChatBotSnapshotSource.THIS_DEVICE -> StreamController.takeSnapshot(context, isChatBot = true, message = message) { saved ->
                    if (saved) {
                        toast(message ?: "Snapshot taken")
                    }
                }
                ChatBotSnapshotSource.WEB_REMOTE_CONTROL -> {
                    val url = StreamController.settings(context).resolve(database.chat.botCommandSnapshot.webRemoteControlUrl)
                    val appContext = context.applicationContext
                    Thread({
                        val jpeg = Snapshots.fetchFromWebRemoteControl(url, age = 0f)
                        if (jpeg == null) {
                            toast("Failed to fetch snapshot from web remote control")
                        } else {
                            StreamController.handleSnapshot(appContext, jpeg, message, isChatBot = true, upload = true)
                        }
                    }, "chat-bot-snapshot").apply { isDaemon = true }.start()
                }
            }
        }
    }

    private fun stream(context: Context, command: ChatBotCommand) {
        when (command.popFirstArgument(ChatBotStreamArgument.entries.toTypedArray())) {
            ChatBotStreamArgument.START -> if (!StreamController.isLive) {
                StreamController.goLive(context)?.let(::toast)
            }
            ChatBotStreamArgument.STOP -> StreamController.stop(context)
            // Upstream changes Twitch only; here Kick too, when the stream is signed in to it.
            ChatBotStreamArgument.TITLE -> {
                val title = command.rest()
                twitchChannel(context) { streamId, channelId ->
                    TwitchChannel.update(context, streamId, channelId, title = title, categoryId = null) { failed("Twitch", it?.message) }
                }
                kickChannel(context) { streamId ->
                    KickChannel.update(context, streamId, title = title, category = null) { failed("Kick", it?.message) }
                }
            }
            ChatBotStreamArgument.CATEGORY -> {
                val name = command.rest()
                // Upstream looks the game up by its exact name.
                twitchChannel(context) { streamId, channelId ->
                    TwitchChannel.searchCategories(context, streamId, name) { categories, _ ->
                        val category = categories?.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return@searchCategories
                        TwitchChannel.update(context, streamId, channelId, title = null, categoryId = category.id) {
                            failed("Twitch", it?.message)
                        }
                    }
                }
                kickChannel(context) { streamId ->
                    KickChannel.searchCategories(context, streamId, name) { categories, _ ->
                        val category = categories?.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return@searchCategories
                        KickChannel.update(context, streamId, title = null, category = category) { failed("Kick", it?.message) }
                    }
                }
            }
            null -> Unit
        }
    }

    /** Upstream ignores these failures; a toast says why the channel did not change. */
    private fun failed(platform: String, message: String?) {
        if (message != null) {
            android.util.Log.w("ChatBot", "$platform channel not changed: $message")
            toast("$platform channel not changed: $message")
        }
    }

    /** Runs [block] with the selected stream, if it is signed in to Kick. */
    private fun kickChannel(context: Context, block: (java.util.UUID) -> Unit) {
        val stream = StreamController.settings(context).current.selectedStream ?: return
        if (stream.kickLoggedIn && stream.kickSlug != null) {
            block(stream.id)
        }
    }

    /** Runs [block] with the selected stream's Twitch sign-in, if any. */
    private fun twitchChannel(context: Context, block: (java.util.UUID, String) -> Unit) {
        val stream = StreamController.settings(context).current.selectedStream ?: return
        if (stream.twitchLoggedIn && stream.twitchChannelId.isNotBlank()) {
            block(stream.id, stream.twitchChannelId)
        }
    }
}
