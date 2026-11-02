// Port of Moblin/View/Settings/Moblink/MoblinkSettingsView.swift @97e26d2.
package io.github.mesmerize08.nexusirl.ui

import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.moblink.MoblinkController
import io.github.mesmerize08.nexusirl.moblink.MoblinkRelayState
import io.github.mesmerize08.nexusirl.moblink.MoblinkStreamerController
import io.github.mesmerize08.nexusirl.remote.RemoteControlAssistantController
import io.github.mesmerize08.nexusirl.settings.SettingsMoblinkRelay
import io.github.mesmerize08.nexusirl.settings.SettingsStore

/**
 * Moblink (upstream MoblinkSettingsView). The streamer part comes first, with the shared password
 * above both: in the field test of 2026-11-02 the relay part, which came first and held the
 * password, was set up on the streaming phone instead, so the phones' roles were swapped.
 */
@Composable
fun MoblinkScreen(store: SettingsStore, onBack: () -> Unit) {
    var password by remember { mutableStateOf(store.moblinkPassword()) }
    Page(title = stringResource(R.string.moblink), onBack = onBack) {
        Text(stringResource(R.string.moblink_intro), style = MaterialTheme.typography.bodySmall)
        SecretTextField(
            label = stringResource(R.string.password),
            value = password,
            onValueChange = { password = it.trim() },
            supportingText = stringResource(R.string.moblink_shared_password_help),
        )
        OutlinedButton(
            enabled = password.isNotEmpty() && password != store.moblinkPassword(),
            onClick = { store.update { it.copy(moblink = it.moblink.copy(password = password)) } },
        ) { Text(stringResource(R.string.save_password)) }
        HorizontalDivider()
        MoblinkStreamerSection(store)
        HorizontalDivider()
        MoblinkRelaySection(store)
    }
}

/** Lend this phone's mobile data to a Moblin streamer on the same Wi-Fi (upstream Moblink relay). */
@Composable
private fun MoblinkRelaySection(store: SettingsStore) {
    val database by store.database.collectAsState()
    val state by MoblinkController.state.collectAsState()
    val streamers by MoblinkController.streamers.collectAsState()
    val relay = database.moblink.relay
    fun update(transform: (SettingsMoblinkRelay) -> SettingsMoblinkRelay) =
        store.update { it.copy(moblink = it.moblink.copy(relay = transform(it.moblink.relay))) }
    Text(stringResource(R.string.moblink_relay), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.moblink_relay_help), style = MaterialTheme.typography.bodySmall)
    SwitchRow(stringResource(R.string.enabled), relay.enabled) { on -> update { it.copy(enabled = on) } }
    if (relay.enabled) {
        Text(stringResource(R.string.moblink_relay_warning), color = MaterialTheme.colorScheme.tertiary)
    }
    if (relay.enabled && database.moblink.password.isEmpty()) {
        Text(stringResource(R.string.moblink_password_needed), color = MaterialTheme.colorScheme.error)
    }
    state?.let {
        Text(
            it.text,
            color = if (it == MoblinkRelayState.WRONG_PASSWORD || it == MoblinkRelayState.UNKNOWN_ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
    for (streamer in streamers) {
        Text(stringResource(R.string.moblink_found_streamer, streamer.name), style = MaterialTheme.typography.bodySmall)
    }
    OutlinedTextField(
        value = relay.name,
        onValueChange = { name -> update { it.copy(name = name) } },
        label = { Text(stringResource(R.string.moblink_relay_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SwitchRow(stringResource(R.string.moblink_manual), relay.manual) { on -> update { it.copy(manual = on) } }
    if (relay.manual) {
        OutlinedTextField(
            value = relay.url,
            onValueChange = { url -> update { it.copy(url = url.trim()) } },
            label = { Text(stringResource(R.string.moblink_streamer_url)) },
            supportingText = { Text(stringResource(R.string.moblink_streamer_url_help)) },
            isError = relay.url.isNotEmpty() && !SettingsMoblinkRelay.isValidWebSocketUrl(relay.url),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Upstream's Moblink streamer: other phones on the Wi-Fi lend their mobile data while live with SRTLA. */
@Composable
fun MoblinkStreamerSection(store: SettingsStore) {
    val database by store.database.collectAsState()
    val relays by MoblinkStreamerController.relays.collectAsState()
    val streamer = database.moblink.streamer
    Text(stringResource(R.string.moblink_streamer), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.moblink_streamer_help), style = MaterialTheme.typography.bodySmall)
    SwitchRow(stringResource(R.string.enabled), streamer.enabled) { on ->
        store.update { it.copy(moblink = it.moblink.copy(streamer = it.moblink.streamer.copy(enabled = on))) }
    }
    if (streamer.enabled && database.moblink.password.isEmpty()) {
        Text(stringResource(R.string.moblink_streamer_password_needed), color = MaterialTheme.colorScheme.error)
    }
    val connected = relays ?: return
    // For a relay that does not find this phone by itself (upstream relays can enter it by hand).
    val urls = remember(connected) { RemoteControlAssistantController.localUrls(streamer.port) }
    if (urls.isNotEmpty()) {
        Text(stringResource(R.string.moblink_streamer_addresses, urls.joinToString()), style = MaterialTheme.typography.bodySmall)
    }
    if (connected.isEmpty()) {
        Text(stringResource(R.string.moblink_streamer_no_relays))
    }
    for (relay in connected) {
        val battery = relay.batteryPercentage?.let { " · $it %" }.orEmpty()
        val thermal = relay.thermalState?.let { " · ${it.rawValue}" }.orEmpty()
        Text("${relay.name}$battery$thermal")
    }
}
