// Port of Moblin/View/Stream/Overlay/StreamOverlayChatView.swift and View/Utils/ChatLineStyle.swift
// @97e26d2 (v1 subset: no interactive chat, badges or timestamps yet).
package io.github.mesmerize08.nexusirl.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.chat.ChatController
import io.github.mesmerize08.nexusirl.chat.ChatPost
import io.github.mesmerize08.nexusirl.chat.EmoteImages
import io.github.mesmerize08.nexusirl.settings.RgbColor
import io.github.mesmerize08.nexusirl.settings.SettingsChat
import io.github.mesmerize08.nexusirl.settings.SettingsStore

private fun RgbColor.color() = Color(argb())

/** The chat over the preview (upstream `StreamOverlayChatView`), newest message at the bottom. */
@Composable
fun ChatOverlay(settings: SettingsChat, modifier: Modifier = Modifier) {
    if (!settings.enabled) {
        return
    }
    val posts by ChatController.posts.collectAsState()
    // Re-read emote images as they arrive.
    EmoteImages.version.collectAsState().value
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        LazyColumn(
            modifier = Modifier
                .width(maxWidth * settings.width.toFloat().coerceIn(0.1f, 1f))
                .height(maxHeight * settings.height.toFloat().coerceIn(0.1f, 1f))
                .scale(scaleX = if (settings.mirrored) -1f else 1f, scaleY = 1f),
            reverseLayout = true,
            userScrollEnabled = false,
        ) {
            items(posts) { post -> ChatLine(post, settings) }
        }
    }
}

@Composable
private fun ChatLine(post: ChatPost, settings: SettingsChat) {
    val shadow = if (settings.shadowColorEnabled) Shadow(settings.shadowColor.color(), Offset(1.5f, 1.5f), 2f) else null
    val style = TextStyle(fontSize = settings.fontSize.sp, shadow = shadow)
    val background = if (settings.backgroundColorEnabled) Modifier.background(settings.backgroundColor.color()) else Modifier
    val highlight = post.highlight
    Row(modifier = background.height(IntrinsicSize.Min).padding(vertical = 1.dp)) {
        if (highlight != null) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(highlight.barColor?.color() ?: Color.Transparent),
            )
        }
        Column(modifier = Modifier.padding(start = if (highlight != null) 4.dp else 0.dp)) {
            if (highlight != null) {
                Text(highlight.title, style = style.copy(color = settings.messageColor.color(), fontSize = (settings.fontSize * 0.8f).sp))
            }
            val (text, inline) = chatLineText(post, settings)
            Text(text, style = style, inlineContent = inline)
        }
    }
}

/** Upstream `ChatLineStyle.makeContent`: "user: message" with emotes as inline images. */
private fun chatLineText(post: ChatPost, settings: SettingsChat): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val usernameColor = (if (settings.sameUsernameColor) null else post.userColor) ?: settings.usernameColor
    val messageColor = if (post.isAction) usernameColor else settings.messageColor
    val inline = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
        val bold = SpanStyle(fontWeight = FontWeight.Bold)
        withStyle(SpanStyle(color = usernameColor.color(), fontWeight = if (settings.boldUsername) FontWeight.Bold else null)) {
            append(post.displayName)
        }
        if (!post.isAction) {
            append(": ")
        } else {
            append(" ")
        }
        withStyle(SpanStyle(color = messageColor.color()).merge(if (settings.boldMessage) bold else SpanStyle())) {
            for (segment in post.segments) {
                val emote = segment.url ?: segment.bigGifUrl
                val url = emote?.url(animated = false)
                val bitmap = url?.let { EmoteImages.get(it) }
                when {
                    bitmap != null -> {
                        val key = "emote${segment.id}"
                        val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                        inline[key] = InlineTextContent(
                            Placeholder((1.5f * ratio).em, 1.5.em, PlaceholderVerticalAlign.TextCenter),
                        ) {
                            Image(bitmap.asImageBitmap(), contentDescription = emote.name)
                        }
                        // Forwarded chat may have nameless emotes; Compose rejects empty alternate text.
                        appendInlineContent(key, emote.name?.takeIf { it.isNotEmpty() } ?: "�")
                        append(" ")
                    }
                    emote != null -> emote.name?.let { append("$it ") }
                    else -> segment.text?.let { append(it) }
                }
            }
        }
    }
    return text to inline
}

/** Chat switch and connection status for the main screen (upstream chat quick button and status). */
@Composable
fun ChatControls(store: SettingsStore, onSettings: () -> Unit) {
    val database by store.database.collectAsState()
    val connected by ChatController.connected.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.show_chat), modifier = Modifier.weight(1f))
        TextButton(onClick = onSettings) { Text(stringResource(R.string.chat_settings)) }
        Switch(
            checked = database.chat.enabled,
            onCheckedChange = { enabled -> store.update { it.copy(chat = it.chat.copy(enabled = enabled)) } },
        )
    }
    if (database.chat.enabled && connected.isNotEmpty()) {
        Text(
            connected.entries.joinToString("  ") { (platform, isConnected) ->
                "${platform.displayName} ${if (isConnected) "✓" else "…"}"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val alerts by ChatController.twitchAlertsConnected.collectAsState()
    alerts?.let {
        Text(
            stringResource(if (it) R.string.twitch_alerts_connected else R.string.twitch_alerts_connecting),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val viewers by ChatController.twitchViewers.collectAsState()
    viewers?.let { Text(stringResource(R.string.twitch_viewers, it), style = MaterialTheme.typography.bodySmall) }
    // Sending is public: only to the platforms the selected stream is signed in to.
    val stream = database.selectedStream
    val toTwitch = stream?.twitchLoggedIn == true
    val toKick = stream?.kickLoggedIn == true && stream.kickSendMessagesTo
    if (database.chat.enabled && (toTwitch || toKick)) {
        ChatSendField(toTwitch, toKick)
    }
}

/**
 * Upstream `sendChatMessage`: posts as the signed-in user in their channel, on Twitch and on Kick
 * (when "send messages to Kick" is on).
 */
@Composable
private fun ChatSendField(toTwitch: Boolean, toKick: Boolean) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(500) },
            label = {
                Text(
                    stringResource(
                        when {
                            toTwitch && toKick -> R.string.send_message_twitch_kick
                            toKick -> R.string.send_message_kick
                            else -> R.string.twitch_send_message
                        },
                    ),
                )
            },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = text.isNotBlank() && !sending,
            onClick = {
                val senders = listOfNotNull(
                    ChatController::sendTwitchMessage.takeIf { toTwitch },
                    ChatController::sendKickMessage.takeIf { toKick },
                )
                var pending = senders.size
                val errors = mutableListOf<String>()
                sending = true
                for (send in senders) {
                    send(text) { error ->
                        error?.let { errors += it }
                        pending -= 1
                        if (pending == 0) {
                            sending = false
                            if (errors.isEmpty()) {
                                text = ""
                            } else {
                                Toast.makeText(context, errors.joinToString("\n"), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            },
        ) { Text(stringResource(R.string.send)) }
    }
}
