// Port of HypeTrain, TwitchPoll and TwitchPrediction in Moblin/Various/Model/Model.swift, their
// updates in Moblin/Various/Model/ModelTwitch.swift and the texts in Moblin/View/Utils/BannersView.swift
// @97e26d2.
package io.github.mesmerize08.nexusirl.chat.twitch

import java.time.Instant
import kotlin.math.min

/** A hype train, poll or prediction, shown as a banner over the preview until closed. */
sealed interface TwitchBanner

/**
 * The banners showing (upstream `Model.hypeTrain`, `twitchPoll` and `twitchPrediction`). Closing
 * one hides it until its next update, as upstream.
 */
data class TwitchBanners(val hypeTrain: TwitchHypeTrain? = null, val poll: TwitchPoll? = null, val prediction: TwitchPrediction? = null) {
    fun with(banner: TwitchBanner): TwitchBanners = when (banner) {
        is TwitchHypeTrain -> copy(hypeTrain = banner)
        is TwitchPoll -> copy(poll = banner)
        is TwitchPrediction -> copy(prediction = banner)
    }

    fun withoutHypeTrain() = copy(hypeTrain = null)

    fun withoutPoll() = copy(poll = null)

    fun withoutPrediction() = copy(prediction = null)

    fun isEmpty(): Boolean = hypeTrain == null && poll == null && prediction == null
}

/** Upstream `parseTwitchTimestamp`: the fraction of a second is dropped. Epoch milliseconds. */
internal fun parseTwitchTimestamp(value: String?): Long? {
    value ?: return null
    val whole = value.indexOf('.').let { if (it < 0) value else value.substring(0, it) + "Z" }
    return runCatching { Instant.parse(whole).toEpochMilli() }.getOrNull()
}

/** Upstream `uptimeFormatter` (abbreviated, zero units left out), e.g. "1h 2m 3s". */
fun formatTwitchCountdown(seconds: Long): String {
    val parts = listOf(seconds / 86_400 to "d", seconds / 3600 % 24 to "h", seconds / 60 % 60 to "m", seconds % 60 to "s")
        .filter { it.first > 0 }
    return if (parts.isEmpty()) "0s" else parts.joinToString(" ") { "${it.first}${it.second}" }
}

/** Upstream `formatTwitchCountdown`: the time left until [targetMillis], rounded up. */
private fun countdown(targetMillis: Long, nowMillis: Long): String =
    formatTwitchCountdown((targetMillis - nowMillis).coerceAtLeast(0).let { (it + 999) / 1000 })

/** Upstream `formatShortDuration` (`DateComponentsFormatter`, short style in English), e.g. "1 min, 30 sec". */
fun formatShortDuration(seconds: Int): String {
    val days = seconds / 86_400
    val parts = listOf(
        days to if (days == 1) "day" else "days",
        seconds / 3600 % 24 to "hr",
        seconds / 60 % 60 to "min",
        seconds % 60 to "sec",
    ).filter { it.first > 0 }
    return if (parts.isEmpty()) "0 sec" else parts.joinToString(", ") { "${it.first} ${it.second}" }
}

/**
 * Upstream `HypeTrain`: the level, progress towards the next level and when it expires; ended
 * trains show full progress and "Ended".
 */
data class TwitchHypeTrain(val level: Int, val progress: Int, val goal: Int, val expiresAtMillis: Long?, val ended: Boolean = false) :
    TwitchBanner {
    /** Upstream `HypeTrainProgressView.percentage`. */
    fun percentage(): Int = if (goal <= 0) 0 else (100 * min(progress.toFloat() / goal, 1f)).toInt()

    /** Upstream `updateHypeTrainCountdown`. */
    fun message(nowMillis: Long): String = when {
        ended -> "Ended"
        expiresAtMillis != null -> "Ends in ${countdown(expiresAtMillis, nowMillis)}"
        else -> ""
    }
}

enum class TwitchPollState { ONGOING, COMPLETED }

data class TwitchPollChoice(val id: String, val title: String, val votes: Int)

/** Upstream `TwitchPoll`. */
data class TwitchPoll(
    val title: String,
    val choices: List<TwitchPollChoice>,
    val state: TwitchPollState,
    val endsAtMillis: Long?,
    /** Shown when there is no countdown: "Poll ended", or the last countdown of an archived poll. */
    val endedMessage: String = "",
) : TwitchBanner {
    val totalVotes: Int get() = choices.sumOf { it.votes }

    /** Upstream `updateTwitchPollCountdown`. */
    fun message(nowMillis: Long): String =
        if (state == TwitchPollState.ONGOING && endsAtMillis != null) "Ends in ${countdown(endsAtMillis, nowMillis)}" else endedMessage
}

enum class TwitchPredictionState { ONGOING, LOCKED, COMPLETED }

data class TwitchPredictionOutcome(
    val id: String,
    val title: String,
    /** "blue" or "pink". */
    val color: String,
    val users: Int,
    val channelPoints: Int,
    val winner: Boolean,
)

/** Upstream `TwitchPrediction`. */
data class TwitchPrediction(
    val title: String,
    val outcomes: List<TwitchPredictionOutcome>,
    val state: TwitchPredictionState,
    val locksAtMillis: Long?,
) : TwitchBanner {
    val totalChannelPoints: Int get() = outcomes.sumOf { it.channelPoints }

    /** Upstream `updateTwitchPredictionCountdown` and the lock and end messages. */
    fun message(nowMillis: Long): String = when (state) {
        TwitchPredictionState.ONGOING -> locksAtMillis?.let { "Locks in ${countdown(it, nowMillis)}" }.orEmpty()
        TwitchPredictionState.LOCKED -> "Locked, waiting for outcome"
        TwitchPredictionState.COMPLETED -> outcomes.firstOrNull { it.winner }?.let { "Outcome: ${it.title}" } ?: "Prediction cancelled"
    }
}
