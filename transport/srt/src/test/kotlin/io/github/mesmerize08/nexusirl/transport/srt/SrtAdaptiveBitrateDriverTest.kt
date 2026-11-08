package io.github.mesmerize08.nexusirl.transport.srt

import io.github.mesmerize08.nexusirl.core.clock.FakeHostClock
import io.github.mesmerize08.nexusirl.core.clock.ManualSerialExecutor
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSettings
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSrtBelabox
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSrtFight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * While the encoder makes a still picture at a fraction of its bitrate (camera gone), the link
 * looks idle: adaptive bitrate must not climb meanwhile, nor learn the low output as the link's
 * capacity, so the camera's return starts from the bitrate in effect before, or lower if the link
 * got worse meanwhile.
 */
class SrtAdaptiveBitrateDriverTest {
    private class FakeSource : SrtAdaptiveBitrateDriver.Source {
        var bytes = 0L
        var rttMs = 30.0
        override val isConnected = true
        override val latency = 3000

        override fun getPerformanceData() = SrtPerformanceData(pktFlightSize = 15, msRtt = rttMs, mbpsSendRate = 5_000_000.0)

        override fun getTotalByteCount() = bytes
    }

    private val clock = FakeHostClock()
    private val source = FakeSource()
    private var still = false
    private val belabox = AdaptiveBitrateSrtBelabox(5_000_000, {}, clock).apply { setSettings(AdaptiveBitrateSettings.BELABOX) }

    private fun driver(limitByTransportBitrate: Boolean) = SrtAdaptiveBitrateDriver(
        source,
        belabox,
        ManualSerialExecutor(clock),
        5_000_000,
        limitByTransportBitrate,
        isEncoderStill = { still },
    )

    /** [seconds] of 20 ms updates, with output at [bitrate]. */
    private fun run(driver: SrtAdaptiveBitrateDriver, seconds: Int, bitrate: Long) {
        repeat(seconds) {
            repeat(50) {
                clock.advanceMillis(20)
                source.bytes += bitrate / 8 / 50
                driver.update()
            }
            driver.updateTransportBitrate()
        }
    }

    @Test
    fun doesNotClimbWhileTheEncoderIsStill() {
        val driver = driver(limitByTransportBitrate = false)
        still = true
        run(driver, 30, 500_000)
        assertEquals(1_000_000, belabox.getCurrentBitrate())
        still = false
        run(driver, 30, 1_000_000)
        assertEquals(5_000_000, belabox.getCurrentBitrate())
    }

    @Test
    fun theLowOutputWhileStillIsNotTakenForTheLinkCapacity() {
        val driver = driver(limitByTransportBitrate = true)
        run(driver, 30, 5_000_000)
        assertEquals(5_000_000, belabox.getCurrentBitrate())
        still = true
        run(driver, 15, 500_000)
        still = false
        run(driver, 1, 5_000_000)
        assertTrue(belabox.getCurrentBitrate() >= 4_500_000, "${belabox.getCurrentBitrate()}")
    }

    @Test
    fun stillLowersWhenTheLinkGetsWorse() {
        val driver = driver(limitByTransportBitrate = false)
        run(driver, 30, 5_000_000)
        assertEquals(5_000_000, belabox.getCurrentBitrate())
        still = true
        source.rttMs = 800.0
        run(driver, 5, 500_000)
        assertTrue(belabox.getCurrentBitrate() < 5_000_000, "${belabox.getCurrentBitrate()}")
    }

    @Test
    fun fightDoesNotClimbWhileStillEither() {
        val fight = AdaptiveBitrateSrtFight(5_000_000, {}, clock).apply { setSettings(AdaptiveBitrateSettings.FAST) }
        val driver = SrtAdaptiveBitrateDriver(source, fight, ManualSerialExecutor(clock), 5_000_000, false, isEncoderStill = { still })
        val before = fight.getCurrentBitrate()
        still = true
        run(driver, 30, 500_000)
        assertTrue(fight.getCurrentBitrate() <= before, "${fight.getCurrentBitrate()} > $before")
        still = false
        run(driver, 30, 5_000_000)
        assertTrue(fight.getCurrentBitrate() > before, "${fight.getCurrentBitrate()} <= $before")
    }
}
