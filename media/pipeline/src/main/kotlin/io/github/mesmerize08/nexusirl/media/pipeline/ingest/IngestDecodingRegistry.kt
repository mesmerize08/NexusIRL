package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import java.util.concurrent.atomic.AtomicBoolean

/** Counts the sources decoding video, at most [max] at once (spec decision 7). Thread-safe. */
internal class IngestDecodingRegistry(private val max: Int) {
    var count = 0
        @Synchronized get
        private set

    @Synchronized
    fun acquire(): Boolean {
        if (count >= max) return false
        count++
        return true
    }

    @Synchronized
    fun release() {
        count--
    }
}

/**
 * One source's place in an [IngestDecodingRegistry]: [take] holds at most one place, and [give]
 * gives it back exactly once however many threads call it. Thread-safe.
 */
internal class IngestDecodingPlace(private val registry: IngestDecodingRegistry) {
    private val held = AtomicBoolean(false)

    val isHeld: Boolean get() = held.get()

    /** Takes a place unless one is held already; false when the registry is full. */
    fun take(): Boolean {
        if (held.get()) return true
        if (!registry.acquire()) return false
        if (!held.compareAndSet(false, true)) registry.release()
        return true
    }

    fun give() {
        if (held.getAndSet(false)) registry.release()
    }
}
