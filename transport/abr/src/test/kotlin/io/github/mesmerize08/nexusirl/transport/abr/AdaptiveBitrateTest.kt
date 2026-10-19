package io.github.mesmerize08.nexusirl.transport.abr

import io.github.mesmerize08.nexusirl.core.clock.FakeHostClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Recorder : AdaptiveBitrateListener {
    val bitrates = ArrayList<Long>()

    override fun setVideoStreamBitrate(bitrate: Long) {
        bitrates.add(bitrate)
    }
}

/** Port of MoblinTests/Moblin/Media/AdaptiveBitrate/AdaptiveBitrateSuite.swift @97e26d2. */
class UpstreamAdaptiveBitrateSuiteTest {
    private fun makeStats(bitrate: Long) = StreamStats(
        rttMs = 30.0,
        packetsInFlight = 15.0,
        transportBitrate = bitrate,
        latency = 3000,
        mbpsSendRate = bitrate.toDouble(),
        relaxed = false,
    )

    /** Upstream sleeps 20 ms before each update; the fake clock does the same deterministically. */
    private fun update(clock: FakeHostClock, belabox: AdaptiveBitrateSrtBelabox, bitrate: Long) {
        clock.advanceMillis(20)
        belabox.update(makeStats(bitrate))
    }

    @Test
    fun belaboxStartAtLowerThanTarget() {
        val clock = FakeHostClock()
        val recorder = Recorder()
        val belabox = AdaptiveBitrateSrtBelabox(5_000_000, recorder, clock)
        belabox.setSettings(AdaptiveBitrateSettings.BELABOX)
        assertEquals(1_000_000, belabox.getCurrentBitrate())
        assertEquals(1000, belabox.getCurrentMaximumBitrateInKbps())
        assertTrue(recorder.bitrates.isEmpty())
        update(clock, belabox, 5_000_000)
        assertEquals(1_133_333, belabox.getCurrentBitrate())
    }

    @Test
    fun belaboxTransportBitrateLimit() {
        val clock = FakeHostClock()
        val recorder = Recorder()
        val belabox = AdaptiveBitrateSrtBelabox(5_000_000, recorder, clock)
        belabox.setSettings(AdaptiveBitrateSettings.BELABOX)
        var updates = 0
        while (belabox.getCurrentBitrate() != 5_000_000L) {
            update(clock, belabox, 5_000_000)
            assertTrue(++updates < 10_000, "never reached target")
        }
        update(clock, belabox, 1_000_000)
        assertEquals(2_000_000, belabox.getCurrentBitrate())
        assertEquals(2_000_000L, recorder.bitrates.last())
    }
}

class AdaptiveBitrateBehaviourTest {
    private fun stats(rtt: Double, pif: Double, transport: Long? = null, latency: Int = 2000, mbps: Double = 5.0) =
        StreamStats(rtt, pif, transport, latency, mbps, false)

    @Test
    fun limitByTransportBitrateAllowsHeadroom() {
        assertEquals(2_000_000, stats(30.0, 10.0, transport = 1_000_000).limitByTransportBitrate(10_000_000))
        assertEquals(17_000_000, stats(30.0, 10.0, transport = 10_000_000).limitByTransportBitrate(20_000_000))
        assertEquals(3_000_000, stats(30.0, 10.0, transport = 10_000_000).limitByTransportBitrate(3_000_000))
        assertEquals(7_000_000, stats(30.0, 10.0, transport = null).limitByTransportBitrate(7_000_000))
    }

    @Test
    fun fightClimbsToTargetOnAGoodLink() {
        val recorder = Recorder()
        val fight = AdaptiveBitrateSrtFight(6_000_000, recorder, FakeHostClock())
        fight.setSettings(AdaptiveBitrateSettings.FAST)
        repeat(1000) { fight.update(stats(rtt = 40.0, pif = 5.0)) }
        assertEquals(6_000, fight.getCurrentMaximumBitrateInKbps())
        assertTrue(fight.getCurrentBitrate() in 5_500_000..6_000_000, "bitrate ${fight.getCurrentBitrate()}")
        assertTrue(recorder.bitrates.zipWithNext().all { (a, b) -> b >= a }, "should only increase on a good link")
    }

    @Test
    fun fightDecreasesOnRttSpikeAndHighRtt() {
        val fight = AdaptiveBitrateSrtFight(6_000_000, Recorder(), FakeHostClock())
        repeat(1000) { fight.update(stats(rtt = 40.0, pif = 5.0)) }
        val before = fight.getCurrentMaximumBitrateInKbps()
        fight.update(stats(rtt = 200.0, pif = 5.0)) // Spike well above avg + 50 ms.
        assertTrue(fight.getCurrentMaximumBitrateInKbps() <= before - 250, "spike: $before -> ${fight.getCurrentMaximumBitrateInKbps()}")
        repeat(100) { fight.update(stats(rtt = 600.0, pif = 5.0)) } // Sustained high RTT.
        assertEquals(50L, fight.getCurrentMaximumBitrateInKbps())
        assertTrue(fight.getActionsTaken().isNotEmpty())
        assertTrue(fight.getActionsTaken().size <= 6)
    }

    @Test
    fun fightDropsToMinimumWhenPacketsInFlightRunAway() {
        val fight = AdaptiveBitrateSrtFight(6_000_000, Recorder(), FakeHostClock())
        repeat(1000) { fight.update(stats(rtt = 40.0, pif = 5.0)) }
        repeat(3) { fight.update(stats(rtt = 40.0, pif = 2_000.0)) }
        assertEquals(AdaptiveBitrateSettings.FAST.minimumBitrate, fight.getCurrentBitrate())
    }

    @Test
    fun fightRespectsTransportBitrate() {
        val fight = AdaptiveBitrateSrtFight(10_000_000, Recorder(), FakeHostClock())
        repeat(2000) { fight.update(stats(rtt = 40.0, pif = 5.0, transport = 1_000_000)) }
        assertEquals(2_000, fight.getCurrentMaximumBitrateInKbps())
    }

    @Test
    fun belaboxSetsMinimumWhenRttReachesAThirdOfLatency() {
        val clock = FakeHostClock()
        val recorder = Recorder()
        val belabox = AdaptiveBitrateSrtBelabox(5_000_000, recorder, clock)
        repeat(500) {
            clock.advanceMillis(20)
            belabox.update(stats(rtt = 30.0, pif = 10.0, latency = 2000, mbps = 6.0))
        }
        assertEquals(5_000_000, belabox.getCurrentBitrate())
        clock.advanceMillis(20)
        belabox.update(stats(rtt = 700.0, pif = 10.0, latency = 2000, mbps = 6.0)) // >= 2000 / 3
        assertEquals(AdaptiveBitrateSettings.BELABOX.minimumBitrate, belabox.getCurrentBitrate())
    }

    @Test
    fun belaboxDecreasesStepwiseOnModerateRtt() {
        val clock = FakeHostClock()
        val belabox = AdaptiveBitrateSrtBelabox(5_000_000, Recorder(), clock)
        repeat(500) {
            clock.advanceMillis(20)
            belabox.update(stats(rtt = 30.0, pif = 10.0, latency = 3000, mbps = 6.0))
        }
        val samples = ArrayList<Long>()
        repeat(50) {
            clock.advanceMillis(20)
            belabox.update(stats(rtt = 700.0, pif = 10.0, latency = 3000, mbps = 6.0)) // > 3000 / 5, < 3000 / 3
            samples.add(belabox.getCurrentBitrate())
        }
        // Fast decreases at most every 250 ms: 1 s of updates gives ~4 steps, not 50.
        val steps = samples.zipWithNext().count { (a, b) -> b < a }
        assertTrue(steps in 3..5, "decrease steps: $steps")
        assertTrue(samples.last() < 5_000_000)
    }

    @Test
    fun belaboxIgnoresZeroRtt() {
        val clock = FakeHostClock()
        val recorder = Recorder()
        val belabox = AdaptiveBitrateSrtBelabox(5_000_000, recorder, clock)
        clock.advanceMillis(20)
        belabox.update(stats(rtt = 0.0, pif = 10.0))
        assertEquals(ADAPTIVE_BITRATE_START, belabox.getCurrentBitrate())
        assertTrue(recorder.bitrates.isEmpty())
    }
}
