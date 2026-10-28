// Long golden API responses are clearer unsplit.
@file:Suppress("ktlint:standard:max-line-length")

package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.chat.twitch.TwitchChat
import io.github.mesmerize08.nexusirl.settings.SettingsStreamChat
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThirdPartyEmotesTest {
    private fun server(responses: Map<String, MockResponse>) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = responses[request.path] ?: MockResponse().setResponseCode(404)
        }
        start()
    }

    private fun ok(body: String) = MockResponse().setBody(body)

    private val responses = mapOf(
        "/3/cached/emotes/global" to ok("""[{"id":"b1","code":"LUL"},{"id":"b2","code":"Shared"}]"""),
        "/3/cached/users/twitch/123" to
            ok(
                """{"sharedEmotes":[{"id":"s1","code":"Shared"}],"channelEmotes":[{"id":"c1","code":"Mine"},{"id":"c2","code":"Shared"}]}""",
            ),
        "/3/cached/frankerfacez/emotes/global" to
            ok("""[{"code":"FfzA","images":{"1x":null,"2x":"https://cdn.ffz/a/2"}},{"code":"NoImage","images":{}}]"""),
        "/v3/emote-sets/global" to
            ok(
                """{"emotes":[{"name":"Seven","data":{"host":{"url":"//cdn.7tv.app/emote/x","files":[{"name":"1x.avif"},{"name":"1x.webp","static_name":"1x_static.webp"}]}}}]}""",
            ),
        "/v3/users/twitch/123" to
            ok(
                """{"emote_set":{"emotes":[{"name":"LUL","data":{"host":{"url":"//cdn.7tv.app/emote/y","files":[{"name":"1x.webp"}]}}}]}}""",
            ),
    )

    private fun loader(server: MockWebServer, settings: SettingsStreamChat, set: EmoteSet, channelId: String = "123") = ThirdPartyEmotes(
        EmotesPlatform.TWITCH,
        channelId,
        settings,
        set,
        bttvBaseUrl = server.url("").toString().removeSuffix("/"),
        seventvBaseUrl = server.url("").toString().removeSuffix("/"),
    )

    private fun emote(set: EmoteSet, word: String) = set.createSegments(word, intArrayOf(0)).first().url

    @Test
    fun allProvidersLikeUpstream() {
        val server = server(responses)
        try {
            val set = EmoteSet()
            // FFZ channel emotes 404: no emotes, not a failure.
            assertTrue(loader(server, SettingsStreamChat(bttvEmotes = true, ffzEmotes = true, seventvEmotes = true), set).fetchAll())
            assertEquals("https://cdn.betterttv.net/emote/c2/1x", emote(set, "Shared")?.moving)
            assertEquals("https://cdn.betterttv.net/emote/c1/1x", emote(set, "Mine")?.moving)
            assertEquals("https://cdn.ffz/a/2", emote(set, "FfzA")?.moving)
            assertEquals(null, emote(set, "NoImage"))
            assertEquals("https://cdn.7tv.app/emote/x/1x.webp", emote(set, "Seven")?.moving)
            assertEquals("https://cdn.7tv.app/emote/x/1x_static.webp", emote(set, "Seven")?.still)
            // 7TV is fetched last, so it wins.
            assertEquals("https://cdn.7tv.app/emote/y/1x.webp", emote(set, "LUL")?.moving)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun disabledAndFailures() {
        val server = server(responses - "/v3/emote-sets/global")
        try {
            val set = EmoteSet()
            assertTrue(loader(server, SettingsStreamChat(), set).fetchAll())
            assertFalse(set.isReady)
            // 7TV global 404 means no list, which upstream treats as an error to retry.
            assertFalse(loader(server, SettingsStreamChat(seventvEmotes = true), set).fetchAll())
            assertEquals("https://cdn.7tv.app/emote/y/1x.webp", emote(set, "LUL")?.moving)
            val noChannel = EmoteSet()
            assertTrue(loader(server, SettingsStreamChat(bttvEmotes = true), noChannel, channelId = "").fetchAll())
            assertEquals(null, emote(noChannel, "Mine"))
            assertEquals("https://cdn.betterttv.net/emote/b2/1x", emote(noChannel, "Shared")?.moving)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun twitchRoomId() {
        val roomIds = mutableListOf<String>()
        val chat = TwitchChat(
            "someone",
            object : ChatListener {
                override fun onPost(post: ChatPost) = Unit
            },
            onRoomId = { roomIds += it },
        )
        chat.handleLine("@emote-only=0;followers-only=-1;r9k=0;room-id=123456;slow=0;subs-only=0 :tmi.twitch.tv ROOMSTATE #someone")
        chat.handleLine(":tmi.twitch.tv ROOMSTATE #someone")
        assertEquals(listOf("123456"), roomIds)
    }
}
