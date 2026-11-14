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

    /**
     * A task that throws is reported, not swallowed (ScheduledExecutorService keeps it in a future
     * nobody reads). Seen on Android 10: a NoSuchMethodError while starting a stream left it at
     * "Connecting" forever, with nothing in the log.
     */
    @Test
    fun aThrowingTaskIsReportedAndLaterTasksStillRun() {
        val errors = java.util.concurrent.LinkedBlockingQueue<Throwable>()
        val executor = ThreadSerialExecutor("test") { errors.add(it) }
        val done = CountDownLatch(1)
        executor.execute { throw NoSuchMethodError("decode") }
        executor.execute { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val error = errors.poll(5, TimeUnit.SECONDS)
        assertTrue(error is NoSuchMethodError && error.message == "decode", "got $error")
        executor.shutdown()
    }

    /** A repeating task that throws once keeps repeating (a status timer must not stop silently). */
    @Test
    fun aRepeatingTaskKeepsRunningAfterThrowing() {
        val errors = java.util.concurrent.LinkedBlockingQueue<Throwable>()
        val executor = ThreadSerialExecutor("test") { errors.add(it) }
        val runs = CountDownLatch(3)
        var first = true
        executor.scheduleRepeating(1_000_000) {
            runs.countDown()
            if (first) {
                first = false
                throw IllegalStateException("once")
            }
        }
        assertTrue(runs.await(5, TimeUnit.SECONDS))
        assertTrue(errors.poll(5, TimeUnit.SECONDS) is IllegalStateException)
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
