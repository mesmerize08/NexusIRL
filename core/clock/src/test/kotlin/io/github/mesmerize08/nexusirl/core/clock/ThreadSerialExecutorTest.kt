package io.github.mesmerize08.nexusirl.core.clock

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class ThreadSerialExecutorTest {
    @Test
    fun runsTasksInOrder() {
        val executor = SerialExecutor.create("test")
        val order = mutableListOf<Int>()
        val done = CountDownLatch(1)
        executor.execute { order += 1 }
        executor.schedule(1_000_000) {
            order += 2
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(order == listOf(1, 2))
        executor.shutdown()
    }

    /** A socket reader reporting a closed connection after its owner stopped (seen with RTMP). */
    @Test
    fun postsAfterShutdownAreDropped() {
        val executor = SerialExecutor.create("test")
        executor.shutdown()
        executor.execute { error("must not run") }
        executor.schedule(1) { error("must not run") }.cancel()
        executor.scheduleRepeating(1_000_000) { error("must not run") }.cancel()
    }
}
