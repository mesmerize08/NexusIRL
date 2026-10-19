package io.github.mesmerize08.nexusirl.core.clock

import java.util.PriorityQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/** A handle to cancel a scheduled task. */
fun interface Cancellable {
    fun cancel()
}

/**
 * A single-threaded executor, the counterpart of upstream's serial `DispatchQueue`s
 * (`srtlaClientQueue`, `processorPipelineQueue`, ...). Code confined to one executor needs no
 * locking, exactly like upstream code confined to one queue.
 */
interface SerialExecutor {
    fun execute(task: () -> Unit)

    fun schedule(delayNanos: Long, task: () -> Unit): Cancellable

    fun scheduleRepeating(periodNanos: Long, task: () -> Unit): Cancellable

    companion object {
        /** A real executor backed by one named daemon thread. */
        fun create(name: String): ThreadSerialExecutor = ThreadSerialExecutor(name)
    }
}

class ThreadSerialExecutor(name: String) : SerialExecutor {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        ThreadFactory { runnable -> Thread(runnable, name).apply { isDaemon = true } },
    )

    override fun execute(task: () -> Unit) {
        executor.execute(task)
    }

    override fun schedule(delayNanos: Long, task: () -> Unit): Cancellable {
        val future = executor.schedule(task, delayNanos, TimeUnit.NANOSECONDS)
        return Cancellable { future.cancel(false) }
    }

    override fun scheduleRepeating(periodNanos: Long, task: () -> Unit): Cancellable {
        val future = executor.scheduleAtFixedRate(task, periodNanos, periodNanos, TimeUnit.NANOSECONDS)
        return Cancellable { future.cancel(false) }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}

/**
 * Deterministic executor for tests: tasks run only when [runPending] or [advanceTo] is called, and
 * scheduled tasks fire when [clock] is advanced past their deadline.
 */
class ManualSerialExecutor(val clock: FakeHostClock = FakeHostClock()) : SerialExecutor {
    private data class Timed(val deadline: Long, val order: Long, val task: () -> Unit, var cancelled: Boolean = false)

    private val immediate = ArrayDeque<() -> Unit>()
    private val timed = PriorityQueue<Timed>(compareBy<Timed>({ it.deadline }, { it.order }))
    private var order = 0L

    override fun execute(task: () -> Unit) {
        immediate.addLast(task)
    }

    override fun schedule(delayNanos: Long, task: () -> Unit): Cancellable {
        val entry = Timed(clock.nanos + delayNanos, order++, task)
        timed.add(entry)
        return Cancellable { entry.cancelled = true }
    }

    override fun scheduleRepeating(periodNanos: Long, task: () -> Unit): Cancellable {
        var current: Cancellable? = null
        var cancelled = false
        fun arm() {
            current = schedule(periodNanos) {
                if (!cancelled) {
                    task()
                    arm()
                }
            }
        }
        arm()
        return Cancellable {
            cancelled = true
            current?.cancel()
        }
    }

    /** Runs queued (non-delayed) tasks, including ones they enqueue. */
    fun runPending() {
        while (immediate.isNotEmpty()) {
            immediate.removeFirst()()
        }
    }

    /** Advances the clock to [nanos], firing due timers in order. */
    fun advanceTo(nanos: Long) {
        runPending()
        while (true) {
            val next = timed.peek() ?: break
            if (next.deadline > nanos) {
                break
            }
            timed.poll()
            clock.nanos = maxOf(clock.nanos, next.deadline)
            if (!next.cancelled) {
                next.task()
            }
            runPending()
        }
        clock.nanos = maxOf(clock.nanos, nanos)
    }

    fun advanceMillis(millis: Long) = advanceTo(clock.nanos + millis * 1_000_000)
}
