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
                    val edited = stream.copy(name = stream.name.trim(), url = url)
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
    }
}
