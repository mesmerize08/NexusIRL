// Long golden responses are clearer unsplit.
@file:Suppress("ktlint:standard:max-line-length")

package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.chat.twitch.TwitchAuth
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchAuthException
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchTokens
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TwitchAuthTest {
    private fun withServer(block: (MockWebServer, TwitchAuth) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server, TwitchAuth("clientid123", baseUrl = server.url("").toString().removeSuffix("/")))
        } finally {
            server.shutdown()
        }
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    @Test
    fun deviceFlow() = withServer { server, auth ->
        server.enqueue(
            json(
                """{"device_code":"dev","expires_in":1800,"interval":5,"user_code":"ABCDEFGH","verification_uri":"https://www.twitch.tv/activate?public=true&device-code=ABCDEFGH"}""",
            ),
        )
        server.enqueue(json("""{"status":400,"message":"authorization_pending"}""", 400))
        server.enqueue(json("""{"status":400,"message":"slow_down"}""", 400))
        server.enqueue(
            json("""{"access_token":"at","expires_in":14000,"refresh_token":"rt","scope":["user:write:chat"],"token_type":"bearer"}"""),
        )
        val sleeps = mutableListOf<Long>()
        var shown = ""
        val tokens = auth.signIn(onCode = { shown = it.userCode }, sleep = { sleeps += it })
        assertEquals("ABCDEFGH", shown)
        assertEquals(TwitchTokens("at", "rt"), tokens)
        // Slowed down by 5 s after slow_down, like the OAuth device flow requires.
        assertEquals(listOf(5000L, 5000L, 10000L), sleeps)
        val start = server.takeRequest()
        assertEquals("/oauth2/device", start.path)
        val form = start.body.readUtf8()
        assertTrue("client_id=clientid123" in form)
        assertTrue("scopes=user%3Awrite%3Achat%20channel%3Amanage%3Abroadcast%20moderator%3Aread%3Afollowers" in form, form)
        val poll = server.takeRequest().body.readUtf8()
        assertTrue("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code" in poll, poll)
        assertTrue("device_code=dev" in poll)
        assertTrue("client_secret" !in poll, "public client: no secret")
    }

    @Test
    fun deniedOrExpired() = withServer { server, auth ->
        server.enqueue(
            json(
                """{"device_code":"dev","expires_in":1800,"interval":1,"user_code":"X","verification_uri":"https://www.twitch.tv/activate"}""",
            ),
        )
        server.enqueue(json("""{"status":400,"message":"invalid device code"}""", 400))
        val error = assertFailsWith<TwitchAuthException> { auth.signIn(onCode = {}, sleep = {}) }
        assertEquals(TwitchAuthException.Reason.EXPIRED, error.reason)
    }

    @Test
    fun refreshValidateRevoke() = withServer { server, auth ->
        server.enqueue(json("""{"access_token":"at2","refresh_token":"rt2","expires_in":14000,"token_type":"bearer"}"""))
        assertEquals(TwitchTokens("at2", "rt2"), auth.refresh(TwitchTokens("at", "rt")))
        val refresh = server.takeRequest().body.readUtf8()
        assertTrue("grant_type=refresh_token" in refresh && "refresh_token=rt" in refresh, refresh)

        server.enqueue(
            json(
                """{"client_id":"clientid123","login":"someone","scopes":["user:write:chat"],"user_id":"141981764","expires_in":5520838}""",
            ),
        )
        val info = auth.validate("at2")
        assertEquals("someone", info.login)
        assertEquals("141981764", info.userId)
        assertEquals(listOf("user:write:chat"), info.scopes)
        assertEquals("OAuth at2", server.takeRequest().getHeader("Authorization"))

        server.enqueue(json("""{"status":401,"message":"invalid access token"}""", 401))
        assertEquals(TwitchAuthException.Reason.INVALID_TOKEN, assertFailsWith<TwitchAuthException> { auth.validate("old") }.reason)
        server.takeRequest()

        server.enqueue(json("", 200))
        auth.revoke("at2")
        val revoke = server.takeRequest()
        assertEquals("/oauth2/revoke", revoke.path)
        assertTrue("token=at2" in revoke.body.readUtf8())
    }
}
