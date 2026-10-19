// Port of the SRT adaptive bitrate driving in Moblin/Various/Media.swift (updateAdaptiveBitrate,
// updateAdaptiveBitrateSrtBela/SrtFight, updateSrtTransportBitrate, streamTransportBitrate) and the
// 20 ms / 1 s timers in Moblin/Various/Model/Model.swift @97e26d2.
package io.github.mesmerize08.nexusirl.transport.srt

import io.github.mesmerize08.nexusirl.core.clock.Cancellable
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrate
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSrtBelabox
import io.github.mesmerize08.nexusirl.transport.abr.StreamStats

/**
 * Feeds [SrtStream] statistics to an [AdaptiveBitrate] algorithm: Belabox every 20 ms, the others
 * every 200 ms, with the transport bitrate smoothed every second. Runs on the stream's executor.
 */
class SrtAdaptiveBitrateDriver(
    private val stream: SrtStream,
    private val adaptiveBitrate: AdaptiveBitrate,
    private val executor: SerialExecutor,
    targetBitrate: Long,
    /** Upstream disables the transport bitrate limit in CBR mode. */
    private val limitByTransportBitrate: Boolean = true,
) {
    private var tickCount = 0L
    private var transportBitrate = targetBitrate
    private var previousTotalByteCount = 0L
    private var tick20ms: Cancellable? = null
    private var tick1s: Cancellable? = null

    fun start() {
        executor.execute {
            tick20ms = executor.scheduleRepeating(20_000_000) { update() }
            tick1s = executor.scheduleRepeating(1_000_000_000) { updateTransportBitrate() }
        }
    }

    fun stop() {
        executor.execute {
            tick20ms?.cancel()
            tick1s?.cancel()
        }
    }

    /** Current smoothed transport bitrate in bits per second (the "Bt" debug line upstream). */
    val currentTransportBitrate: Long
        get() = transportBitrate

    private fun update() {
        tickCount += 1
        val is200MsTick = tickCount % 10 == 0L
        if (!stream.isConnected) {
            return
        }
        if (adaptiveBitrate !is AdaptiveBitrateSrtBelabox && !is200MsTick) {
            return
        }
        val stats = stream.getPerformanceData() ?: return
        adaptiveBitrate.update(
            StreamStats(
                rttMs = stats.msRtt,
                packetsInFlight = stats.pktFlightSize.toDouble(),
                transportBitrate = if (limitByTransportBitrate) transportBitrate else null,
                latency = stream.latency,
                mbpsSendRate = stats.mbpsSendRate,
                relaxed = false,
            ),
        )
    }

    private fun updateTransportBitrate() {
        val total = stream.getTotalByteCount()
        val byteCount = maxOf(total - previousTotalByteCount, 0)
        transportBitrate = (transportBitrate * 0.7 + 8.0 * byteCount * 0.3).toLong()
        previousTotalByteCount = total
    }
}
