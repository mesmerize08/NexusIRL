// Port of Moblin/Media/AdaptiveBitrate/AdaptiveBitrateSrtFight.swift @97e26d2
package io.github.mesmerize08.nexusirl.transport.abr

import io.github.mesmerize08.nexusirl.core.clock.HostClock

/**
 * The "IRL" (fast/slow/custom) algorithm. Call [update] every 200 ms while live.
 *
 * Upstream notes: tested to 15000 kbps, 2000 ms latency, RTT generally under 100 ms. RTT over
 * 450 ms is unacceptable. The current maximum is the real calculated bitrate; the output bounces
 * between 0 and it with packets-in-flight spikes, which yields a higher overall bitrate than
 * dropping low and climbing back slowly.
 *
 * [rttMax] is 250 for SRT and 500 for RTMP; [pifMax] is 100.
 */
class AdaptiveBitrateSrtFight(
    targetBitrate: Long,
    listener: AdaptiveBitrateListener,
    clock: HostClock,
    private val rttMax: Double = 250.0,
    private val pifMax: Double = 100.0,
) : AdaptiveBitrate(listener, clock) {
    private var avgRtt = 0.0
    private var fastRtt = 0.0
    private var currentBitrate = ADAPTIVE_BITRATE_START
    private var previousBitrate = ADAPTIVE_BITRATE_START
    private var targetBitrate = targetBitrate
    private var currentMaximumBitrate = ADAPTIVE_BITRATE_START
    private var smoothPif = 0.0
    private var fastPif = 0.0
    private var settings = AdaptiveBitrateSettings.FAST

    override fun setTargetBitrate(bitrate: Long) {
        targetBitrate = bitrate
    }

    override fun setSettings(settings: AdaptiveBitrateSettings) {
        this.settings = settings
    }

    override fun getCurrentBitrate(): Long = currentBitrate

    override fun getCurrentMaximumBitrateInKbps(): Long = currentMaximumBitrate / 1000

    override fun getFastPif(): Long = fastPif.toLong()

    override fun getSmoothPif(): Long = smoothPif.toLong()

    override fun update(stats: StreamStats) {
        calcPifs(stats)
        calcRtts(stats)
        increaseCurrentMaxBitrate(stats, allowedRttJitter = 15.0, allowedPifJitter = 10.0)
        // Slow decreases if needed. (Upstream uses fixed 0.9 / 250k for the first two.)
        decreaseMaxRateIfPifIsHigh(factor = 0.9, minimumDecrease = 250_000)
        decreaseMaxRateIfRttIsHigh(factor = 0.9, minimumDecrease = 250_000)
        decreaseMaxRateIfRttDiffIsHigh(stats, settings.rttDiffHighFactor, settings.rttDiffHighAllowedSpike, settings.rttDiffHighMinDecrease)
        calculateCurrentBitrate(stats)
        if (previousBitrate != currentBitrate) {
            listener.setVideoStreamBitrate(currentBitrate)
            previousBitrate = currentBitrate
        }
        super.update(stats)
    }

    private fun calcPifs(stats: StreamStats) {
        if (stats.packetsInFlight > smoothPif) {
            smoothPif = smoothPif * 0.97 + stats.packetsInFlight * 0.03
        } else {
            smoothPif = smoothPif * 0.9 + stats.packetsInFlight * 0.1
        }
        fastPif = fastPif * 0.67 + stats.packetsInFlight * 0.33
    }

    private fun calcRtts(stats: StreamStats) {
        if (avgRtt < 1) {
            avgRtt = stats.rttMs
        }
        if (avgRtt > stats.rttMs) {
            avgRtt = avgRtt * 0.60 + stats.rttMs * 0.40
        } else {
            avgRtt *= 0.96
            avgRtt += if (stats.rttMs < 450) stats.rttMs * 0.04 else 450 * 0.001
        }
        if (fastRtt > stats.rttMs) {
            fastRtt = fastRtt * 0.70 + stats.rttMs * 0.30
        } else {
            fastRtt = fastRtt * 0.90 + stats.rttMs * 0.10
        }
        if (avgRtt > 450) {
            avgRtt = 450.0
        }
    }

    private fun increaseCurrentMaxBitrate(stats: StreamStats, allowedRttJitter: Double, allowedPifJitter: Double) {
        val pifSpikeDiff = (stats.packetsInFlight - smoothPif).toLong().coerceIn(0, settings.packetsInFlight)
        val pifDiffThing = settings.packetsInFlight - pifSpikeDiff
        if (smoothPif < settings.packetsInFlight && fastRtt <= avgRtt + allowedRttJitter) {
            if (stats.packetsInFlight - smoothPif < allowedPifJitter) {
                currentMaximumBitrate += (settings.pifDiffIncreaseFactor * pifDiffThing) / settings.packetsInFlight
                if (currentMaximumBitrate > targetBitrate) {
                    currentMaximumBitrate = targetBitrate
                }
            }
        }
    }

    private fun decreaseMaxRateIfPifIsHigh(factor: Double, minimumDecrease: Long) {
        if (smoothPif <= pifMax) {
            return
        }
        val decrease = maxOf((currentMaximumBitrate * (1 - factor)).toLong(), minimumDecrease)
        currentMaximumBitrate -= decrease
        logAdaptiveAction("PIF: Decreasing bitrate by ${decrease / 1000}k, smooth ${smoothPif.toInt()} > max ${pifMax.toInt()}")
    }

    private fun decreaseMaxRateIfRttIsHigh(factor: Double, minimumDecrease: Long) {
        if (avgRtt <= rttMax) {
            return
        }
        val decrease = maxOf((currentMaximumBitrate * (1 - factor)).toLong(), minimumDecrease)
        currentMaximumBitrate -= decrease
        logAdaptiveAction("RTT: Decrease bitrate by $decrease, avg $avgRtt > max $rttMax")
    }

    private fun decreaseMaxRateIfRttDiffIsHigh(stats: StreamStats, factor: Double, rttSpikeAllowed: Double, minimumDecrease: Long) {
        if (stats.rttMs <= avgRtt + rttSpikeAllowed) {
            return
        }
        val decrease = maxOf((currentMaximumBitrate * (1 - factor)).toLong(), minimumDecrease)
        currentMaximumBitrate -= decrease
        logAdaptiveAction(
            "RTT: Decreasing bitrate by ${decrease / 1000}k, ${stats.rttMs.toInt()} > avg + allow ${avgRtt.toInt()} + ${rttSpikeAllowed.toInt()}",
        )
    }

    private fun calculateCurrentBitrate(stats: StreamStats) {
        var pifSpikeDiff = fastPif.toLong() - smoothPif.toLong()
        // Lazy decrease.
        if (pifSpikeDiff > settings.packetsInFlight) {
            logAdaptiveAction("PIF: Lazy decrease diff $pifSpikeDiff > ${settings.packetsInFlight}")
            currentMaximumBitrate = (currentMaximumBitrate * 0.95).toLong()
        }
        if (pifSpikeDiff <= settings.packetsInFlight / 5) {
            pifSpikeDiff = 0
        }
        pifSpikeDiff = pifSpikeDiff.coerceIn(0, settings.packetsInFlight)
        // Harder decrease.
        if (pifSpikeDiff == settings.packetsInFlight) {
            currentMaximumBitrate -= 500_000
            logAdaptiveAction("PIF: -500 dec diff $pifSpikeDiff == ${settings.packetsInFlight}")
        }
        val pifDiffThing = settings.packetsInFlight - pifSpikeDiff
        currentMaximumBitrate = stats.limitByTransportBitrate(currentMaximumBitrate)
        val minimumBitrate = maxOf(50_000, settings.minimumBitrate)
        if (currentMaximumBitrate < minimumBitrate) {
            currentMaximumBitrate = minimumBitrate
        }
        currentBitrate = currentMaximumBitrate * pifDiffThing / settings.packetsInFlight
        if (currentBitrate < settings.minimumBitrate) {
            currentBitrate = settings.minimumBitrate
        }
        // PIF running away: temporarily drop to the minimum.
        if ((fastPif - smoothPif).toInt() > settings.packetsInFlight * 2) {
            currentBitrate = settings.minimumBitrate
        }
    }
}
