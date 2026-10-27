package io.github.mesmerize08.nexusirl.remote

import io.github.mesmerize08.nexusirl.chat.ChatEmote
import io.github.mesmerize08.nexusirl.chat.ChatHighlightKind
import io.github.mesmerize08.nexusirl.chat.ChatPlatform
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlChatEmote
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlChatHighlight
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlChatHighlightKind
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlChatMessage
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlChatSegment
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlPlatform
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlRgbColor
import io.github.mesmerize08.nexusirl.settings.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForwardedChatTest {
    @Test
    fun messageBecomesPost() {
        val post = RemoteControlChatMessage(
            id = 1,
            platform = RemoteControlPlatform.KICK,
            displayName = "Viewer",
            userColor = RemoteControlRgbColor(1, 2, 3),
            segments = listOf(
                RemoteControlChatSegment(0, text = "hi "),
                RemoteControlChatSegment(1, url = RemoteControlChatEmote(still = "http://e/1.png", name = "Kappa")),
            ),
            isModerator = true,
            highlight = RemoteControlChatHighlight(
                RemoteControlChatHighlightKind.REDEMPTION,
                RemoteControlRgbColor(255, 0, 0),
                "medal",
                listOf(RemoteControlChatSegment(0, text = "Redeemed "), RemoteControlChatSegment(1, text = "Hydrate ")),
            ),
        ).toChatPost()
        assertEquals(ChatPlatform.KICK, post.platform)
        assertEquals("Viewer", post.displayName)
        assertEquals("Viewer", post.user)
        assertEquals(RgbColor(1, 2, 3), post.userColor)
        assertEquals(ChatEmote(null, "http://e/1.png", "Kappa"), post.segments[1].url)
        assertEquals("hi Kappa", post.text())
        assertEquals(true, post.isModerator)
        assertEquals(ChatHighlightKind.REDEMPTION, post.highlight?.kind)
        assertEquals("Redeemed Hydrate", post.highlight?.title)
        assertEquals(RgbColor(255, 0, 0), post.highlight?.barColor)
    }

    @Test
    fun minimalMessage() {
        val post = RemoteControlChatMessage(id = 2, platform = RemoteControlPlatform.SOOP).toChatPost()
        assertEquals(ChatPlatform.SOOP, post.platform)
        assertEquals("", post.displayName)
        assertNull(post.highlight)
    }
}
