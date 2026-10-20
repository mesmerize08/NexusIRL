// Port of Moblin/Various/Network/WebSocketClient.swift @97e26d2 on OkHttp.
package io.github.mesmerize08.nexusirl.chat.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A WebSocket that reconnects like upstream's WebSocketClient: 0.5 s after a failure, doubling up
 * to 10 s, reset on a successful connect. A ping every 10 s detects dead connections.
 * Callbacks run on one thread per socket, in order.
 */
class ReconnectingWebSocket(
    private val url: String,
    private val listener: Listener,
    private val client: OkHttpClient = SHARED_CLIENT,
    private val headers: Map<String, String> = emptyMap(),
) {
    interface Listener {
        fun onConnected(socket: ReconnectingWebSocket)

        fun onMessage(socket: ReconnectingWebSocket, text: String)

        fun onDisconnected(socket: ReconnectingWebSocket) {}
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "websocket").apply { isDaemon = true } }
    private var webSocket: WebSocket? = null
    private var generation = 0
    private var delayMillis = SHORTEST_DELAY_MILLIS
    private var reconnect: ScheduledFuture<*>? = null
    private var running = false

    @Volatile
    var isConnected = false
        private set

    fun start() {
        executor.execute {
            running = true
            connect()
        }
    }

    fun stop() {
        executor.execute {
            running = false
            reconnect?.cancel(false)
            disconnect()
        }
        executor.shutdown()
    }

    fun send(text: String) {
        executor.execute { webSocket?.send(text) }
    }

    private fun connect() {
        if (!running) {
            return
        }
        disconnect()
        val current = ++generation
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = onThread(current) {
                    delayMillis = SHORTEST_DELAY_MILLIS
                    isConnected = true
                    listener.onConnected(this@ReconnectingWebSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) = onThread(current) {
                    listener.onMessage(this@ReconnectingWebSocket, text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    onThread(current) { handleDisconnect() }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onThread(current) {
                    handleDisconnect()
                }
            },
        )
    }

    /** Runs [block] on the socket thread if it still belongs to the current connection. */
    private fun onThread(connection: Int, block: () -> Unit) {
        try {
            executor.execute {
                if (connection == generation && running) {
                    block()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Stopped.
        }
    }

    private fun handleDisconnect() {
        val wasConnected = isConnected
        disconnect()
        if (wasConnected) {
            listener.onDisconnected(this)
        }
        reconnect = executor.schedule({ connect() }, delayMillis, TimeUnit.MILLISECONDS)
        delayMillis = (delayMillis * 2).coerceAtMost(LONGEST_DELAY_MILLIS)
    }

    private fun disconnect() {
        isConnected = false
        webSocket?.cancel()
        webSocket = null
        generation++
    }

    companion object {
        private const val SHORTEST_DELAY_MILLIS = 500L
        private const val LONGEST_DELAY_MILLIS = 10_000L

        /** One client for all chat connections; pings every 10 s like upstream. */
        val SHARED_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
        }
    }
}
