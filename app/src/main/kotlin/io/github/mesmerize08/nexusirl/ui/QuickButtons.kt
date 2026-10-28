// Port of Moblin/View/ControlBar/QuickButtonsView.swift and
// View/Settings/QuickButtons/QuickButtonsSettingsView.swift @97e26d2 (v1 subset: the buttons
// Android can act on, shown by name instead of SF Symbols).
package io.github.mesmerize08.nexusirl.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.settings.QuickButtonType
import io.github.mesmerize08.nexusirl.settings.SettingsQuickButton
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.stream.Microphones
import io.github.mesmerize08.nexusirl.stream.StreamController

/** The quick buttons Android implements so far; the others stay in settings for iOS round trips. */
val SUPPORTED_QUICK_BUTTONS = setOf(
    QuickButtonType.TORCH,
    QuickButtonType.MUTE,
    QuickButtonType.LIVE,
    QuickButtonType.MIC,
    QuickButtonType.BITRATE,
    QuickButtonType.RECORD,
    QuickButtonType.SNAPSHOT,
    QuickButtonType.WIDGETS,
    QuickButtonType.LOCAL_OVERLAYS,
    QuickButtonType.BLACK_SCREEN,
    QuickButtonType.GRAY_SCALE,
    QuickButtonType.SEPIA,
    QuickButtonType.PIXELLATE,
    QuickButtonType.FOUR_THREE,
    QuickButtonType.CRT,
    QuickButtonType.WHIRLPOOL,
    QuickButtonType.PINCH,
    QuickButtonType.LUTS,
    QuickButtonType.PORTRAIT,
)

/** Enabled quick buttons in their configured order (upstream pages are shown one after another). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickButtonBar(store: SettingsStore, onWidgets: () -> Unit, onLuts: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val database by store.database.collectAsState()
    val status by StreamController.status.collectAsState()
    val recording by StreamController.recording.collectAsState()
    val camera by StreamController.camera.collectAsState()
    val buttons = database.quickButtons.filter { it.enabled && it.type in SUPPORTED_QUICK_BUTTONS }.sortedBy { it.page }
    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    var picker by remember { mutableStateOf<QuickButtonType?>(null) }
    when (picker) {
        QuickButtonType.MIC -> MicPicker(onDismiss = { picker = null })
        QuickButtonType.BITRATE -> BitratePicker(store, onDismiss = { picker = null })
        else -> Unit
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (button in buttons) {
            val selected = when (button.type) {
                QuickButtonType.TORCH -> camera.torch
                QuickButtonType.LIVE -> status.active
                QuickButtonType.RECORD -> recording != null
                QuickButtonType.SNAPSHOT, QuickButtonType.WIDGETS, QuickButtonType.LUTS, QuickButtonType.MIC,
                QuickButtonType.BITRATE,
                -> false
                QuickButtonType.PORTRAIT -> database.portrait
                else -> button.isOn
            }
            FilterChip(
                selected = selected,
                onClick = {
                    when (button.type) {
                        QuickButtonType.LIVE -> if (status.active) {
                            StreamController.stop(
                                context,
                            )
                        } else {
                            StreamController.goLive(context)?.let(::toast)
                        }
                        QuickButtonType.RECORD -> if (recording == null) {
                            StreamController.startRecording(context)?.let(::toast)
                        } else {
                            val saved = StreamController.stopRecording(context)
                            toast(resources.getString(if (saved) R.string.recording_saved else R.string.recording_failed))
                        }
                        QuickButtonType.SNAPSHOT -> StreamController.takeSnapshot(context) { saved ->
                            Toast.makeText(
                                context,
                                if (saved) R.string.snapshot_saved else R.string.snapshot_failed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        QuickButtonType.MIC, QuickButtonType.BITRATE -> picker = button.type
                        QuickButtonType.WIDGETS -> onWidgets()
                        QuickButtonType.LUTS -> onLuts()
                        QuickButtonType.TORCH -> StreamController.setQuickButton(context, button.type, !camera.torch)
                        else -> StreamController.setQuickButton(context, button.type, !button.isOn)
                    }
                },
                label = { Text(button.name) },
            )
        }
        TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
    }
}

/** Which quick buttons are shown and in what order (upstream quick button settings). */
@Composable
fun QuickButtonsScreen(store: SettingsStore, onBack: () -> Unit) {
    val database by store.database.collectAsState()
    // Shown in bar order: by page, then by position.
    val buttons = database.quickButtons.filter { it.type in SUPPORTED_QUICK_BUTTONS }.sortedBy { it.page }
    fun move(button: SettingsQuickButton, offset: Int) {
        val index = buttons.indexOf(button)
        val other = buttons.getOrNull(index + offset) ?: return
        store.update { current ->
            val list = current.quickButtons.toMutableList()
            val a = list.indexOfFirst { it.id == button.id }
            val b = list.indexOfFirst { it.id == other.id }
            if (a >= 0 && b >= 0) {
                // Swap places and pages, so the order also holds across upstream's pages.
                list[a] = other.copy(page = button.page)
                list[b] = button.copy(page = other.page)
            }
            current.copy(quickButtons = list)
        }
    }
    Page(title = stringResource(R.string.quick_buttons), onBack = onBack) {
        Text(stringResource(R.string.quick_buttons_help))
        for (button in buttons) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(button.name, modifier = Modifier.weight(1f))
                TextButton(onClick = { move(button, -1) }) { Text("↑") }
                TextButton(onClick = { move(button, 1) }) { Text("↓") }
                Switch(checked = button.enabled, onCheckedChange = { on ->
                    store.update { current ->
                        current.copy(quickButtons = current.quickButtons.map { if (it.id == button.id) it.copy(enabled = on) else it })
                    }
                })
            }
        }
    }
}

/** Upstream mic picker: the connected microphones; picking one stops automatic switching. */
@Composable
private fun MicPicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = StreamController.settings(context)
    val database by store.database.collectAsState()
    val mics by Microphones.mics.collectAsState()
    val selected by Microphones.selected.collectAsState()
    PickerDialog(
        title = stringResource(R.string.microphone),
        options = mics.map { it.id to it.name },
        selected = selected?.id,
        onPick = { Microphones.select(it) },
        onDismiss = onDismiss,
        footer = {
            SwitchRow(stringResource(R.string.prefer_stereo_mic), database.audio.preferStereoMic) { on ->
                store.update { it.copy(audio = it.audio.copy(preferStereoMic = on)) }
            }
            Text(stringResource(R.string.prefer_stereo_mic_help), style = MaterialTheme.typography.bodySmall)
        },
    )
}

/** Upstream bitrate picker: the presets; the stream keeps the choice and it applies at once when live. */
@Composable
private fun BitratePicker(store: SettingsStore, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val database by store.database.collectAsState()
    val current = database.selectedStream?.bitrate
    PickerDialog(
        title = stringResource(R.string.bitrate),
        options = database.bitratePresets.sortedByDescending { it.bitrate }.map { it.bitrate.toString() to formatMbps(it.bitrate) },
        selected = current?.toString(),
        onPick = { StreamController.setBitrate(context, it.toLong()) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun PickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for ((id, name) in options) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            onPick(id)
                            onDismiss()
                        },
                    ) {
                        RadioButton(selected = id == selected, onClick = {
                            onPick(id)
                            onDismiss()
                        })
                        Text(name)
                    }
                }
                footer?.invoke()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
