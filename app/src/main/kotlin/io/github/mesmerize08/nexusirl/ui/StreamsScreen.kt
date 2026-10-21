// Port of Moblin/View/Settings/Streams/StreamsSettingsView.swift @97e26d2 (v1 subset).
package io.github.mesmerize08.nexusirl.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.SettingsStream
import io.github.mesmerize08.nexusirl.settings.StreamUrlSecrets
import java.util.UUID

/** One-line summary: `scheme://host · 1080p 30 fps · 5 Mbps · H.265` (never the key). */
fun SettingsStream.summary(): String =
    "${StreamUrlSecrets.display(url)} · ${resolution.shortName}${if (portrait) " portrait" else ""} $fps fps · " +
        "${formatMbps(bitrate)} · ${codec.shortName}"

fun formatMbps(bitrate: Long): String {
    val mbps = bitrate / 1_000_000.0
    return if (mbps == Math.rint(mbps)) "${mbps.toLong()} Mbps" else "%.1f Mbps".format(mbps)
}

@Composable
fun StreamsScreen(store: SettingsStore, live: Boolean, onEdit: (UUID?) -> Unit, onBack: () -> Unit) {
    val database by store.database.collectAsState()
    var deleting by remember { mutableStateOf<SettingsStream?>(null) }
    Page(
        title = stringResource(R.string.streams),
        onBack = onBack,
        actions = { Button(onClick = { onEdit(null) }) { Text(stringResource(R.string.add_stream)) } },
    ) {
        if (database.streams.isEmpty()) {
            Text(stringResource(R.string.no_streams_here))
        }
        if (live) {
            Text(stringResource(R.string.streams_locked_while_live), style = MaterialTheme.typography.bodySmall)
        }
        for (stream in database.streams) {
            val selected = stream.id == database.selectedStream?.id
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !live) { store.update { it.selectStream(stream.id) } },
            ) {
                RadioButton(selected = selected, onClick = { store.update { it.selectStream(stream.id) } }, enabled = !live)
                Column(modifier = Modifier.weight(1f)) {
                    Text(stream.name, style = MaterialTheme.typography.titleMedium)
                    Text(stream.summary(), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onEdit(stream.id) }, enabled = !live || !selected) { Text(stringResource(R.string.edit)) }
                TextButton(onClick = { deleting = stream }, enabled = !live || !selected) { Text(stringResource(R.string.delete)) }
            }
        }
    }
    deleting?.let { stream ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_stream_title, stream.name)) },
            text = { Text(stringResource(R.string.delete_stream_text)) },
            confirmButton = {
                TextButton(onClick = {
                    store.update { it.removeStream(stream.id) }
                    deleting = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
