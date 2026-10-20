// Port of Moblin/View/Settings/Chat/ChatSettingsView.swift @97e26d2 (v1 subset: appearance and
// maximum age; filters, text to speech and the bot come later).
package io.github.mesmerize08.nexusirl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.settings.SettingsChat
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import kotlin.math.roundToInt

/** Chat settings: how the chat over the preview looks (upstream `database.chat`). */
@Composable
fun ChatSettingsScreen(store: SettingsStore, onBack: () -> Unit) {
    val database by store.database.collectAsState()
    val chat = database.chat
    fun update(transform: (SettingsChat) -> SettingsChat) = store.update { it.copy(chat = transform(it.chat)) }
    Page(title = stringResource(R.string.chat), onBack = onBack) {
        SwitchRow(stringResource(R.string.enabled), chat.enabled) { on -> update { it.copy(enabled = on) } }
        SliderRow(stringResource(R.string.font_size), chat.fontSize, 10f..40f, "${chat.fontSize.roundToInt()}") { size ->
            update { it.copy(fontSize = size.roundToInt().toFloat()) }
        }
        SliderRow(stringResource(R.string.width), chat.width.toFloat(), 0.1f..1f, "${(chat.width * 100).roundToInt()} %") { width ->
            update { it.copy(width = (width * 20).roundToInt() / 20.0) }
        }
        SliderRow(stringResource(R.string.height), chat.height.toFloat(), 0.1f..1f, "${(chat.height * 100).roundToInt()} %") { height ->
            update { it.copy(height = (height * 20).roundToInt() / 20.0) }
        }
        ColorChoice(stringResource(R.string.username_color), chat.usernameColor) { color -> update { it.copy(usernameColor = color) } }
        SwitchRow(stringResource(R.string.same_username_color), chat.sameUsernameColor) { on -> update { it.copy(sameUsernameColor = on) } }
        ColorChoice(stringResource(R.string.text_color), chat.messageColor) { color -> update { it.copy(messageColor = color) } }
        SwitchRow(
            stringResource(R.string.background),
            chat.backgroundColorEnabled,
        ) { on -> update { it.copy(backgroundColorEnabled = on) } }
        if (chat.backgroundColorEnabled) {
            ColorChoice(stringResource(R.string.background_color), chat.backgroundColor) { color ->
                update { it.copy(backgroundColor = color) }
            }
        }
        SwitchRow(stringResource(R.string.border), chat.shadowColorEnabled) { on -> update { it.copy(shadowColorEnabled = on) } }
        SwitchRow(stringResource(R.string.bold_username), chat.boldUsername) { on -> update { it.copy(boldUsername = on) } }
        SwitchRow(stringResource(R.string.bold_message), chat.boldMessage) { on -> update { it.copy(boldMessage = on) } }
        SwitchRow(stringResource(R.string.mirrored), chat.mirrored) { on -> update { it.copy(mirrored = on) } }
        SwitchRow(stringResource(R.string.maximum_age), chat.maximumAgeEnabled) { on -> update { it.copy(maximumAgeEnabled = on) } }
        if (chat.maximumAgeEnabled) {
            SliderRow(stringResource(R.string.maximum_age), chat.maximumAge.toFloat(), 5f..300f, "${chat.maximumAge} s") { age ->
                update { it.copy(maximumAge = (age / 5).roundToInt() * 5) }
            }
        }
    }
}
