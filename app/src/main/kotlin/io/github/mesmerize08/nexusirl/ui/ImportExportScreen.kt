// Port of Moblin/View/Settings/ImportExport/*.swift and ModelSettingsImportExport.swift @97e26d2,
// with the export and import guardrails of docs/PORT_PLAN.md §9.7.
package io.github.mesmerize08.nexusirl.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.settings.Database
import io.github.mesmerize08.nexusirl.settings.EncryptedSettings
import io.github.mesmerize08.nexusirl.settings.SettingsArchive
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.StreamUrlSecrets
import io.github.mesmerize08.nexusirl.stream.StreamController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** An import waiting for the user's confirmation. */
private class PendingImport(val json: String, val database: Database) {
    val containsSecrets: Boolean = database.streams.any { StreamUrlSecrets.split(it.url).second.isNotEmpty() }
}

/** How the next export is written. */
private enum class ExportMode { WITHOUT_KEYS, WITH_KEYS, ENCRYPTED }

/** Largest settings file accepted (upstream archives can include media; v1 reads settings.json only). */
private const val MAX_IMPORT_BYTES = 64 * 1024 * 1024

private fun readBytes(context: Context, uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use { input ->
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

private fun parseImport(archive: ByteArray): PendingImport {
    val json = SettingsArchive.readSettingsJson(archive)
    return PendingImport(json, Database.fromJson(json))
}

private fun exportFileName(extension: String): String =
    "NexusIRL_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.$extension"

@Composable
fun ImportExportScreen(store: SettingsStore, live: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var encryptedImport by remember { mutableStateOf<ByteArray?>(null) }
    var exportMode by remember { mutableStateOf(ExportMode.WITHOUT_KEYS) }
    var exportPassphrase by remember { mutableStateOf<CharArray?>(null) }
    var chooseKeysExport by remember { mutableStateOf(false) }
    var askExportPassphrase by remember { mutableStateOf(false) }

    fun failure(prefix: String, e: Exception) = "$prefix: ${e.message ?: e.javaClass.simpleName}"

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val bytes = readBytes(context, uri)
                if (EncryptedSettings.isEncrypted(bytes)) {
                    encryptedImport = bytes
                } else {
                    pendingImport = parseImport(bytes)
                }
            } catch (e: Exception) {
                message = failure(context.getString(R.string.import_failed), e)
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val passphrase = exportPassphrase
        exportPassphrase = null
        if (uri == null) {
            passphrase?.fill(' ')
            return@rememberLauncherForActivityResult
        }
        val mode = exportMode
        busy = true
        scope.launch {
            message = try {
                // Key derivation takes about a second: off the main thread.
                withContext(Dispatchers.Default) {
                    val archive = SettingsArchive.write(store.exportJson(includeSecrets = mode != ExportMode.WITHOUT_KEYS))
                    val data = if (mode == ExportMode.ENCRYPTED) EncryptedSettings.encrypt(archive, passphrase!!) else archive
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                }
                context.getString(
                    when (mode) {
                        ExportMode.WITHOUT_KEYS -> R.string.exported_without_keys
                        ExportMode.WITH_KEYS -> R.string.exported_with_keys
                        ExportMode.ENCRYPTED -> R.string.exported_encrypted
                    },
                )
            } catch (e: Exception) {
                context.getString(R.string.export_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                passphrase?.fill(' ')
                busy = false
            }
        }
    }

    Page(title = stringResource(R.string.import_export), onBack = onBack) {
        Text(stringResource(R.string.import_help))
        Button(
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            enabled = !live && !busy,
        ) { Text(stringResource(R.string.import_settings)) }
        if (live) {
            Text(stringResource(R.string.import_not_while_live), style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Text(stringResource(R.string.export_help))
        Button(onClick = {
            exportMode = ExportMode.WITHOUT_KEYS
            exportLauncher.launch(exportFileName(SettingsArchive.FILE_EXTENSION))
        }, enabled = !busy) { Text(stringResource(R.string.export_without_keys)) }
        OutlinedButton(onClick = { chooseKeysExport = true }, enabled = !busy) { Text(stringResource(R.string.export_with_keys)) }
        if (busy) {
            Text(stringResource(R.string.working), style = MaterialTheme.typography.bodyMedium)
        }
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
                        StreamController.settingsImported(context)
                        context.getString(R.string.imported)
                    } catch (e: Exception) {
                        failure(context.getString(R.string.import_failed), e)
                    }
                    pendingImport = null
                }) { Text(stringResource(R.string.replace_settings)) }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    encryptedImport?.let { data ->
        PassphraseDialog(
            text = stringResource(R.string.import_passphrase_text),
            confirm = false,
            onDismiss = { encryptedImport = null },
            onPassphrase = { passphrase ->
                encryptedImport = null
                busy = true
                scope.launch {
                    try {
                        val archive = withContext(Dispatchers.Default) { EncryptedSettings.decrypt(data, passphrase) }
                        pendingImport = parseImport(archive)
                    } catch (e: Exception) {
                        message = failure(context.getString(R.string.import_failed), e)
                    } finally {
                        passphrase.fill(' ')
                        busy = false
                    }
                }
            },
        )
    }

    if (chooseKeysExport) {
        AlertDialog(
            onDismissRequest = { chooseKeysExport = false },
            title = { Text(stringResource(R.string.export_with_keys)) },
            text = {
                // Scrolls: in landscape the dialog is short.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.export_with_keys_choice))
                    Button(onClick = {
                        chooseKeysExport = false
                        askExportPassphrase = true
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_encrypted)) }
                    Text(stringResource(R.string.export_with_keys_warning), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        chooseKeysExport = false
                        exportMode = ExportMode.WITH_KEYS
                        exportLauncher.launch(exportFileName(SettingsArchive.FILE_EXTENSION))
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_unencrypted)) }
                }
            },
            confirmButton = { TextButton(onClick = { chooseKeysExport = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (askExportPassphrase) {
        PassphraseDialog(
            text = null,
            confirm = true,
            onDismiss = { askExportPassphrase = false },
            onPassphrase = { passphrase ->
                askExportPassphrase = false
                exportMode = ExportMode.ENCRYPTED
                exportPassphrase = passphrase
                exportLauncher.launch(exportFileName(EncryptedSettings.FILE_EXTENSION))
            },
        )
    }
}

/** Asks for a passphrase; with [confirm], twice and at least the minimum length (for exports). */
@Composable
private fun PassphraseDialog(text: String?, confirm: Boolean, onDismiss: () -> Unit, onPassphrase: (CharArray) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val minimum = EncryptedSettings.MINIMUM_PASSPHRASE_LENGTH
    val valid = if (confirm) passphrase.length >= minimum && passphrase == repeat else passphrase.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.passphrase)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                text?.let { Text(it) }
                SecretTextField(
                    label = stringResource(R.string.passphrase),
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    supportingText = if (confirm) pluralStringResource(R.plurals.passphrase_help, minimum, minimum) else null,
                )
                if (confirm) {
                    SecretTextField(label = stringResource(R.string.passphrase_repeat), value = repeat, onValueChange = { repeat = it })
                    if (repeat.isNotEmpty() && repeat != passphrase) {
                        Text(stringResource(R.string.passphrase_mismatch), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPassphrase(passphrase.toCharArray()) }, enabled = valid) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
