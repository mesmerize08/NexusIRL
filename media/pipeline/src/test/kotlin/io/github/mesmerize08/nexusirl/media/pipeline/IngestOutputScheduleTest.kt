package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IngestOutputScheduleTest {
    private val tickNanos = 1024 * 1_000_000_000.0 / 48_000

    private fun schedule() = IngestOutputSchedule(48_000, 1024, maxBehindNanos = 100_000_000)

    /** Runs ticks, waking up [late] (by tick index) nanoseconds after each is due; returns the PTS. */
    private fun run(schedule: IngestOutputSchedule, ticks: Int, late: (Int) -> Long): List<Long> {
        var now = 1_000_000_000_000L
        val pts = ArrayList<Long>()
        for (i in 0 until ticks) {
            pts += schedule.next(now)
            // The thread sleeps until the next tick and wakes up late; a tick already due runs at once.
            now = maxOf(now, schedule.nextTickNanos() + late(i + 1))
        }
        return pts
    }

    private fun assertOnGrid(pts: List<Long>) {
        for (i in 1 until pts.size) {
            val step = pts[i] - pts[i - 1]
            assertTrue(kotlin.math.abs(step - tickNanos) <= 1, "step $step ns at tick $i: $pts")
        }
    }

    @Test
    fun onTimeTicksAreOnTheGrid() {
        val pts = run(schedule(), 100) { 0 }
        assertEquals(1_000_000_000_000L, pts.first())
        assertOnGrid(pts)
    }

    /** A thread woken up 35 ms late catches up with back-to-back ticks: no hole in the PTS. */
    @Test
    fun aLateWakeUpLeavesNoHole() {
        assertOnGrid(run(schedule(), 100) { if (it == 20) 35_000_000 else 0 })
    }

    /** Woken up late every time by up to 25 ms (a slow, busy device): still no hole. */
    @Test
    fun jitteryWakeUpsLeaveNoHole() {
        assertOnGrid(run(schedule(), 300) { (it * 7_919_000L) % 25_000_000 })
    }

    /** Further behind than the limit, the missed ticks are skipped; the PTS still only go forward. */
    @Test
    fun aLongStallSkipsTicks() {
        val pts = run(schedule(), 100) { if (it == 20) 300_000_000 else 0 }
        for (i in 1 until pts.size) assertTrue(pts[i] - pts[i - 1] >= tickNanos - 1, "step back at $i: $pts")
        assertTrue(pts.last() - pts.first() > 99 * tickNanos + 200_000_000, "the stall was not skipped: $pts")
    }
}
