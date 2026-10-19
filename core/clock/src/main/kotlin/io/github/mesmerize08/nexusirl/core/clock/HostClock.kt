package io.github.mesmerize08.nexusirl.core.clock

/**
 * The single host clock that every media timestamp is expressed in (docs/PORT_PLAN.md §3,
 * "Timestamp rule"). It replaces iOS's host time clock (`CMClockGetHostTimeClock`): monotonic,
 * not affected by wall clock changes, nanosecond resolution.
 *
 * On Android this is `System.nanoTime()` (CLOCK_MONOTONIC). Camera timestamps whose source is
 * REALTIME and audio timestamps must be converted into this domain before use.
 */
fun interface HostClock {
    fun nanoTime(): Long

    fun now(): MediaTime = MediaTime(nanoTime(), MediaTime.NANOSECONDS)

    companion object {
        val system: HostClock = HostClock { System.nanoTime() }
    }
}

/** A manually advanced clock for tests and deterministic simulations. */
class FakeHostClock(var nanos: Long = 0) : HostClock {
    override fun nanoTime(): Long = nanos

    fun advanceMillis(millis: Long) {
        nanos += millis * 1_000_000
    }
}
