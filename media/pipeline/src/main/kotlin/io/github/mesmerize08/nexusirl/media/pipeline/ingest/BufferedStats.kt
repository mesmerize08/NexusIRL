// Port of Moblin/Media/HaishinKit/Media/BufferedStats.swift @97e26d2.
package io.github.mesmerize08.nexusirl.media.pipeline.ingest

/** Counts duplicated and dropped frames and reports them at most every 5 s. Not thread-safe. */
class BufferedStats {
    private var numberOfDuplicated = 0
    private var numberOfDropped = 0
    private var latestPresentationTimeStamp = 0.0

    fun incrementDuplicated() {
        numberOfDuplicated += 1
    }

    fun incrementDropped(count: Int) {
        numberOfDropped += count
    }

    /** Returns (duplicated, dropped) and resets the counters, or null when nothing is due. */
    fun getStats(presentationTimeStamp: Double): Pair<Int, Int>? {
        if (presentationTimeStamp <= latestPresentationTimeStamp + OUTPUT_INTERVAL) return null
        if (numberOfDuplicated <= 0 && numberOfDropped <= 0) return null
        val result = numberOfDuplicated to numberOfDropped
        numberOfDuplicated = 0
        numberOfDropped = 0
        latestPresentationTimeStamp = presentationTimeStamp
        return result
    }

    private companion object {
        const val OUTPUT_INTERVAL = 5.0
    }
}
