package io.github.mesmerize08.nexusirl.media.pipeline

import io.github.mesmerize08.nexusirl.media.pipeline.ingest.BufferedAudioClock

/**
 * The ingest audio output's ticks: one per [frames] on an absolute schedule of the host clock,
 * each with its PTS from a [BufferedAudioClock] grid (upstream `BufferedAudio.output()`). The
 * grid follows the time each tick was due, not the time the thread woke up for it: a thread woken
 * up late runs the ticks it missed back to back, and their PTS stay one frame apart (following
 * the wake-up time, the grid would jump a frame ahead, a hole in the audio). Further behind than
 * [maxBehindNanos] (the thread was not scheduled), the missed ticks are skipped and the schedule
 * starts again from the time given. Not thread-safe.
 */
internal class IngestOutputSchedule(
    private val sampleRate: Int,
    private val frames: Int,
    private val maxBehindNanos: Long,
    log: (String) -> Unit = {},
) {
    private val clock = BufferedAudioClock(sampleRate, frames, log)
    private var start = 0L
    private var tick = -1L

    /** The PTS (nanoseconds) of the tick due at or before [nowNanos], the host clock now. */
    fun next(nowNanos: Long): Long {
        if (tick < 0 || nowNanos - tickNanos(tick) > maxBehindNanos) {
            start = nowNanos
            tick = 0
        }
        val due = tickNanos(tick)
        tick++
        return Math.round(clock.next(due / 1e9) * 1e9)
    }

    /** When the next tick is due (host clock, nanoseconds). */
    fun nextTickNanos(): Long = tickNanos(tick)

    private fun tickNanos(tick: Long) = start + tick * frames * 1_000_000_000L / sampleRate
}
