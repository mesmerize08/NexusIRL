package io.github.mesmerize08.nexusirl.stream

import java.util.concurrent.Executor

/**
 * Runs the blocking part of going live, stopping and recording off the main thread. Attaching and
 * detaching encoders waits for the GL thread, codecs and the muxer, seconds at worst, which would
 * freeze the UI (and end the app as not responding). [executor] runs one task at a time, so a
 * quick start, stop and start still apply in that order.
 */
internal class CaptureControl(private val executor: Executor, private val main: (Runnable) -> Unit) {
    /** Work queued whose result has not been handed back yet. Main thread. */
    var pending = 0
        private set

    /** Runs [work] on [executor], then [done] with its result on the main thread. Main thread. */
    fun <T> run(work: () -> T, done: (T) -> Unit = {}) {
        pending += 1
        executor.execute {
            val result = work()
            main(
                Runnable {
                    pending -= 1
                    done(result)
                },
            )
        }
    }
}
