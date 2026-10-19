// Port of Moblin/Media/AdaptiveBitrate/AdaptiveBitrateSrtBelabox.swift @97e26d2
// Adaptive bitrate algorithm from Belacoder, designed by rationalsa for the BELABOX project
// (https://github.com/BELABOX/belacoder); this is a port of Moblin's MIT re-implementation.
package io.github.mesmerize08.nexusirl.transport.abr

import io.github.mesmerize08.nexusirl.core.clock.HostClock

private const val BITRATE_INCR_MIN = 100L * 1000
private const val BITRATE_INCR_INTERVAL_NANOS = 400_000_000L
private const val BITRATE_INCR_SCALE = 30L
private const val BITRATE_DECR_MIN = 100L * 1000
private const val BITRATE_DECR_INTERVAL_NANOS = 200_000_000L
private const val BITRATE_DECR_FAST_INTERVAL_NANOS = 250_000_000L
private const val BITRATE_DECR_SCALE = 10L

/** The BELABOX algorithm. Call [update] every 20 ms while live. */
class AdaptiveBitrateSrtBelabox(
    targetBitrate: Long,
    listener: AdaptiveBitrateListener,
    clock: HostClock,
) : AdaptiveBitrate(listener, clock) {
    private var targetBitrate = targetBitrate
    private var settings = AdaptiveBitrateSettings.BELABOX
    private var sendBufferSizeAverage = 0.0
    private var sendBufferSizeJitter = 0.0
    private var prevSendBufferSize = 0.0
    private var rttAverage = 0.0
    private var rttAverageDelta = 0.0
    private var prevRtt = 300.0
    private var rttMin = 200.0
    private var rttJitter = 0.0
    private var throughput = 0.0
    private var nextBitrateIncrTime = clock.nanoTime()
    private var nextBitrateDecrTime = clock.nanoTime()
    private var currentBitrate = ADAPTIVE_BITRATE_START

    override fun setTargetBitrate(bitrate: Long) {
        targetBitrate = bitrate
    }

    override fun setSettings(settings: AdaptiveBitrateSettings) {
        this.settings = settings
    }

    override fun getCurrentBitrate(): Long = currentBitrate

    override fun getCurrentMaximumBitrateInKbps(): Long = currentBitrate / 1000

    private fun rttToSendBufferSize(rtt: Double, throughput: Double): Double = (throughput / 8) * rtt / 1316

    private fun updateBitrate(stats: StreamStats) {
        if (stats.rttMs == 0.0) {
            return
        }
        val sendBufferSize = stats.packetsInFlight
        sendBufferSizeAverage = sendBufferSizeAverage * 0.99 + sendBufferSize * 0.01
        sendBufferSizeJitter *= 0.99
        val deltaSendBufferSize = sendBufferSize - prevSendBufferSize
        if (deltaSendBufferSize > sendBufferSizeJitter) {
            sendBufferSizeJitter = deltaSendBufferSize
        }
        prevSendBufferSize = sendBufferSize
        val rtt = stats.rttMs
        rttAverage = if (rttAverage == 0.0) rtt else rttAverage * 0.99 + 0.01 * rtt
        val deltaRtt = rtt - prevRtt
        rttAverageDelta = rttAverageDelta * 0.8 + deltaRtt * 0.2
        prevRtt = rtt
        rttMin *= 1.001
        if (rtt != 100.0 && rtt < rttMin && rttAverageDelta < 1.0) {
            rttMin = rtt
        }
        rttJitter *= 0.99
        if (deltaRtt > rttJitter) {
            rttJitter = deltaRtt
        }
        throughput *= 0.97
        throughput += ((stats.mbpsSendRate ?: 0.0) * 1000.0 * 1000.0 / 1024.0) * 0.03
        val srtLatency = (stats.latency ?: DEFAULT_SRT_LATENCY_MS).toDouble()
        val currentTime = clock.nanoTime()
        var bitrate = currentBitrate
        val sendBufferSizeTh3 = (sendBufferSizeAverage + sendBufferSizeJitter) * 4
        var sendBufferSizeTh2 = maxOf(50.0, sendBufferSizeAverage + maxOf(sendBufferSizeJitter * 3.0, sendBufferSizeAverage))
        sendBufferSizeTh2 = minOf(sendBufferSizeTh2, rttToSendBufferSize(srtLatency / 2, throughput))
        if (stats.relaxed == true) {
            sendBufferSizeTh2 *= 2
        }
        val sendBufferSizeTh1 = maxOf(50.0, sendBufferSizeAverage + sendBufferSizeJitter * 2.5)
        val rttThMax = rttAverage + maxOf(rttJitter * 4, rttAverage * 15 / 100)
        val rttThMin = rttMin + maxOf(1.0, rttJitter * 2)
        if (bitrate > settings.minimumBitrate && (rtt >= srtLatency / 3 || sendBufferSize > sendBufferSizeTh3)) {
            bitrate = settings.minimumBitrate
            nextBitrateDecrTime = currentTime + BITRATE_DECR_INTERVAL_NANOS
            logAdaptiveAction("Set min: ${bitrate / 1000}, rtt: $rtt >= latency / 3: ${srtLatency / 3} or bs: $sendBufferSize > bs_th3: ${"%.2f".format(sendBufferSizeTh3)}")
        } else if (currentTime > nextBitrateDecrTime && (rtt > srtLatency / 5 || sendBufferSize > sendBufferSizeTh2)) {
            bitrate -= BITRATE_DECR_MIN + bitrate / BITRATE_DECR_SCALE
            nextBitrateDecrTime = currentTime + BITRATE_DECR_FAST_INTERVAL_NANOS
            logAdaptiveAction("Fast decr: ${(BITRATE_DECR_MIN + bitrate / BITRATE_DECR_SCALE) / 1000}, rtt: $rtt > latency / 5: ${srtLatency / 5} or bs: $sendBufferSize > bs_th2: ${"%.2f".format(sendBufferSizeTh2)}")
        } else if (currentTime > nextBitrateDecrTime && (rtt > rttThMax || sendBufferSize > sendBufferSizeTh1)) {
            bitrate -= BITRATE_DECR_MIN
            nextBitrateDecrTime = currentTime + BITRATE_DECR_INTERVAL_NANOS
            logAdaptiveAction("Decr: ${BITRATE_DECR_MIN / 1000}, rtt: $rtt > rtt_th_max: ${"%.2f".format(rttThMax)} or bs: $sendBufferSize > bs_th1: ${"%.2f".format(sendBufferSizeTh1)}")
        } else if (currentTime > nextBitrateIncrTime && rtt < rttThMin && rttAverageDelta < 0.01) {
            bitrate += BITRATE_INCR_MIN + bitrate / BITRATE_INCR_SCALE
            nextBitrateIncrTime = currentTime + BITRATE_INCR_INTERVAL_NANOS
        }
        bitrate = stats.limitByTransportBitrate(bitrate)
        bitrate = maxOf(minOf(bitrate, targetBitrate), settings.minimumBitrate)
        if (bitrate != currentBitrate) {
            currentBitrate = bitrate
            listener.setVideoStreamBitrate(bitrate)
        }
    }

    override fun update(stats: StreamStats) {
        updateBitrate(stats)
        super.update(stats)
    }
}
