// Port of the Twitch title and category settings (Moblin/View/Settings/Streams/Stream/Twitch) @97e26d2.
package io.github.mesmerize08.nexusirl.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchCategory
import io.github.mesmerize08.nexusirl.settings.SettingsStream
import io.github.mesmerize08.nexusirl.twitch.TwitchChannel
import kotlinx.coroutines.delay

/** Twitch allows titles up to 140 characters. */
private const val MAXIMUM_TITLE_LENGTH = 140

/**
 * The signed-in channel's title and category: loaded from Twitch, edited here, and changed on the
 * channel only after a confirmation, since viewers see it at once.
 */
@Composable
fun TwitchChannelSection(stream: SettingsStream) {
    if (!stream.twitchLoggedIn || stream.twitchChannelId.isBlank()) {
        return
    }
    val context = LocalContext.current
    var loaded by remember(stream.id) { mutableStateOf(false) }
    var title by remember(stream.id) { mutableStateOf("") }
    var category by remember(stream.id) { mutableStateOf<TwitchCategory?>(null) }
    var query by remember(stream.id) { mutableStateOf("") }
    var results by remember(stream.id) { mutableStateOf(emptyList<TwitchCategory>()) }
    var message by remember(stream.id) { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(stream.id) {
        TwitchChannel.load(context, stream.id, stream.twitchChannelId) { info, failure ->
            loaded = info != null
            info?.let {
                title = it.title
                category = TwitchCategory(it.categoryId, it.categoryName).takeIf { c -> c.id.isNotEmpty() }
            }
            message = failure?.message
        }
    }
    // Search as the user types, after a short pause.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(400)
        TwitchChannel.searchCategories(context, stream.id, query.trim()) { found, failure ->
            results = found.orEmpty()
            failure?.let { message = it.message }
        }
    }

    Text(stringResource(R.string.twitch_channel_info), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.twitch_channel_info_help), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = title,
        onValueChange = { title = it.take(MAXIMUM_TITLE_LENGTH) },
        label = { Text(stringResource(R.string.twitch_title)) },
        supportingText = { Text("${title.length}/$MAXIMUM_TITLE_LENGTH") },
        enabled = loaded,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.twitch_category_current, category?.name ?: "-"))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(stringResource(R.string.twitch_category_search)) },
        singleLine = true,
        enabled = loaded,
        modifier = Modifier.fillMaxWidth(),
    )
    if (results.isNotEmpty()) {
        Column {
            for (result in results) {
                Text(
                    result.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            category = result
                            query = ""
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                )
            }
        }
    }
    Button(onClick = { confirming = true }, enabled = loaded && !busy && title.isNotBlank()) {
        Text(stringResource(R.string.twitch_update_channel))
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.twitch_update_channel)) },
            text = { Text(stringResource(R.string.twitch_update_confirm, stream.twitchChannelName, title, category?.name ?: "-")) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    busy = true
                    TwitchChannel.update(context, stream.id, stream.twitchChannelId, title.trim(), category?.id) { failure ->
                        busy = false
                        message = failure?.message
                        if (failure == null) {
                            android.widget.Toast.makeText(
                                context,
                                R.string.twitch_channel_updated,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }) { Text(stringResource(R.string.twitch_update_channel)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
