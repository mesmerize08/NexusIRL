// Port of Moblin/View/Settings/ImportExport/*.swift and ModelSettingsImportExport.swift @97e26d2,
// with the export and import guardrails of docs/PORT_PLAN.md §9.7.
package io.github.mesmerize08.nexusirl.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.settings.Database
import io.github.mesmerize08.nexusirl.settings.SettingsArchive
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.StreamUrlSecrets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** An import waiting for the user's confirmation. */
private class PendingImport(val json: String, val database: Database) {
    val containsSecrets: Boolean = database.streams.any { StreamUrlSecrets.split(it.url).second.isNotEmpty() }
}

/** Largest settings file accepted (upstream archives can include media; v1 reads settings.json only). */
private const val MAX_IMPORT_BYTES = 64 * 1024 * 1024

private fun readImport(context: Context, uri: Uri): PendingImport {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        // InputStream.readNBytes needs API 33; read with an explicit size limit instead.
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            if (out.size() > MAX_IMPORT_BYTES) throw SettingsArchive.InvalidArchiveException("File is too large")
        }
        out.toByteArray()
    } ?: throw SettingsArchive.InvalidArchiveException("Cannot read file")
    val json = SettingsArchive.readSettingsJson(bytes)
    return PendingImport(json, Database.fromJson(json))
}

private fun exportFileName(): String =
    "NexusIRL_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.${SettingsArchive.FILE_EXTENSION}"

@Composable
fun ImportExportScreen(store: SettingsStore, live: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var includeSecrets by remember { mutableStateOf(false) }
    var confirmSecretsExport by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingImport = try {
                readImport(context, uri)
            } catch (e: Exception) {
                message = context.getString(R.string.import_failed) + ": " + (e.message ?: e.javaClass.simpleName)
                null
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            message = try {
                val archive = SettingsArchive.write(store.exportJson(includeSecrets))
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(archive) }
                context.getString(if (includeSecrets) R.string.exported_with_keys else R.string.exported_without_keys)
            } catch (e: Exception) {
                context.getString(R.string.export_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    Page(title = stringResource(R.string.import_export), onBack = onBack) {
        Text(stringResource(R.string.import_help))
        Button(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !live) { Text(stringResource(R.string.import_settings)) }
        if (live) {
            Text(stringResource(R.string.import_not_while_live), style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Text(stringResource(R.string.export_help))
        Button(onClick = {
            includeSecrets = false
            exportLauncher.launch(exportFileName())
        }) { Text(stringResource(R.string.export_without_keys)) }
        OutlinedButton(onClick = { confirmSecretsExport = true }) { Text(stringResource(R.string.export_with_keys)) }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.import_confirm_title)) },
            text = {
                val streams = pending.database.streams.joinToString(", ") { it.name }.ifEmpty { "-" }
                Text(
                    pluralStringResource(
                        R.plurals.import_confirm_text,
                        pending.database.streams.size,
                        pending.database.streams.size,
                        streams,
                    ) +
                        if (pending.containsSecrets) "\n\n" + stringResource(R.string.import_contains_keys) else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    message = try {
                        store.import(pending.json)
                        context.getString(R.string.imported)
                    } catch (e: Exception) {
                        context.getString(R.string.import_failed) + ": " + (e.message ?: e.javaClass.simpleName)
                    }
                    pendingImport = null
                }) { Text(stringResource(R.string.replace_settings)) }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmSecretsExport) {
        AlertDialog(
            onDismissRequest = { confirmSecretsExport = false },
            title = { Text(stringResource(R.string.export_with_keys)) },
            text = { Text(stringResource(R.string.export_with_keys_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSecretsExport = false
                    includeSecrets = true
                    exportLauncher.launch(exportFileName())
                }) { Text(stringResource(R.string.export_anyway)) }
            },
            dismissButton = { TextButton(onClick = { confirmSecretsExport = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
