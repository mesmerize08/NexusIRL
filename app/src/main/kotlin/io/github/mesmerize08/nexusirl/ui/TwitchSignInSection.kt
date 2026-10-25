package io.github.mesmerize08.nexusirl.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.twitch.TwitchAccount
import io.github.mesmerize08.nexusirl.twitch.TwitchSignIn
import java.util.UUID

/**
 * Sign in to Twitch for a saved stream with the Device Code flow: the app shows a code, the user
 * enters it at twitch.tv/activate on any device. [onSignedIn] gets the channel name and id.
 */
@Composable
fun TwitchSignInSection(store: SettingsStore, streamId: UUID?, onSignedIn: (name: String, id: String) -> Unit) {
    val context = LocalContext.current
    val database by store.database.collectAsState()
    val signIn by TwitchAccount.signIn.collectAsState()
    if (!TwitchAccount.isAvailable) {
        return
    }
    if (streamId == null) {
        Text(stringResource(R.string.twitch_save_first), style = MaterialTheme.typography.bodySmall)
        return
    }
    val stream = database.streams.firstOrNull { it.id == streamId } ?: return
    LaunchedEffect(signIn) {
        if (signIn is TwitchSignIn.Done) {
            onSignedIn(stream.twitchChannelName, stream.twitchChannelId)
        }
    }
    if (stream.twitchLoggedIn) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.twitch_signed_in_as, stream.twitchChannelName))
            OutlinedButton(onClick = { TwitchAccount.signOut(context, streamId) }) { Text(stringResource(R.string.sign_out)) }
        }
    } else {
        Text(stringResource(R.string.twitch_sign_in_help), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { TwitchAccount.startSignIn(context, streamId) }) { Text(stringResource(R.string.twitch_sign_in)) }
    }
    (signIn as? TwitchSignIn.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
    val waiting = signIn as? TwitchSignIn.Waiting
    if (waiting != null && waiting.streamId == streamId) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.twitch_sign_in)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.twitch_enter_code))
                    Text(
                        waiting.code.userCode,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 32.sp,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(stringResource(R.string.twitch_waiting), style = MaterialTheme.typography.bodySmall)
                    // Found on a Galaxy S26: after approving, the Twitch app or browser shows a blank page.
                    Text(stringResource(R.string.twitch_come_back), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                // On this phone: the link already contains the code.
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, waiting.code.verificationUri.toUri()))
                }) { Text(stringResource(R.string.twitch_open_activate)) }
            },
            dismissButton = { TextButton(onClick = { TwitchAccount.cancelSignIn() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
