// Port of Moblin/RemoteControl/RemoteControlAssistant.swift @97e26d2 (v1 subset: no Twitch relay
// or chat history). Adds docs/PORT_PLAN.md §9.6: local interfaces only and a wrong-password lockout.
package io.github.mesmerize08.nexusirl.remotecontrol

import io.github.mesmerize08.nexusirl.core.codable.SwiftJson
import io.github.mesmerize08.nexusirl.core.websocket.CleartextPolicy
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Upstream `DefaultTcpPorts.remoteControlAssistant`. */
const val REMOTE_CONTROL_DEFAULT_PORT = 2345

/** What the assistant shows (upstream `RemoteControlAssistantDelegate`). Called on the server's threads. */
interface RemoteControlAssistantDelegate {
    fun connected() {}

    fun disconnected() {}

    fun preview(jpeg: ByteArray) {}

    fun stateChanged(state: RemoteControlAssistantStreamerState) {}

    fun log(entry: String) {}

    fun status(general: RemoteControlStatusGeneral?, topLeft: RemoteControlStatusTopLeft?, topRight: RemoteControlStatusTopRight?) {}
}

/**
 * The assistant side of remote control: a WebSocket server (port 2345) that one streamer connects
 * to. Challenges it with upstream's password hash, then sends requests and receives events.
 * Expects the streamer's app-level ping at least every 60 s.
 *
 * Security additions to upstream: connections are only accepted on addresses [acceptLocalAddress]
 * allows (by default loopback, LAN and Tailscale; the app also excludes mobile data), and after
 * [MAXIMUM_FAILURES] wrong passwords an address is refused for [LOCKOUT_MILLIS].
 */
class RemoteControlAssistant(
    private val port: Int,
    private val password: String,
    private val delegate: RemoteControlAssistantDelegate,
    private val acceptLocalAddress: (InetAddress) -> Boolean = { address ->
        val host = address.hostAddress.orEmpty()
        CleartextPolicy.isLoopback(host) || CleartextPolicy.isLocalNetwork(host)
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val random = SecureRandom()
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "remote-control-assistant").apply { isDaemon = true } }
    private var server: Server? = null
    private var streamer: WebSocket? = null
    private var identified = false
    private var challenge = ""
    private var salt = ""
    private var gotPing = false
    private var keepAlive: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null
    private var nextId = 0
    private val requests = HashMap<Int, (RemoteControlResult, RemoteControlResponse?) -> Unit>()
    private val failures = HashMap<String, Pair<Int, Long>>()

    @Volatile
    var isConnected = false
        private set

    /** The port actually listened on (useful with port 0 in tests). */
    val boundPort: Int
        get() = server?.port ?: port

    fun start() = startServer()

    fun stop() {
        synchronized(lock) {
            retry?.cancel(false)
            keepAlive?.cancel(false)
            try {
                server?.stop(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            server = null
            streamer = null
            setConnected(false)
            requests.clear()
        }
        timer.shutdownNow()
    }

    private fun startServer() {
        synchronized(lock) {
            val newServer = Server(InetSocketAddress(port))
            server = newServer
            newServer.isReuseAddr = true
            // WebSocket pings every 30 s; a silent connection is closed (upstream ping timer).
            newServer.connectionLostTimeout = 30
            newServer.start()
        }
    }

    /** Upstream `startRetryStartTimer`: try to listen again in 5 seconds. */
    private fun retryLater() {
        retry = try {
            timer.schedule({ startServer() }, 5, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            null
        }
    }

    private fun setConnected(connected: Boolean) {
        if (isConnected != connected) {
            isConnected = connected
            if (connected) delegate.connected() else delegate.disconnected()
        }
    }

    private fun randomString(): String = ByteArray(32).also(random::nextBytes).let { Base64.getEncoder().encodeToString(it) }

    private fun send(socket: WebSocket?, message: RemoteControlMessageToStreamer) {
        try {
            socket?.send(SwiftJson.encodeToString(RemoteControlMessageToStreamer.Serializer, message))
        } catch (e: Exception) {
            // Closed meanwhile.
        }
    }

    /**
     * Upstream `performRequest`: sends [data] to the streamer; [onResult] gets the result and any
     * response data (ERROR when not connected or disconnected meanwhile).
     */
    fun request(data: RemoteControlRequest, onResult: (RemoteControlResult, RemoteControlResponse?) -> Unit = { _, _ -> }) {
        val socket: WebSocket
        val id: Int
        synchronized(lock) {
            val current = streamer
            if (current == null || !identified) {
                onResult(RemoteControlResult.ERROR, null)
                return
            }
            socket = current
            id = nextId++
            requests[id] = onResult
        }
        send(socket, RemoteControlMessageToStreamer.Request(id, data))
    }

    private fun isLockedOut(host: String): Boolean = synchronized(lock) {
        val (count, lockedUntil) = failures[host] ?: return false
        count >= MAXIMUM_FAILURES && clock() < lockedUntil
    }

    private fun recordFailure(host: String) = synchronized(lock) {
        val (count, lockedUntil) = failures[host] ?: (0 to 0L)
        val newCount = if (count >= MAXIMUM_FAILURES && clock() >= lockedUntil) 1 else count + 1
        failures[host] = newCount to if (newCount >= MAXIMUM_FAILURES) clock() + LOCKOUT_MILLIS else 0L
    }

    private fun startKeepAlive(socket: WebSocket) {
        keepAlive?.cancel(false)
        gotPing = false
        keepAlive = try {
            timer.scheduleWithFixedDelay({
                synchronized(lock) {
                    if (streamer === socket && !gotPing) {
                        socket.close()
                    }
                    gotPing = false
                }
            }, 60, 60, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            null
        }
    }

    private fun handleMessage(socket: WebSocket, text: String) {
        val message = try {
            SwiftJson.decodeFromString(RemoteControlMessageToAssistant.Serializer, text)
        } catch (e: Exception) {
            return
        }
        when (message) {
            is RemoteControlMessageToAssistant.Identify -> handleIdentify(socket, message.authentication)
            is RemoteControlMessageToAssistant.Ping -> {
                synchronized(lock) { gotPing = true }
                send(socket, RemoteControlMessageToStreamer.Pong)
            }
            else -> {
                if (!synchronized(lock) { identified && streamer === socket }) {
                    return
                }
                when (message) {
                    is RemoteControlMessageToAssistant.Response -> synchronized(
                        lock,
                    ) { requests.remove(message.id) }?.invoke(message.result, message.data)
                    is RemoteControlMessageToAssistant.Event -> when (val event = message.data) {
                        is RemoteControlEvent.State -> delegate.stateChanged(event.data)
                        is RemoteControlEvent.Log -> delegate.log(event.entry)
                        is RemoteControlEvent.Status -> delegate.status(event.general, event.topLeft, event.topRight)
                    }
                    is RemoteControlMessageToAssistant.Preview -> delegate.preview(message.preview)
                    else -> Unit
                }
            }
        }
    }

    private fun handleIdentify(socket: WebSocket, authentication: String) {
        val host = socket.remoteSocketAddress?.address?.hostAddress.orEmpty()
        val expected: String
        synchronized(lock) {
            if (streamer !== socket) {
                return
            }
            expected = remoteControlHashPassword(challenge, salt, password)
        }
        // Constant time, so the comparison leaks nothing about the hash.
        if (MessageDigest.isEqual(expected.toByteArray(), authentication.toByteArray())) {
            synchronized(lock) {
                identified = true
                failures.remove(host)
            }
            send(socket, RemoteControlMessageToStreamer.Identified(RemoteControlResult.OK))
            setConnected(true)
        } else {
            recordFailure(host)
            send(socket, RemoteControlMessageToStreamer.Identified(RemoteControlResult.WRONG_PASSWORD))
            socket.close()
        }
    }

    private inner class Server(address: InetSocketAddress) : WebSocketServer(address) {
        override fun onStart() {
            retry?.cancel(false)
        }

        override fun onOpen(socket: WebSocket, handshake: ClientHandshake) {
            val local = socket.localSocketAddress?.address
            val host = socket.remoteSocketAddress?.address?.hostAddress.orEmpty()
            if (local == null || !acceptLocalAddress(local) || isLockedOut(host)) {
                socket.close()
                return
            }
            val previous: WebSocket?
            synchronized(lock) {
                // One streamer at a time, as upstream: a new connection replaces the old one.
                previous = streamer
                streamer = socket
                identified = false
                requests.clear()
                challenge = randomString()
                salt = randomString()
            }
            if (previous != null) {
                previous.close()
                setConnected(false)
            }
            send(socket, RemoteControlMessageToStreamer.Hello(REMOTE_CONTROL_API_VERSION, RemoteControlAuthentication(challenge, salt)))
            startKeepAlive(socket)
        }

        override fun onClose(socket: WebSocket, code: Int, reason: String?, remote: Boolean) {
            val wasCurrent = synchronized(lock) {
                if (streamer === socket) {
                    streamer = null
                    identified = false
                    keepAlive?.cancel(false)
                    val pending = requests.values.toList()
                    requests.clear()
                    pending.forEach { it(RemoteControlResult.ERROR, null) }
                    true
                } else {
                    false
                }
            }
            if (wasCurrent) {
                setConnected(false)
            }
        }

        override fun onMessage(socket: WebSocket, message: String) = handleMessage(socket, message)

        override fun onError(socket: WebSocket?, error: Exception) {
            // A failure without a connection is the listening socket: try again later (upstream).
            if (socket == null) {
                retryLater()
            }
        }
    }

    companion object {
        const val MAXIMUM_FAILURES = 5
        const val LOCKOUT_MILLIS = 60_000L
    }
}
