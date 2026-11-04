package io.github.mesmerize08.nexusirl.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.remote.PendingRemoteImport
import kotlinx.coroutines.delay

/**
 * Asks before an assistant's settings import replaces everything (threat T6), with the same
 * summary as a file import. The controller rejects it when time runs out or the stream goes live.
 */
@Composable
fun RemoteImportDialog(pending: PendingRemoteImport, onAccept: () -> Unit, onReject: () -> Unit) {
    var now by remember(pending) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(pending) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val seconds = ((pending.expiresAtMillis - now + 999) / 1000).coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.remote_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.remote_import_text))
                Text(importSummary(pending.database))
                Text(stringResource(R.string.remote_import_countdown, seconds.toInt()), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.replace_settings)) } },
        dismissButton = { TextButton(onClick = onReject) { Text(stringResource(R.string.reject)) } },
    )
}
