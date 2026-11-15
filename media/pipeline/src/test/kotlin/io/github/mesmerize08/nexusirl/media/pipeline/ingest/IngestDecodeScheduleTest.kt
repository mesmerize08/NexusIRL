package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IngestDecodeScheduleTest {
    private val ms = 1_000_000L

    @Test
    fun startsAt300Milliseconds() {
        assertEquals(300 * ms, IngestDecodeSchedule().aheadNanos)
    }

    @Test
    fun lateFrameAddsFiftyMillisecondsUpTo500() {
        val schedule = IngestDecodeSchedule()
        schedule.onLate()
        assertEquals(350 * ms, schedule.aheadNanos)
        repeat(4) { schedule.onLate() }
        assertEquals(500 * ms, schedule.aheadNanos)
    }

    @Test
    fun quietSecondsTakeTenMillisecondsEachDownTo150() {
        val schedule = IngestDecodeSchedule()
        var now = 7_000 * ms
        schedule.onTick(now)
        repeat(10) {
            now += 100 * ms
            schedule.onTick(now)
        }
        assertEquals(290 * ms, schedule.aheadNanos)
        repeat(390) {
            now += 100 * ms
            schedule.onTick(now)
        }
        assertEquals(150 * ms, schedule.aheadNanos)
    }

    @Test
    fun lateFrameRestartsTheQuietSecond() {
        val schedule = IngestDecodeSchedule()
        schedule.onTick(0)
        schedule.onTick(900 * ms)
        schedule.onLate()
        schedule.onTick(1_800 * ms)
        assertEquals(350 * ms, schedule.aheadNanos)
        schedule.onTick(2_000 * ms)
        assertEquals(340 * ms, schedule.aheadNanos)
    }

    @Test
    fun clockGoingBackIsIgnored() {
        val schedule = IngestDecodeSchedule()
        schedule.onTick(5_000 * ms)
        schedule.onTick(1_000 * ms)
        schedule.onTick(1_500 * ms)
        assertEquals(300 * ms, schedule.aheadNanos)
    }
}

class IngestNoPictureWatchdogTest {
    private val s = 1_000_000_000L

    @Test
    fun firesAfterFiveSecondsOfFeedingWithoutAPicture() {
        val watchdog = IngestNoPictureWatchdog()
        repeat(4) { watchdog.onQueued(10 * s + it) }
        assertFalse(watchdog.expired(14 * s))
        assertTrue(watchdog.expired(15 * s))
    }

    @Test
    fun aPictureRestartsIt() {
        val watchdog = IngestNoPictureWatchdog()
        repeat(4) { watchdog.onQueued(0) }
        watchdog.onPicture()
        assertFalse(watchdog.expired(60 * s))
        repeat(4) { watchdog.onQueued(61 * s) }
        assertFalse(watchdog.expired(65 * s))
        assertTrue(watchdog.expired(66 * s))
    }

    @Test
    fun aStreamRestartRestartsIt() {
        val watchdog = IngestNoPictureWatchdog()
        repeat(4) { watchdog.onQueued(0) }
        watchdog.onRestart()
        assertFalse(watchdog.expired(10 * s))
    }

    @Test
    fun theLastUnitsAHoldingDecoderKeepDoNotFireIt() {
        // A software decoder keeps the last two frames until more data comes: not a dead decoder.
        val watchdog = IngestNoPictureWatchdog()
        watchdog.onQueued(0)
        watchdog.onQueued(1)
        assertFalse(watchdog.expired(60 * s))
    }
}

class IngestDecoderInFlightTest {
    private val s = 1_000_000_000L

    @Test
    fun aPictureRetiresItsUnitAndEveryEarlierOne() {
        val inFlight = IngestDecoderInFlight()
        listOf(1L, 3L, 2L, 4L).forEach { inFlight.add(it * 33) }
        assertEquals(4, inFlight.size)
        inFlight.onPicture(2 * 33)
        assertEquals(2, inFlight.size)
        inFlight.onPicture(4 * 33)
        assertEquals(0, inFlight.size)
    }

    @Test
    fun unitsFarAheadOfAPictureAreStale() {
        // The publisher's clock went back: units queued before it will never come out.
        val inFlight = IngestDecoderInFlight()
        inFlight.add(100 * s)
        inFlight.add(100 * s + 33)
        inFlight.add(10 * s)
        inFlight.add(10 * s + 33)
        inFlight.onPicture(10 * s)
        assertEquals(1, inFlight.size)
    }

    @Test
    fun clearEmptiesIt() {
        val inFlight = IngestDecoderInFlight()
        inFlight.add(1)
        inFlight.clear()
        assertEquals(0, inFlight.size)
    }
}
