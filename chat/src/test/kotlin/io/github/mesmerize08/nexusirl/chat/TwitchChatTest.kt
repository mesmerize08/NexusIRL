// Long golden IRC lines are clearer unsplit.
@file:Suppress("ktlint:standard:max-line-length")

package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.chat.twitch.InvalidTwitchMessageException
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchChat
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchChatCommand
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchChatMessage
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchMessageEmote
import io.github.mesmerize08.nexusirl.chat.twitch.createTwitchSegments
import io.github.mesmerize08.nexusirl.settings.RgbColor
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from upstream MoblinTests/Moblin/StreamingPlatforms/Twitch/TwitchChatSuite.swift and
// TwitchChatSegmentsSuite.swift @97e26d2.
class TwitchChatTest {
    private fun texts(segments: List<ChatPostSegment>) = segments.map { it.text }

    private fun emoteIds(segments: List<ChatPostSegment>) = segments.map { segment -> segment.url?.still?.split("/")?.getOrNull(5) }

    private class Recorder : ChatListener {
        val posts = mutableListOf<ChatPost>()
        val deletedMessages = mutableListOf<String>()
        val deletedUsers = mutableListOf<String>()

        override fun onPost(post: ChatPost) {
            posts += post
        }

        override fun onDeleteMessage(messageId: String) {
            deletedMessages += messageId
        }

        override fun onDeleteUser(userId: String) {
            deletedUsers += userId
        }
    }

    @Test
    fun emptyAndUnknown() {
        assertFailsWith<InvalidTwitchMessageException> { TwitchChatMessage.parse("") }
        assertFailsWith<InvalidTwitchMessageException> { TwitchChatMessage.parse(":tmi.twitch.tv 001 eerimoq :Welcome") }
    }

    @Test
    fun basicMessage() {
        val message = TwitchChatMessage.parse(
            "@badge-info=subscriber/13;badges=broadcaster/1,subscriber/0,turbo/1;client-nonce=11b2e915221ab4bcfb44714bda0fb575;" +
                "color=;display-name=eerimoq;emotes=;first-msg=0;flags=;id=52db2f3d-cc5a-46ea-ba0b-bd910579c248;mod=0;" +
                "returning-chatter=0;room-id=63482386;subscriber=1;tmi-sent-ts=1760946171865;turbo=1;user-id=63482386;user-type= " +
                ":eerimoq!eerimoq@eerimoq.tmi.twitch.tv PRIVMSG #eerimoq :hi all",
        )
        assertEquals(TwitchChatCommand.PRIVATE_MESSAGE, message.command)
        assertEquals(listOf("#eerimoq", "hi all"), message.parameters)
        assertEquals("eerimoq", message.displayName)
        assertEquals("eerimoq", message.user)
        assertEquals("63482386", message.userId)
        assertNull(message.color)
        assertTrue(message.emotes.isEmpty())
        assertEquals(listOf("broadcaster/1", "subscriber/0", "turbo/1"), message.badges)
        assertEquals("52db2f3d-cc5a-46ea-ba0b-bd910579c248", message.id)
        assertFalse(message.firstMessage)
        assertTrue(message.subscriber)
        assertFalse(message.moderator)
    }

    @Test
    fun unescapedTagValues() {
        val message = TwitchChatMessage.parse(
            "@display-name=eerimoq;reply-parent-display-name=someone;reply-parent-msg-body=a\\sb\\:c\\rd\\ne\\\\f\\qg " +
                ":eerimoq!eerimoq@eerimoq.tmi.twitch.tv PRIVMSG #eerimoq :hi",
        )
        assertEquals("someone", message.replySender)
        assertEquals("a b;c\rd\ne\\fqg", message.replyText)
        assertEquals("a b", TwitchChatMessage.parse("@reply-parent-msg-body=a\\sb\\ :e!e@e PRIVMSG #e :hi").replyText)
    }

    @Test
    fun unicodeWhitespaces() {
        val message = TwitchChatMessage.parse(
            "@display-name=eerimoq;reply-parent-display-name=someone;reply-parent-msg-body=こんにちは　みなさん " +
                ":eerimoq!eerimoq@eerimoq.tmi.twitch.tv PRIVMSG #eerimoq :hi all",
        )
        assertEquals("こんにちは　みなさん", message.replyText)
        assertEquals(listOf("#eerimoq", "hi all"), message.parameters)
    }

    @Test
    fun gifs() {
        val message = TwitchChatMessage.parse(
            "@display-name=eerimoq;emotes=;gifs=0-34|l0MYDEPLWRWbJoRuU|https://media3.giphy.com/media/l0MYDEPLWRWbJoRuU/giphy.gif?" +
                "cid=095d7a5dpq5y4f8xwwlqk053r89k5ezmzgauu6wyjbrun0k0&ep=v1_gifs_trending&rid=giphy.gif&ct=g " +
                ":eerimoq!eerimoq@eerimoq.tmi.twitch.tv PRIVMSG #eerimoq :[George Costanza Hello GIF by HULU]",
        )
        val gif = message.emotes.single()
        assertTrue(gif.isGif)
        assertEquals(0 to 34, gif.first to gif.last)
        assertEquals(
            "https://media3.giphy.com/media/l0MYDEPLWRWbJoRuU/100.gif?cid=095d7a5dpq5y4f8xwwlqk053r89k5ezmzgauu6wyjbrun0k0" +
                "&ep=v1_gifs_trending&rid=giphy.gif&ct=g",
            gif.url,
        )
        val both = TwitchChatMessage.parse(
            "@gifs=6-10|abc|https://media.giphy.com/media/abc/giphy.gif;emotes=25:0-4 :eerimoq!eerimoq@eerimoq.tmi.twitch.tv PRIVMSG #eerimoq :Kappa [gif]",
        )
        assertEquals(listOf(true, false), both.emotes.map { it.isGif })
        assertEquals(listOf(6 to 10, 0 to 4), both.emotes.map { it.first to it.last })
        assertTrue(TwitchChatMessage.parse("@gifs=0-4|abc :e!e@e PRIVMSG #e :[gif]").emotes.isEmpty())
    }

    @Test
    fun emotesAndGigantified() {
        val message = TwitchChatMessage.parse(
            "@badge-info=;badges=;color=;display-name=eerimoq;emotes=25:0-4,12-16/1902:6-10;id=f44b10f3;" +
                "msg-id=gigantified-emote-message;user-id=63482386 :eerimoq!eerimoq@eerimoq.tmi.twitch.tv PRIVMSG #eerimoq :Kappa Keepo Kappa",
        )
        assertTrue(message.isGigantifiedEmote)
        assertEquals(listOf(0 to 4, 12 to 16, 6 to 10), message.emotes.map { it.first to it.last })
        assertEquals("https://static-cdn.jtvnw.net/emoticons/v2/25/default/dark/3.0", message.emotes[1].url)
    }

    @Test
    fun announcementClearAndPing() {
        val announcement = TwitchChatMessage.parse(
            "@badges=broadcaster/1;display-name=eerimoq;id=e60e8de0;login=eerimoq;msg-id=announcement;user-id=63482386 " +
                ":tmi.twitch.tv USERNOTICE #eerimoq :foobar",
        )
        assertEquals(TwitchChatCommand.USER_NOTICE, announcement.command)
        assertEquals("eerimoq", announcement.user)
        assertEquals("announcement", announcement.messageId)
        assertEquals(
            "8b4f-4dda-a4e6",
            TwitchChatMessage.parse("@login=eerimoq;target-msg-id=8b4f-4dda-a4e6 :tmi.twitch.tv CLEARMSG #eerimoq :bye").targetMessageId,
        )
        assertEquals(
            "63482386",
            TwitchChatMessage.parse("@target-user-id=63482386 :tmi.twitch.tv CLEARCHAT #eerimoq :baduser").targetUserId,
        )
        val ping = TwitchChatMessage.parse("PING :tmi.twitch.tv")
        assertEquals(TwitchChatCommand.PING, ping.command)
        assertEquals(listOf("tmi.twitch.tv"), ping.parameters)
    }

    @Test
    fun meMessageIsAction() {
        val recorder = Recorder()
        val chat = TwitchChat("eerimoq", recorder, EmoteSet())
        chat.handleLine(
            "@badges=broadcaster/1;color=;display-name=eerimoq;emotes=emotesv2_1927bf80a13d46049223d39d14ea1e40:8-23/425618:2-4;" +
                "first-msg=0;id=817ca58d;mod=0;subscriber=1;user-id=63482386 :eerimoq!eerimoq@eerimoq.tmi.twitch.tv PRIVMSG #eerimoq " +
                ":\u0001ACTION 1 LUL 2 eerimoPartyDance\u0001",
        )
        val post = recorder.posts.single()
        assertTrue(post.isAction)
        assertEquals("eerimoq", post.displayName)
        assertEquals(listOf("1 ", null, "", "2 ", null, ""), texts(post.segments))
        assertEquals(listOf(null, "425618", null, null, "emotesv2_1927bf80a13d46049223d39d14ea1e40", null), emoteIds(post.segments))
        chat.handleLine("@login=eerimoq;target-msg-id=abc :tmi.twitch.tv CLEARMSG #eerimoq :bye")
        chat.handleLine("@target-user-id=42 :tmi.twitch.tv CLEARCHAT #eerimoq :baduser")
        assertEquals(listOf("abc"), recorder.deletedMessages)
        assertEquals(listOf("42"), recorder.deletedUsers)
    }

    @Test
    fun highlightsAndColors() {
        val recorder = Recorder()
        val chat = TwitchChat("x", recorder)
        chat.handleLine("@display-name=A;color=#179451;first-msg=1 :a!a@a PRIVMSG #x :hello")
        chat.handleLine("@display-name=B;reply-parent-display-name=A;reply-parent-msg-body=hello :b!b@b PRIVMSG #x :hi")
        chat.handleLine("@display-name=C;msg-id=sub :tmi.twitch.tv USERNOTICE #x :ignored")
        assertEquals(2, recorder.posts.size)
        assertEquals(RgbColor(0x17, 0x94, 0x51), recorder.posts[0].userColor)
        assertEquals(ChatHighlightKind.FIRST_MESSAGE, recorder.posts[0].highlight?.kind)
        assertEquals("Replying to A:", recorder.posts[1].highlight?.title)
        assertEquals("hi", recorder.posts[1].text())
    }

    @Test
    fun segmentsWithThirdPartyEmotes() {
        val emotes = EmoteSet(mapOf("LUL" to ThirdPartyEmote("https://cdn.example.com/lul")))
        val segments = createTwitchSegments(
            "LUL Kappa x",
            listOf(TwitchMessageEmote("https://e/25/default", "https://static-cdn.jtvnw.net/emoticons/v2/25/static/dark/3.0", 4, 8)),
            emotes,
            intArrayOf(0),
        )
        assertEquals(listOf("", "", null, "", "x "), texts(segments))
        assertEquals("LUL", segments[0].url?.name)
        assertEquals("25", emoteIds(segments)[2])
        assertEquals(segments.size, segments.map { it.id }.toSet().size)
        // Emote ranges count code points: an emoji before the emote shifts it by one, not two.
        val emoji = createTwitchSegments("😀 Kappa", listOf(TwitchMessageEmote("u", "s", 2, 6)), EmoteSet(), intArrayOf(0))
        assertEquals("Kappa", emoji[1].url?.name)
    }

    /** End to end against a local WebSocket server: anonymous login, a message, and PING/PONG. */
    @Test
    fun connectsAnonymouslyAndReceivesChat() {
        val received = LinkedBlockingQueue<String>()
        var serverSocket: WebSocket? = null
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket = webSocket
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                }
            }),
        )
        server.start()
        val posts = LinkedBlockingQueue<ChatPost>()
        val chat = TwitchChat(
            "SomeChannel",
            object : ChatListener {
                override fun onPost(post: ChatPost) {
                    posts += post
                }
            },
            url = server.url("/").toString().replace("http", "ws"),
        )
        try {
            chat.start()
            val handshake = (1..6).map { received.poll(5, TimeUnit.SECONDS) }
            assertEquals(
                listOf(
                    "CAP REQ :twitch.tv/membership",
                    "CAP REQ :twitch.tv/tags",
                    "CAP REQ :twitch.tv/commands",
                    "PASS oauth:SCHMOOPIIE",
                    "NICK justinfan67420",
                    "JOIN #somechannel",
                ),
                handshake,
            )
            assertNotNull(
                serverSocket,
            ).send("@display-name=Viewer :viewer!viewer@viewer PRIVMSG #somechannel :hello there\r\nPING :tmi.twitch.tv\r\n")
            assertEquals("hello there", posts.poll(5, TimeUnit.SECONDS)?.text())
            assertEquals("PONG tmi.twitch.tv", received.poll(5, TimeUnit.SECONDS))
        } finally {
            chat.stop()
            server.shutdown()
        }
    }
}
