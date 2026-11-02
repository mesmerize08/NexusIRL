package io.github.mesmerize08.nexusirl.stream

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class CaptureControlTest {
    private val executor = Executors.newSingleThreadExecutor()

    /** Stands in for the main looper: the test thread runs what is posted. */
    private val mainQueue = LinkedBlockingQueue<Runnable>()
    private val control = CaptureControl(executor) { mainQueue.add(it) }

    @AfterTest
    fun shutDown() {
        executor.shutdownNow()
    }

    private fun runMain(count: Int) = repeat(count) {
        assertNotNull(mainQueue.poll(5, TimeUnit.SECONDS), "nothing handed back to the main thread").run()
    }

    @Test
    fun workRunsInCallOrderOffTheCallingThread() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val caller = Thread.currentThread()
        val release = CountDownLatch(1)
        // A slow stop (a stalled GL handoff) must not let the next start overtake it.
        control.run({
            assertNotSame(caller, Thread.currentThread())
            release.await()
            events += "stop"
        })
        control.run({ events += "start" })
        control.run({ events += "stop again" })
        assertEquals(3, control.pending)
        release.countDown()
        runMain(3)
        assertEquals(listOf("stop", "start", "stop again"), events)
    }

    @Test
    fun resultsComeBackOnTheMainThreadAfterTheWork() {
        val results = mutableListOf<String>()
        control.run({ "saved" }) { results += it }
        control.run({ "failed" }) { results += it }
        assertTrue(results.isEmpty())
        runMain(1)
        assertEquals(listOf("saved"), results)
        assertEquals(1, control.pending)
        runMain(1)
        assertEquals(listOf("saved", "failed"), results)
        assertEquals(0, control.pending)
    }

    @Test
    fun pendingCountsUntilTheResultIsHandledSoCaptureEndsAfterTheWork() {
        var pendingWhenDone = -1
        control.run({ }) { pendingWhenDone = control.pending }
        runMain(1)
        // [StreamController.afterCaptureEnded] runs in done and must see nothing left to finish.
        assertEquals(0, pendingWhenDone)
    }
}
