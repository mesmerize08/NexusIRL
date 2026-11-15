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
import java.util.concurrent.atomic.AtomicReference

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
 * wrong-key lockout, [RtmpServerLimits.maxConnections] and [RtmpServerLimits.maxUnauthenticated]
 * (connections not publishing yet). A server starts once; after [stop] it cannot start again.
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
    private val pool = ThreadPoolExecutor(
        0,
        limits.maxConnections,
        WORKER_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { Thread(it, "rtmp-server-client-${workerNumber.incrementAndGet()}").apply { isDaemon = true } },
    )

    // Guarded by lock.
    private val lock = Any()
    private val clients = HashSet<Client>()
    private val failures = HashMap<InetAddress, Failures>()
    private var started = false
    private var stopped = false

    /** Who publishes each stream; written on the executor only. */
    private val publishers = ConcurrentHashMap<UUID, Client>()

    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var housekeeping: Cancellable? = null

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
        serverSocket = server
        running = true
        executor.execute { executorThread = Thread.currentThread() }
        housekeeping = executor.scheduleRepeating(HOUSEKEEPING_PERIOD_NANOS) { housekeep() }
        acceptThread = Thread({ acceptLoop(server) }, "rtmp-server-accept").apply {
            isDaemon = true
            start()
        }
        log("rtmp-server: Listening on port ${server.localPort}")
    }

    /**
     * Closes every connection and the listening socket and ends all threads. The listener gets
     * `onPublishStop(id, "Server stop")` for each publisher, and no call after this returns.
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
                    try {
                        listener.onPublishStop(id, SERVER_STOP)
                    } catch (e: Exception) {
                        log("rtmp-server: Listener failed with ${e.javaClass.simpleName}")
                    }
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
        executor.shutdown()
        pool.shutdown()
        pool.awaitTermination(JOIN_MILLIS, TimeUnit.MILLISECONDS)
        log("rtmp-server: Stopped")
    }

    fun connectedStreamIds(): Set<UUID> = publishers.keys.toSet()

    fun numberOfClients(): Int = synchronized(lock) { clients.size }

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
            log("rtmp-server: Refused $remote, not an allowed peer")
            closeQuietly(socket)
            return
        }
        var admitted: Client? = null
        val refusal = synchronized(lock) {
            when {
                isLockedOut(remote) -> "locked out after wrong stream keys"
                clients.size >= limits.maxConnections -> "too many connections"
                clients.count { !it.publishing } >= limits.maxUnauthenticated -> "too many connections not publishing"
                else -> {
                    admitted = Client(socket).also { clients.add(it) }
                    null
                }
            }
        }
        val client = admitted
        if (client == null) {
            log("rtmp-server: Refused $remote, $refusal")
            closeQuietly(socket)
            return
        }
        try {
            pool.execute { client.run() }
        } catch (e: RejectedExecutionException) {
            synchronized(lock) { clients.remove(client) }
            log("rtmp-server: Refused $remote, no worker free")
            closeQuietly(socket)
        }
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
            listener.onPublishStop(id, SAME_STREAM_KEY)
            old.requestClose(SAME_STREAM_KEY)
        }
        listener.onPublishStart(id)
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
            } catch (e: Exception) {
                // IOException from the socket, or anything unexpected: the connection ends.
                session.close(closeReason.get() ?: error(e))
            } finally {
                closeQuietly(socket)
                synchronized(lock) { clients.remove(this) }
            }
        }

        /** From any thread: the worker closes the session with [reason] once it notices. */
        fun requestClose(reason: String) {
            if (closeReason.compareAndSet(null, reason)) {
                closeQuietly(socket)
            }
        }

        fun deadlineReason(now: Long): String? {
            val phase = phase
            val age = now - acceptedAt
            return when {
                phase == RtmpServerSession.State.CLOSED -> null
                phase == RtmpServerSession.State.HANDSHAKE && age > limits.handshakeTimeoutMs * NANOS_PER_MILLI -> "Handshake timeout"
                phase != RtmpServerSession.State.PUBLISHING && age > limits.publishTimeoutMs * NANOS_PER_MILLI -> "Publish timeout"
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

        private fun error(e: Exception) = "Error ${e.message ?: e.javaClass.simpleName}"

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

        override fun onMedia(media: RtmpIngestMedia) {
            val id = streamId ?: return
            executor.execute {
                if (running && publishers[id] === this) {
                    listener.onMedia(id, media)
                }
            }
        }

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
        const val SERVER_STOP = "Server stop"
        const val SAME_STREAM_KEY = "Same stream key"
    }
}
