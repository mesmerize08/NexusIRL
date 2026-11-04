// Port of Moblin/View/ControlBar/QuickButton/QuickButtonObsView.swift and
// View/Settings/Streams/Stream/ObsRemoteControl/StreamObsRemoteControlSettingsView.swift @97e26d2.
package io.github.mesmerize08.nexusirl.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.core.websocket.CleartextPolicy
import io.github.mesmerize08.nexusirl.obs.ObsController
import io.github.mesmerize08.nexusirl.obs.ObsOutputState
import io.github.mesmerize08.nexusirl.obs.ObsSceneMediaSource
import io.github.mesmerize08.nexusirl.settings.SettingsMoblinkRelay
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.SettingsStream
import io.github.mesmerize08.nexusirl.stream.StreamController
import java.net.URI
import java.util.UUID

/** What is wrong with an OBS WebSocket URL, as a string resource, or null. */
fun obsUrlProblem(url: String): Int? {
    if (!SettingsMoblinkRelay.isValidWebSocketUrl(url)) {
        return R.string.obs_url_invalid
    }
    val host = try {
        URI(url).host
    } catch (e: Exception) {
        null
    } ?: return R.string.obs_url_invalid
    return if (url.startsWith("ws:", ignoreCase = true) && !CleartextPolicy.isAllowed(host, allowLocalNetwork = true)) {
        R.string.obs_url_not_local
    } else {
        null
    }
}

/**
 * The stream's OBS remote control settings, in the stream editor. [url] and [password] hold the
 * resolved secrets while editing.
 */
@Composable
fun ObsSettingsSection(
    stream: SettingsStream,
    url: String,
    password: String,
    onChange: (SettingsStream) -> Unit,
    onUrl: (String) -> Unit,
    onPassword: (String) -> Unit,
) {
    val obs by ObsController.state.collectAsState()
    // Scenes to pick from, when connected to this stream's OBS.
    val scenes = if (stream.enabled && obs.connected) obs.scenes else emptyList()
    HorizontalDivider()
    Text(stringResource(R.string.obs_remote_control), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.obs_remote_control_help), style = MaterialTheme.typography.bodySmall)
    SwitchRow(stringResource(R.string.enabled), stream.obsWebSocketEnabled) { onChange(stream.copy(obsWebSocketEnabled = it)) }
    if (!stream.obsWebSocketEnabled) {
        return
    }
    val problem = url.takeIf { it.isNotEmpty() }?.let(::obsUrlProblem)
    OutlinedTextField(
        value = url,
        onValueChange = { onUrl(it.trim()) },
        label = { Text(stringResource(R.string.url)) },
        supportingText = { Text(stringResource(problem ?: R.string.obs_url_help)) },
        isError = problem != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SecretTextField(stringResource(R.string.password), password, onPassword)
    SceneField(stringResource(R.string.obs_main_scene), stream.obsMainScene, scenes) { onChange(stream.copy(obsMainScene = it)) }
    SceneField(stringResource(R.string.obs_brb_scene), stream.obsBrbScene, scenes) { onChange(stream.copy(obsBrbScene = it)) }
    Text(stringResource(R.string.obs_scenes_help), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = stream.obsSourceName,
        onValueChange = { onChange(stream.copy(obsSourceName = it)) },
        label = { Text(stringResource(R.string.obs_source_name)) },
        supportingText = { Text(stringResource(R.string.obs_source_name_help)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SwitchRow(stringResource(R.string.obs_auto_start_stream), stream.obsAutoStartStream) { onChange(stream.copy(obsAutoStartStream = it)) }
    SwitchRow(stringResource(R.string.obs_auto_stop_stream), stream.obsAutoStopStream) { onChange(stream.copy(obsAutoStopStream = it)) }
    SwitchRow(stringResource(R.string.obs_auto_start_recording), stream.obsAutoStartRecording) {
        onChange(stream.copy(obsAutoStartRecording = it))
    }
    SwitchRow(stringResource(R.string.obs_auto_stop_recording), stream.obsAutoStopRecording) {
        onChange(stream.copy(obsAutoStopRecording = it))
    }
}

/** A scene name, typed or picked from OBS's scenes. */
@Composable
private fun SceneField(label: String, value: String, scenes: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        if (scenes.isNotEmpty()) {
            Box {
                TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.obs_pick)) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    for (scene in scenes) {
                        DropdownMenuItem(text = { Text(scene) }, onClick = {
                            expanded = false
                            onChange(scene)
                        })
                    }
                }
            }
        }
    }
}

/** The OBS panel (upstream's OBS quick button): streaming, recording, scenes, audio and the source. */
@Composable
fun ObsScreen(store: SettingsStore, onEditStream: (UUID) -> Unit, onBack: () -> Unit) {
    val database by store.database.collectAsState()
    val obs by ObsController.state.collectAsState()
    val status by StreamController.status.collectAsState()
    val stream = database.selectedStream
    DisposableEffect(Unit) {
        ObsController.openPanel()
        onDispose { ObsController.closePanel() }
    }
    Page(title = stringResource(R.string.obs_remote_control), onBack = onBack) {
        when {
            !obs.configured -> Text(stringResource(R.string.obs_not_configured))
            !obs.connected -> Text(stringResource(R.string.obs_not_connected, obs.statusText))
            else -> ObsConnected(stream?.obsSourceName.orEmpty(), status.active)
        }
        if (stream != null) {
            OutlinedButton(onClick = { onEditStream(stream.id) }) { Text(stringResource(R.string.obs_settings)) }
        }
    }
}

@Composable
private fun ObsConnected(sourceName: String, live: Boolean) {
    val obs by ObsController.state.collectAsState()
    val screenshot by ObsController.screenshot.collectAsState()
    StartStopButton(
        obs.streamingState,
        stringResource(R.string.obs_start_streaming),
        stringResource(R.string.obs_stop_streaming),
        ObsController::startStream,
        ObsController::stopStream,
    )
    StartStopButton(
        obs.recordingState,
        stringResource(R.string.obs_start_recording),
        stringResource(R.string.obs_stop_recording),
        ObsController::startRecording,
        ObsController::stopRecording,
    )
    HorizontalDivider()
    Text(stringResource(R.string.obs_snapshot), style = MaterialTheme.typography.titleMedium)
    val bitmap = remember(screenshot) { screenshot?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth())
    } else {
        Text(stringResource(R.string.obs_no_snapshot))
    }
    HorizontalDivider()
    Text(stringResource(R.string.obs_scenes), style = MaterialTheme.typography.titleMedium)
    for (scene in obs.scenes) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { if (scene != obs.currentScene) ObsController.setScene(scene) },
        ) {
            RadioButton(selected = scene == obs.currentScene, onClick = { if (scene != obs.currentScene) ObsController.setScene(scene) })
            Text(scene)
        }
    }
    if (obs.sceneInputs.any { it.muted != null }) {
        HorizontalDivider()
        Text(stringResource(R.string.obs_audio_inputs), style = MaterialTheme.typography.titleMedium)
        for (input in obs.sceneInputs) {
            val muted = input.muted ?: continue
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(input.name, modifier = Modifier.weight(1f))
                Text(
                    stringResource(if (muted) R.string.obs_muted else R.string.obs_unmuted),
                    color = if (muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                )
                Switch(checked = !muted, onCheckedChange = { on -> ObsController.setMuted(input.name, !on) })
            }
        }
    }
    if (obs.sceneMediaSources.isNotEmpty()) {
        HorizontalDivider()
        Text(stringResource(R.string.obs_media_sources), style = MaterialTheme.typography.titleMedium)
        for (source in obs.sceneMediaSources) {
            MediaSourceField(source)
        }
    }
    HorizontalDivider()
    if (sourceName.isEmpty()) {
        Text(stringResource(R.string.obs_configure_source_name), style = MaterialTheme.typography.bodySmall)
        return
    }
    Button(onClick = { ObsController.fixSource() }, enabled = !obs.fixing, modifier = Modifier.fillMaxWidth()) {
        Text(if (obs.fixing) stringResource(R.string.obs_fixing) else stringResource(R.string.obs_fix_source, sourceName))
    }
    Text(stringResource(R.string.obs_fix_source_help, sourceName), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.obs_audio_sync, sourceName), style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (step in listOf(-100, -10)) {
            OutlinedButton(onClick = { ObsController.setAudioDelay(obs.audioDelayMs + step) }) { Text("$step") }
        }
        Text("${obs.audioDelayMs} ms", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        for (step in listOf(10, 100)) {
            OutlinedButton(onClick = { ObsController.setAudioDelay(obs.audioDelayMs + step) }) { Text("+$step") }
        }
    }
    Text(stringResource(R.string.obs_audio_levels, sourceName), style = MaterialTheme.typography.titleMedium)
    Text(
        when {
            !live -> stringResource(R.string.obs_go_live_for_audio_levels)
            obs.audioLevels.isEmpty() -> stringResource(R.string.obs_no_audio_levels)
            else -> obs.audioLevels
        },
    )
}

/** Upstream `ObsSceneMediaSourceView`: what a media source plays, such as an SRT URL. */
@Composable
private fun MediaSourceField(source: ObsSceneMediaSource) {
    var input by remember(source) { mutableStateOf(source.input) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(stringResource(R.string.obs_media_source_input, source.name)) },
            placeholder = { Text("srt://1.2.3.4:4000") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { ObsController.setMediaSourceInput(source.name, input.trim()) }, enabled = input.trim() != source.input) {
            Text(stringResource(R.string.save))
        }
    }
}

/** Upstream `ObsStartStopButtonView`: start or stop with a confirmation, or the state while changing. */
@Composable
private fun StartStopButton(state: ObsOutputState, startText: String, stopText: String, onStart: () -> Unit, onStop: () -> Unit) {
    var confirm by remember { mutableStateOf<String?>(null) }
    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.obs_confirm, action)) },
            confirmButton = {
                TextButton(onClick = {
                    if (action == startText) onStart() else onStop()
                    confirm = null
                }) { Text(action) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    when (state) {
        ObsOutputState.STOPPED -> OutlinedButton(onClick = { confirm = startText }, modifier = Modifier.fillMaxWidth()) { Text(startText) }
        ObsOutputState.STARTED -> Button(onClick = { confirm = stopText }, modifier = Modifier.fillMaxWidth()) { Text(stopText) }
        ObsOutputState.STARTING -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.obs_starting))
        }
        ObsOutputState.STOPPING -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.obs_stopping))
        }
    }
}
