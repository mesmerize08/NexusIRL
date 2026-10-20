package io.github.mesmerize08.nexusirl.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserChatProtocolTest {
    private fun post(vararg segments: ChatPostSegment) = ChatPost(
        platform = ChatPlatform.TWITCH,
        messageId = "1",
        displayName = "Viewer",
        user = "viewer",
        userId = "2",
        userColor = null,
        userBadges = emptyList(),
        segments = segments.toList(),
        isAction = false,
        isSubscriber = false,
        isModerator = false,
        bits = null,
        highlight = null,
    )

    @Test
    fun subscriptions() {
        assertEquals(BrowserChatSubscription(null), BrowserChatProtocol.parseChatSubscription("""{"subscribe":{"topic":{"chat":{}}}}"""))
        assertEquals(
            BrowserChatSubscription("!"),
            BrowserChatProtocol.parseChatSubscription("""{"subscribe":{"topic":{"chat":{"prefix":"!"}}}}"""),
        )
        assertNull(BrowserChatProtocol.parseChatSubscription("""{"subscribe":{"topic":{"speechToText":{}}}}"""))
        assertNull(BrowserChatProtocol.parseChatSubscription("""{"ping":{}}"""))
        assertNull(BrowserChatProtocol.parseChatSubscription("not json"))
    }

    @Test
    fun prefix() {
        val message = post(ChatPostSegment(0, text = "!so "), ChatPostSegment(1, text = "someone "))
        assertTrue(BrowserChatProtocol.matches(BrowserChatSubscription(null), message))
        assertTrue(BrowserChatProtocol.matches(BrowserChatSubscription("!"), message))
        assertFalse(BrowserChatProtocol.matches(BrowserChatSubscription("?"), message))
        assertFalse(BrowserChatProtocol.matches(BrowserChatSubscription("!"), post()))
    }

    @Test
    fun encodesLikeSwift() {
        val message = post(
            ChatPostSegment(0, text = "hi "),
            ChatPostSegment(1, url = ChatEmote("https://a/m.gif", "https://a/s.png", "Kappa")),
            ChatPostSegment(2, text = ""),
        )
        assertEquals(
            """{"message":{"data":{"chat":{"message":{"user":"viewer","segments":[{"id":0,"text":"hi "},""" +
                """{"id":1,"url":{"moving":"https://a/m.gif","still":"https://a/s.png","name":"Kappa"}},{"id":2,"text":""}]}}}}}""",
            BrowserChatProtocol.encodeChatMessage(message),
        )
    }
}
