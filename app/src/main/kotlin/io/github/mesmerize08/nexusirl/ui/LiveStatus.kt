// Compact take on upstream's stream overlay (Moblin/View/Stream/Overlay/StreamOverlay*.swift):
// the state, uptime, bitrate and audio level that matter while live, always in view.
package io.github.mesmerize08.nexusirl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.stream.StreamController
import io.github.mesmerize08.nexusirl.stream.StreamState
import io.github.mesmerize08.nexusirl.stream.StreamStatus
import io.github.mesmerize08.nexusirl.stream.ThermalState
import io.github.mesmerize08.nexusirl.transport.srt.srtla.formatBondingStatistics
import kotlinx.coroutines.delay
import java.util.Locale

private val LIVE_RED = Color(0xFFE53935)
private val WAITING_AMBER = Color(0xFFFFB300)
private val GOOD_GREEN = Color(0xFF43A047)

/** "1:02:03" or "2:03". */
fun formatUptime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}

/** Audio meter fill for a dBFS level: -60 dB and below is empty, 0 dB is full. */
fun audioMeterFraction(dbfs: Float): Float = ((dbfs + 60f) / 60f).coerceIn(0f, 1f)

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Stream state, uptime, bitrate, fps, RTT, audio level, recording and thermal warnings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveStatusBar(status: StreamStatus) {
    val recording by StreamController.recording.collectAsState()
    var recordingSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recording) {
        while (recording != null) {
            recordingSeconds = (android.os.SystemClock.elapsedRealtime() - recording!!.startedAtElapsedMillis) / 1000
            delay(500)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (status.state) {
                StreamState.LIVE -> Badge(stringResource(R.string.status_live) + "  " + formatUptime(status.uptimeSeconds), LIVE_RED)
                StreamState.CONNECTING -> Badge(stringResource(R.string.status_connecting), WAITING_AMBER)
                StreamState.RECONNECTING -> Badge(stringResource(R.string.status_reconnecting, status.reconnects), WAITING_AMBER)
                StreamState.IDLE, StreamState.STOPPED -> Badge(stringResource(R.string.status_ready), Color.DarkGray)
            }
            recording?.let { Badge("● REC " + formatUptime(recordingSeconds), LIVE_RED) }
            if (status.thermal != ThermalState.NORMAL) {
                Badge(
                    stringResource(R.string.status_thermal, status.thermal.name.lowercase()),
                    if (status.thermal == ThermalState.FAIR) WAITING_AMBER else LIVE_RED,
                )
            }
        }
        if (status.active) {
            Text(
                String.format(
                    Locale.US,
                    "%.1f Mbps%s · %d fps · RTT %d ms",
                    status.videoBitrateKbps / 1000.0,
                    // Upstream's "x3" when also streaming to more RTMP destinations.
                    if (status.destinations > 1) " x${status.destinations}" else "",
                    status.fps,
                    status.rttMs.toInt(),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            // Upstream's bonding status: each network's share of the data, e.g. "60% Cellular, 40% WiFi".
            formatBondingStatistics(status.bonding)?.let { Text(it.usage, style = MaterialTheme.typography.bodySmall) }
        }
        if (status.active || recording != null) {
            // Peak level: green, amber above -12 dBFS, red above -3 dBFS (clipping soon).
            val fraction = audioMeterFraction(status.audioLevelDbfs)
            val color = when {
                status.audioLevelDbfs > -3f -> LIVE_RED
                status.audioLevelDbfs > -12f -> WAITING_AMBER
                else -> GOOD_GREEN
            }
            Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Color.DarkGray, RoundedCornerShape(3.dp))) {
                Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color, RoundedCornerShape(3.dp)))
            }
            Text(status.microphone, style = MaterialTheme.typography.bodySmall)
        }
    }
}
