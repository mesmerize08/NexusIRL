// Port of the Kick sign-in, "send messages to" and title/category settings
// (Moblin/View/Settings/Streams/Stream/Kick/StreamKickSettingsView.swift) @97e26d2.
package io.github.mesmerize08.nexusirl.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.chat.kick.KickCategory
import io.github.mesmerize08.nexusirl.kick.KickAccount
import io.github.mesmerize08.nexusirl.kick.KickChannel
import io.github.mesmerize08.nexusirl.kick.KickLoginActivity
import io.github.mesmerize08.nexusirl.kick.KickSignIn
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.SettingsStream
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Sign in to Kick for a saved stream, on Kick's own page ([KickLoginActivity]). [onSignedIn] gets
 * the stream as saved, with the signed-in user's channel.
 */
@Composable
fun KickSignInSection(store: SettingsStore, streamId: UUID?, onSignedIn: (SettingsStream) -> Unit) {
    val context = LocalContext.current
    val database by store.database.collectAsState()
    val signIn by KickAccount.signIn.collectAsState()
    if (streamId == null) {
        Text(stringResource(R.string.kick_save_first), style = MaterialTheme.typography.bodySmall)
        return
    }
    val stream = database.streams.firstOrNull { it.id == streamId } ?: return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val token = result.data?.getStringExtra(KickLoginActivity.EXTRA_TOKEN)
        if (result.resultCode == Activity.RESULT_OK && token != null) {
            KickAccount.signedIn(context, streamId, token)
        }
    }
    LaunchedEffect(stream.kickLoggedIn, stream.kickChannelId) {
        if (stream.kickLoggedIn) {
            onSignedIn(stream)
        }
    }
    if (stream.kickLoggedIn) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.kick_signed_in_as, stream.kickChannelName))
            OutlinedButton(onClick = { KickAccount.signOut(context, streamId) }) { Text(stringResource(R.string.sign_out)) }
        }
    } else {
        Text(stringResource(R.string.kick_sign_in_help), style = MaterialTheme.typography.bodySmall)
        Button(
            enabled = signIn != KickSignIn.Finishing,
            onClick = { launcher.launch(Intent(context, KickLoginActivity::class.java)) },
        ) { Text(stringResource(R.string.kick_sign_in)) }
    }
    if (signIn == KickSignIn.Finishing) {
        Text(stringResource(R.string.kick_signing_in), style = MaterialTheme.typography.bodySmall)
    }
    (signIn as? KickSignIn.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
}

/**
 * The signed-in Kick channel's title and category: loaded from Kick, edited here, and changed on
 * the channel only after a confirmation, since viewers see it at once.
 */
@Composable
fun KickChannelSection(stream: SettingsStream) {
    if (!stream.kickLoggedIn || stream.kickSlug == null) {
        return
    }
    val context = LocalContext.current
    var loaded by remember(stream.id) { mutableStateOf(false) }
    var title by remember(stream.id) { mutableStateOf("") }
    var categoryName by remember(stream.id) { mutableStateOf<String?>(null) }
    var category by remember(stream.id) { mutableStateOf<KickCategory?>(null) }
    var query by remember(stream.id) { mutableStateOf("") }
    var results by remember(stream.id) { mutableStateOf(emptyList<KickCategory>()) }
    var message by remember(stream.id) { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(stream.id) {
        KickChannel.load(context, stream.id) { info, failure ->
            loaded = info != null
            info?.let {
                title = it.title
                categoryName = it.categoryName
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
        KickChannel.searchCategories(context, stream.id, query.trim()) { found, failure ->
            results = found.orEmpty()
            failure?.let { message = it.message }
        }
    }

    Text(stringResource(R.string.kick_channel_info), style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text(stringResource(R.string.twitch_title)) },
        enabled = loaded,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.twitch_category_current, category?.name ?: categoryName ?: "-"))
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
        Text(stringResource(R.string.kick_update_channel))
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.kick_update_channel)) },
            text = {
                Text(stringResource(R.string.kick_update_confirm, stream.kickChannelName, title, category?.name ?: categoryName ?: "-"))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    busy = true
                    KickChannel.update(context, stream.id, title.trim(), category) { failure ->
                        busy = false
                        message = failure?.message
                        if (failure == null) {
                            category?.let { categoryName = it.name }
                            android.widget.Toast.makeText(context, R.string.kick_channel_updated, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text(stringResource(R.string.kick_update_channel)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
