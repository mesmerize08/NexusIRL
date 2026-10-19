package io.github.mesmerize08.nexusirl

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.json.JSONArray

/** One third-party library, from the licensee report bundled in the APK. */
private data class Library(val name: String, val coordinates: String, val licenses: String)

private fun loadLibraries(context: Context): List<Library> {
    val json = context.assets.open("app/cash/licensee/artifacts.json").bufferedReader().use { it.readText() }
    val array = JSONArray(json)
    return (0 until array.length()).map { index ->
        val artifact = array.getJSONObject(index)
        val spdx = artifact.optJSONArray("spdxLicenses") ?: JSONArray()
        val other = artifact.optJSONArray("unknownLicenses") ?: JSONArray()
        val licenses = (0 until spdx.length()).map { spdx.getJSONObject(it).getString("name") } +
            (0 until other.length()).map { other.getJSONObject(it).optString("name", other.getJSONObject(it).optString("url")) }
        Library(
            name = artifact.optString("name").ifEmpty { artifact.getString("artifactId") },
            coordinates = "${artifact.getString("groupId")}:${artifact.getString("artifactId")}:${artifact.getString("version")}",
            licenses = licenses.joinToString(", "),
        )
    }.sortedBy { it.name.lowercase() }
}

private fun loadText(context: Context, asset: String): String = context.assets.open(asset).bufferedReader().use { it.readText() }

/** "Open source licences": this app's licence, Moblin's notice and every bundled library. */
@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val libraries = remember { loadLibraries(context) }
    val license = remember { loadText(context, "LICENSE") }
    val notice = remember { loadText(context, "NOTICE") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.open_source_licenses)) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                item { Text(license, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
                item { Text(notice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
                items(libraries) { library ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(library.name, style = MaterialTheme.typography.bodyMedium)
                        Text(library.coordinates, style = MaterialTheme.typography.bodySmall)
                        Text(library.licenses, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}
