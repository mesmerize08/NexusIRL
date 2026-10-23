// Responses follow Twitch's Helix reference examples.
@file:Suppress("ktlint:standard:max-line-length")

package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.chat.twitch.TwitchApi
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchCategory
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchChannelInfo
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchMissingScopeException
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchUnauthorizedException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TwitchApiTest {
    private fun withServer(block: (MockWebServer, TwitchApi) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server, TwitchApi("clientid123", "token", OkHttpClient(), server.url("/helix").toString()))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun channelInformation() = withServer { server, api ->
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"broadcaster_id":"141981764","broadcaster_login":"twitchdev","broadcaster_name":"TwitchDev","broadcaster_language":"en","game_id":"509670","game_name":"Science & Technology","title":"TwitchDev Monthly Update // May 6, 2021","delay":0,"tags":["DevsInTheKnow"]}]}""",
            ),
        )
        assertEquals(
            TwitchChannelInfo("TwitchDev Monthly Update // May 6, 2021", "509670", "Science & Technology"),
            api.getChannelInformation("141981764"),
        )
        val request = server.takeRequest()
        assertEquals("/helix/channels?broadcaster_id=141981764", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals("clientid123", request.getHeader("Client-Id"))
    }

    @Test
    fun categorySearch() = withServer { server, api ->
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"id":"509658","name":"Just Chatting","box_art_url":"x"},{"id":"509672","name":"Travel & Outdoors","box_art_url":"y"}]}""",
            ),
        )
        assertEquals(
            listOf(TwitchCategory("509658", "Just Chatting"), TwitchCategory("509672", "Travel & Outdoors")),
            api.searchCategories("just chat"),
        )
        assertEquals("/helix/search/categories?query=just%20chat&first=10", server.takeRequest().path)
    }

    @Test
    fun modifyChannelInformation() = withServer { server, api ->
        server.enqueue(MockResponse().setResponseCode(204))
        api.modifyChannelInformation("141981764", "IRL walk", "509672")
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/helix/channels?broadcaster_id=141981764", request.path)
        assertEquals("""{"title":"IRL walk","game_id":"509672"}""", request.body.readUtf8())
    }

    @Test
    fun titleOnly() = withServer { server, api ->
        server.enqueue(MockResponse().setResponseCode(204))
        api.modifyChannelInformation("1", "Only the title", null)
        assertEquals("""{"title":"Only the title"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun missingScopeIsNotAnExpiredSignIn() = withServer { server, api ->
        server.enqueue(
            MockResponse().setResponseCode(
                401,
            ).setBody("""{"error":"Unauthorized","status":401,"message":"Missing scope: channel:manage:broadcast"}"""),
        )
        val error = assertFailsWith<TwitchMissingScopeException> { api.modifyChannelInformation("1", "t", null) }
        assertEquals("channel:manage:broadcast", error.scope)
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized","status":401,"message":"Invalid OAuth token"}"""),
        )
        assertFailsWith<TwitchUnauthorizedException> { api.getChannelInformation("1") }
    }
}
