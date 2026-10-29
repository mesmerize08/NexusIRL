// Requests follow upstream's KickApi.swift; responses are trimmed from kick.com's.
@file:Suppress("ktlint:standard:max-line-length")

package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.chat.kick.KickApi
import io.github.mesmerize08.nexusirl.chat.kick.KickCategory
import io.github.mesmerize08.nexusirl.chat.kick.KickChannelInfo
import io.github.mesmerize08.nexusirl.chat.kick.KickStreamInfo
import io.github.mesmerize08.nexusirl.chat.kick.KickUnauthorizedException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KickApiTest {
    private fun withServer(block: (MockWebServer, KickApi) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/").toString().trimEnd('/')
            block(server, KickApi("session|token", OkHttpClient(), baseUrl = url, searchUrl = "$url/search"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun username() = withServer { server, api ->
        server.enqueue(MockResponse().setBody("""{"id":123,"username":"Some_User","email_verified_at":"x"}"""))
        assertEquals("Some_User", api.username())
        val request = server.takeRequest()
        assertEquals("/api/v1/user", request.path)
        assertEquals("Bearer session|token", request.getHeader("Authorization"))
    }

    @Test
    fun channelTriesTheSlugWithDashes() = withServer { server, api ->
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"id":9,"slug":"some-user","chatroom":{"id":456,"channel_id":789,"created_at":"x"}}"""))
        assertEquals(KickChannelInfo("some-user", "456", "789"), api.channel("some_user"))
        assertEquals("/api/v1/channels/some_user", server.takeRequest().path)
        val second = server.takeRequest()
        assertEquals("/api/v1/channels/some-user", second.path)
        // The channel lookup is public; the session only goes where it is needed.
        assertNull(second.getHeader("Authorization"))
    }

    @Test
    fun sendMessage() = withServer { server, api ->
        server.enqueue(MockResponse().setBody("""{"status":{"error":false}}"""))
        api.sendMessage("456", "Hello \"chat\"")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v2/messages/send/456", request.path)
        assertEquals("""{"type":"message","content":"Hello \"chat\""}""", request.body.readUtf8())
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
    }

    @Test
    fun streamInfo() = withServer { server, api ->
        server.enqueue(MockResponse().setBody("""{"stream_title":"Walking around","category":{"id":15,"name":"IRL"},"is_mature":false}"""))
        assertEquals(KickStreamInfo("Walking around", "IRL"), api.streamInfo("some-user"))
        assertEquals("/api/v2/channels/some-user/stream-info", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("""{"stream_title":"No category yet","category":null}"""))
        assertEquals(KickStreamInfo("No category yet", null), api.streamInfo("some-user"))
    }

    @Test
    fun setTitleAndCategory() = withServer { server, api ->
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        api.setTitle("some-user", "New title")
        api.setCategory("some-user", 15)
        val title = server.takeRequest()
        assertEquals("PATCH", title.method)
        assertEquals("/api/v2/channels/some-user/stream-info", title.path)
        assertEquals("""{"stream_title":"New title"}""", title.body.readUtf8())
        assertEquals("""{"category_id":15}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun searchCategories() = withServer { server, api ->
        server.enqueue(
            MockResponse().setBody(
                """{"found":2,"hits":[{"document":{"id":"15","name":"IRL","slug":"irl"}},{"document":{"id":"x","name":"Broken"}},{"document":{"id":"28","name":"Travel","slug":"travel"}}]}""",
            ),
        )
        assertEquals(listOf(KickCategory(15, "IRL"), KickCategory(28, "Travel")), api.searchCategories("i r"))
        val request = server.takeRequest()
        assertEquals("/search/collections/subcategory_index/documents/search?q=i%20r&query_by=name", request.path)
        assertEquals("nXIMW0iEN6sMujFYjFuhdrSwVow3pDQu", request.getHeader("X-Typesense-Api-Key"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun unauthorized() = withServer { server, api ->
        server.enqueue(MockResponse().setResponseCode(401))
        assertFailsWith<KickUnauthorizedException> { api.sendMessage("456", "hi") }
    }
}
