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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
    private val acceptLocalAddress: (local: InetAddress, remote: InetAddress?) -> Boolean = CleartextPolicy::isLocalConnection,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The address to listen on; null for all (tests use loopback). */
    private val bindAddress: InetAddress? = null,
) {
    private val lock = Any()
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
    private val lockout = PasswordLockout(clock)

    @Volatile
    var isConnected = false
        private set

    fun start() = startServer()

    fun stop() {
        val stoppedServer = synchronized(lock) {
            retry?.cancel(false)
            keepAlive?.cancel(false)
            val current = server
            server = null
            streamer = null
            identified = false
            requests.clear()
            updateConnected(false)
            timer.shutdown()
            current
        }
        // The WebSocket library can hold its connection lock while calling onClose.
        // Give that callback access to our lock before closing sockets and joining the server.
        try {
            stoppedServer?.stop(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun startServer() {
        synchronized(lock) {
            if (timer.isShutdown) {
                return
            }
            val newServer = Server(InetSocketAddress(bindAddress, port))
            server = newServer
            newServer.isReuseAddr = true
            // WebSocket pings every 30 s; a silent connection is closed (upstream ping timer).
            newServer.connectionLostTimeout = 30
            newServer.start()
        }
    }

    /** Upstream `startRetryStartTimer`: try to listen again in 5 seconds. */
    // Called under lock. Queue notifications in state-transition order and run the callbacks
    // on the executor so a delegate can call back into the assistant without holding its lock.
    private fun updateConnected(connected: Boolean) {
        if (isConnected != connected) {
            isConnected = connected
            timer.execute { if (connected) delegate.connected() else delegate.disconnected() }
        }
    }

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
        val request = synchronized(lock) {
            streamer?.takeIf { identified }?.let { socket ->
                val id = nextId++
                requests[id] = onResult
                socket to id
            }
        } ?: return onResult(RemoteControlResult.ERROR, null)
        val (socket, id) = request
        send(socket, RemoteControlMessageToStreamer.Request(id, data))
    }

    private fun startKeepAlive(socket: WebSocket) {
        keepAlive?.cancel(false)
        gotPing = false
        keepAlive = try {
            timer.scheduleWithFixedDelay({
                val shouldClose = synchronized(lock) {
                    if (streamer !== socket) return@synchronized false
                    (!gotPing).also { gotPing = false }
                }
                if (shouldClose) {
                    socket.close()
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
                synchronized(lock) { if (streamer === socket) gotPing = true }
                send(socket, RemoteControlMessageToStreamer.Pong)
            }
            else -> synchronized(lock) {
                if (!identified || streamer !== socket) return
                // Queue these under the same lock as connection transitions. In particular,
                // a replacement streamer's first event must follow the previous disconnect.
                when (message) {
                    is RemoteControlMessageToAssistant.Response -> requests.remove(message.id)?.let { callback ->
                        timer.execute { callback(message.result, message.data) }
                    }
                    is RemoteControlMessageToAssistant.Event -> timer.execute {
                        when (val event = message.data) {
                            is RemoteControlEvent.State -> delegate.stateChanged(event.data)
                            is RemoteControlEvent.Log -> delegate.log(event.entry)
                            is RemoteControlEvent.Status -> delegate.status(event.general, event.topLeft, event.topRight)
                        }
                    }
                    is RemoteControlMessageToAssistant.Preview -> timer.execute { delegate.preview(message.preview) }
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
                if (streamer !== socket) {
                    return
                }
                identified = true
                lockout.recordSuccess(host)
            }
            send(socket, RemoteControlMessageToStreamer.Identified(RemoteControlResult.OK))
            synchronized(lock) {
                if (streamer === socket && identified) {
                    updateConnected(true)
                }
            }
        } else {
            lockout.recordFailure(host)
            send(socket, RemoteControlMessageToStreamer.Identified(RemoteControlResult.WRONG_PASSWORD))
            socket.close()
        }
    }

    private inner class Server(address: InetSocketAddress) : WebSocketServer(address, listOf(LimitedDraft(MAXIMUM_MESSAGE_SIZE))) {
        override fun onStart() {
            synchronized(lock) { if (server === this) retry?.cancel(false) }
        }

        override fun onOpen(socket: WebSocket, handshake: ClientHandshake) {
            if (lockout.refusal(socket.localSocketAddress?.address, socket.remoteSocketAddress?.address, acceptLocalAddress) != null) {
                socket.close()
                return
            }
            val previous: WebSocket?
            val authentication: RemoteControlAuthentication
            synchronized(lock) {
                if (server !== this) {
                    return
                }
                // One streamer at a time, as upstream: a new connection replaces the old one.
                previous = streamer
                streamer = socket
                identified = false
                requests.clear()
                challenge = newChallenge()
                salt = newChallenge()
                authentication = RemoteControlAuthentication(challenge, salt)
                updateConnected(false)
                startKeepAlive(socket)
            }
            previous?.close()
            send(socket, RemoteControlMessageToStreamer.Hello(REMOTE_CONTROL_API_VERSION, authentication))
        }

        override fun onClose(socket: WebSocket, code: Int, reason: String?, remote: Boolean) {
            synchronized(lock) {
                if (streamer === socket) {
                    streamer = null
                    identified = false
                    keepAlive?.cancel(false)
                    val pending = requests.values.toList()
                    requests.clear()
                    pending.forEach { callback -> timer.execute { callback(RemoteControlResult.ERROR, null) } }
                    updateConnected(false)
                }
            }
        }

        override fun onMessage(socket: WebSocket, message: String) = handleMessage(socket, message)

        override fun onError(socket: WebSocket?, error: Exception) {
            // A failure without a connection is the listening socket: try again later (upstream).
            synchronized(lock) {
                if (socket == null && server === this) {
                    retry?.cancel(false)
                    retry = timer.schedule({ startServer() }, 5, TimeUnit.SECONDS)
                }
            }
        }
    }

    companion object {
        const val MAXIMUM_FAILURES = PasswordLockout.MAXIMUM_FAILURES
        const val LOCKOUT_MILLIS = PasswordLockout.LOCKOUT_MILLIS

        /**
         * The largest message (or frame) a streamer may send. Previews are the largest legitimate
         * ones: upstream's are 400 pixel JPEGs at quality 0.3, tens of KB even as base64; settings
         * and status are a few KB. 4 MB leaves room for other streamers and future previews.
         */
        const val MAXIMUM_MESSAGE_SIZE = 4 * 1024 * 1024
    }
}
