// Payloads follow Twitch's EventSub reference examples; texts and titles are upstream's.
@file:Suppress("ktlint:standard:max-line-length")

package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.chat.twitch.TwitchApi
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchEventSub
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchUnauthorizedException
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwitchEventSubTest {
    private fun notification(type: String, event: String) =
        """{"metadata":{"message_id":"1","message_type":"notification","message_timestamp":"2023-07-19T14:56:51.634234626Z","subscription_type":"$type","subscription_version":"1"},"payload":{"subscription":{"id":"x","type":"$type"},"event":$event}}"""

    private fun collect(vararg messages: String): List<ChatPost> {
        val posts = mutableListOf<ChatPost>()
        val eventSub = TwitchEventSub(
            "1337",
            object : ChatListener {
                override fun onPost(post: ChatPost) {
                    posts += post
                }
            },
            api = { null },
        )
        messages.forEach(eventSub::handleMessage)
        return posts
    }

    private fun collectAlerts(vararg messages: String): List<TwitchAlert> {
        val alerts = mutableListOf<TwitchAlert>()
        val eventSub = TwitchEventSub(
            "1337",
            object : ChatListener {
                override fun onPost(post: ChatPost) = Unit

                override fun onTwitchAlert(alert: TwitchAlert) {
                    alerts += alert
                }
            },
            api = { null },
        )
        messages.forEach(eventSub::handleMessage)
        return alerts
    }

    /** Notifications a remote control assistant forwards become alerts; its session messages are ignored. */
    @Test
    fun forwardedNotifications() {
        val alerts = LinkedBlockingQueue<TwitchAlert>()
        val api = LinkedBlockingQueue<Unit>()
        val eventSub = TwitchEventSub(
            "1337",
            object : ChatListener {
                override fun onPost(post: ChatPost) = Unit

                override fun onTwitchAlert(alert: TwitchAlert) {
                    alerts += alert
                }
            },
            api = {
                api += Unit
                null
            },
        )
        eventSub.handleForwardedMessage("""{"metadata":{"message_type":"session_welcome"},"payload":{"session":{"id":"s"}}}""")
        eventSub.handleForwardedMessage("not json")
        eventSub.handleForwardedMessage(notification("channel.follow", """{"user_name":"Alice"}"""))
        val alert = assertNotNull(alerts.poll(5, TimeUnit.SECONDS))
        assertEquals(TwitchAlertType.FOLLOW, alert.type)
        assertEquals("Alice", alert.user)
        assertTrue(api.isEmpty(), "a forwarded welcome must not subscribe")
        eventSub.stop()
        eventSub.handleForwardedMessage(notification("channel.follow", """{"user_name":"Bob"}"""))
        assertNull(alerts.poll(200, TimeUnit.MILLISECONDS))
    }

    /** Each alert says what kind it is (for the per-kind chat and toast settings) and the bits of a cheer. */
    @Test
    fun typedAlerts() {
        val alerts = collectAlerts(
            notification("channel.follow", """{"user_name":"Cool_User"}"""),
            notification("channel.subscribe", """{"user_name":"Cool_User","tier":"1000","is_gift":false}"""),
            notification("channel.subscription.message", """{"user_name":"Cool_User","tier":"1000","cumulative_months":3}"""),
            notification("channel.subscription.gift", """{"user_name":"Cool_User","total":2,"tier":"1000","is_anonymous":false}"""),
            notification("channel.cheer", """{"is_anonymous":false,"user_name":"Cool_User","message":"","bits":250}"""),
            notification("channel.raid", """{"from_broadcaster_user_name":"Cool_User","viewers":12}"""),
            notification(
                "channel.channel_points_custom_reward_redemption.add",
                """{"user_name":"Cool_User","user_input":"","reward":{"title":"Hydrate"}}""",
            ),
        )
        assertEquals(
            listOf(
                TwitchAlertType.FOLLOW,
                TwitchAlertType.SUBSCRIPTION,
                TwitchAlertType.RESUBSCRIPTION,
                TwitchAlertType.GIFT_SUBSCRIPTION,
                TwitchAlertType.CHEER,
                TwitchAlertType.RAID,
                TwitchAlertType.REWARD,
            ),
            alerts.map { it.type },
        )
        val cheer = alerts.single { it.type == TwitchAlertType.CHEER }
        assertEquals(250, cheer.bits)
        assertEquals("Cool_User", cheer.user)
        assertEquals("just followed!", alerts.first().text)
    }

    private fun line(post: ChatPost) = "${post.highlight?.title}: ${post.displayName} ${post.text()}"

    @Test
    fun alertsLikeUpstream() {
        val posts = collect(
            notification(
                "channel.follow",
                """{"user_id":"1234","user_login":"cool_user","user_name":"Cool_User","broadcaster_user_id":"1337","followed_at":"2020-07-15T18:16:11.17106713Z"}""",
            ),
            notification("channel.subscribe", """{"user_name":"Cool_User","tier":"2000","is_gift":false}"""),
            notification("channel.subscribe", """{"user_name":"Gifted_User","tier":"1000","is_gift":true}"""),
            notification(
                "channel.subscription.message",
                """{"user_name":"Cool_User","tier":"1000","message":{"text":"Love the stream! FevziGG","emotes":[]},"cumulative_months":15,"streak_months":1,"duration_months":6}""",
            ),
            notification(
                "channel.subscription.message",
                """{"user_name":"Quiet","tier":"3000","message":{"text":"","emotes":[]},"cumulative_months":2,"streak_months":null}""",
            ),
            notification(
                "channel.subscription.gift",
                """{"user_name":"Cool_User","total":2,"tier":"1000","cumulative_total":284,"is_anonymous":false}""",
            ),
            notification("channel.subscription.gift", """{"user_name":null,"total":5,"tier":"1000","is_anonymous":true}"""),
            notification("channel.cheer", """{"is_anonymous":false,"user_name":"Cool_User","message":"pogchamp","bits":1000}"""),
            notification(
                "channel.raid",
                """{"from_broadcaster_user_name":"Cool_User","to_broadcaster_user_name":"Cooler_User","viewers":9001}""",
            ),
            notification(
                "channel.channel_points_custom_reward_redemption.add",
                """{"user_name":"Cool_User","user_input":"pogchamp","status":"unfulfilled","reward":{"id":"9001","title":"title","cost":100,"prompt":"reward prompt"}}""",
            ),
            notification(
                "channel.channel_points_custom_reward_redemption.add",
                """{"user_name":"Cool_User","user_input":"","reward":{"title":"Hydrate"}}""",
            ),
            notification("channel.poll.begin", """{"title":"ignored"}"""),
            """{"metadata":{"message_type":"session_keepalive"},"payload":{}}""",
            "not json",
        )
        assertEquals(
            listOf(
                "New follower: Cool_User just followed!",
                "New subscriber: Cool_User just subscribed tier 2!",
                "New resubscribe: Cool_User just resubscribed tier 1 for 15 months, 1 in a row! Love the stream! FevziGG",
                "New resubscribe: Quiet just resubscribed tier 3 for 2 months!",
                "Gift subscriptions: Cool_User just gifted 2 tier 1 subscriptions!",
                "Gift subscriptions: Anonymous just gifted 5 tier 1 subscriptions!",
                "Cheer: Cool_User cheered 1000 bits! pogchamp",
                "Raid: Cool_User raided with a party of 9001!",
                "Reward redemption: Cool_User redeemed title! pogchamp",
                "Reward redemption: Cool_User redeemed Hydrate!",
            ),
            posts.map(::line),
        )
        assertEquals(ChatHighlightKind.NEW_FOLLOWER, posts[0].highlight?.kind)
        assertEquals(ChatHighlightKind.REDEMPTION, posts[8].highlight?.kind)
    }

    @Test
    fun welcomeSubscribesThenNotifies() {
        val helix = MockWebServer()
        repeat(7) { helix.enqueue(MockResponse().setResponseCode(202).setBody("""{"data":[{"id":"x","status":"enabled"}],"total":1}""")) }
        helix.start()
        val sockets = LinkedBlockingQueue<WebSocket>()
        val eventSubServer = MockWebServer()
        eventSubServer.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sockets += webSocket
                    webSocket.send(
                        """{"metadata":{"message_type":"session_welcome"},"payload":{"session":{"id":"AQoQexAWVYKSTIu4ec_2VAxyuhAB","status":"connected","keepalive_timeout_seconds":10,"reconnect_url":null}}}""",
                    )
                }
            }),
        )
        eventSubServer.start()
        val posts = LinkedBlockingQueue<ChatPost>()
        val connected = LinkedBlockingQueue<Boolean>()
        val eventSub = TwitchEventSub(
            "1337",
            object : ChatListener {
                override fun onPost(post: ChatPost) {
                    posts += post
                }

                override fun onConnectionChanged(platform: ChatPlatform, isConnected: Boolean) {
                    connected += isConnected
                }
            },
            api = { TwitchApi("clientid123", "token", baseUrl = helix.url("/helix").toString().removeSuffix("/")) },
            url = eventSubServer.url("/ws").toString().replace("http", "ws"),
        )
        try {
            eventSub.start()
            assertEquals(true, connected.poll(10, TimeUnit.SECONDS))
            val requests = (1..7).map { helix.takeRequest(5, TimeUnit.SECONDS)!! }
            assertTrue(
                requests.all {
                    it.path == "/helix/eventsub/subscriptions" &&
                        it.getHeader("Authorization") == "Bearer token" &&
                        it.getHeader("Client-Id") == "clientid123"
                },
            )
            val follow = requests.first().body.readUtf8()
            assertEquals(
                """{"type":"channel.follow","version":"2","condition":{"broadcaster_user_id":"1337","moderator_user_id":"1337"},"transport":{"method":"websocket","session_id":"AQoQexAWVYKSTIu4ec_2VAxyuhAB"}}""",
                follow,
            )
            assertTrue(requests.any { "\"to_broadcaster_user_id\":\"1337\"" in it.body.readUtf8() })
            val socket = assertNotNull(sockets.poll(5, TimeUnit.SECONDS))
            socket.send(notification("channel.follow", """{"user_name":"Cool_User"}"""))
            assertEquals("Cool_User just followed!", posts.poll(5, TimeUnit.SECONDS)?.let { "${it.displayName} ${it.text()}" })
        } finally {
            eventSub.stop()
            eventSubServer.shutdown()
            helix.shutdown()
        }
    }

    @Test
    fun helix() {
        val server = MockWebServer()
        server.start()
        try {
            val api = TwitchApi("clientid123", "token", baseUrl = server.url("/helix").toString().removeSuffix("/"))
            server.enqueue(
                MockResponse().setBody(
                    """{"data":[{"id":"1","user_id":"1337","type":"live","title":"Hello","game_name":"IRL","viewer_count":78365}],"pagination":{}}""",
                ),
            )
            assertEquals(78365, api.getStream("1337")?.viewerCount)
            assertEquals("/helix/streams?user_id=1337", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"data":[],"pagination":{}}"""))
            assertNull(api.getStream("1337"))
            server.takeRequest()
            // Sending chat is only ever exercised against this mock (it is public on a real channel).
            server.enqueue(MockResponse().setBody("""{"data":[{"message_id":"abc","is_sent":true}]}"""))
            api.sendChatMessage("1337", "1337", "Hello")
            val send = server.takeRequest()
            assertEquals("/helix/chat/messages", send.path)
            assertEquals("""{"broadcaster_id":"1337","sender_id":"1337","message":"Hello"}""", send.body.readUtf8())
            server.enqueue(
                MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized","status":401,"message":"Invalid OAuth token"}"""),
            )
            assertFailsWith<TwitchUnauthorizedException> { api.getStream("1337") }
        } finally {
            server.shutdown()
        }
    }
}
