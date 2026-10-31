package io.github.mesmerize08.nexusirl.media.pipeline

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandoffTest {
    private val thread = Executors.newSingleThreadExecutor()

    private val post: (Runnable) -> Boolean = {
        thread.execute(it)
        true
    }

    @AfterTest
    fun shutdown() {
        thread.shutdownNow()
    }

    @Test
    fun runsTheWork() {
        val ran = AtomicBoolean(false)
        assertTrue(Handoff.run(post, 1_000_000_000) { ran.set(true) })
        assertTrue(ran.get())
    }

    @Test
    fun workThatHasNotStartedByTheTimeoutNeverRuns() {
        // The other thread is stalled (as a GL thread busy for seconds).
        val release = CountDownLatch(1)
        thread.execute { release.await() }
        val ran = AtomicBoolean(false)
        assertFalse(Handoff.run(post, 50_000_000) { ran.set(true) })
        release.countDown()
        // Let the stalled thread reach the cancelled work.
        thread.submit {}.get(1, TimeUnit.SECONDS)
        assertFalse(ran.get(), "cancelled work must not run later")
    }

    @Test
    fun startedWorkIsWaitedForPastTheTimeout() {
        val ran = AtomicBoolean(false)
        assertTrue(
            Handoff.run(post, 20_000_000) {
                Thread.sleep(150)
                ran.set(true)
            },
        )
        assertTrue(ran.get(), "a started block finishes before run returns")
    }

    @Test
    fun aThreadThatIsGoneRunsNothing() {
        val ran = AtomicBoolean(false)
        assertFalse(Handoff.run({ false }, 1_000_000_000) { ran.set(true) })
        assertFalse(ran.get())
    }
}
