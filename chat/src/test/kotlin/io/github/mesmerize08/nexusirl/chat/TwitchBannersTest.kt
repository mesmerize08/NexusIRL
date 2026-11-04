package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.chat.twitch.TwitchBanners
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchHypeTrain
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchPoll
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchPollState
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchPrediction
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchPredictionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwitchBannersTest {
    private val train = TwitchHypeTrain(level = 2, progress = 10, goal = 100, expiresAtMillis = null)
    private val poll = TwitchPoll("Poll?", emptyList(), TwitchPollState.ONGOING, null)
    private val prediction = TwitchPrediction("Prediction?", emptyList(), TwitchPredictionState.ONGOING, null)

    /** Each banner replaces only its own kind; closing one leaves the others (upstream `removeHypeTrain` and so on). */
    @Test
    fun updateAndClose() {
        val banners = TwitchBanners().with(train).with(poll).with(prediction)
        assertEquals(TwitchBanners(train, poll, prediction), banners)
        assertFalse(banners.isEmpty())
        val closed = banners.withoutPoll()
        assertNull(closed.poll)
        assertEquals(train, closed.hypeTrain)
        assertEquals(prediction, closed.prediction)
        // The next update shows it again, as upstream.
        assertEquals(poll, closed.with(poll).poll)
        assertTrue(banners.withoutHypeTrain().withoutPoll().withoutPrediction().isEmpty())
    }
}
