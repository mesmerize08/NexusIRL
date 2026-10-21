// Port of the assistant view in Moblin/View/RemoteControl/RemoteControlAssistantView.swift @97e26d2
// (v1 subset).
package io.github.mesmerize08.nexusirl.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.remote.RemoteControlAssistantController
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlFilter
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlRequest
import io.github.mesmerize08.nexusirl.settings.SettingsStore

private val ASSISTANT_FILTERS = listOf(
    RemoteControlFilter.GRAY_SCALE to R.string.filter_gray_scale,
    RemoteControlFilter.SEPIA to R.string.filter_sepia,
    RemoteControlFilter.PIXELLATE to R.string.filter_pixellate,
    RemoteControlFilter.FOUR_THREE to R.string.filter_four_three,
    RemoteControlFilter.CRT to R.string.filter_crt,
)

/** Control another phone running Moblin or NexusIRL that connects here (upstream assistant). */
@Composable
fun RemoteAssistantScreen(store: SettingsStore, onBack: () -> Unit) {
    val database by store.database.collectAsState()
    val view by RemoteControlAssistantController.view.collectAsState()
    val assistant = database.remoteControl.assistant
    var confirmLive by remember { mutableStateOf<Boolean?>(null) }
    fun send(request: RemoteControlRequest) = RemoteControlAssistantController.send(request)
    Page(title = stringResource(R.string.remote_assistant), onBack = onBack) {
        Text(stringResource(R.string.remote_assistant_help), style = MaterialTheme.typography.bodySmall)
        SwitchRow(stringResource(R.string.enabled), assistant.enabled) { on ->
            store.update { it.copy(remoteControl = it.remoteControl.copy(assistant = it.remoteControl.assistant.copy(enabled = on))) }
        }
        if (assistant.enabled) {
            Text(stringResource(R.string.remote_assistant_urls), style = MaterialTheme.typography.bodySmall)
            for (url in RemoteControlAssistantController.localUrls(assistant.port)) {
                Text(url, style = MaterialTheme.typography.bodyLarge)
            }
            Text(stringResource(R.string.remote_assistant_password), style = MaterialTheme.typography.bodySmall)
        }
        val current = view ?: return@Page
        HorizontalDivider()
        if (!current.connected) {
            Text(stringResource(R.string.remote_assistant_waiting))
            return@Page
        }
        val state = current.state
        current.preview?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth()) }
        val status = listOfNotNull(
            current.topLeft?.stream?.message,
            current.topRight?.bitrate?.message,
            current.topRight?.uptime?.message,
            current.general?.batteryLevel?.let { "$it %" },
        ).joinToString(" · ")
        if (status.isNotEmpty()) {
            Text(status)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Going live or stopping affects a real stream: ask first.
            Button(onClick = { confirmLive = state.streaming != true }) {
                Text(stringResource(if (state.streaming == true) R.string.end_stream else R.string.go_live))
            }
            OutlinedButton(onClick = { send(RemoteControlRequest.SetRecord(state.recording != true)) }) {
                Text(stringResource(if (state.recording == true) R.string.stop_recording else R.string.record))
            }
        }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = state.muted == true, onClick = { send(RemoteControlRequest.SetMute(state.muted != true)) }, label = {
                Text(stringResource(R.string.mute))
            })
            FilterChip(selected = state.torchOn == true, onClick = { send(RemoteControlRequest.SetTorch(state.torchOn != true)) }, label = {
                Text(stringResource(R.string.torch))
            })
            FilterChip(
                selected = state.stealthMode == true,
                onClick = { send(RemoteControlRequest.SetStealthMode(state.stealthMode != true)) },
                label = { Text(stringResource(R.string.stealth_mode)) },
            )
        }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((filter, label) in ASSISTANT_FILTERS) {
                val on = state.filters?.get(filter) == true
                FilterChip(
                    selected = on,
                    onClick = { send(RemoteControlRequest.SetFilter(filter, !on)) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        current.settings?.scenes?.takeIf { it.isNotEmpty() }?.let { scenes ->
            Text(stringResource(R.string.scenes), style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (scene in scenes) {
                    FilterChip(selected = state.scene == scene.id, onClick = { send(RemoteControlRequest.SetScene(scene.id)) }, label = {
                        Text(scene.name)
                    })
                }
            }
        }
        state.zoom?.let { zoom ->
            SliderRow(stringResource(R.string.zoom), zoom, 0.5f..10f, "%.1fx".format(zoom)) { send(RemoteControlRequest.SetZoom(it)) }
        }
    }
    confirmLive?.let { goLive ->
        AlertDialog(
            onDismissRequest = { confirmLive = null },
            text = { Text(stringResource(if (goLive) R.string.remote_confirm_go_live else R.string.remote_confirm_end)) },
            confirmButton = {
                Button(onClick = {
                    send(RemoteControlRequest.SetLive(goLive))
                    confirmLive = null
                }) { Text(stringResource(if (goLive) R.string.go_live else R.string.end_stream)) }
            },
            dismissButton = { TextButton(onClick = { confirmLive = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
