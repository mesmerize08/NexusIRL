// Long golden JSON is clearer unsplit.
@file:Suppress("ktlint:standard:max-line-length")

package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.chat.youtube.YouTubeChatException
import io.github.mesmerize08.nexusirl.chat.youtube.YouTubeLiveChat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from upstream MoblinTests/Moblin/StreamingPlatforms/YouTube/YouTubeLiveChatSuite.swift @97e26d2.
class YouTubeLiveChatTest {
    private fun getLiveChat(actions: List<String>, continuation: String? = "next"): String {
        val continuations = if (continuation != null) """[{"invalidationContinuationData": {"continuation": "$continuation"}}]""" else "[]"
        val joined = actions.joinToString(",")
        return """{"continuationContents": {"liveChatContinuation": {"continuations": $continuations, "actions": [$joined]}}}"""
    }

    private fun action(item: String) = """{"addChatItemAction": {"item": {$item}}}"""

    private fun textMessage(author: String = "Viewer", runs: String, badges: String = "[]") = action(
        """"liveChatTextMessageRenderer": {"authorName": {"simpleText": "$author"}, "authorExternalChannelId": "UC123", "message": {"runs": $runs}, "authorBadges": $badges}""",
    )

    private fun badge(iconType: String) = """{"liveChatAuthorBadgeRenderer": {"icon": {"iconType": "$iconType"}}}"""

    private val emojiRun = """{"emoji": {"emojiId": "UCkszU2WH9gy1mb0dV-11UJg/1", "shortcuts": [":yt:", ":youtube:"], "image": {"thumbnails": [{"url": "https://yt3.example.com/yt.png"}]}}}"""

    private fun handle(data: String): List<ChatPost> {
        val posts = mutableListOf<ChatPost>()
        YouTubeLiveChat(
            "video",
            object : ChatListener {
                override fun onPost(post: ChatPost) {
                    posts += post
                }
            },
        ).handleGetLiveChat(data)
        return posts
    }

    private fun texts(segments: List<ChatPostSegment>) = segments.map { it.text }

    @Test
    fun textMessageAndEmoji() {
        val message = handle(getLiveChat(listOf(textMessage(runs = """[{"text": "hello world"}]""")))).single()
        assertEquals("Viewer", message.displayName)
        assertEquals("UC123", message.userId)
        assertEquals(listOf("hello ", "world "), texts(message.segments))
        assertNull(message.highlight)
        val emoji = handle(
            getLiveChat(listOf(textMessage(runs = """[{"text": "hi "}, $emojiRun, {"text": " there"}]"""))),
        ).single().segments
        assertEquals(listOf("hi ", null, "there "), texts(emoji))
        assertEquals("https://yt3.example.com/yt.png", emoji[1].url?.still)
        assertEquals("yt", emoji[1].url?.name)
        assertEquals(emoji.size, emoji.map { it.id }.toSet().size)
        val noShortcut = handle(
            getLiveChat(
                listOf(textMessage(runs = """[{"emoji": {"image": {"thumbnails": [{"url": "https://yt3.example.com/a.png"}]}}}]""")),
            ),
        ).single()
        assertNull(noShortcut.segments.single().url?.name)
        assertEquals(
            listOf("hi "),
            texts(
                handle(
                    getLiveChat(listOf(textMessage(runs = """[{"text": "hi"}, {"emoji": {"image": {"thumbnails": []}}}]"""))),
                ).single().segments,
            ),
        )
    }

    @Test
    fun rolesAndEmpty() {
        val posts = handle(
            getLiveChat(
                listOf(
                    textMessage(runs = """[{"text": "a"}]""", badges = "[${badge("OWNER")}]"),
                    textMessage(runs = """[{"text": "b"}]""", badges = "[${badge("MODERATOR")}]"),
                    textMessage(runs = """[{"text": "c"}]""", badges = "[${badge("VERIFIED")}, {}]"),
                    textMessage(runs = "[]"),
                ),
            ),
        )
        assertEquals(listOf(true, true, false), posts.map { it.isModerator })
    }

    @Test
    fun paidAndMemberships() {
        val paid = handle(
            getLiveChat(
                listOf(
                    action(
                        """"liveChatPaidMessageRenderer": {"authorName": {"simpleText": "Fan"}, "purchaseAmountText": {"simpleText": "${'$'}5.00"}, "message": {"runs": [{"text": "great stream"}]}}""",
                    ),
                ),
            ),
        ).single()
        assertEquals("Super Chat", paid.highlight?.title)
        assertNull(paid.userId)
        assertTrue(paid.text().contains("\$5.00"))
        assertTrue(paid.text().endsWith("great stream"))
        val sticker = handle(
            getLiveChat(
                listOf(
                    action(
                        """"liveChatPaidStickerRenderer": {"authorName": {"simpleText": "Fan"}, "purchaseAmountText": {"simpleText": "€2.00"}}""",
                    ),
                ),
            ),
        ).single()
        assertEquals("Super Sticker", sticker.highlight?.title)
        assertTrue(sticker.text().contains("€2.00"))
        val member = handle(
            getLiveChat(listOf(action(""""liveChatMembershipItemRenderer": {"authorName": {"simpleText": "Member"}}"""))),
        ).single()
        assertTrue(member.segments.isEmpty())
        assertEquals("Member", member.highlight?.title)
        val ordered = handle(
            getLiveChat(
                listOf(
                    action(
                        """"liveChatMembershipItemRenderer": {"authorName": {"simpleText": "Member"}, "headerSubtext": {"runs": [{"text": "Welcome"}]}, "message": {"runs": [{"text": "hi"}]}}""",
                    ),
                ),
            ),
        ).single()
        assertEquals(listOf("Welcome ", "hi "), texts(ordered.segments))
    }

    @Test
    fun gifts() {
        val moderator = badge("MODERATOR")
        val purchase = handle(
            getLiveChat(
                listOf(
                    action(
                        """"liveChatSponsorshipsGiftPurchaseAnnouncementRenderer": {"header": {"liveChatSponsorshipsHeaderRenderer": {"authorName": {"simpleText": "Gifter"}, "authorExternalChannelId": "UC456", "primaryText": {"runs": [{"text": "Gifted "}, {"text": "5"}, {"text": " memberships"}]}, "authorBadges": [$moderator]}}}""",
                    ),
                ),
            ),
        ).single()
        assertEquals("UC456", purchase.userId)
        assertTrue(purchase.isModerator)
        assertEquals("Gifted Memberships", purchase.highlight?.title)
        assertEquals(listOf("Gifted ", "5 ", "memberships "), texts(purchase.segments))
        assertTrue(
            handle(
                getLiveChat(
                    listOf(
                        action(
                            """"liveChatSponsorshipsGiftPurchaseAnnouncementRenderer": {"header": {"liveChatSponsorshipsHeaderRenderer": {"authorName": {"simpleText": "Gifter"}}}}""",
                        ),
                    ),
                ),
            ).isEmpty(),
        )
        val jewels = handle(
            getLiveChat(
                listOf(
                    action(
                        """"giftMessageViewModel": {"authorName": {"content": "Jeweler"}, "text": {"content": "sent Girl power for 50 Jewels"}}""",
                    ),
                ),
            ),
        ).single()
        assertEquals("Jewels", jewels.highlight?.title)
        assertEquals("sent Girl power for 50 Jewels", jewels.text())
    }

    @Test
    fun unknownAndErrors() {
        val posts = handle(
            getLiveChat(
                listOf(
                    """{"markChatItemAsDeletedAction": {"targetItemId": "abc"}}""",
                    action(""""liveChatViewerEngagementMessageRenderer": {"id": "x"}"""),
                    textMessage(runs = """[{"text": "hi"}]"""),
                ),
            ),
        )
        assertEquals(1, posts.size)
        assertTrue(
            handle(
                """{"continuationContents": {"liveChatContinuation": {"continuations": [{"invalidationContinuationData": {"continuation": "abc"}}]}}}""",
            ).isEmpty(),
        )
        assertFailsWith<YouTubeChatException> { handle(getLiveChat(emptyList(), continuation = null)) }
        assertFailsWith<YouTubeChatException> { handle("{}") }
    }

    @Test
    fun videoIdFromHandle() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""<html><link rel="shortlinkUrl" href="https://youtu.be/abc123XYZ"></html>"""))
        server.start()
        try {
            assertEquals("abc123XYZ", YouTubeLiveChat.fetchVideoId("@someone", baseUrl = server.url("").toString().removeSuffix("/")))
            assertEquals("/@someone/live", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }
}
