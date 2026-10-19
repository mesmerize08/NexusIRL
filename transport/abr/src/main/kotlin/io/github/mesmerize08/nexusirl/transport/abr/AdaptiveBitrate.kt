// Port of Moblin/Media/AdaptiveBitrate/AdaptiveBitrate.swift @97e26d2
package io.github.mesmerize08.nexusirl.transport.abr

import io.github.mesmerize08.nexusirl.core.clock.HostClock
import java.time.LocalTime
import java.time.format.DateTimeFormatter

const val ADAPTIVE_BITRATE_START: Long = 1_000_000
const val ADAPTIVE_BITRATE_TRANSPORT_MINIMUM: Long = ADAPTIVE_BITRATE_START

/** Upstream `defaultSrtLatency`, used when the latency is unknown. */
const val DEFAULT_SRT_LATENCY_MS: Int = 3000

/** Receives the new encoder video bitrate. */
fun interface AdaptiveBitrateListener {
    fun setVideoStreamBitrate(bitrate: Long)
}

/** Transport statistics fed to adaptive bitrate on every tick. */
data class StreamStats(
    val rttMs: Double,
    val packetsInFlight: Double,
    val transportBitrate: Long?,
    val latency: Int?,
    val mbpsSendRate: Double?,
    val relaxed: Boolean?,
) {
    /**
     * Do not push a much higher bitrate than the transport actually carries (e.g. after a static
     * scene the encoder may output far less than configured).
     */
    fun limitByTransportBitrate(bitrate: Long): Long {
        val transportBitrate = transportBitrate ?: return bitrate
        val maximumBitrate = maxOf(transportBitrate + ADAPTIVE_BITRATE_TRANSPORT_MINIMUM, (17 * transportBitrate) / 10)
        return minOf(bitrate, maximumBitrate)
    }
}

/** Tunables (upstream `AdaptiveBitrateSettings`). */
data class AdaptiveBitrateSettings(
    val packetsInFlight: Long,
    val rttDiffHighFactor: Double,
    val rttDiffHighAllowedSpike: Double,
    val rttDiffHighMinDecrease: Long,
    val pifDiffIncreaseFactor: Long,
    val minimumBitrate: Long,
) {
    companion object {
        /** "Fast IRL". */
        val FAST = AdaptiveBitrateSettings(200, 0.9, 50.0, 250_000, 100_000, 50_000)

        /** "Slow IRL". */
        val SLOW = AdaptiveBitrateSettings(500, 0.95, 100.0, 100_000, 25_000, 50_000)

        /** BELABOX defaults. */
        val BELABOX = AdaptiveBitrateSettings(200, 0.9, 50.0, 250_000, 100_000, 250_000)
    }
}

/** Base class: keeps the last few actions taken, shown in the debug overlay. */
abstract class AdaptiveBitrate(protected val listener: AdaptiveBitrateListener, protected val clock: HostClock) {
    private class ActionTaken(val timestamp: Long, val message: String)

    private val actionsTaken = ArrayDeque<ActionTaken>()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    open fun setTargetBitrate(bitrate: Long) {}

    open fun setSettings(settings: AdaptiveBitrateSettings) {}

    open fun getCurrentBitrate(): Long = 0

    fun getCurrentBitrateInKbps(): Long = getCurrentBitrate() / 1000

    open fun getCurrentMaximumBitrateInKbps(): Long = 0

    open fun getFastPif(): Long = 0

    open fun getSmoothPif(): Long = 0

    open fun update(stats: StreamStats) {
        removeOldActionsTaken()
    }

    fun getActionsTaken(): List<String> = actionsTaken.map { it.message }

    protected fun logAdaptiveAction(actionTaken: String) {
        actionsTaken.addLast(ActionTaken(clock.nanoTime(), LocalTime.now().format(timeFormatter) + " " + actionTaken))
        while (actionsTaken.size > 6) {
            actionsTaken.removeFirst()
        }
    }

    private fun removeOldActionsTaken() {
        val now = clock.nanoTime()
        while (true) {
            val first = actionsTaken.firstOrNull() ?: break
            if (now - first.timestamp > 15_000_000_000) {
                actionsTaken.removeFirst()
            } else {
                break
            }
        }
    }
}
