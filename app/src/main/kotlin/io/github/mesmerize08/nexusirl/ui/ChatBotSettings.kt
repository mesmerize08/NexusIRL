// Port of Moblin/View/Settings/Chat/ChatBotSettingsView.swift @97e26d2 (the commands NexusIRL runs,
// their permissions, and aliases).
package io.github.mesmerize08.nexusirl.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.settings.ChatBotSnapshotSource
import io.github.mesmerize08.nexusirl.settings.ChatBotSnapshotWebRemoteControlMode
import io.github.mesmerize08.nexusirl.settings.SettingsChat
import io.github.mesmerize08.nexusirl.settings.SettingsChatBotAlias
import io.github.mesmerize08.nexusirl.settings.SettingsChatBotCommandSnapshot
import io.github.mesmerize08.nexusirl.settings.SettingsChatBotPermissions
import io.github.mesmerize08.nexusirl.settings.SettingsChatBotPermissionsCommand

/** A command group: what it does, its permissions in the settings, and how to change them. */
private class BotCommand(
    val usage: String,
    val get: (SettingsChatBotPermissions) -> SettingsChatBotPermissionsCommand,
    val set: (SettingsChatBotPermissions, SettingsChatBotPermissionsCommand) -> SettingsChatBotPermissions,
    val isSnapshot: Boolean = false,
)

private val COMMANDS = listOf(
    BotCommand("!moblin scene <name>", { it.scene }, { p, c -> p.copy(scene = c) }),
    BotCommand("!moblin mute, !moblin unmute", { it.audio }, { p, c -> p.copy(audio = c) }),
    BotCommand("!moblin zoom <x>", { it.zoom }, { p, c -> p.copy(zoom = c) }),
    BotCommand("!moblin torch on, !moblin torch off", { it.torch }, { p, c -> p.copy(torch = c) }),
    BotCommand(
        "!moblin filter <grayscale, sepia, pixellate, 4:3, whirlpool or pinch> <on or off>",
        { it.filter },
        { p, c -> p.copy(filter = c) },
    ),
    BotCommand("!moblin snapshot", { it.snapshot }, { p, c -> p.copy(snapshot = c) }, isSnapshot = true),
    BotCommand(
        "!moblin stream start, stop, title <title> or category <category name>",
        { it.stream },
        { p, c -> p.copy(stream = c) },
    ),
)

private val COOLDOWNS = listOf<Int?>(null, 1, 2, 3, 5, 10, 15, 30, 60)

/** Upstream `ChatBotAliasSettingsView.onAliasChange`. */
private fun aliasError(value: String): Int? = when {
    value.isEmpty() -> R.string.alias_empty
    !value.startsWith("!") -> R.string.alias_start
    value.length <= 1 -> R.string.alias_short
    value.startsWith("!moblin") -> R.string.alias_moblin
    value.split(" ").filter { it.isNotEmpty() }.size != 1 -> R.string.alias_one_word
    else -> null
}

/** Upstream `ChatBotAliasSettingsView.onReplacementChange`. */
private fun replacementError(value: String): Int? = when {
    value.isEmpty() -> R.string.replacement_empty
    !value.startsWith("!moblin") -> R.string.replacement_start
    value.split(" ").filter { it.isNotEmpty() }.size <= 1 -> R.string.replacement_words
    else -> null
}

/** The chat bot part of the chat settings. */
@Composable
fun ChatBotSettings(chat: SettingsChat, resolve: (String) -> String, update: ((SettingsChat) -> SettingsChat) -> Unit) {
    var editing by remember { mutableStateOf<BotCommand?>(null) }
    var addingAlias by remember { mutableStateOf(false) }
    Text(stringResource(R.string.bot), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.chat_bot_help), style = MaterialTheme.typography.bodySmall)
    SwitchRow(stringResource(R.string.enabled), chat.botEnabled) { on -> update { it.copy(botEnabled = on) } }
    Text(stringResource(R.string.commands), style = MaterialTheme.typography.titleSmall)
    for (command in COMMANDS) {
        TextButton(onClick = { editing = command }, modifier = Modifier.fillMaxWidth()) {
            Text(command.usage, modifier = Modifier.fillMaxWidth())
        }
    }
    Text(stringResource(R.string.aliases), style = MaterialTheme.typography.titleSmall)
    for ((index, alias) in chat.aliases.withIndex()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("${alias.alias} → ${alias.replacement}", modifier = Modifier.weight(1f))
            TextButton(onClick = { update { it.copy(aliases = it.aliases.filterIndexed { i, _ -> i != index }) } }) {
                Text(stringResource(R.string.delete))
            }
        }
    }
    TextButton(onClick = { addingAlias = true }) { Text(stringResource(R.string.add)) }

    editing?.let { command ->
        // The snapshot command can also take its picture from another phone (upstream's source).
        var snapshot by remember(command) {
            mutableStateOf(chat.botCommandSnapshot.copy(webRemoteControlUrl = resolve(chat.botCommandSnapshot.webRemoteControlUrl)))
        }
        val isSnapshot = command.isSnapshot
        PermissionsDialog(
            command.usage,
            command.get(chat.botCommandPermissions),
            onDismiss = {
                if (isSnapshot) {
                    update { it.copy(botCommandSnapshot = snapshot) }
                }
                editing = null
            },
            extra = if (isSnapshot) {
                { SnapshotSource(snapshot) { snapshot = it } }
            } else {
                {}
            },
        ) { changed ->
            update { it.copy(botCommandPermissions = command.set(it.botCommandPermissions, changed)) }
        }
    }
    if (addingAlias) {
        AliasDialog(onDismiss = { addingAlias = false }) { alias ->
            update { it.copy(aliases = it.aliases + alias) }
            addingAlias = false
        }
    }
}

/** Upstream `PermissionsSettingsInnerView`. Changes apply at once. */
@Composable
private fun PermissionsDialog(
    title: String,
    permissions: SettingsChatBotPermissionsCommand,
    onDismiss: () -> Unit,
    extra: @Composable () -> Unit = {},
    onChange: (SettingsChatBotPermissionsCommand) -> Unit,
) {
    // Follows the edits, since the settings flow back in only after recomposition.
    var current by remember { mutableStateOf(permissions) }
    fun change(changed: SettingsChatBotPermissionsCommand) {
        current = changed
        onChange(changed)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                extra()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.permissions), style = MaterialTheme.typography.labelLarge)
                SwitchRow(stringResource(R.string.moderators), current.moderatorsEnabled) { change(current.copy(moderatorsEnabled = it)) }
                SwitchRow(
                    stringResource(R.string.subscribers),
                    current.subscribersEnabled,
                ) { change(current.copy(subscribersEnabled = it)) }
                if (current.subscribersEnabled) {
                    ChoiceField(
                        stringResource(
                            R.string.minimum_subscriber_tier,
                        ),
                        current.minimumSubscriberTier,
                        listOf(3, 2, 1),
                        { "$it" },
                        {
                            change(current.copy(minimumSubscriberTier = it))
                        },
                    )
                }
                SwitchRow(stringResource(R.string.others), current.othersEnabled) { change(current.copy(othersEnabled = it)) }
                val none = stringResource(R.string.cooldown_none)
                ChoiceField(
                    stringResource(
                        R.string.cooldown,
                    ),
                    current.cooldown,
                    COOLDOWNS,
                    { it?.let { seconds -> "${seconds}s" } ?: none },
                    {
                        change(current.copy(cooldown = it))
                    },
                )
                Text(stringResource(R.string.cooldown_footer), style = MaterialTheme.typography.bodySmall)
                SwitchRow(
                    stringResource(R.string.send_chat_responses),
                    current.sendChatMessages,
                ) { change(current.copy(sendChatMessages = it)) }
                Text(stringResource(R.string.send_chat_responses_footer), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) } },
    )
}

/** Upstream `SnapshotPermissionsSettingsView`'s source part. The URL is kept secret on save. */
@Composable
private fun SnapshotSource(snapshot: SettingsChatBotCommandSnapshot, onChange: (SettingsChatBotCommandSnapshot) -> Unit) {
    val sources = mapOf(
        ChatBotSnapshotSource.THIS_DEVICE to stringResource(R.string.this_device),
        ChatBotSnapshotSource.WEB_REMOTE_CONTROL to stringResource(R.string.web_remote_control),
    )
    ChoiceField(stringResource(R.string.source), snapshot.source, sources.keys.toList(), { sources.getValue(it) }, {
        onChange(snapshot.copy(source = it))
    })
    if (snapshot.source == ChatBotSnapshotSource.WEB_REMOTE_CONTROL) {
        val modes = mapOf(
            ChatBotSnapshotWebRemoteControlMode.FALLBACK_WHEN_NOT_LIVE to stringResource(R.string.when_not_live),
            ChatBotSnapshotWebRemoteControlMode.ALWAYS to stringResource(R.string.always),
        )
        ChoiceField(
            stringResource(
                R.string.use_remote_source,
            ),
            snapshot.webRemoteControlMode,
            modes.keys.toList(),
            { modes.getValue(it) },
            {
                onChange(snapshot.copy(webRemoteControlMode = it))
            },
        )
        SecretTextField(
            stringResource(R.string.url),
            snapshot.webRemoteControlUrl,
            { onChange(snapshot.copy(webRemoteControlUrl = it.trim())) },
        )
        Text(stringResource(R.string.snapshot_source_token_help), style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.snapshot_source_help), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AliasDialog(onDismiss: () -> Unit, onAdd: (SettingsChatBotAlias) -> Unit) {
    var alias by remember { mutableStateOf("!") }
    var replacement by remember { mutableStateOf("!moblin ") }
    val aliasError = aliasError(alias.trim())
    val replacementError = replacementError(replacement.trim())
    val noCapitals = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alias)) },
        text = {
            Column {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text(stringResource(R.string.alias)) },
                    singleLine = true,
                    isError = aliasError != null,
                    supportingText = aliasError?.let { { Text(stringResource(it)) } },
                    keyboardOptions = noCapitals,
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text(stringResource(R.string.replacement)) },
                    singleLine = true,
                    isError = replacementError != null,
                    supportingText = replacementError?.let { { Text(stringResource(it)) } },
                    keyboardOptions = noCapitals,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(SettingsChatBotAlias(alias.trim(), replacement.trim())) },
                enabled = aliasError == null && replacementError == null,
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
