package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataRateLimiterTest {
    private val frameNanos = 1_000_000_000L / 30

    @Test
    fun allowsOneSecondOfOutputAtOnceThenTheRate() {
        val limiter = DataRateLimiter()
        // 1 Mbps allows 150,000 bytes at once, refilled at 150,000 bytes per second.
        assertTrue(limiter.allows(0, 1_000_000))
        limiter.add(0, 1_000_000, 149_000)
        assertTrue(limiter.allows(0, 1_000_000))
        limiter.add(0, 1_000_000, 2_000)
        assertFalse(limiter.allows(0, 1_000_000))
        // 1,000 bytes short: about 7 ms of refill.
        assertFalse(limiter.allows(5_000_000, 1_000_000))
        assertTrue(limiter.allows(frameNanos, 1_000_000))
    }

    /** An encoder that makes frames of [frameBytes] whatever its bitrate, run for [seconds]. */
    private fun run(limiter: DataRateLimiter, bitrate: (Int) -> Long, frameBytes: (Int) -> Int, seconds: Int): Pair<List<Long>, Int> {
        val bitsPerSecond = MutableList(seconds) { 0L }
        var held = 0
        for (frame in 0 until 30 * seconds) {
            val now = frame * frameNanos
            if (limiter.allows(now, bitrate(frame))) {
                limiter.add(now, bitrate(frame), frameBytes(frame))
                bitsPerSecond[frame / 30] += frameBytes(frame) * 8L
            } else {
                held++
            }
        }
        return bitsPerSecond to held
    }

    @Test
    fun anEncoderSendingTooMuchIsHeldToTheLimit() {
        // 35 kB frames (8.4 Mbps at 30 fps) whatever the 2 Mbps target: the S26 HEVC encoder.
        val (bitsPerSecond, _) = run(DataRateLimiter(), { 2_000_000 }, { 35_000 }, 10)
        for (bits in bitsPerSecond.drop(1)) {
            assertTrue(bits <= 2_000_000 * DataRateLimiter.FACTOR + 35_000 * 8, "$bits")
            // ...but still close to what is allowed, not much less.
            assertTrue(bits >= 2_000_000, "$bits")
        }
    }

    @Test
    fun anEncoderWithinItsBitrateIsNeverHeldBack() {
        // A 100 kB keyframe every 2 seconds and 18 kB frames between: about 5 Mbps.
        val (_, held) = run(DataRateLimiter(), { 5_000_000 }, { if (it % 60 == 0) 100_000 else 18_000 }, 10)
        assertEquals(0, held)
    }

    @Test
    fun aSharpDropAfterABurstHoldsFramesBackForAtMostASecond() {
        // Field test 2026-11-02: 5 Mbps, a burst of output, then 1.4 Mbps. The frames were held
        // back for 4 seconds; now the debt is capped at one second of allowance.
        val (_, held) = run(
            DataRateLimiter(),
            { if (it < 60) 5_000_000 else 1_400_000 },
            {
                if (it == 59) {
                    800_000
                } else if (it < 60) {
                    20_000
                } else {
                    5_000
                }
            },
            6,
        )
        assertTrue(held in 1..31, "$held")
    }

    @Test
    fun aLowerBitrateDoesNotHoldBackOutputMadeBeforeIt() {
        // 5 Mbps for 3 seconds, then adaptive bitrate lowers it to 1 Mbps and the encoder follows.
        val (bitsPerSecond, held) = run(
            DataRateLimiter(),
            { if (it < 90) 5_000_000 else 1_000_000 },
            { if (it < 90) 20_000 else 4_000 },
            6,
        )
        assertEquals(0, held)
        assertEquals(960_000, bitsPerSecond.last())
    }
}
