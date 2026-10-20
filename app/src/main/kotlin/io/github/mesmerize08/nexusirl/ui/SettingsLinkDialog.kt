// Port of the confirmation flow in Moblin/Various/Model/ModelSettingsUrl.swift @97e26d2, with the
// untrusted-input guardrails of docs/PORT_PLAN.md §9.7 (threat T7).
package io.github.mesmerize08.nexusirl.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.settings.Database
import io.github.mesmerize08.nexusirl.settings.MoblinSettingsUrl
import io.github.mesmerize08.nexusirl.settings.StreamUrlSecrets

/**
 * Shows exactly what a `moblin://` link will change before anything is saved. Stream keys are
 * never displayed (host only). Applying a link never starts streaming.
 */
@Composable
fun SettingsLinkDialog(link: MoblinSettingsUrl, database: Database, onApply: (replaceCollisions: Boolean) -> Unit, onDismiss: () -> Unit) {
    val collisions = link.collidingStreamNames(database)
    val streams = link.streams.orEmpty()
    val containsKeys = streams.any { StreamUrlSecrets.split(it.url).second.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (streams.isEmpty()) {
                    Text(stringResource(R.string.link_no_streams))
                }
                for (stream in streams) {
                    val action = if (stream.name in collisions) R.string.link_stream_exists else R.string.link_stream_new
                    Text("• ${stream.name}: ${StreamUrlSecrets.display(stream.url)} (${stringResource(action)})")
                }
                if (containsKeys) {
                    Text(stringResource(R.string.link_contains_keys), modifier = Modifier.heightIn(min = 8.dp))
                }
                if (link.ignoredParts.isNotEmpty()) {
                    Text(
                        stringResource(R.string.link_ignored, link.ignoredParts.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(stringResource(R.string.link_never_starts), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row {
                if (collisions.isNotEmpty()) {
                    TextButton(onClick = { onApply(false) }) { Text(stringResource(R.string.link_add_new)) }
                    TextButton(onClick = { onApply(true) }) { Text(stringResource(R.string.link_replace)) }
                } else {
                    TextButton(onClick = { onApply(false) }, enabled = streams.isNotEmpty()) { Text(stringResource(R.string.link_import)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
