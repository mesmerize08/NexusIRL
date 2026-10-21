// Port of the streamer part of Moblin/View/Settings/RemoteControl/RemoteControlSettingsView.swift
// @97e26d2.
package io.github.mesmerize08.nexusirl.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.core.websocket.CleartextPolicy
import io.github.mesmerize08.nexusirl.remote.RemoteControlAssistantController
import io.github.mesmerize08.nexusirl.remote.RemoteControlConnection
import io.github.mesmerize08.nexusirl.remote.RemoteControlController
import io.github.mesmerize08.nexusirl.scene.WidgetBitmaps
import io.github.mesmerize08.nexusirl.settings.SettingsRemoteControl
import io.github.mesmerize08.nexusirl.settings.SettingsRemoteControlStreamer
import io.github.mesmerize08.nexusirl.settings.SettingsRemoteControlWeb
import io.github.mesmerize08.nexusirl.settings.SettingsStore

private val PREVIEW_FPS_CHOICES = listOf(0f, 0.5f, 1f, 2f, 5f)

/** Lets an assistant (another phone running Moblin, or a compatible app) control this one. */
@Composable
fun RemoteControlScreen(store: SettingsStore, onBack: () -> Unit) {
    val database by store.database.collectAsState()
    val connection by RemoteControlController.connection.collectAsState()
    val streamer = database.remoteControl.streamer
    // The password lives in the secret store; it is read only while this screen is open.
    var password by remember { mutableStateOf(store.remoteControlPassword()) }
    fun update(transform: (SettingsRemoteControlStreamer) -> SettingsRemoteControlStreamer) =
        store.update { it.copy(remoteControl = it.remoteControl.copy(streamer = transform(it.remoteControl.streamer))) }
    Page(title = stringResource(R.string.remote_control), onBack = onBack) {
        Text(stringResource(R.string.remote_control_help), style = MaterialTheme.typography.bodySmall)
        SwitchRow(stringResource(R.string.enabled), streamer.enabled) { on -> update { it.copy(enabled = on) } }
        Text(
            stringResource(
                when (connection) {
                    RemoteControlConnection.OFF -> R.string.remote_control_off
                    RemoteControlConnection.CONNECTING -> R.string.remote_control_connecting
                    RemoteControlConnection.CONNECTED -> R.string.remote_control_connected
                    RemoteControlConnection.WRONG_PASSWORD -> R.string.remote_control_wrong_password
                },
            ),
            color = if (connection == RemoteControlConnection.WRONG_PASSWORD) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        OutlinedTextField(
            value = streamer.url,
            onValueChange = { url -> update { it.copy(url = url.trim()) } },
            label = { Text(stringResource(R.string.assistant_url)) },
            supportingText = { Text(stringResource(R.string.assistant_url_help)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (streamer.url.startsWith("ws://", ignoreCase = true) && !isLocalAddress(streamer.url)) {
            // docs/PORT_PLAN.md §9.6: the password hash can be sniffed and brute forced over plain ws://.
            Text(
                stringResource(R.string.assistant_url_unencrypted),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SecretTextField(
            label = stringResource(R.string.password),
            value = password,
            onValueChange = { password = it.trim() },
            supportingText = stringResource(R.string.remote_control_password_help),
        )
        OutlinedButton(
            enabled = password.isNotEmpty() && password != store.remoteControlPassword(),
            onClick = { store.update { it.copy(remoteControl = it.remoteControl.copy(password = password)) } },
        ) { Text(stringResource(R.string.save_password)) }
        OutlinedButton(onClick = { password = SettingsRemoteControl.generatePassword() }) {
            Text(stringResource(R.string.generate_password))
        }
        HorizontalDivider()
        ChoiceField(
            stringResource(
                R.string.preview_fps,
            ),
            streamer.previewFps,
            PREVIEW_FPS_CHOICES,
            { if (it == 0f) "Off" else "$it" },
            { fps ->
                update { it.copy(previewFps = fps) }
            },
        )
        HorizontalDivider()
        WebRemoteControlSection(store)
    }
}

/** Whether plain ws:// may be used: loopback, LAN and Tailscale addresses (CleartextPolicy). */
private fun isLocalAddress(url: String): Boolean {
    val host = url.substringAfter("://").substringBefore('/').substringBeforeLast(':')
    return CleartextPolicy.isLoopback(host) || CleartextPolicy.isLocalNetwork(host)
}

/**
 * Upstream's web remote control, with Android's access token (docs/PORT_PLAN.md §9.6): a browser
 * on the same Wi-Fi opens the link once; making a new link signs every browser out.
 */
@Composable
fun WebRemoteControlSection(store: SettingsStore) {
    val database by store.database.collectAsState()
    val running by RemoteControlController.webRunning.collectAsState()
    val web = database.remoteControl.web
    Text(stringResource(R.string.web_remote_control), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.web_remote_control_help), style = MaterialTheme.typography.bodySmall)
    SwitchRow(stringResource(R.string.enabled), web.enabled) { on ->
        store.update { database ->
            val current = database.remoteControl.web
            // A token is made on first use; it is a secret (moved to the secret store on save).
            val token = if (on && current.token.isEmpty()) SettingsRemoteControlWeb.generateToken() else current.token
            database.copy(remoteControl = database.remoteControl.copy(web = current.copy(enabled = on, token = token)))
        }
    }
    if (!web.enabled) {
        return
    }
    if (!running) {
        Text(stringResource(R.string.web_remote_control_not_running, web.androidPort), color = MaterialTheme.colorScheme.error)
        return
    }
    val token = store.webControlToken()
    val links = RemoteControlAssistantController.localAddresses().map { "http://$it:${web.androidPort}/?t=$token" }
    if (links.isEmpty()) {
        Text(stringResource(R.string.web_remote_control_no_network))
        return
    }
    Text(stringResource(R.string.web_remote_control_open), style = MaterialTheme.typography.bodySmall)
    for (link in links) {
        Text(link, style = MaterialTheme.typography.bodyMedium)
    }
    val qr = remember(links.first()) { WidgetBitmaps.qrCode(links.first(), 400) }
    qr?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(200.dp)) }
    OutlinedButton(onClick = {
        store.update {
            it.copy(
                remoteControl = it.remoteControl.copy(web = it.remoteControl.web.copy(token = SettingsRemoteControlWeb.generateToken())),
            )
        }
    }) { Text(stringResource(R.string.web_remote_control_new_link)) }
}
