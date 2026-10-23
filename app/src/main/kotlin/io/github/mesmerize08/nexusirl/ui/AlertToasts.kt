// Upstream makeToast for Twitch alerts (Moblin/Various/Model/ModelTwitch.swift @97e26d2).
package io.github.mesmerize08.nexusirl.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.chat.ChatController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/** How long each alert toast stays up; several alerts in a row show one after another. */
private const val TOAST_MILLIS = 4_000L

/** Twitch alerts the stream's toast settings let through, one at a time, over every screen. */
@Composable
fun AlertToastHost(modifier: Modifier = Modifier, toasts: Flow<String> = ChatController.toasts) {
    var visible by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(toasts) {
        // Collecting suspends while a toast shows, so alerts queue in the flow's buffer.
        toasts.collect { alert ->
            text = alert
            visible = true
            delay(TOAST_MILLIS)
            visible = false
            delay(300)
        }
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn() + slideInVertically(), exit = fadeOut()) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .background(Color(0xE6202020), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
