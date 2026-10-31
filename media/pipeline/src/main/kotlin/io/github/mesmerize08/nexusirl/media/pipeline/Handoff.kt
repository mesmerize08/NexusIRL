package io.github.mesmerize08.nexusirl.media.pipeline

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs work on another thread and waits for it, so the caller always knows whether it ran.
 *
 * [run] posts [block] with [post] and waits up to [timeoutNanos]. If the other thread has not
 * started it by then (it is stalled), the work is cancelled and never runs: the caller can safely
 * undo its side of the work. Once started, the work is waited for to the end.
 */
internal object Handoff {
    private const val PENDING = 0
    private const val STARTED = 1
    private const val CANCELLED = 2

    /** True if [block] ran; false if it was cancelled or could not be posted. */
    fun run(post: (Runnable) -> Boolean, timeoutNanos: Long, block: () -> Unit): Boolean {
        val state = AtomicInteger(PENDING)
        val done = CountDownLatch(1)
        val posted = post(
            Runnable {
                if (!state.compareAndSet(PENDING, STARTED)) {
                    return@Runnable
                }
                try {
                    block()
                } finally {
                    done.countDown()
                }
            },
        )
        if (!posted) {
            return false
        }
        if (done.await(timeoutNanos, TimeUnit.NANOSECONDS)) {
            return true
        }
        if (state.compareAndSet(PENDING, CANCELLED)) {
            return false
        }
        // Started just now or still running: its effects must be known, so wait for the end.
        done.await()
        return true
    }
}
