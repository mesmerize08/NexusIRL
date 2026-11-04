// Port of Moblin/View/Utils/BannersView.swift @97e26d2 (hype train, poll and prediction banners;
// the raid banner comes with outgoing raids).
package io.github.mesmerize08.nexusirl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.chat.ChatController
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchBanners
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchHypeTrain
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchPoll
import io.github.mesmerize08.nexusirl.chat.twitch.TwitchPrediction
import kotlinx.coroutines.delay

/** Upstream `bannerBackgroundColor`. */
private val BANNER_BACKGROUND = Color(0xFF6441A5)

/** Upstream's prediction outcome colours. */
private val PREDICTION_PINK = Color(0xFFF5009B)
private val PREDICTION_BLUE = Color(0xFF387AFF)

/** Upstream `formatPercentage`: whole percent, rounded toward zero. */
internal fun formatBannerPercentage(fraction: Double): String = "${(fraction * 100).toInt()}%"

private fun fraction(part: Int, total: Int): Double = if (total > 0) part.toDouble() / total else 0.0

/**
 * Upstream `BannersView`: the ongoing hype train, poll and prediction, each closable; tapping
 * minimizes them all to one line.
 */
@Composable
fun TwitchBannersView(modifier: Modifier = Modifier) {
    val banners by ChatController.twitchBanners.collectAsState()
    if (banners.isEmpty()) {
        return
    }
    var minimized by rememberSaveable { mutableStateOf(false) }
    // Upstream updates the countdowns every second.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BANNER_BACKGROUND, RoundedCornerShape(8.dp))
            .clickable { minimized = !minimized },
    ) {
        if (minimized) {
            MinimizedBanners(banners)
        } else {
            banners.hypeTrain?.let { HypeTrainBanner(it, now) }
            banners.poll?.let { PollBanner(it, now) }
            banners.prediction?.let { PredictionBanner(it, now) }
        }
    }
}

@Composable
private fun MinimizedBanners(banners: TwitchBanners) {
    val names = listOfNotNull(
        banners.hypeTrain?.let { stringResource(R.string.hype_train) },
        banners.poll?.let { stringResource(R.string.poll) },
        banners.prediction?.let { stringResource(R.string.prediction) },
    )
    Text(names.joinToString(" · "), color = Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
private fun BannerHeader(title: String, detail: String?, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        detail?.let { Text(it, color = Color.White) }
        OutlinedButton(onClick = onClose) { Text(stringResource(R.string.close), color = Color.White) }
    }
}

/** Upstream `OptionBarView`. */
@Composable
private fun OptionBar(title: String, detail: String, fraction: Double, color: Color, bold: Boolean) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row {
            Text(
                title,
                color = Color.White,
                fontWeight = weight,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(detail, color = Color.White, fontWeight = weight, style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(progress = {
            fraction.toFloat()
        }, color = color, trackColor = Color.White.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth())
    }
}

/** Upstream `HypeTrainView`. */
@Composable
private fun HypeTrainBanner(train: TwitchHypeTrain, now: Long) {
    Column(modifier = Modifier.padding(10.dp)) {
        BannerHeader(stringResource(R.string.hype_train), stringResource(R.string.hype_train_level, train.level)) {
            ChatController.closeTwitchBanner(TwitchBanners::withoutHypeTrain)
        }
        LinearProgressIndicator(
            progress = { train.percentage() / 100f },
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Row {
            Text("${train.percentage()}%", color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(train.message(now), color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Upstream `TwitchPollView`. */
@Composable
private fun PollBanner(poll: TwitchPoll, now: Long) {
    Column(modifier = Modifier.padding(10.dp)) {
        BannerHeader(poll.title, null) { ChatController.closeTwitchBanner(TwitchBanners::withoutPoll) }
        poll.choices.forEach { choice ->
            val fraction = fraction(choice.votes, poll.totalVotes)
            OptionBar(
                choice.title,
                stringResource(R.string.poll_choice_votes, formatBannerPercentage(fraction), choice.votes),
                fraction,
                Color.White,
                bold = false,
            )
        }
        Text(poll.message(now), color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

/** Upstream `TwitchPredictionView`. */
@Composable
private fun PredictionBanner(prediction: TwitchPrediction, now: Long) {
    Column(modifier = Modifier.padding(10.dp)) {
        BannerHeader(prediction.title, null) { ChatController.closeTwitchBanner(TwitchBanners::withoutPrediction) }
        prediction.outcomes.forEach { outcome ->
            val fraction = fraction(outcome.channelPoints, prediction.totalChannelPoints)
            OptionBar(
                outcome.title,
                stringResource(R.string.prediction_outcome_points, formatBannerPercentage(fraction), outcome.channelPoints, outcome.users),
                fraction,
                if (outcome.color == "pink") PREDICTION_PINK else PREDICTION_BLUE,
                bold = outcome.winner,
            )
        }
        Text(prediction.message(now), color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}
