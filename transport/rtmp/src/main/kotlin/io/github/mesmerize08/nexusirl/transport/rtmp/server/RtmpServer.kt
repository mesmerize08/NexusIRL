// Port of Moblin/Media/RtmpServer/RtmpServer.swift @97e26d2
// (client registry, same stream key, 3 s housekeeping, receive timeout), on blocking sockets.
package io.github.mesmerize08.nexusirl.transport.rtmp.server

import io.github.mesmerize08.nexusirl.core.clock.Cancellable
import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.ThreadSerialExecutor
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An RTMP ingest server on `0.0.0.0:port` for untrusted LAN clients.
 *
 * Threads: one accept thread ("rtmp-server-accept"); one worker per connection
 * ("rtmp-server-client-N", at most [RtmpServerLimits.maxConnections]) that alone reads its socket
 * and drives its [RtmpServerSession]; and one serial executor ("rtmp-server") that runs the
 * housekeeping and makes every [listener] call, in order. Other threads end a connection only by
 * recording a reason and closing its socket; the worker then closes the session with that reason.
 *
 * Admission happens on the accept thread before any byte is read or written: [acceptPeer], the
 * wrong-key lockout, [RtmpServerLimits.maxConnections], [RtmpServerLimits.maxUnauthenticated]
 * (connections not publishing yet) and [RtmpServerLimits.maxUnauthenticatedPerAddress]. Refusals
 * are logged at most once per address every 10 s. Media of all clients shares one budget of
 * [RtmpServerLimits.maxQueuedMediaBytes] waiting for the listener. A server starts once; after
 * [stop] it cannot start again.
 *
 * [log] is called from all of these threads, also concurrently, so it must be thread-safe. [stop]
 * can block for several seconds; call it off the main thread.
 */
class RtmpServer(
    settings: RtmpServerSettings,
    private val listener: RtmpServerListener,
    private val acceptPeer: (local: InetAddress, remote: InetAddress) -> Boolean,
    private val limits: RtmpServerLimits = RtmpServerLimits(),
    private val clock: HostClock = HostClock.system,
    private val log: (String) -> Unit = {},
) {
    private val requestedPort = settings.port
    private val streams = settings.streams.toList()
    private val random = SecureRandom()

    private val executor = ThreadSerialExecutor("rtmp-server") { error ->
        // The listener is the app's; its message is not ours to log.
        log("rtmp-server: Listener failed with ${error.javaClass.simpleName}")
    }

    @Volatile
    private var executorThread: Thread? = null

    private val workerNumber = AtomicInteger()

    // One more than the limit, which `clients` enforces: a worker that has just left `clients`
    // may not be back in the pool yet when the next connection is admitted.
    private val pool = ThreadPoolExecutor(
        0,
        limits.maxConnections + 1,
        WORKER_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { Thread(it, "rtmp-server-client-${workerNumber.incrementAndGet()}").apply { isDaemon = true } },
    )

    // Guarded by lock.
    private val lock = Any()
    private val clients = HashSet<Client>()
    private val failures = HashMap<InetAddress, Failures>()
    private val refusalLines = HashMap<InetAddress, RefusalLines>()
    private var started = false
    private var stopped = false

    /**
     * Media bytes of all clients posted to the executor and not yet handled, guarded by [budget]:
     * one budget of [RtmpServerLimits.maxQueuedMediaBytes] for the whole server.
     */
    private var queuedMediaBytes = 0L
    private val budget = ReentrantLock()
    private val budgetFreed = budget.newCondition()
    private val peakQueuedMediaBytes = AtomicLong()

    /** Who publishes each stream; written on the executor only. */
    private val publishers = ConcurrentHashMap<UUID, Client>()

    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    // Written in start() with lock held; read in stop() after taking it.
    private var acceptThread: Thread? = null
    private var housekeeping: Cancellable? = null

    /** Runs in [start] right after the bind, with the bound port (for tests). */
    internal var afterBind: (Int) -> Unit = {}

    /** The bound port: [RtmpServerSettings.port], or the one the system chose for 0. */
    val port: Int
        get() = serverSocket?.localPort ?: requestedPort

    /** Binds `0.0.0.0:port`; throws [IOException] if the port is taken. */
    fun start() {
        synchronized(lock) {
            check(!started && !stopped) { "A server starts once" }
            started = true
        }
        val server = ServerSocket()
        try {
            server.bind(InetSocketAddress(requestedPort), BACKLOG)
        } catch (e: IOException) {
            closeQuietly(server)
            stop()
            throw e
        }
        afterBind(server.localPort)
        // A stop() that ran during the bind has seen nothing to close: then nothing starts. Under
        // the lock, a later stop() sees everything started here.
        synchronized(lock) {
            if (stopped) {
                closeQuietly(server)
                return
            }
            serverSocket = server
            running = true
            executor.execute { executorThread = Thread.currentThread() }
            housekeeping = executor.scheduleRepeating(HOUSEKEEPING_PERIOD_NANOS) { housekeep() }
            acceptThread = Thread({ acceptLoop(server) }, "rtmp-server-accept").apply {
                isDaemon = true
                start()
            }
        }
        log("rtmp-server: Listening on port ${server.localPort}")
    }

    /**
     * Closes every connection and the listening socket and ends the threads. It blocks for up to
     * about 6 s (2 s each for the accept thread, the listener and the workers), so call it off
     * the main thread.
     *
     * With a responsive listener, each publisher gets `onPublishStop(id, "Server stop")` before
     * this returns and there are no listener calls after. If the listener does not return within
     * 2 s, stop() returns anyway: the stalled call, and possibly stops after it, still run after
     * this returns, and a publisher may get no stop at all.
     */
    fun stop() {
        synchronized(lock) {
            if (stopped) {
                return
            }
            stopped = true
        }
        running = false
        serverSocket?.let { closeQuietly(it) }
        acceptThread?.takeIf { it !== Thread.currentThread() }?.join(JOIN_MILLIS)
        housekeeping?.cancel()
        synchronized(lock) { clients.toList() }.forEach { it.requestClose(SERVER_STOP) }
        // On the executor, so a publish start running right now is either done (and stopped here)
        // or not yet run (and dropped, as running is false).
        val stopPublishers = {
            for (id in publishers.keys.toList()) {
                if (publishers.remove(id) != null) {
                    notifyListener { listener.onPublishStop(id, SERVER_STOP) }
                }
            }
        }
        if (Thread.currentThread() === executorThread) {
            stopPublishers()
        } else if (executorThread != null) {
            val done = CountDownLatch(1)
            executor.execute {
                stopPublishers()
                done.countDown()
            }
            if (!done.await(JOIN_MILLIS, TimeUnit.MILLISECONDS)) {
                log("rtmp-server: Listener did not finish in time")
            }
        }
        val onExecutor = Thread.currentThread() === executorThread
        executor.shutdown()
        pool.shutdown()
        if (onExecutor) {
            // shutdown() interrupted this very thread; the workers end on their own.
            Thread.interrupted()
        } else {
            pool.awaitTermination(JOIN_MILLIS, TimeUnit.MILLISECONDS)
        }
        log("rtmp-server: Stopped")
    }

    fun connectedStreamIds(): Set<UUID> = publishers.keys.toSet()

    fun numberOfClients(): Int = synchronized(lock) { clients.size }

    /** The most media bytes all clients together had waiting for the listener (for tests). */
    internal fun peakQueuedMediaBytes(): Long = peakQueuedMediaBytes.get()

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (!running || server.isClosed) {
                    return
                }
                // Out of file descriptors, say: do not spin.
                try {
                    Thread.sleep(ACCEPT_ERROR_PAUSE_MILLIS)
                } catch (interrupted: InterruptedException) {
                    return
                }
                continue
            }
            // Nothing in here may end the loop; failing to start a thread is an OutOfMemoryError.
            try {
                admit(socket)
            } catch (e: Throwable) {
                synchronized(lock) { clients.removeAll { it.socket === socket } }
                closeQuietly(socket)
            }
        }
    }

    /** Admits [socket], or closes it before reading or writing a byte. */
    private fun admit(socket: Socket) {
        val remote = socket.inetAddress
        val allowed = running &&
            try {
                acceptPeer(socket.localAddress, remote)
            } catch (e: Exception) {
                false
            }
        if (!allowed) {
            logRefusal(remote, "not an allowed peer")
            closeQuietly(socket)
            return
        }
        var admitted: Client? = null
        val refusal = synchronized(lock) {
            when {
                stopped -> "server stopped"
                isLockedOut(remote) -> "locked out after wrong stream keys"
                clients.size >= limits.maxConnections -> "too many connections"
                clients.count { !it.publishing } >= limits.maxUnauthenticated -> "too many connections not publishing"
                clients.count { !it.publishing && it.address == remote } >= limits.maxUnauthenticatedPerAddress ->
                    "too many connections not publishing from this address"
                else -> {
                    admitted = Client(socket).also { clients.add(it) }
                    null
                }
            }
        }
        val client = admitted
        if (client == null) {
            logRefusal(remote, refusal ?: "")
            closeQuietly(socket)
            return
        }
        try {
            pool.execute { client.run() }
        } catch (e: RejectedExecutionException) {
            synchronized(lock) { clients.remove(client) }
            logRefusal(remote, "no worker free")
            closeQuietly(socket)
        }
    }

    private class RefusalLines(var loggedAt: Long) {
        var suppressed = 0
    }

    /**
     * At most one refusal line per address per [REFUSAL_LOG_INTERVAL_MILLIS], so that a device
     * reconnecting in a loop cannot flood the log; the next line counts the ones left out.
     */
    private fun logRefusal(address: InetAddress, reason: String) {
        val line = synchronized(lock) {
            val now = clock.nanoTime()
            val interval = REFUSAL_LOG_INTERVAL_MILLIS * NANOS_PER_MILLI
            val entry = refusalLines[address]
            if (entry != null && now - entry.loggedAt < interval) {
                entry.suppressed += 1
                return
            }
            // Bounded: forget addresses with nothing left to count.
            refusalLines.values.removeAll { now - it.loggedAt >= interval && it.suppressed == 0 }
            if (refusalLines.size >= MAX_REFUSAL_ADDRESSES) {
                refusalLines.clear()
            }
            val suppressed = entry?.suppressed ?: 0
            refusalLines[address] = RefusalLines(now)
            "rtmp-server: Refused $address, $reason" + if (suppressed > 0) " ($suppressed more not logged)" else ""
        }
        log(line)
    }

    private fun housekeep() {
        val now = clock.nanoTime()
        for (client in synchronized(lock) { clients.toList() }) {
            client.deadlineReason(now)?.let { client.requestClose(it) }
        }
    }

    // Lockout: maxFailedKeys wrong keys within lockoutMs lock the address out until lockoutMs
    // after the last of them.

    private class Failures {
        val times = ArrayDeque<Long>()
        var locked = false
        var lockedUntil = 0L
    }

    private fun recordWrongKey(address: InetAddress) {
        val window = limits.lockoutMs * NANOS_PER_MILLI
        synchronized(lock) {
            val now = clock.nanoTime()
            // Forget addresses that are neither locked nor have a recent failure.
            failures.values.removeAll { entry ->
                (!entry.locked || now - entry.lockedUntil >= 0) && (entry.times.isEmpty() || now - entry.times.last() >= window)
            }
            val entry = failures.getOrPut(address) { Failures() }
            while (entry.times.isNotEmpty() && now - entry.times.first() >= window) {
                entry.times.removeFirst()
            }
            entry.times.addLast(now)
            while (entry.times.size > limits.maxFailedKeys) {
                entry.times.removeFirst()
            }
            if (entry.times.size >= limits.maxFailedKeys) {
                entry.locked = true
                entry.lockedUntil = now + window
                log("rtmp-server: Locking out $address for ${limits.lockoutMs} ms after ${entry.times.size} wrong stream keys")
            }
        }
    }

    /** Called with [lock] held. */
    private fun isLockedOut(address: InetAddress): Boolean {
        val entry = failures[address] ?: return false
        return entry.locked && clock.nanoTime() - entry.lockedUntil < 0
    }

    /** On the executor: the newest publisher of a stream wins, as upstream. */
    private fun publishStarted(client: Client, id: UUID) {
        if (!running) {
            return
        }
        val old = publishers.put(id, client)
        if (old != null && old !== client) {
            log("rtmp-server: Client ${old.address} replaced by ${client.address}: $SAME_STREAM_KEY")
            notifyListener { listener.onPublishStop(id, SAME_STREAM_KEY) }
            old.requestClose(SAME_STREAM_KEY)
        }
        notifyListener { listener.onPublishStart(id) }
    }

    /** A throwing listener is logged, so the calls after it still happen. */
    private fun notifyListener(call: () -> Unit) {
        try {
            call()
        } catch (e: Exception) {
            log("rtmp-server: Listener failed with ${e.javaClass.simpleName}")
        }
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (e: IOException) {
            // Already closed.
        }
    }

    private inner class Client(val socket: Socket) : RtmpServerSession.Events {
        val address: InetAddress = socket.inetAddress
        private val acceptedAt = clock.nanoTime()

        @Volatile
        private var lastReceivedAt = acceptedAt

        /** The session's state, for other threads; PUBLISHING is set with [lock] held. */
        @Volatile
        private var phase = RtmpServerSession.State.HANDSHAKE

        val publishing: Boolean
            get() = phase == RtmpServerSession.State.PUBLISHING

        private val closeReason = AtomicReference<String?>()

        /** While the worker waits for the media budget, which is not being idle. */
        @Volatile
        private var waitingForBudget = false

        // Worker thread only.
        private var output: OutputStream? = null
        private var streamId: UUID? = null
        private val session = RtmpServerSession({ streams }, limits, clock, { send(it) }, this, random)

        fun run() {
            log("rtmp-server: Client $address connected")
            try {
                socket.soTimeout = limits.readTimeoutMs
                socket.tcpNoDelay = true
                val input = socket.getInputStream()
                output = socket.getOutputStream()
                val buffer = ByteArray(READ_BUFFER_SIZE)
                while (session.state != RtmpServerSession.State.CLOSED) {
                    val count = try {
                        input.read(buffer)
                    } catch (e: SocketTimeoutException) {
                        0
                    }
                    if (count < 0) {
                        session.close(closeReason.get() ?: "Connection closed")
                        break
                    }
                    if (count > 0) {
                        lastReceivedAt = clock.nanoTime()
                        session.feed(buffer, 0, count)
                        if (session.state == RtmpServerSession.State.CONNECTED) {
                            phase = RtmpServerSession.State.CONNECTED
                        }
                    }
                    // A read timeout only checks the deadlines. So does every read, so that a
                    // client trickling bytes cannot outlive its deadline by a housekeeping period.
                    deadlineReason(clock.nanoTime())?.let { session.close(it) }
                }
            } catch (e: Throwable) {
                // IOException from the socket, or anything unexpected, even an Error: this
                // connection ends, and nothing escapes into the pool thread.
                try {
                    session.close(closeReason.get() ?: error(e))
                } catch (inner: Throwable) {
                    log("rtmp-server: Client $address close failed with ${inner.javaClass.simpleName}")
                }
            } finally {
                closeQuietly(socket)
                synchronized(lock) { clients.remove(this) }
            }
        }

        /** From any thread: the worker closes the session with [reason] once it notices. */
        fun requestClose(reason: String) {
            if (closeReason.compareAndSet(null, reason)) {
                closeQuietly(socket)
                // A worker waiting for the media budget wakes up and ends.
                budget.withLock { budgetFreed.signalAll() }
            }
        }

        fun deadlineReason(now: Long): String? {
            val phase = phase
            val age = now - acceptedAt
            return when {
                phase == RtmpServerSession.State.CLOSED -> null
                phase == RtmpServerSession.State.HANDSHAKE && age > limits.handshakeTimeoutMs * NANOS_PER_MILLI -> "Handshake timeout"
                phase != RtmpServerSession.State.PUBLISHING && age > limits.publishTimeoutMs * NANOS_PER_MILLI -> "Publish timeout"
                waitingForBudget -> null
                now - lastReceivedAt > limits.idleTimeoutMs * NANOS_PER_MILLI -> "Receive timeout"
                else -> null
            }
        }

        private fun send(bytes: ByteArray) {
            try {
                output!!.write(bytes)
            } catch (e: IOException) {
                // Not thrown: it would escape feed(). The session stops the feed once closed.
                session.close(closeReason.get() ?: error(e))
            }
        }

        private fun error(e: Throwable) = if (e is Exception) {
            "Error ${e.message ?: e.javaClass.simpleName}"
        } else {
            // An Error's message is not ours to show.
            "Error ${e.javaClass.simpleName}"
        }

        override fun onPublish(stream: RtmpServerStream) {
            synchronized(lock) { phase = RtmpServerSession.State.PUBLISHING }
            streamId = stream.id
            log("rtmp-server: Client $address publishing to \"${stream.name}\"")
            executor.execute { publishStarted(this, stream.id) }
        }

        override fun onWrongKey() {
            log("rtmp-server: Client $address used a wrong stream key")
            recordWrongKey(address)
        }

        /**
         * Waits while the media of all clients would exceed [RtmpServerLimits.maxQueuedMediaBytes]
         * (one message always fits when nothing waits): the worker stops reading and TCP slows the
         * publisher down. The wait does not count as idle. A close or stop ends it; nothing is
         * dropped.
         */
        override fun onMedia(media: RtmpIngestMedia) {
            val id = streamId ?: return
            val size = mediaSize(media)
            budget.withLock {
                waitingForBudget = true
                try {
                    while (queuedMediaBytes > 0 && queuedMediaBytes + size > limits.maxQueuedMediaBytes) {
                        val reason = closeReason.get() ?: if (running) null else SERVER_STOP
                        if (reason != null) {
                            // Stops the rest of this feed.
                            session.close(reason)
                            return
                        }
                        try {
                            budgetFreed.await(BUDGET_RECHECK_MILLIS, TimeUnit.MILLISECONDS)
                        } catch (e: InterruptedException) {
                            session.close(closeReason.get() ?: SERVER_STOP)
                            return
                        }
                    }
                } finally {
                    lastReceivedAt = clock.nanoTime()
                    waitingForBudget = false
                }
                queuedMediaBytes += size
                peakQueuedMediaBytes.accumulateAndGet(queuedMediaBytes, ::maxOf)
            }
            executor.execute {
                try {
                    if (running && publishers[id] === this) {
                        listener.onMedia(id, media)
                    }
                } finally {
                    budget.withLock {
                        queuedMediaBytes -= size
                        budgetFreed.signalAll()
                    }
                }
            }
        }

        private fun mediaSize(media: RtmpIngestMedia): Long = when (media) {
            is RtmpIngestMedia.VideoConfig -> media.config.size
            is RtmpIngestMedia.VideoFrame -> media.data.size
            is RtmpIngestMedia.AudioConfig -> media.audioSpecificConfig.size
            is RtmpIngestMedia.AudioFrame -> media.data.size
        }.toLong()

        override fun onClosed(reason: String) {
            phase = RtmpServerSession.State.CLOSED
            log("rtmp-server: Client $address closed: $reason")
            val id = streamId ?: return
            executor.execute {
                if (publishers.remove(id, this)) {
                    listener.onPublishStop(id, reason)
                }
            }
        }
    }

    private companion object {
        const val BACKLOG = 16
        const val READ_BUFFER_SIZE = 16 * 1024
        const val WORKER_KEEP_ALIVE_SECONDS = 30L
        const val HOUSEKEEPING_PERIOD_NANOS = 3_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val JOIN_MILLIS = 2_000L
        const val ACCEPT_ERROR_PAUSE_MILLIS = 100L
        const val BUDGET_RECHECK_MILLIS = 100L
        const val REFUSAL_LOG_INTERVAL_MILLIS = 10_000L
        const val MAX_REFUSAL_ADDRESSES = 1_024
        const val SERVER_STOP = "Server stop"
        const val SAME_STREAM_KEY = "Same stream key"
    }
}
