// Port of MoblinTests/Moblin/Media/HaishinKit/Media/DriftTrackerSuite.swift @97e26d2.
package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpstreamDriftTrackerSuiteTest {
    @Test
    fun steadyLevelDoesNotAdjust() {
        val tracker = createTracker()
        val adjustments = drive(tracker, from = 0.0, to = 120.0, video = { it + 1.0 })
        assertTrue(adjustments.isEmpty())
        assertEquals(0.0, tracker.getDrift())
    }

    @Test
    fun stallBurstDoesNotAdjust() {
        val tracker = createTracker()
        val adjustments = drive(tracker, from = 0.0, to = 50.0, video = { it + 1.0 }).toMutableList()
        adjustments += drive(tracker, from = 60.0, to = 65.0, video = { 51.0 + (it - 60.0) * 3.0 })
        adjustments += drive(tracker, from = 65.0, to = 150.0, video = { it + 1.0 })
        assertTrue(adjustments.isEmpty())
        assertEquals(0.0, tracker.getDrift())
    }

    @Test
    fun slowCatchUpAfterStallDoesNotAdjust() {
        val tracker = createTracker()
        val adjustments = drive(tracker, from = 0.0, to = 60.0, video = { it + 1.0 }).toMutableList()
        adjustments += drive(tracker, from = 61.3, to = 87.3, video = { it - 0.3 + (it - 61.3) * 0.05 })
        adjustments += drive(tracker, from = 87.3, to = 200.0, video = { it + 1.0 })
        assertTrue(adjustments.isEmpty())
        assertEquals(0.0, tracker.getDrift())
    }

    @Test
    fun sustainedJumpAdjustsToTarget() {
        val tracker = createTracker()
        val adjustments = drive(tracker, from = 0.0, to = 50.0, video = { it + 1.0 }).toMutableList()
        adjustments += drive(tracker, from = 50.0, to = 150.0, video = { it })
        assertEquals(1, adjustments.size)
        assertTrue(abs(tracker.getDrift() - 1.0) < 0.01)
    }

    @Test
    fun initialDriftIsIncludedInFillLevel() {
        val tracker = createTracker()
        tracker.setDrift(0.5)
        val adjustments = drive(tracker, from = 0.0, to = 120.0, video = { it + 0.5 })
        assertTrue(adjustments.isEmpty())
        assertEquals(0.5, tracker.getDrift())
    }

    @Test
    fun audioAndVideoShareOneDrift() {
        val tracker = createTracker()
        val adjustments = drive(
            tracker,
            from = 0.0,
            to = 50.0,
            audio = { it + 1.0 },
            video = { it + 1.0 },
        ).toMutableList()
        adjustments += drive(tracker, from = 50.0, to = 150.0, audio = { it }, video = { it })
        assertEquals(1, adjustments.size)
        assertTrue(abs(tracker.getDrift() - 1.0) < 0.01)
    }

    @Test
    fun leadingMediaKeepsExtraBuffer() {
        val tracker = createTracker()
        val adjustments = drive(tracker, from = 0.0, to = 200.0, audio = { it + 1.5 }, video = { it + 1.0 })
        assertTrue(adjustments.isEmpty())
        assertEquals(0.0, tracker.getDrift())
    }

    @Test
    fun laggingMediaIsHeldAtTarget() {
        val tracker = createTracker()
        val adjustments = drive(tracker, from = 0.0, to = 200.0, audio = { it + 1.0 }, video = { it + 0.5 })
        assertEquals(1, adjustments.size)
        assertTrue(abs(tracker.getDrift() - 0.5) < 0.01)
    }

    @Test
    fun staleMediaIsIgnored() {
        val tracker = createTracker()
        val adjustments = drive(
            tracker,
            from = 0.0,
            to = 50.0,
            audio = { it + 1.0 },
            video = { it + 1.0 },
        ).toMutableList()
        adjustments += drive(tracker, from = 50.0, to = 150.0, video = { it + 2.0 })
        assertEquals(1, adjustments.size)
        assertTrue(abs(tracker.getDrift() + 1.0) < 0.01)
    }

    @Test
    fun mediaWithTooFewSamplesIsIgnored() {
        val tracker = createTracker()
        val adjustments = drive(tracker, from = 0.0, to = 50.0, video = { it + 1.0 }).toMutableList()
        adjustments += drive(tracker, from = 50.0, to = 55.0, audio = { it + 3.0 }, video = { it })
        adjustments += drive(tracker, from = 55.0, to = 150.0, video = { it })
        assertEquals(1, adjustments.size)
        assertTrue(abs(tracker.getDrift() - 1.0) < 0.01)
    }

    @Test
    fun unknownMediaIsIgnored() {
        val tracker = DriftTracker("")
        tracker.addMedia(DriftTrackerMedia.VIDEO, 1.0)
        val adjustments = drive(tracker, from = 0.0, to = 120.0, audio = { it + 5.0 })
        assertTrue(adjustments.isEmpty())
        assertEquals(0.0, tracker.getDrift())
    }

    private fun createTracker(): DriftTracker {
        val tracker = DriftTracker("")
        tracker.addMedia(DriftTrackerMedia.AUDIO, 1.0)
        tracker.addMedia(DriftTrackerMedia.VIDEO, 1.0)
        return tracker
    }

    private fun drive(
        tracker: DriftTracker,
        from: Double,
        to: Double,
        audio: ((Double) -> Double)? = null,
        video: ((Double) -> Double)? = null,
    ): List<Double> {
        val adjustments = mutableListOf<Double>()
        var time = from
        while (time < to) {
            for ((media, newest) in listOf(DriftTrackerMedia.AUDIO to audio, DriftTrackerMedia.VIDEO to video)) {
                if (newest == null) continue
                val drift = tracker.getDrift()
                tracker.update(media, time, newest(time))
                if (tracker.getDrift() != drift) adjustments += tracker.getDrift()
            }
            time += 0.6
        }
        return adjustments
    }
}
