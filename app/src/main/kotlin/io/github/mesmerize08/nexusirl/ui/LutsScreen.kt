// Port of Moblin/View/Settings/Camera/LutsSettingsView.swift @97e26d2 (v1 subset).
package io.github.mesmerize08.nexusirl.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.scene.LutLoader
import io.github.mesmerize08.nexusirl.settings.SettingsColor
import io.github.mesmerize08.nexusirl.settings.SettingsColorLut
import io.github.mesmerize08.nexusirl.settings.SettingsStore

/** Applies [transform] to whichever LUT list holds [lut]. */
private fun SettingsColor.mapLut(lut: SettingsColorLut, transform: (SettingsColorLut) -> SettingsColorLut?): SettingsColor {
    fun List<SettingsColorLut>.apply() = mapNotNull { if (it.id == lut.id) transform(it) else it }
    return copy(
        bundledLuts = bundledLuts.apply(),
        diskLuts = diskLuts.apply(),
        diskLutsPng = diskLutsPng.apply(),
        diskLutsCube = diskLutsCube.apply(),
    )
}

@Composable
fun LutsScreen(store: SettingsStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val database by store.database.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            message = try {
                val (lut, isCube) = LutLoader.import(context, uri)
                store.update { db ->
                    db.copy(
                        color = if (isCube) {
                            db.color.copy(diskLutsCube = db.color.diskLutsCube + lut)
                        } else {
                            db.color.copy(diskLutsPng = db.color.diskLutsPng + lut)
                        },
                    )
                }
                null
            } catch (e: Exception) {
                resources.getString(R.string.lut_import_failed) + ": " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }
    Page(
        title = stringResource(R.string.luts),
        onBack = onBack,
        actions = { Button(onClick = { importLauncher.launch("*/*") }) { Text(stringResource(R.string.add_lut)) } },
    ) {
        Text(stringResource(R.string.luts_help), style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        for (lut in database.color.allLuts + database.color.diskLuts) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(lut.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (lut.type == "bundled") stringResource(R.string.lut_bundled) else lut.type,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (lut.type != "bundled") {
                    TextButton(onClick = {
                        LutLoader.delete(context, lut)
                        store.update { db -> db.copy(color = db.color.mapLut(lut) { null }) }
                    }) { Text(stringResource(R.string.delete)) }
                }
                Switch(checked = lut.enabled, onCheckedChange = { enabled ->
                    store.update { db -> db.copy(color = db.color.mapLut(lut) { it.copy(enabled = enabled) }) }
                })
            }
        }
    }
}
