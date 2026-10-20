// Port of Moblin/View/ControlBar/QuickButtonsView.swift and
// View/Settings/QuickButtons/QuickButtonsSettingsView.swift @97e26d2 (v1 subset: the buttons
// Android can act on, shown by name instead of SF Symbols).
package io.github.mesmerize08.nexusirl.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.settings.QuickButtonType
import io.github.mesmerize08.nexusirl.settings.SettingsQuickButton
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.stream.StreamController

/** The quick buttons Android implements so far; the others stay in settings for iOS round trips. */
val SUPPORTED_QUICK_BUTTONS = setOf(
    QuickButtonType.TORCH,
    QuickButtonType.MUTE,
    QuickButtonType.LIVE,
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
)

/** Enabled quick buttons in their configured order (upstream pages are shown one after another). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickButtonBar(store: SettingsStore, onWidgets: () -> Unit, onLuts: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val database by store.database.collectAsState()
    val status by StreamController.status.collectAsState()
    val recording by StreamController.recording.collectAsState()
    val camera by StreamController.camera.collectAsState()
    val buttons = database.quickButtons.filter { it.enabled && it.type in SUPPORTED_QUICK_BUTTONS }.sortedBy { it.page }
    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (button in buttons) {
            val selected = when (button.type) {
                QuickButtonType.TORCH -> camera.torch
                QuickButtonType.LIVE -> status.active
                QuickButtonType.RECORD -> recording != null
                QuickButtonType.SNAPSHOT, QuickButtonType.WIDGETS, QuickButtonType.LUTS -> false
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
                            toast(context.getString(if (saved) R.string.recording_saved else R.string.recording_failed))
                        }
                        QuickButtonType.SNAPSHOT -> StreamController.takeSnapshot(context) { saved ->
                            Toast.makeText(
                                context,
                                if (saved) R.string.snapshot_saved else R.string.snapshot_failed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
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
