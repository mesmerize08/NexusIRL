// Long golden Pusher payloads are clearer unsplit.
@file:Suppress("ktlint:standard:max-line-length")

package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.chat.kick.KickChat
import io.github.mesmerize08.nexusirl.chat.kick.KickChatIds
import io.github.mesmerize08.nexusirl.chat.kick.createKickSegments
import io.github.mesmerize08.nexusirl.settings.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Ported from upstream MoblinTests/Moblin/StreamingPlatforms/Kick/KickChatSegmentsSuite.swift @97e26d2.
class KickChatTest {
    private fun segments(message: String, emotes: List<String> = emptyList()) =
        createKickSegments(message, EmoteSet(emotes.associateWith { ThirdPartyEmote("https://cdn.example.com/$it") }), intArrayOf(0))

    private fun texts(segments: List<ChatPostSegment>) = segments.map { it.text }

    private fun names(segments: List<ChatPostSegment>) = segments.map { it.url?.name }

    @Test
    fun segmentsLikeUpstream() {
        assertEquals(listOf("hello ", "world "), texts(segments("hello world")))
        val middle = segments("hey [emote:37226:KEKW] there")
        assertEquals(listOf("hey ", null, "there "), texts(middle))
        assertEquals("https://files.kick.com/emotes/37226/fullsize", middle[1].url?.still)
        assertEquals(listOf(null, "KEKW", null), names(middle))
        assertEquals(listOf("a:b"), names(segments("[emote:1:a:b]")))
        assertEquals(listOf(null, "hi ", null), texts(segments("[emote:1:A] hi [emote:2:B]")))
        assertEquals(listOf(null, null), texts(segments("[emote:1:A][emote:2:B]")))
        assertEquals(listOf("[emote:abc:A] ", "hi "), texts(segments("[emote:abc:A] hi")))
        val thirdParty = segments("LUL [emote:1:A] LUL", listOf("LUL"))
        assertEquals(listOf("", "", null, "", ""), texts(thirdParty))
        assertEquals(listOf("LUL", null, "A", "LUL", null), names(thirdParty))
        val unique = segments("a [emote:1:A] b LUL c", listOf("LUL"))
        assertEquals(unique.size, unique.map { it.id }.toSet().size)
    }

    @Test
    fun pusherEvents() {
        val posts = mutableListOf<ChatPost>()
        val deletedMessages = mutableListOf<String>()
        val deletedUsers = mutableListOf<String>()
        val chat = KickChat(
            "someone",
            object : ChatListener {
                override fun onPost(post: ChatPost) {
                    posts += post
                }

                override fun onDeleteMessage(messageId: String) {
                    deletedMessages += messageId
                }

                override fun onDeleteUser(userId: String) {
                    deletedUsers += userId
                }
            },
            KickChatIds("1", "2"),
        )

        // Pusher sends "data" as a JSON string inside the envelope.
        fun event(type: String, data: String) =
            """{"event":"${type.replace("\\", "\\\\")}","data":${kotlinx.serialization.json.JsonPrimitive(data)}}"""
        chat.handleMessage(
            event(
                "App\\Events\\ChatMessageEvent",
                """{"id":"m1","type":"message","content":"hi [emote:5:Pog]","sender":{"id":42,"username":"viewer",
                   "identity":{"color":"#FF0000","badges":[{"type":"moderator","text":"Moderator"},{"type":"subscriber","count":3}]}}}""",
            ),
        )
        chat.handleMessage(
            event(
                "App\\Events\\ChatMessageEvent",
                """{"id":"m2","type":"reply","content":"yes","sender":{"username":"b","identity":{"color":"","badges":[]}},
                   "metadata":{"original_sender":{"username":"viewer"},"original_message":{"content":"hi"}}}""",
            ),
        )
        chat.handleMessage(event("App\\Events\\MessageDeletedEvent", """{"message":{"id":"m1"}}"""))
        chat.handleMessage(
            event(
                "App\\Events\\UserBannedEvent",
                """{"id":"x","user":{"id":42,"slug":"v","username":"viewer"},"banned_by":{"id":1,"slug":"m","username":"m"},"permanent":true}""",
            ),
        )
        chat.handleMessage("""{"event":"pusher:connection_established","data":"{}"}""")
        chat.handleMessage("not json")
        // The ban also shows in chat (an alert, which goes to chat by default).
        assertEquals(3, posts.size)
        assertEquals("User banned", posts[2].highlight?.title)
        assertEquals("was banned from chat!", posts[2].text())
        assertEquals("viewer", posts[0].displayName)
        assertEquals("42", posts[0].userId)
        assertEquals(RgbColor(255, 0, 0), posts[0].userColor)
        assertTrue(posts[0].isModerator && posts[0].isSubscriber)
        assertEquals("hi Pog", posts[0].text())
        assertEquals("Replying to viewer:", posts[1].highlight?.title)
        assertEquals(listOf("m1"), deletedMessages)
        assertEquals(listOf("42"), deletedUsers)
    }

    // Payloads as upstream's KickPusher.swift decodes them; texts as ModelKick.swift.
    @Test
    fun alerts() {
        val alerts = mutableListOf<KickAlert>()
        val chat = KickChat(
            "someone",
            object : ChatListener {
                override fun onPost(post: ChatPost) {}

                override fun onKickAlert(alert: KickAlert) {
                    alerts += alert
                }
            },
            KickChatIds("1", "2"),
        )

        fun event(type: String, data: String) =
            """{"event":"${type.replace("\\", "\\\\")}","data":${kotlinx.serialization.json.JsonPrimitive(data)}}"""
        chat.handleMessage(event("App\\Events\\SubscriptionEvent", """{"chatroom_id":1,"username":"sub","months":3}"""))
        chat.handleMessage(
            event(
                "GiftedSubscriptionsEvent",
                """{"chatroom_id":1,"gifted_usernames":["a","b"],"gifter_username":"gifter","gifter_total":7}""",
            ),
        )
        chat.handleMessage(
            event(
                "RewardRedeemedEvent",
                """{"reward_title":"Hydrate","username":"fan","user_input":"now","reward_background_color":"#fff"}""",
            ),
        )
        chat.handleMessage(event("RewardRedeemedEvent", """{"reward_title":"Wave","username":"fan","user_input":""}"""))
        chat.handleMessage(
            event("App\\Events\\StreamHostEvent", """{"chatroom_id":1,"optional_message":"","number_viewers":12,"host_username":"host"}"""),
        )
        chat.handleMessage(
            event(
                "KicksGifted",
                """{"message":"nice","sender":{"id":5,"username":"big","username_color":"#fff"},"gift":{"gift_id":"x","name":"Rage Quit","amount":100}}""",
            ),
        )
        chat.handleMessage(
            event(
                "App\\Events\\UserBannedEvent",
                """{"id":"x","user":{"id":9,"slug":"t","username":"troll"},"banned_by":{"id":1,"slug":"m","username":"m"},"permanent":false}""",
            ),
        )
        assertEquals(
            listOf(
                KickAlertType.SUBSCRIPTION,
                KickAlertType.GIFTED_SUBSCRIPTIONS,
                KickAlertType.REWARD,
                KickAlertType.REWARD,
                KickAlertType.HOST,
                KickAlertType.KICKS,
                KickAlertType.BAN,
            ),
            alerts.map { it.type },
        )
        assertEquals(
            listOf(
                "🎉 sub just subscribed! They've been subscribed for 3 months!",
                "🎁 gifter just gifted 2 subscription(s)! They've gifted 7 in total!",
                "🎁 fan redeemed Hydrate: now",
                "🎁 fan redeemed Wave",
                "📺 host is now hosting with 12 viewers!",
                "big sent Rage Quit 💎 100 nice",
                null,
            ),
            alerts.map { it.toast },
        )
        assertEquals(100, alerts[5].kicks)
        assertEquals("New subscriber", alerts[0].post.highlight?.title)
        assertEquals(ChatPlatform.KICK, alerts[0].post.platform)
        assertEquals("User timed out", alerts[6].post.highlight?.title)
        assertEquals("was timed out from chat!", alerts[6].post.text())
    }
}
