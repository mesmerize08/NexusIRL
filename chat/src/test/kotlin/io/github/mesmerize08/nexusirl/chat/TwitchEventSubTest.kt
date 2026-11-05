// Payloads follow Twitch's EventSub reference examples; texts and titles are upstream's.
@file:Suppress("ktlint:standard:max-line-length")

package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.chat.twitch.TwitchApi
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchBanner
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchEventSub
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchHypeTrain
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchPoll
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchPollState
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchPrediction
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchPredictionState
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchUnauthorizedException
import io.github.mesmerize08.nexusirl.chat.twitch.formatShortDuration
import io.github.mesmerize08.nexusirl.chat.twitch.formatTwitchCountdown
import io.github.mesmerize08.nexusirl.core.websocket.testing.loopbackUrl
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwitchEventSubTest {
    private fun notification(type: String, event: String) =
        """{"metadata":{"message_id":"1","message_type":"notification","message_timestamp":"2023-07-19T14:56:51.634234626Z","subscription_type":"$type","subscription_version":"1"},"payload":{"subscription":{"id":"x","type":"$type"},"event":$event}}"""

    /** A `channel.chat.notification` shaped like Twitch's reference example, with [fields] for the notice. */
    private fun chatNotification(
        notice: String,
        fields: String,
        chatter: String = "Cool_User",
        anonymous: Boolean = false,
        message: String = """{"text":"","fragments":[]}""",
        source: String? = null,
    ): String {
        // The other notice objects are null, as Twitch sends them.
        val nulls = listOf("sub", "resub", "sub_gift", "community_sub_gift", "gift_paid_upgrade", "prime_paid_upgrade", "raid", "unraid", "announcement")
            .filter { "\"$it\":" !in fields }
            .joinToString("") { "\"$it\":null," }
        val sourceFields = if (source != null) {
            """"source_broadcaster_user_id":"9999","source_broadcaster_user_login":"${source.lowercase()}","source_broadcaster_user_name":"$source","""
        } else {
            """"source_broadcaster_user_id":null,"source_broadcaster_user_login":null,"source_broadcaster_user_name":null,"""
        }
        return notification(
            "channel.chat.notification",
            """{"broadcaster_user_id":"1337","broadcaster_user_login":"cooler_user","broadcaster_user_name":"Cooler_User","chatter_user_id":"4242","chatter_user_login":"${chatter.lowercase()}","chatter_user_name":"$chatter","chatter_is_anonymous":$anonymous,"color":"#FF0000","badges":[{"set_id":"subscriber","id":"6","info":"6"}],"system_message":"system","message_id":"d62235c8-47ff-a4f4-84e8-5a29a65a9c03","message":$message,"notice_type":"$notice",$sourceFields$nulls$fields}""",
        )
    }

    /** Everything an EventSub connection reports to its listener, in order. */
    private class Recorder : ChatListener {
        val posts = mutableListOf<ChatPost>()
        val alerts = mutableListOf<TwitchAlert>()
        val banners = mutableListOf<TwitchBanner>()
        val toasts = mutableListOf<String>()

        override fun onPost(post: ChatPost) {
            posts += post
        }

        override fun onTwitchAlert(alert: TwitchAlert) {
            alerts += alert
            posts += alert.post
        }

        override fun onTwitchBanner(banner: TwitchBanner) {
            banners += banner
        }

        override fun onToast(text: String) {
            toasts += text
        }
    }

    private fun record(vararg messages: String): Recorder {
        val recorder = Recorder()
        val eventSub = TwitchEventSub("1337", recorder, api = { null })
        messages.forEach(eventSub::handleMessage)
        return recorder
    }

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
            chatNotification("sub", """"sub":{"sub_tier":"1000","is_prime":false,"duration_months":1}"""),
            chatNotification(
                "resub",
                """"resub":{"cumulative_months":3,"duration_months":0,"streak_months":null,"sub_tier":"1000","is_prime":false,"is_gift":false}""",
            ),
            chatNotification(
                "community_sub_gift",
                """"community_sub_gift":{"id":"1","total":2,"sub_tier":"1000","cumulative_total":null}""",
            ),
            notification("channel.cheer", """{"is_anonymous":false,"user_name":"Cool_User","message":"","bits":250}"""),
            notification("channel.raid", """{"from_broadcaster_user_name":"Cool_User","viewers":12}"""),
            notification(
                "channel.channel_points_custom_reward_redemption.add",
                """{"user_name":"Cool_User","user_input":"","reward":{"title":"Hydrate"}}""",
            ),
            chatNotification("watch_streak", """"watch_streak":{"streak_count":7}"""),
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
                TwitchAlertType.WATCH_STREAK,
            ),
            alerts.map { it.type },
        )
        val cheer = alerts.single { it.type == TwitchAlertType.CHEER }
        assertEquals(250, cheer.bits)
        assertEquals("Cool_User", cheer.user)
        assertEquals("just followed!", alerts.first().text)
        assertEquals(7, alerts.last().streak)
        assertTrue(alerts.none { it.sharedChat })
    }

    private fun line(post: ChatPost) = "${post.highlight?.title}: ${post.displayName} ${post.text()}"

    /** Upstream `handleNotificationChannelChatNotification` and the alert texts in ModelTwitch.swift. */
    @Test
    fun chatNotificationsLikeUpstream() {
        val recorder = record(
            chatNotification("sub", """"sub":{"sub_tier":"2000","is_prime":false,"duration_months":1}"""),
            chatNotification("sub", """"sub":{"sub_tier":"1000","is_prime":true,"duration_months":1}""", chatter = "Prime_User"),
            chatNotification(
                "resub",
                """"resub":{"cumulative_months":15,"duration_months":6,"streak_months":1,"sub_tier":"1000","is_prime":false,"is_gift":false}""",
                message = """{"text":"Love the stream! Kappa","fragments":[{"type":"text","text":"Love the stream! ","cheermote":null,"emote":null,"mention":null},{"type":"emote","text":"Kappa","cheermote":null,"emote":{"id":"25","emote_set_id":"0","owner_id":"0","format":["static"]},"mention":null}]}""",
            ),
            chatNotification(
                "resub",
                """"resub":{"cumulative_months":2,"duration_months":0,"streak_months":null,"sub_tier":"3000","is_prime":false,"is_gift":false}""",
                chatter = "Quiet",
            ),
            chatNotification(
                "sub_gift",
                """"sub_gift":{"duration_months":1,"cumulative_total":null,"recipient_user_id":"1","recipient_user_name":"Lucky","recipient_user_login":"lucky","sub_tier":"1000","community_gift_id":null}""",
            ),
            // Part of the community gift below: upstream shows only the total.
            chatNotification(
                "sub_gift",
                """"sub_gift":{"duration_months":1,"cumulative_total":null,"recipient_user_id":"1","recipient_user_name":"Lucky","recipient_user_login":"lucky","sub_tier":"1000","community_gift_id":"42"}""",
            ),
            chatNotification(
                "community_sub_gift",
                """"community_sub_gift":{"id":"42","total":5,"sub_tier":"1000","cumulative_total":null}""",
                chatter = "Anonymous",
                anonymous = true,
            ),
            chatNotification("prime_paid_upgrade", """"prime_paid_upgrade":{"sub_tier":"1000"}"""),
            chatNotification(
                "gift_paid_upgrade",
                """"gift_paid_upgrade":{"gifter_is_anonymous":false,"gifter_user_id":"1","gifter_user_name":"Giver","gifter_user_login":"giver"}""",
            ),
            chatNotification(
                "watch_streak",
                """"watch_streak":{"streak_count":12}""",
                message = """{"text":"Hi!","fragments":[{"type":"text","text":"Hi!"}]}""",
            ),
            // channel.raid reports raids into this channel; a plain "raid" notice would show it twice.
            chatNotification(
                "raid",
                """"raid":{"user_id":"1","user_name":"Raider","user_login":"raider","viewer_count":10,"profile_image_url":""}""",
            ),
            chatNotification("announcement", """"announcement":{"color":"PRIMARY"}"""),
        )
        assertEquals(
            listOf(
                "New subscriber: Cool_User just subscribed tier 2!",
                "New subscriber: Prime_User just subscribed with Prime!",
                "New resubscribe: Cool_User just resubscribed tier 1 for 15 months, 1 in a row! Love the stream! Kappa",
                "New resubscribe: Quiet just resubscribed tier 3 for 2 months!",
                "Gift subscriptions: Cool_User just gifted 1 tier 1 subscriptions!",
                "Gift subscriptions: Anonymous just gifted 5 tier 1 subscriptions!",
                "New subscriber: Cool_User just converted their Prime subscription to tier 1!",
                "New subscriber: Cool_User just continued their gift subscription!",
                "Watch streak: Cool_User just watched 12 streams in a row! Hi!",
            ),
            recorder.posts.map(::line),
        )
        val resub = recorder.posts[2]
        // Emotes in the viewer's message are Twitch emote images, as upstream `createTwitchSegments(fragments:)`.
        assertEquals("https://static-cdn.jtvnw.net/emoticons/v2/25/default/dark/3.0", resub.segments.firstNotNullOf { it.url }.moving)
        assertEquals(RgbColor(255, 0, 0), resub.userColor)
        // Anonymous chatters have no colour.
        assertNull(recorder.posts[5].userColor)
        assertEquals("just watched 12 streams in a row! Hi!", recorder.alerts.last().text)
        assertEquals(12, recorder.alerts.last().streak)
        assertEquals(RgbColor(255, 149, 0), recorder.posts.last().highlight?.barColor)
    }

    /** Alerts from shared chat partners (shared_chat_* notices) are marked so settings can hide them. */
    @Test
    fun sharedChatAlerts() {
        val recorder = record(
            chatNotification(
                "shared_chat_sub",
                """"shared_chat_sub":{"sub_tier":"1000","is_prime":false,"duration_months":1}""",
                source = "Partner",
            ),
            chatNotification(
                "shared_chat_resub",
                """"shared_chat_resub":{"cumulative_months":4,"duration_months":0,"streak_months":2,"sub_tier":"2000","is_prime":false,"is_gift":false}""",
                source = "Partner",
            ),
            chatNotification(
                "shared_chat_community_sub_gift",
                """"shared_chat_community_sub_gift":{"id":"7","total":10,"sub_tier":"1000","cumulative_total":20}""",
                source = "Partner",
            ),
            chatNotification(
                "shared_chat_raid",
                """"shared_chat_raid":{"user_id":"55","user_name":"Raider","user_login":"raider","viewer_count":321,"profile_image_url":""}""",
                source = "Partner",
            ),
        )
        assertEquals(
            listOf(
                "New subscriber: Cool_User just subscribed tier 1!",
                "New resubscribe: Cool_User just resubscribed tier 2 for 4 months, 2 in a row!",
                "Gift subscriptions: Cool_User just gifted 10 tier 1 subscriptions!",
                "Raid: Raider raided with a party of 321!",
            ),
            recorder.posts.map(::line),
        )
        assertTrue(recorder.alerts.all { it.sharedChat })
        assertEquals(
            listOf(TwitchAlertType.SUBSCRIPTION, TwitchAlertType.RESUBSCRIPTION, TwitchAlertType.GIFT_SUBSCRIPTION, TwitchAlertType.RAID),
            recorder.alerts.map { it.type },
        )
    }

    private fun epochMillis(timestamp: String) = java.time.Instant.parse(timestamp).toEpochMilli()

    /** Upstream `twitchEventSubChannelHypeTrain*`: a banner with level, progress and countdown, and chat lines at start and end. */
    @Test
    fun hypeTrain() {
        val recorder = record(
            notification(
                "channel.hype_train.begin",
                """{"id":"1b0AsbInCHZW2SQFQkCzqN07Ib2","broadcaster_user_id":"1337","broadcaster_user_login":"cool_user","broadcaster_user_name":"Cool_User","total":137,"progress":137,"goal":500,"top_contributions":[{"user_id":"123","user_login":"pogchamp","user_name":"PogChamp","type":"bits","total":50}],"shared_train_participants":null,"level":1,"started_at":"2020-07-15T17:16:03.17106713Z","expires_at":"2020-07-15T17:16:11.17106713Z","type":"regular","is_shared_train":false}""",
            ),
            notification(
                "channel.hype_train.progress",
                """{"id":"1b0AsbInCHZW2SQFQkCzqN07Ib2","broadcaster_user_id":"1337","broadcaster_user_login":"cool_user","broadcaster_user_name":"Cool_User","total":700,"progress":200,"goal":1000,"top_contributions":[],"shared_train_participants":null,"level":2,"started_at":"2020-07-15T17:16:03.17106713Z","expires_at":"2020-07-15T17:18:11.17106713Z","type":"regular","is_shared_train":false}""",
            ),
            notification(
                "channel.hype_train.end",
                """{"id":"1b0AsbInCHZW2SQFQkCzqN07Ib2","broadcaster_user_id":"1337","broadcaster_user_login":"cool_user","broadcaster_user_name":"Cool_User","total":137,"level":2,"top_contributions":[],"shared_train_participants":null,"started_at":"2020-07-15T17:16:03.17106713Z","ended_at":"2020-07-15T17:16:11.17106713Z","cooldown_ends_at":"2020-07-15T18:16:11.17106713Z","type":"regular","is_shared_train":false}""",
            ),
        )
        assertEquals(
            listOf("Hype train started: Cool_User started a hype train!", "Hype train ended: Cool_User ended the hype train at level 2!"),
            recorder.posts.map(::line),
        )
        assertEquals(ChatHighlight.Colors.PURPLE, recorder.posts[0].highlight?.barColor)
        val trains = recorder.banners.map { it as TwitchHypeTrain }
        assertEquals(listOf(1, 2, 2), trains.map { it.level })
        val begin = trains[0]
        assertEquals(27, begin.percentage())
        // Upstream drops the fraction of the timestamp and rounds the time left up.
        assertEquals("Ends in 8s", begin.message(epochMillis("2020-07-15T17:16:03.5Z")))
        assertEquals("Ends in 0s", begin.message(epochMillis("2020-07-15T17:20:00Z")))
        assertEquals("Ends in 2m 8s", trains[1].message(epochMillis("2020-07-15T17:16:03Z")))
        assertEquals(20, trains[1].percentage())
        assertEquals(100, trains[2].percentage())
        assertEquals("Ended", trains[2].message(0))
    }

    /** Upstream `twitchEventSubChannelPoll*`: a banner with votes per choice, and chat lines at start and end. */
    @Test
    fun poll() {
        val begin =
            """{"id":"1243456","broadcaster_user_id":"1337","broadcaster_user_login":"cool_user","broadcaster_user_name":"Cool_User","title":"Aren’t shoes just really hard socks?","choices":[{"id":"123","title":"Yeah!"},{"id":"124","title":"No!"},{"id":"125","title":"Maybe!"}],"bits_voting":{"is_enabled":true,"amount_per_vote":10},"channel_points_voting":{"is_enabled":true,"amount_per_vote":10},"started_at":"2020-07-15T17:16:03.17106713Z","ends_at":"2020-07-15T17:16:08.17106713Z"}"""
        val progress =
            """{"id":"1243456","broadcaster_user_id":"1337","broadcaster_user_login":"cool_user","broadcaster_user_name":"Cool_User","title":"Aren’t shoes just really hard socks?","choices":[{"id":"123","title":"Yeah!","bits_votes":5,"channel_points_votes":7,"votes":12},{"id":"124","title":"No!","bits_votes":10,"channel_points_votes":30,"votes":40},{"id":"125","title":"Maybe!","bits_votes":0,"channel_points_votes":0,"votes":0}],"bits_voting":{"is_enabled":true,"amount_per_vote":10},"channel_points_voting":{"is_enabled":true,"amount_per_vote":10},"started_at":"2020-07-15T17:16:03.17106713Z","ends_at":"2020-07-15T17:16:08.17106713Z"}"""
        val end =
            """{"id":"1243456","broadcaster_user_id":"1337","broadcaster_user_login":"cool_user","broadcaster_user_name":"Cool_User","title":"Aren’t shoes just really hard socks?","choices":[{"id":"123","title":"Blue","bits_votes":50,"channel_points_votes":70,"votes":120},{"id":"124","title":"Yellow","bits_votes":100,"channel_points_votes":300,"votes":400},{"id":"125","title":"Green","bits_votes":10,"channel_points_votes":0,"votes":10}],"bits_voting":{"is_enabled":true,"amount_per_vote":10},"channel_points_voting":{"is_enabled":true,"amount_per_vote":10},"status":"completed","started_at":"2020-07-15T17:16:03.17106713Z","ended_at":"2020-07-15T17:16:11.17106713Z"}"""
        val recorder = record(
            notification("channel.poll.begin", begin),
            notification("channel.poll.progress", progress),
            notification("channel.poll.end", end),
            // An archived poll updates the banner but says nothing in chat.
            notification("channel.poll.end", end.replace("\"completed\"", "\"archived\"")),
        )
        assertEquals(
            listOf(
                "Poll started: Cool_User started a poll: Aren’t shoes just really hard socks?",
                "Poll ended: Cool_User ended the poll: Aren’t shoes just really hard socks? Winner: Yellow",
            ),
            recorder.posts.map(::line),
        )
        assertEquals(RgbColor(88, 86, 214), recorder.posts[0].highlight?.barColor)
        val polls = recorder.banners.map { it as TwitchPoll }
        assertEquals(listOf(0, 52, 530, 530), polls.map { it.totalVotes })
        assertEquals(
            listOf(TwitchPollState.ONGOING, TwitchPollState.ONGOING, TwitchPollState.COMPLETED, TwitchPollState.COMPLETED),
            polls.map {
                it.state
            },
        )
        assertEquals("Ends in 5s", polls[1].message(epochMillis("2020-07-15T17:16:03Z")))
        assertEquals(listOf("Yeah!", "No!", "Maybe!"), polls[1].choices.map { it.title })
        assertEquals(listOf(12, 40, 0), polls[1].choices.map { it.votes })
        assertEquals("Poll ended", polls[2].message(0))
    }

    /** Upstream `twitchEventSubChannelPrediction*`: a banner with points per outcome, and chat lines at start and end. */
    @Test
    fun prediction() {
        val outcomes =
            """[{"id":"1243456","title":"Yeah!","color":"blue","users":10,"channel_points":15000,"top_predictors":[{"user_name":"Cool_User","user_login":"cool_user","user_id":"1234","channel_points_won":null,"channel_points_used":500}]},{"id":"2243456","title":"No!","color":"pink","users":5,"channel_points":5000,"top_predictors":[]}]"""
        val head = """"id":"1243456","broadcaster_user_id":"1337","broadcaster_user_login":"cool_user","broadcaster_user_name":"Cool_User","title":"Aren’t shoes just really hard socks?""""
        val recorder = record(
            notification(
                "channel.prediction.begin",
                """{$head,"outcomes":[{"id":"1243456","title":"Yeah!","color":"blue"},{"id":"2243456","title":"No!","color":"pink"}],"started_at":"2020-07-15T17:16:03.17106713Z","locks_at":"2020-07-15T17:21:03.17106713Z"}""",
            ),
            notification(
                "channel.prediction.progress",
                """{$head,"outcomes":$outcomes,"started_at":"2020-07-15T17:16:03.17106713Z","locks_at":"2020-07-15T17:21:03.17106713Z"}""",
            ),
            notification(
                "channel.prediction.lock",
                """{$head,"outcomes":$outcomes,"started_at":"2020-07-15T17:16:03.17106713Z","locked_at":"2020-07-15T17:21:03.17106713Z"}""",
            ),
            notification(
                "channel.prediction.end",
                """{$head,"winning_outcome_id":"2243456","outcomes":$outcomes,"status":"resolved","started_at":"2020-07-15T17:16:03.17106713Z","ended_at":"2020-07-15T17:16:11.17106713Z"}""",
            ),
            notification(
                "channel.prediction.end",
                """{$head,"winning_outcome_id":null,"outcomes":$outcomes,"status":"canceled","started_at":"2020-07-15T17:16:03.17106713Z","ended_at":"2020-07-15T17:16:11.17106713Z"}""",
            ),
        )
        assertEquals(
            listOf(
                "Prediction started: Cool_User started a prediction: Aren’t shoes just really hard socks?",
                "Prediction ended: Cool_User ended the prediction: Aren’t shoes just really hard socks? Outcome: No!",
                "Prediction ended: Cool_User cancelled the prediction: Aren’t shoes just really hard socks?",
            ),
            recorder.posts.map(::line),
        )
        assertEquals(RgbColor(0, 199, 190), recorder.posts[0].highlight?.barColor)
        val predictions = recorder.banners.map { it as TwitchPrediction }
        assertEquals(
            listOf(
                TwitchPredictionState.ONGOING,
                TwitchPredictionState.ONGOING,
                TwitchPredictionState.LOCKED,
                TwitchPredictionState.COMPLETED,
                TwitchPredictionState.COMPLETED,
            ),
            predictions.map { it.state },
        )
        assertEquals("Locks in 5m", predictions[1].message(epochMillis("2020-07-15T17:16:03Z")))
        assertEquals(20000, predictions[1].totalChannelPoints)
        assertEquals(listOf(10, 5), predictions[1].outcomes.map { it.users })
        assertEquals(listOf("blue", "pink"), predictions[1].outcomes.map { it.color })
        assertEquals("Locked, waiting for outcome", predictions[2].message(0))
        assertEquals("Outcome: No!", predictions[3].message(0))
        assertEquals(listOf(false, true), predictions[3].outcomes.map { it.winner })
        assertEquals("Prediction cancelled", predictions[4].message(0))
    }

    /** Upstream `twitchEventSubChannelAdBreakBegin`: a toast, whatever the alert settings. */
    @Test
    fun adBreak() {
        val recorder = record(
            notification(
                "channel.ad_break.begin",
                """{"duration_seconds":60,"started_at":"2019-11-16T10:11:12.634234626Z","is_automatic":false,"broadcaster_user_id":"1337","broadcaster_user_login":"cool_user","broadcaster_user_name":"Cool_User","requester_user_id":"1337","requester_user_login":"cool_user","requester_user_name":"Cool_User"}""",
            ),
            // Twitch's reference example quotes the numbers.
            notification(
                "channel.ad_break.begin",
                """{"duration_seconds":"90","started_at":"2019-11-16T10:11:12.634234626Z","is_automatic":"true","broadcaster_user_id":"1337","broadcaster_user_login":"cool_user","broadcaster_user_name":"Cool_User","requester_user_id":"1337","requester_user_login":"cool_user","requester_user_name":"Cool_User"}""",
            ),
        )
        assertEquals(listOf("1 min manual commercial starting", "1 min, 30 sec automatic commercial starting"), recorder.toasts)
        assertTrue(recorder.posts.isEmpty())
    }

    /** Upstream `twitchEventSubChannelShoutoutCreate`: a chat line. */
    @Test
    fun shoutout() {
        val recorder = record(
            notification(
                "channel.shoutout.create",
                """{"broadcaster_user_id":"12345","broadcaster_user_name":"SimplySimple","broadcaster_user_login":"simplysimple","moderator_user_id":"98765","moderator_user_name":"ParticularlyParticular123","moderator_user_login":"particularlyparticular123","to_broadcaster_user_id":"626262","to_broadcaster_user_name":"SandySanderman","to_broadcaster_user_login":"sandysanderman","started_at":"2022-07-26T17:00:03.17106713Z","viewer_count":860,"cooldown_ends_at":"2022-07-26T17:02:03.17106713Z","target_cooldown_ends_at":"2022-07-26T18:00:03.17106713Z"}""",
            ),
        )
        assertEquals(listOf("Shoutout sent: ParticularlyParticular123 gave a shoutout to SandySanderman!"), recorder.posts.map(::line))
        assertEquals(RgbColor(255, 149, 0), recorder.posts[0].highlight?.barColor)
    }

    /** Upstream `makeSubscriptions`, less channel.moderate and outgoing raids (no raid banner yet). */
    @Test
    fun subscriptionsLikeUpstream() {
        val eventSub = TwitchEventSub("1337", Recorder(), api = { null })
        val broadcaster = mapOf("broadcaster_user_id" to "1337")
        val moderator = broadcaster + ("moderator_user_id" to "1337")
        assertEquals(
            listOf(
                Triple("channel.follow", 2, moderator),
                Triple("channel.chat.notification", 1, broadcaster + ("user_id" to "1337")),
                Triple("channel.channel_points_custom_reward_redemption.add", 1, broadcaster),
                Triple("channel.raid", 1, mapOf("to_broadcaster_user_id" to "1337")),
                Triple("channel.cheer", 1, broadcaster),
                Triple("channel.hype_train.begin", 2, broadcaster),
                Triple("channel.hype_train.progress", 2, broadcaster),
                Triple("channel.hype_train.end", 2, broadcaster),
                Triple("channel.ad_break.begin", 1, broadcaster),
                Triple("channel.poll.begin", 1, broadcaster),
                Triple("channel.poll.progress", 1, broadcaster),
                Triple("channel.poll.end", 1, broadcaster),
                Triple("channel.prediction.begin", 1, broadcaster),
                Triple("channel.prediction.progress", 1, broadcaster),
                Triple("channel.prediction.lock", 1, broadcaster),
                Triple("channel.prediction.end", 1, broadcaster),
                Triple("channel.shoutout.create", 1, moderator),
            ),
            eventSub.subscriptions.map { Triple(it.type, it.version, it.condition) },
        )
    }

    /**
     * A sign-in from before NexusIRL asked for the new permissions: Twitch refuses those
     * subscriptions (403). The others still work, subscriptions fall back to channel.subscribe and
     * the app is told to suggest signing in again.
     */
    @Test
    fun missingPermissionsFallBack() {
        val helix = MockWebServer()
        val forbidden = setOf(
            "channel.chat.notification",
            "channel.hype_train.begin",
            "channel.hype_train.progress",
            "channel.hype_train.end",
            "channel.ad_break.begin",
            "channel.poll.begin",
            "channel.poll.progress",
            "channel.poll.end",
            "channel.prediction.begin",
            "channel.prediction.progress",
            "channel.prediction.lock",
            "channel.prediction.end",
            "channel.shoutout.create",
        )
        helix.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val type = Regex("\"type\":\"([a-z._]+)\"").find(request.body.clone().readUtf8())!!.groupValues[1]
                return if (type in forbidden) {
                    MockResponse().setResponseCode(
                        403,
                    ).setBody("""{"error":"Forbidden","status":403,"message":"subscription missing proper authorization"}""")
                } else {
                    MockResponse().setResponseCode(202).setBody("""{"data":[{"id":"x","status":"enabled"}],"total":1}""")
                }
            }
        }
        helix.start()
        val eventSubServer = MockWebServer()
        eventSubServer.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        """{"metadata":{"message_type":"session_welcome"},"payload":{"session":{"id":"s1","keepalive_timeout_seconds":10}}}""",
                    )
                }
            }),
        )
        eventSubServer.start()
        val connected = LinkedBlockingQueue<Boolean>()
        val missing = LinkedBlockingQueue<List<String>>()
        val eventSub = TwitchEventSub(
            "1337",
            object : ChatListener {
                override fun onPost(post: ChatPost) = Unit

                override fun onConnectionChanged(platform: ChatPlatform, isConnected: Boolean) {
                    connected += isConnected
                }
            },
            api = { TwitchApi("clientid123", "token", baseUrl = helix.loopbackUrl("/helix").toString().removeSuffix("/")) },
            url = eventSubServer.loopbackUrl("/ws").toString().replace("http", "ws"),
            onMissingPermissions = { missing += it },
        )
        try {
            eventSub.start()
            assertEquals(true, connected.poll(10, TimeUnit.SECONDS))
            assertEquals(forbidden.toList(), missing.poll(5, TimeUnit.SECONDS))
            val types = (1..helix.requestCount).map {
                Regex("\"type\":\"([a-z._]+)\"").find(helix.takeRequest().body.readUtf8())!!.groupValues[1]
            }
            assertEquals(
                listOf("channel.subscribe", "channel.subscription.message", "channel.subscription.gift"),
                types.filter { it.startsWith("channel.subscri") },
            )
            assertEquals(20, types.size)
        } finally {
            eventSub.stop()
            eventSubServer.shutdown()
            helix.shutdown()
        }
    }

    @Test
    fun formatting() {
        assertEquals("0s", formatTwitchCountdown(0))
        assertEquals("1h 1m 1s", formatTwitchCountdown(3661))
        assertEquals("30 sec", formatShortDuration(30))
        assertEquals("3 min", formatShortDuration(180))
        assertEquals("1 hr, 2 min", formatShortDuration(3720))
    }

    /** channel.subscribe, subscription.message and subscription.gift: the fallback for sign-ins without user:read:chat. */
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
            notification("channel.goal.begin", """{"type":"follower","description":"ignored"}"""),
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
        repeat(17) { helix.enqueue(MockResponse().setResponseCode(202).setBody("""{"data":[{"id":"x","status":"enabled"}],"total":1}""")) }
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
            api = { TwitchApi("clientid123", "token", baseUrl = helix.loopbackUrl("/helix").toString().removeSuffix("/")) },
            url = eventSubServer.loopbackUrl("/ws").toString().replace("http", "ws"),
        )
        try {
            eventSub.start()
            assertEquals(true, connected.poll(10, TimeUnit.SECONDS))
            val requests = (1..17).map { helix.takeRequest(5, TimeUnit.SECONDS)!! }
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
            val api = TwitchApi("clientid123", "token", baseUrl = server.loopbackUrl("/helix").toString().removeSuffix("/"))
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
