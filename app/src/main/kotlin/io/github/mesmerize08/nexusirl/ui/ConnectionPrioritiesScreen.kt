// Port of Moblin/View/Settings/Streams/Stream/Srt/StreamSrtConnectionPriority2View.swift @97e26d2.
package io.github.mesmerize08.nexusirl.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.settings.MAXIMUM_SRT_CONNECTION_PRIORITY
import io.github.mesmerize08.nexusirl.settings.MINIMUM_SRT_CONNECTION_PRIORITY
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.SettingsStreamSrtConnectionPriorities
import io.github.mesmerize08.nexusirl.settings.SettingsStreamSrtConnectionPriority
import io.github.mesmerize08.nexusirl.settings.clampConnectionPriority
import io.github.mesmerize08.nexusirl.stream.StreamController
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Stream [streamId]'s SRTLA connection priorities. Like upstream, every change is saved and
 * applied to a live stream at once (no Save button). [url], when given, decides the protocol
 * instead of the saved URL (the stream editor's unsaved URL).
 */
@Composable
fun ConnectionPrioritiesScreen(store: SettingsStore, streamId: UUID, onBack: () -> Unit, url: String? = null) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val database by store.database.collectAsState()
    val saved = database.streams.firstOrNull { it.id == streamId }
    Page(title = stringResource(R.string.connection_priorities), onBack = onBack) {
        val stream = saved?.let { if (url != null) it.copy(url = url) else it } ?: return@Page
        if (!stream.isSrtla) {
            Text(stringResource(R.string.connection_priorities_not_supported, stream.protocolString()))
            return@Page
        }
        fun change(transform: (SettingsStreamSrtConnectionPriorities) -> SettingsStreamSrtConnectionPriorities) =
            StreamController.updateConnectionPriorities(context, streamId, transform)
        val priorities = stream.srt.connectionPriorities
        SwitchRow(stringResource(R.string.enabled), priorities.enabled) { enabled -> change { it.copy(enabled = enabled) } }
        for (priority in priorities.priorities) {
            PriorityRow(
                priority,
                onChange = { value, enabled -> change { it.withPriority(priority.id, value, enabled) } },
                onDelete = { change { it.without(priority.id) } },
            )
        }
        Text(stringResource(R.string.connection_priorities_help), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.connection_priorities_disabled_help), style = MaterialTheme.typography.bodySmall)
    }
}

/** Upstream `PriorityItemView`: the slider saves when released. */
@Composable
private fun PriorityRow(priority: SettingsStreamSrtConnectionPriority, onChange: (Int, Boolean) -> Unit, onDelete: () -> Unit) {
    var value by remember(priority.id, priority.priority) { mutableFloatStateOf(priority.priority.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(priority.name, maxLines = 1, modifier = Modifier.width(90.dp))
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onChange(clampConnectionPriority(value.roundToInt()), priority.enabled) },
            valueRange = MINIMUM_SRT_CONNECTION_PRIORITY.toFloat()..MAXIMUM_SRT_CONNECTION_PRIORITY.toFloat(),
            steps = MAXIMUM_SRT_CONNECTION_PRIORITY - MINIMUM_SRT_CONNECTION_PRIORITY - 1,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = priority.enabled, onCheckedChange = { onChange(priority.priority, it) })
        if (priority.canDelete) {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
        }
    }
}
