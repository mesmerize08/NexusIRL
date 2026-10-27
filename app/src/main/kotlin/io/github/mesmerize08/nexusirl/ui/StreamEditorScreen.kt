// Port of Moblin/View/Settings/Streams/Stream/*.swift @97e26d2 (v1 subset: URL, video, SRT).
package io.github.mesmerize08.nexusirl.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.core.secrets.MissingSecretException
import io.github.mesmerize08.nexusirl.media.pipeline.EncoderCapabilities
import io.github.mesmerize08.nexusirl.settings.STREAM_FPS_CHOICES
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.SettingsStream
import io.github.mesmerize08.nexusirl.settings.SettingsTwitchAlerts
import io.github.mesmerize08.nexusirl.settings.SrtAdaptiveBitrateAlgorithm
import io.github.mesmerize08.nexusirl.settings.StreamCodec
import io.github.mesmerize08.nexusirl.settings.StreamResolution
import io.github.mesmerize08.nexusirl.settings.StreamUrlSecrets
import io.github.mesmerize08.nexusirl.settings.StreamUrls
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

    // Discord webhook URLs hold the webhook's token: secret too.
    fun resolved(value: String) = try {
        store.resolve(value)
    } catch (e: MissingSecretException) {
        StreamUrlSecrets.publicPart(value)
    }
    var snapshotWebhook by remember(streamId) { mutableStateOf(original?.let { resolved(it.discordSnapshotWebhook) }.orEmpty()) }
    var chatBotWebhook by remember(streamId) { mutableStateOf(original?.let { resolved(it.discordChatBotSnapshotWebhook) }.orEmpty()) }
    val hevcSupported = remember { EncoderCapabilities.supportsHevc() }
    val urlValid = StreamUrlSecrets.isValid(url)
    val scheme = url.substringBefore("://", "").lowercase()
    val isSrt = scheme == "srt" || scheme == "srtla"

    Page(
        title = stringResource(if (original == null) R.string.add_stream else R.string.edit_stream),
        onBack = onDone,
        actions = {
            Button(
                enabled = stream.name.isNotBlank() && urlValid,
                onClick = {
                    // The Twitch account is saved by sign-in and sign-out directly; keep it.
                    val account = store.current.streams.firstOrNull { it.id == stream.id }
                    val edited = stream.copy(
                        name = stream.name.trim(),
                        url = url,
                        discordSnapshotWebhook = snapshotWebhook,
                        discordChatBotSnapshotWebhook = chatBotWebhook,
                        twitchAccessToken = account?.twitchAccessToken.orEmpty(),
                        twitchLoggedIn = account?.twitchLoggedIn ?: false,
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
        )
        if (scheme == "rtmp") {
            // docs/PORT_PLAN.md §9.5: RTMP sends the stream key in cleartext.
            Text(
                stringResource(R.string.rtmp_unencrypted_warning),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.adaptive_bitrate), modifier = Modifier.weight(1f))
                Switch(checked = stream.srt.adaptiveBitrateEnabled, onCheckedChange = {
                    stream = stream.copy(srt = stream.srt.copy(adaptiveBitrateEnabled = it))
                })
            }
            if (stream.srt.adaptiveBitrateEnabled) {
                ChoiceField(stringResource(R.string.algorithm), stream.srt.adaptiveBitrate.algorithm, ALGORITHMS, ::algorithmName, {
                    stream = stream.copy(srt = stream.srt.copy(adaptiveBitrate = stream.srt.adaptiveBitrate.copy(algorithm = it)))
                })
            }
        }
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
        // Upstream's snapshot settings: Discord uploads.
        Text("Discord", style = MaterialTheme.typography.titleMedium)
        SecretTextField(stringResource(R.string.webhook_url), snapshotWebhook, { snapshotWebhook = it.trim() })
        SecretTextField(stringResource(R.string.chat_bot_webhook_url), chatBotWebhook, { chatBotWebhook = it.trim() })
        SwitchRow(stringResource(R.string.only_when_live), stream.discordSnapshotWebhookOnlyWhenLive) {
            stream = stream.copy(discordSnapshotWebhookOnlyWhenLive = it)
        }
        Text(stringResource(R.string.discord_snapshots_help), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.discord_webhook_help), style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun EmoteSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.emotes_provider, label), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
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
