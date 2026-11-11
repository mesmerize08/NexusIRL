// Port of Moblin/View/Settings/Streams/Stream/*.swift @97e26d2 (v1 subset: URL, video, SRT).
package io.github.mesmerize08.nexusirl.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.core.secrets.MissingSecretException
import io.github.mesmerize08.nexusirl.media.pipeline.EncoderCapabilities
import io.github.mesmerize08.nexusirl.settings.STREAM_FPS_CHOICES
import io.github.mesmerize08.nexusirl.settings.SettingsKickAlerts
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.SettingsStream
import io.github.mesmerize08.nexusirl.settings.SettingsStreamMultiStreamingDestination
import io.github.mesmerize08.nexusirl.settings.SettingsTwitchAlerts
import io.github.mesmerize08.nexusirl.settings.SrtAdaptiveBitrateAlgorithm
import io.github.mesmerize08.nexusirl.settings.StreamAudioCodec
import io.github.mesmerize08.nexusirl.settings.StreamCodec
import io.github.mesmerize08.nexusirl.settings.StreamKeyPaste
import io.github.mesmerize08.nexusirl.settings.StreamResolution
import io.github.mesmerize08.nexusirl.settings.StreamUrlSecrets
import io.github.mesmerize08.nexusirl.settings.StreamUrls
import io.github.mesmerize08.nexusirl.stream.StreamController
import java.util.UUID

/** Bitrate choices in kbps (upstream's bitrate presets cover the same range). */
private val BITRATES = listOf(500, 1000, 2000, 3000, 4000, 5000, 6000, 8000, 10_000, 12_000, 15_000, 20_000, 30_000, 50_000)
    .map { it * 1000L }
private val SRT_LATENCIES = listOf(200, 500, 1000, 1500, 2000, 2500, 3000, 4000, 5000)
private val ALGORITHMS = listOf(
    SrtAdaptiveBitrateAlgorithm.FastIrl,
    SrtAdaptiveBitrateAlgorithm.SlowIrl,
    SrtAdaptiveBitrateAlgorithm.Belabox,
    SrtAdaptiveBitrateAlgorithm.CustomIrl,
)

private fun algorithmName(algorithm: SrtAdaptiveBitrateAlgorithm): String = when (algorithm) {
    SrtAdaptiveBitrateAlgorithm.FastIrl -> "Fast IRL"
    SrtAdaptiveBitrateAlgorithm.SlowIrl -> "Slow IRL"
    SrtAdaptiveBitrateAlgorithm.Belabox -> "BELABOX"
    SrtAdaptiveBitrateAlgorithm.CustomIrl -> "Custom IRL"
}

/** Creates ([streamId] null) or edits a stream. Changes are saved only with Save. */
@Composable
fun StreamEditorScreen(store: SettingsStore, streamId: UUID?, onDone: () -> Unit) {
    val original = remember(streamId) { store.current.streams.firstOrNull { it.id == streamId } }
    var stream by remember(streamId) { mutableStateOf(original ?: SettingsStream(name = "")) }
    // The editable URL holds the real secret in memory while this screen is open.
    var url by remember(streamId) {
        mutableStateOf(
            original?.let {
                try {
                    store.resolveUrl(it)
                } catch (e: MissingSecretException) {
                    StreamUrlSecrets.publicPart(it.url)
                }
            } ?: "",
        )
    }

    // Discord webhook URLs hold the webhook's token: secret too. All of these are URL settings,
    // whose secrets are always stored inside a scheme://host URL.
    fun resolved(value: String) = store.resolveOrPublicPart(value)
    var snapshotWebhook by remember(streamId) { mutableStateOf(original?.let { resolved(it.discordSnapshotWebhook) }.orEmpty()) }
    var chatBotWebhook by remember(streamId) { mutableStateOf(original?.let { resolved(it.discordChatBotSnapshotWebhook) }.orEmpty()) }
    // The OBS password, and any user info in its URL, are secret too.
    var obsUrl by remember(streamId) { mutableStateOf(original?.let { resolved(it.obsWebSocketUrl) }.orEmpty()) }
    var obsPassword by remember(streamId) {
        mutableStateOf(
            original?.let {
                try {
                    store.obsWebSocketPassword(it)
                } catch (e: MissingSecretException) {
                    ""
                }
            }.orEmpty(),
        )
    }
    // Multi streaming destinations hold stream keys: real URLs only in memory while editing.
    var destinations by remember(streamId) {
        mutableStateOf(original?.multiStreaming?.destinations.orEmpty().map { it.copy(url = resolved(it.url)) })
    }
    val context = LocalContext.current
    // Saved keys need the screen lock to be shown or changed (SecretUnlock); new ones do not.
    var unlocked by remember(streamId) { mutableStateOf(false) }
    val savedSecrets = remember(streamId) { listOf(url, snapshotWebhook, chatBotWebhook) + destinations.map { it.url } }
    fun locked(value: String) = value in savedSecrets && SecretUnlock.needsUnlock(value, unlocked)
    fun unlockThen(value: String, action: () -> Unit) = if (locked(value)) {
        SecretUnlock.authenticate(context) {
            unlocked = true
            action()
        }
    } else {
        action()
    }
    val unlock = { unlockThen(url) {} }
    val hevcSupported = remember { EncoderCapabilities.supportsHevc() }
    val urlValid = StreamUrlSecrets.isValid(url)
    val scheme = url.substringBefore("://", "").lowercase()
    val isSrt = scheme == "srt" || scheme == "srtla"
    // Shown in place of the editor, so the unsaved edits stay.
    var showConnectionPriorities by remember(streamId) { mutableStateOf(false) }
    if (showConnectionPriorities && original != null) {
        ConnectionPrioritiesScreen(store, original.id, onBack = { showConnectionPriorities = false }, url = url)
        return
    }

    Page(
        title = stringResource(if (original == null) R.string.add_stream else R.string.edit_stream),
        onBack = onDone,
        actions = {
            Button(
                enabled = stream.name.isNotBlank() &&
                    urlValid &&
                    (!stream.obsWebSocketEnabled || obsUrl.isEmpty() || obsUrlProblem(obsUrl) == null),
                onClick = {
                    // The Twitch and Kick accounts are saved by sign-in and sign-out directly; keep them.
                    val account = store.current.streams.firstOrNull { it.id == stream.id }
                    val edited = stream.copy(
                        name = stream.name.trim(),
                        url = url,
                        discordSnapshotWebhook = snapshotWebhook,
                        discordChatBotSnapshotWebhook = chatBotWebhook,
                        obsWebSocketUrl = obsUrl,
                        obsWebSocketPassword = obsPassword,
                        multiStreaming = stream.multiStreaming.copy(destinations = destinations),
                        twitchAccessToken = account?.twitchAccessToken.orEmpty(),
                        twitchLoggedIn = account?.twitchLoggedIn ?: false,
                        kickAccessToken = account?.kickAccessToken.orEmpty(),
                        kickLoggedIn = account?.kickLoggedIn ?: false,
                        // Saved by the connection priorities screen (and remote control, Moblink) directly.
                        srt = stream.srt.copy(connectionPriorities = account?.srt?.connectionPriorities ?: stream.srt.connectionPriorities),
                    )
                    store.update { if (original == null) it.addStream(edited) else it.updateStream(edited) }
                    onDone()
                },
            ) { Text(stringResource(R.string.save)) }
        },
    ) {
        OutlinedTextField(
            value = stream.name,
            onValueChange = { stream = stream.copy(name = it) },
            label = { Text(stringResource(R.string.stream_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SecretTextField(
            label = stringResource(R.string.stream_url),
            value = url,
            onValueChange = { url = it.trim() },
            supportingText = when {
                url.isNotEmpty() && !urlValid -> StreamUrls.validate(url) ?: stringResource(R.string.stream_url_invalid)
                else -> stringResource(R.string.stream_url_help)
            },
            locked = locked(url),
            onUnlock = unlock,
        )
        OutlinedButton(onClick = { unlockThen(url) { pasteStreamKey(context, url)?.let { url = it } } }) {
            Text(stringResource(R.string.paste_stream_key))
        }
        if (scheme == "rtmp") {
            // docs/PORT_PLAN.md §9.5: RTMP sends the stream key in cleartext.
            Text(
                stringResource(R.string.rtmp_unencrypted_warning),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (scheme == "rtmp" || scheme == "rtmps") {
            MultiStreamingSection(destinations, ::locked, unlock) { destinations = it }
        }
        HorizontalDivider()
        Text(stringResource(R.string.video), style = MaterialTheme.typography.titleMedium)
        ChoiceField(
            stringResource(R.string.resolution),
            stream.resolution,
            StreamResolution.entries.filter {
                it.width <= 3840
            },
            { it.shortName },
            {
                stream = stream.copy(resolution = it)
            },
        )
        SwitchRow(stringResource(R.string.portrait), stream.portrait) { stream = stream.copy(portrait = it) }
        ChoiceField(stringResource(R.string.fps), stream.fps, STREAM_FPS_CHOICES.sorted(), { "$it" }, { stream = stream.copy(fps = it) })
        ChoiceField(stringResource(R.string.bitrate), stream.bitrate, (BITRATES + stream.bitrate).distinct().sorted(), { formatMbps(it) }, {
            stream = stream.copy(bitrate = it)
        })
        ChoiceField(
            stringResource(R.string.codec),
            stream.codec,
            StreamCodec.entries,
            { it.shortName },
            { stream = stream.copy(codec = it) },
        )
        if (stream.codec == StreamCodec.H265_HEVC && !hevcSupported) {
            Text(stringResource(R.string.hevc_fallback_note), style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        // Upstream StreamAudioSettingsView's codec picker; applies from the next go live, so,
        // as upstream, it cannot be changed for the stream that is live.
        Text(stringResource(R.string.audio), style = MaterialTheme.typography.titleMedium)
        val live = StreamController.status.collectAsState().value.active && stream.enabled
        ChoiceField(
            stringResource(R.string.codec),
            stream.audioCodec,
            StreamAudioCodec.entries,
            { it.displayName },
            { stream = stream.copy(audioCodec = it) },
            enabled = !live,
        )
        Text(stringResource(R.string.audio_codec_help), style = MaterialTheme.typography.bodySmall)
        if (isSrt) {
            HorizontalDivider()
            Text(stringResource(R.string.srt), style = MaterialTheme.typography.titleMedium)
            ChoiceField(
                stringResource(
                    R.string.srt_latency,
                ),
                stream.srt.latency,
                (SRT_LATENCIES + stream.srt.latency).distinct().sorted(),
                {
                    "$it ms"
                },
                {
                    stream = stream.copy(srt = stream.srt.copy(latency = it))
                },
            )
            SwitchRow(stringResource(R.string.adaptive_bitrate), stream.srt.adaptiveBitrateEnabled) {
                stream = stream.copy(srt = stream.srt.copy(adaptiveBitrateEnabled = it))
            }
            if (stream.srt.adaptiveBitrateEnabled) {
                ChoiceField(stringResource(R.string.algorithm), stream.srt.adaptiveBitrate.algorithm, ALGORITHMS, ::algorithmName, {
                    stream = stream.copy(srt = stream.srt.copy(adaptiveBitrate = stream.srt.adaptiveBitrate.copy(algorithm = it)))
                })
            }
            if (original == null) {
                Text(stringResource(R.string.connection_priorities_save_first), style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedButton(onClick = { showConnectionPriorities = true }) { Text(stringResource(R.string.connection_priorities)) }
            }
        }
        ObsSettingsSection(stream, obsUrl, obsPassword, { stream = it }, { obsUrl = it }, { obsPassword = it })
        HorizontalDivider()
        Text(stringResource(R.string.chat), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.chat_channels_help), style = MaterialTheme.typography.bodySmall)
        TwitchSignInSection(store, original?.id) { name, id -> stream = stream.copy(twitchChannelName = name, twitchChannelId = id) }
        // The saved stream: it holds the current sign-in.
        val savedStreams = store.database.collectAsState().value.streams
        original?.let { saved -> savedStreams.firstOrNull { it.id == saved.id } }?.let { TwitchChannelSection(it) }
        ChatTextField(stringResource(R.string.twitch_channel_name), stream.twitchChannelName) {
            stream = stream.copy(twitchChannelName = it)
        }
        // Upstream forgets the looked-up Kick ids when the channel changes.
        ChatTextField(stringResource(R.string.kick_channel_name), stream.kickChannelName) {
            stream = stream.copy(kickChannelName = it, kickChannelId = null, kickChatroomChannelId = null)
        }
        KickSignInSection(store, original?.id) { saved ->
            stream = stream.copy(
                kickChannelName = saved.kickChannelName,
                kickChannelId = saved.kickChannelId,
                kickChatroomChannelId = saved.kickChatroomChannelId,
                kickSlug = saved.kickSlug,
            )
        }
        val savedKick = original?.let { saved -> savedStreams.firstOrNull { it.id == saved.id } }?.takeIf { it.kickLoggedIn }
        if (savedKick != null) {
            SwitchRow(stringResource(R.string.kick_send_messages), stream.kickSendMessagesTo) {
                stream = stream.copy(kickSendMessagesTo = it)
            }
            KickChannelSection(savedKick)
        }
        ChatTextField(stringResource(R.string.youtube_handle), stream.youTubeHandle) {
            stream = stream.copy(youTubeHandle = it)
        }
        ChatTextField(stringResource(R.string.youtube_video_ids), stream.youTubeVideoIds) {
            stream = stream.copy(youTubeVideoIds = it.replace(" ", ""))
        }
        Text(stringResource(R.string.emotes_help), style = MaterialTheme.typography.bodySmall)
        EmoteSwitch("BTTV", stream.chat.bttvEmotes) { stream = stream.copy(chat = stream.chat.copy(bttvEmotes = it)) }
        EmoteSwitch("FFZ", stream.chat.ffzEmotes) { stream = stream.copy(chat = stream.chat.copy(ffzEmotes = it)) }
        EmoteSwitch("7TV", stream.chat.seventvEmotes) { stream = stream.copy(chat = stream.chat.copy(seventvEmotes = it)) }
        HorizontalDivider()
        // Upstream's Twitch alert settings: follows, subs, cheers and so on, with sign-in.
        Text(stringResource(R.string.twitch_alerts), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.twitch_alerts_help), style = MaterialTheme.typography.bodySmall)
        TwitchAlertSwitches(stringResource(R.string.twitch_alerts_toasts), stream.twitchToastAlerts) {
            stream = stream.copy(twitchToastAlerts = it)
        }
        TwitchAlertSwitches(stringResource(R.string.twitch_alerts_chat), stream.twitchChatAlerts) {
            stream = stream.copy(twitchChatAlerts = it)
        }
        HorizontalDivider()
        // Upstream's Kick alert settings; read from Kick's public chat, no sign-in needed.
        Text(stringResource(R.string.kick_alerts), style = MaterialTheme.typography.titleMedium)
        KickAlertSwitches(stringResource(R.string.twitch_alerts_toasts), stream.kickToastAlerts, toasts = true) {
            stream = stream.copy(kickToastAlerts = it)
        }
        KickAlertSwitches(stringResource(R.string.twitch_alerts_chat), stream.kickChatAlerts, toasts = false) {
            stream = stream.copy(kickChatAlerts = it)
        }
        HorizontalDivider()
        // Upstream's snapshot settings: Discord uploads.
        Text("Discord", style = MaterialTheme.typography.titleMedium)
        SecretTextField(
            stringResource(R.string.webhook_url),
            snapshotWebhook,
            { snapshotWebhook = it.trim() },
            locked = locked(snapshotWebhook),
            onUnlock = unlock,
        )
        SecretTextField(
            stringResource(R.string.chat_bot_webhook_url),
            chatBotWebhook,
            { chatBotWebhook = it.trim() },
            locked = locked(chatBotWebhook),
            onUnlock = unlock,
        )
        SwitchRow(stringResource(R.string.only_when_live), stream.discordSnapshotWebhookOnlyWhenLive) {
            stream = stream.copy(discordSnapshotWebhookOnlyWhenLive = it)
        }
        Text(stringResource(R.string.discord_snapshots_help), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.discord_webhook_help), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Reads the clipboard and returns the stream URL with the pasted URL or key in it, or null (a
 * toast says why). The toast shows the server only, never the key.
 */
private fun pasteStreamKey(context: Context, current: String): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    val (url, message) = when (val result = StreamKeyPaste.apply(current, text)) {
        is StreamKeyPaste.Result.Pasted -> result.url to context.getString(
            if (result.wholeUrl) R.string.paste_stream_url_done else R.string.paste_stream_key_done,
            StreamUrlSecrets.display(result.url),
        )
        is StreamKeyPaste.Result.Failed -> null to context.getString(
            when (result.failure) {
                StreamKeyPaste.Failure.EMPTY -> R.string.paste_stream_key_empty
                StreamKeyPaste.Failure.NOT_A_KEY -> R.string.paste_stream_key_not_a_key
                StreamKeyPaste.Failure.INVALID_URL -> R.string.paste_stream_key_invalid
                StreamKeyPaste.Failure.NEEDS_SERVER -> R.string.paste_stream_key_needs_server
            },
        )
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    return url
}

/** Upstream `StreamMultiStreamingSettingsView`: more RTMP servers fed by the same encoders. */
@Composable
private fun MultiStreamingSection(
    destinations: List<SettingsStreamMultiStreamingDestination>,
    locked: (String) -> Boolean,
    onUnlock: () -> Unit,
    onChange: (List<SettingsStreamMultiStreamingDestination>) -> Unit,
) {
    HorizontalDivider()
    Text(stringResource(R.string.multi_streaming), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.multi_streaming_help), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.multi_streaming_warning), style = MaterialTheme.typography.bodySmall)
    fun change(index: Int, transform: (SettingsStreamMultiStreamingDestination) -> SettingsStreamMultiStreamingDestination) =
        onChange(destinations.mapIndexed { i, destination -> if (i == index) transform(destination) else destination })
    for ((index, destination) in destinations.withIndex()) {
        OutlinedTextField(
            value = destination.name,
            onValueChange = { name -> change(index) { it.copy(name = name) } },
            label = { Text(stringResource(R.string.destination)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val url = destination.url
        SecretTextField(
            label = stringResource(R.string.url),
            value = url,
            onValueChange = { value -> change(index) { it.copy(url = value.trim()) } },
            locked = locked(url),
            onUnlock = onUnlock,
            supportingText = if (url.isNotEmpty() && (!StreamUrlSecrets.isValid(url) || !url.startsWith("rtmp", ignoreCase = true))) {
                stringResource(R.string.multi_streaming_url_invalid)
            } else {
                null
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.enabled), modifier = Modifier.weight(1f))
            Switch(checked = destination.enabled, onCheckedChange = { on -> change(index) { it.copy(enabled = on) } })
            TextButton(onClick = { onChange(destinations.filterIndexed { i, _ -> i != index }) }) {
                Text(stringResource(R.string.delete))
            }
        }
    }
    TextButton(onClick = { onChange(destinations + SettingsStreamMultiStreamingDestination(url = "")) }) {
        Text(stringResource(R.string.add))
    }
}

/** One set of upstream's per-kind Twitch alert switches (for toasts, or for chat). */
@Composable
private fun TwitchAlertSwitches(title: String, alerts: SettingsTwitchAlerts, onChange: (SettingsTwitchAlerts) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    SwitchRow(stringResource(R.string.alerts_follows), alerts.follows) { onChange(alerts.copy(follows = it)) }
    SwitchRow(stringResource(R.string.alerts_subscriptions), alerts.subscriptions) { onChange(alerts.copy(subscriptions = it)) }
    SwitchRow(stringResource(R.string.alerts_resubscriptions), alerts.resubscriptions) { onChange(alerts.copy(resubscriptions = it)) }
    SwitchRow(stringResource(R.string.alerts_gift_subscriptions), alerts.giftSubscriptions) {
        onChange(alerts.copy(giftSubscriptions = it))
    }
    SwitchRow(stringResource(R.string.alerts_rewards), alerts.rewards) { onChange(alerts.copy(rewards = it)) }
    SwitchRow(stringResource(R.string.alerts_raids), alerts.raids) { onChange(alerts.copy(raids = it)) }
    SwitchRow(stringResource(R.string.alerts_cheers), alerts.cheers) { onChange(alerts.copy(cheers = it)) }
    if (alerts.cheers) {
        ChoiceField(
            stringResource(R.string.alerts_minimum_bits),
            alerts.minimumCheerBits,
            (MINIMUM_BITS_CHOICES + alerts.minimumCheerBits).distinct().sorted(),
            { "$it" },
            { onChange(alerts.copy(minimumCheerBits = it)) },
        )
    }
}

private val MINIMUM_BITS_CHOICES = listOf(0, 100, 500, 1000, 5000, 10000)

/** Upstream's Kick alert switches; bans only show in chat, as upstream. */
@Composable
private fun KickAlertSwitches(title: String, alerts: SettingsKickAlerts, toasts: Boolean, onChange: (SettingsKickAlerts) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    SwitchRow(stringResource(R.string.alerts_subscriptions), alerts.subscriptions) { onChange(alerts.copy(subscriptions = it)) }
    SwitchRow(stringResource(R.string.alerts_gift_subscriptions), alerts.giftedSubscriptions) {
        onChange(alerts.copy(giftedSubscriptions = it))
    }
    SwitchRow(stringResource(R.string.alerts_rewards), alerts.rewards) { onChange(alerts.copy(rewards = it)) }
    SwitchRow(stringResource(R.string.alerts_hosts), alerts.hosts) { onChange(alerts.copy(hosts = it)) }
    if (!toasts) {
        SwitchRow(stringResource(R.string.alerts_bans), alerts.bans) { onChange(alerts.copy(bans = it)) }
    }
    SwitchRow(stringResource(R.string.alerts_kicks), alerts.kicks) { onChange(alerts.copy(kicks = it)) }
    if (alerts.kicks) {
        ChoiceField(
            stringResource(R.string.alerts_minimum_kicks),
            alerts.minimumKicks,
            (MINIMUM_KICKS_CHOICES + alerts.minimumKicks).distinct().sorted(),
            { "$it" },
            { onChange(alerts.copy(minimumKicks = it)) },
        )
    }
}

private val MINIMUM_KICKS_CHOICES = listOf(0, 100, 500, 1000, 5000, 10000)

@Composable
private fun EmoteSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow(stringResource(R.string.emotes_provider, label), checked, onChange)
}

@Composable
private fun ChatTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
