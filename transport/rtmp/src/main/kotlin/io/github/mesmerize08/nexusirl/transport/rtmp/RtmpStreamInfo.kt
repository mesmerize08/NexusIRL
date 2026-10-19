// Port of Moblin/Media/HaishinKit/Rtmp/RtmpStreamInfo.swift and Util/BitrateStats.swift @97e26d2
package io.github.mesmerize08.nexusirl.transport.rtmp

import io.github.mesmerize08.nexusirl.core.clock.HostClock

/** Smoothed bytes-per-second (upstream `BitrateStats`); [speedChangeRate] is a percentage. */
class BitrateStats(private val speedChangeRate: Long = 100) {
    var totalBytes = 0L
        private set
    private var previousTotalBytes = 0L
    var latestSpeed = 0L
        private set

    fun add(bytesTransferred: Int) {
        totalBytes += bytesTransferred
    }

    /** Call once per second. */
    fun update() {
        val speed = totalBytes - previousTotalBytes
        latestSpeed = (speedChangeRate * speed + (100 - speedChangeRate) * latestSpeed) / 100
        previousTotalBytes = totalBytes
    }
}

data class RtmpStreamStats(val rttMs: Double = 0.0, val packetsInFlight: Long = 0)

/**
 * RTMP transport statistics for adaptive bitrate: bytes in flight (written minus acknowledged by
 * the server, in 1400-byte "packets") and RTT measured from server Acknowledgement messages.
 */
class RtmpStreamInfo(private val clock: HostClock) {
    private class SendTiming(val timestamp: Long, val sequence: Long)

    val bitrateStats = BitrateStats(speedChangeRate = 30)

    @Volatile
    var stats = RtmpStreamStats()
        private set
    private val sendTimings = ArrayDeque<SendTiming>()
    private var latestWrittenSequence = 0L
    private var latestAckedSequenceLow = 0L
    private var latestAckedSequenceHigh = 0L

    fun clear() {
        stats = RtmpStreamStats()
        sendTimings.clear()
        latestWrittenSequence = 0
        latestAckedSequenceLow = 0
        latestAckedSequenceHigh = 0
    }

    fun onTimeout() {
        bitrateStats.update()
    }

    fun onWritten(sequence: Long) {
        latestWrittenSequence = sequence
        // Just for safety.
        if (sendTimings.size < 500) {
            sendTimings.addLast(SendTiming(clock.nanoTime(), sequence))
        }
        stats = stats.copy(packetsInFlight = packetsInFlight())
    }

    /** [sequence] is the server's 32-bit acknowledged byte count. */
    fun onAck(sequence: Long) {
        if (sequence < latestAckedSequenceLow) {
            // Twitch rolls over at Int32.max. Bug?
            latestAckedSequenceHigh += if (latestAckedSequenceLow <= Int.MAX_VALUE) {
                Int.MAX_VALUE.toLong() + 1
            } else {
                0xFFFF_FFFFL + 1
            }
        }
        latestAckedSequenceLow = sequence
        var acked: SendTiming? = null
        while (true) {
            val first = sendTimings.firstOrNull() ?: break
            if (latestAckedSequence() > first.sequence) {
                acked = first
                sendTimings.removeFirst()
            } else {
                break
            }
        }
        if (acked != null) {
            val rttMs = ((clock.nanoTime() - acked.timestamp) / 1_000_000).toDouble()
            stats = RtmpStreamStats(rttMs, packetsInFlight())
        }
    }

    private fun latestAckedSequence(): Long = latestAckedSequenceHigh + latestAckedSequenceLow

    private fun packetsInFlight(): Long = (latestWrittenSequence - latestAckedSequence()).coerceIn(0, 0xFFFF_FFFFL) / 1400
}
