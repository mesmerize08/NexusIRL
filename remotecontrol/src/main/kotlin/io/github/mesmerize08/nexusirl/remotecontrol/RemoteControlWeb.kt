// Port of Moblin/RemoteControl/RemoteControlWeb.swift @97e26d2 (v1 subset: no recordings or
// scoreboards yet). Adds docs/PORT_PLAN.md §9.6: upstream's web remote control has no login; this
// one needs an access token, local interfaces only.
package io.github.mesmerize08.nexusirl.remotecontrol

import io.github.mesmerize08.nexusirl.core.codable.SwiftJson
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.java_websocket.WebSocket
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The browser remote control: an HTTP server on [port] serving upstream's web frontend (static
 * files from [asset]), and its WebSocket on port + 1 speaking the same protocol as an assistant
 * (requests are answered by [delegate], like [RemoteControlStreamer]).
 *
 * Access needs [token]: the link `http://<address>:<port>/?t=<token>` sets an HttpOnly cookie,
 * which browsers also send to the WebSocket port (cookies are per host, not port), so upstream's
 * frontend works unchanged. Requests without it get 401, WebSocket pages are closed at once. Only
 * addresses [acceptLocalAddress] allows are served (LAN, hotspot, Tailscale; never mobile data),
 * checked before a connection gets a worker. Workers are bounded and request heads are limited in
 * size and time, so slow or hostile clients can delay the web page but never stop the stream.
 */
class RemoteControlWeb(
    private val port: Int,
    private val token: String,
    private val delegate: RemoteControlStreamerDelegate,
    private val asset: (String) -> ByteArray?,
    private val acceptLocalAddress: (local: InetAddress, remote: InetAddress?) -> Boolean,
    /** A JPEG of the current frame for `/snapshot`, or null. Blocking. */
    private val snapshot: () -> ByteArray? = { null },
    /** Called with true when the first page asks for previews and false when the last one stops. */
    private val onPreviewWanted: (Boolean) -> Unit = {},
    /** The Recordings page's files, or null when there are none to offer. */
    private val recordings: WebRecordings? = null,
    /** The address to listen on; null for all (tests use loopback). */
    private val bindAddress: InetAddress? = null,
    /** How long a client has to send its whole request head. */
    private val requestHeadTimeoutMillis: Long = 10_000,
) {
    private val pages: MutableSet<WebSocket> = Collections.synchronizedSet(LinkedHashSet())
    private val previewConnections: MutableSet<WebSocket> = Collections.synchronizedSet(HashSet())

    // Bounded, with no queue: when every worker is busy a new connection is closed at once rather
    // than letting clients that never finish their request start threads until the app dies.
    private val pool = ThreadPoolExecutor(0, MAX_WORKERS, 30, TimeUnit.SECONDS, SynchronousQueue()) {
        Thread(it, "remote-control-web").apply { isDaemon = true }
    }

    /** Closes connections that have not sent their whole request head in time. */
    private val deadlines = ScheduledThreadPoolExecutor(1) {
        Thread(it, "remote-control-web-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val clients: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
    private var http: ServerSocket? = null
    private var websocketServer: WebServer? = null

    @Volatile
    private var running = false

    val websocketPort: Int
        get() = port + 1

    /** Whether any page is connected. */
    val isConnected: Boolean
        get() = pages.isNotEmpty()

    fun start() {
        running = true
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(bindAddress, port))
        http = server
        Thread({ acceptLoop(server) }, "remote-control-web-accept").apply { isDaemon = true }.start()
        websocketServer = WebServer(InetSocketAddress(bindAddress, websocketPort)).apply {
            isReuseAddr = true
            connectionLostTimeout = 30
            start()
        }
    }

    fun stop() {
        running = false
        http?.close()
        try {
            websocketServer?.stop(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        pool.shutdownNow()
        deadlines.shutdownNow()
        // Workers blocked reading or writing do not notice interrupts; closing their sockets ends them.
        synchronized(clients) { clients.toList() }.forEach { closeQuietly(it) }
        pages.clear()
        if (previewConnections.isNotEmpty()) {
            previewConnections.clear()
            onPreviewWanted(false)
        }
    }

    private fun broadcast(message: RemoteControlMessageToAssistant, to: Collection<WebSocket> = pages) {
        val text = message.toJson()
        synchronized(to) { to.toList() }.forEach {
            try {
                it.send(text)
            } catch (e: Exception) {
                // Closing.
            }
        }
    }

    fun stateChanged(state: RemoteControlAssistantStreamerState) = broadcast(
        RemoteControlMessageToAssistant.Event(RemoteControlEvent.State(state)),
    )

    fun sendStatus(general: RemoteControlStatusGeneral?, topLeft: RemoteControlStatusTopLeft?, topRight: RemoteControlStatusTopRight?) =
        broadcast(RemoteControlMessageToAssistant.Event(RemoteControlEvent.Status(general, topLeft, topRight)))

    fun sendPreview(jpeg: ByteArray) = broadcast(RemoteControlMessageToAssistant.Preview(jpeg), previewConnections)

    // HTTP (upstream HttpServer): GET (and DELETE for recordings), a fixed set of paths, one
    // request per connection.

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (server.isClosed) {
                    return
                }
                // Out of file descriptors, say: do not spin.
                Thread.sleep(100)
                continue
            }
            // Nothing in here may end the loop: anything thrown from this thread ended the whole
            // process. Throwable, as failing to start a thread is an OutOfMemoryError.
            try {
                if (!running || !isAllowed(socket.localAddress, socket.inetAddress)) {
                    closeQuietly(socket)
                    continue
                }
                clients += socket
                pool.execute { serve(socket) }
            } catch (e: Throwable) {
                // Every worker busy (RejectedExecutionException), or no thread to be had.
                clients -= socket
                closeQuietly(socket)
            }
        }
    }

    private fun serve(socket: Socket) {
        // A browser that goes away mid-response (or any bad request) must never take the app down:
        // an uncaught exception here ended the whole process.
        try {
            socket.use { handleHttp(it) }
        } catch (e: Throwable) {
            // The connection is closed; nothing to answer.
        } finally {
            clients -= socket
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: Exception) {
            // Already closed.
        }
    }

    private fun isAllowed(local: InetAddress?, remote: InetAddress?) = local != null && acceptLocalAddress(local, remote)

    private fun tokenMatches(value: String?) = value != null && MessageDigest.isEqual(value.toByteArray(), token.toByteArray())

    private fun cookieToken(cookieHeader: String?): String? = cookieHeader?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$COOKIE=") }
        ?.substringAfter('=')

    /** The request line and headers, or null if the client sends too much or not enough in time. */
    private fun readRequestHead(socket: Socket): Pair<String, Map<String, String>>? {
        // soTimeout is per read, so a client sending a byte every few seconds would hold a worker
        // forever; the whole head has to arrive before the deadline.
        val deadline = deadlines.schedule({ closeQuietly(socket) }, requestHeadTimeoutMillis, TimeUnit.MILLISECONDS)
        try {
            val input = BufferedInputStream(socket.getInputStream())
            var remaining = MAX_HEAD_BYTES
            val requestLine = readLine(input, remaining) ?: return null
            remaining -= requestLine.length + 2
            val headers = HashMap<String, String>()
            var count = 0
            while (true) {
                val line = readLine(input, remaining) ?: return null
                if (line.isEmpty()) {
                    return requestLine to headers
                }
                remaining -= line.length + 2
                count += 1
                if (count > MAX_HEADERS) {
                    return null
                }
                headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':', "").trim()
            }
        } finally {
            deadline.cancel(false)
        }
    }

    private fun handleHttp(socket: Socket) {
        socket.soTimeout = 10_000
        val (requestLine, headers) = readRequestHead(socket) ?: return
        val parts = requestLine.split(' ')
        val output = socket.getOutputStream()
        if (parts.size != 3) {
            return respond(output, 400, "text/plain", "Bad request".toByteArray())
        }
        val (method, target) = parts
        val path = target.substringBefore('?')
        // DELETE only removes recordings; the SameSite=Strict cookie keeps other sites from sending it.
        if (method != "GET" && !(method == "DELETE" && path.startsWith(RECORDINGS_PREFIX))) {
            return respond(output, 405, "text/plain", "Method not allowed".toByteArray())
        }
        val query = target.substringAfter('?', "").split('&').associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        // The link with ?t=<token> logs in: set the cookie and drop the token from the address bar.
        if (tokenMatches(query["t"])) {
            return respond(
                output,
                302,
                "text/plain",
                ByteArray(0),
                listOf("Location" to path, "Set-Cookie" to "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict"),
            )
        }
        if (!tokenMatches(cookieToken(headers["cookie"]))) {
            return respond(output, 401, "text/html; charset=utf-8", UNAUTHORIZED_PAGE.toByteArray())
        }
        when (path) {
            "/js/config.mjs" -> respond(output, 200, "text/javascript", "export const websocketPort = $websocketPort;\n".toByteArray())
            "/snapshot" -> snapshot()?.let { respond(output, 200, "image/jpeg", it) } ?: respond(output, 503, "text/plain", ByteArray(0))
            "/recordings.json" -> {
                val list = buildJsonArray {
                    recordings?.list()?.forEach { recording ->
                        add(
                            buildJsonObject {
                                put("name", recording.name)
                                put("size", recording.size)
                            },
                        )
                    }
                }
                respond(output, 200, "application/json", list.toString().toByteArray())
            }
            else -> when {
                path.startsWith(RECORDINGS_PREFIX) -> recording(output, method, decodePath(path.removePrefix(RECORDINGS_PREFIX)))
                path.startsWith(THUMBNAILS_PREFIX) ->
                    recordings?.thumbnail(decodePath(path.removePrefix(THUMBNAILS_PREFIX)))
                        ?.let { respond(output, 200, "image/jpeg", it) }
                        ?: respond(output, 404, "text/plain", "Not found".toByteArray())
                else -> staticFile(output, path)
            }
        }
    }

    /** A path segment: percent-decoded, with '+' kept (it is not a space in paths). */
    private fun decodePath(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

    /** Upstream `handleRecordingsFile`: download (GET) or delete (DELETE) one recording. */
    private fun recording(output: OutputStream, method: String, name: String) {
        val recordings = recordings ?: return respond(output, 404, "text/plain", "Not found".toByteArray())
        if (method == "DELETE") {
            return if (recordings.delete(name)) {
                respond(output, 200, "text/plain", ByteArray(0))
            } else {
                respond(output, 404, "text/plain", "Not found".toByteArray())
            }
        }
        val (length, input) = recordings.open(name) ?: return respond(output, 404, "text/plain", "Not found".toByteArray())
        input.use {
            // The name is one of ours, but keep quotes and control characters out of the header anyway.
            val safeName = name.filter { c -> c != '"' && c >= ' ' }
            writeHead(output, 200, "video/mp4", length, listOf("Content-Disposition" to "attachment; filename=\"$safeName\""))
            it.copyTo(output)
            output.flush()
        }
    }

    private fun staticFile(output: OutputStream, path: String) {
        val file = if (path == "/") "/index.html" else path
        val body = if (file in STATIC_FILES) asset(file.removePrefix("/")) else null
        if (body == null) {
            respond(output, 404, "text/plain", "Not found".toByteArray())
        } else {
            respond(output, 200, contentType(file), body)
        }
    }

    private fun readLine(input: BufferedInputStream, limit: Int): String? {
        val line = StringBuilder()
        while (line.length < minOf(8192, limit)) {
            val byte = input.read()
            if (byte < 0) {
                return null
            }
            if (byte == '\n'.code) {
                return line.toString().trimEnd('\r')
            }
            line.append(byte.toChar())
        }
        return null
    }

    private fun respond(
        output: OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        headers: List<Pair<String, String>> = emptyList(),
    ) {
        writeHead(output, status, contentType, body.size.toLong(), headers)
        output.write(body)
        output.flush()
    }

    private fun writeHead(
        output: OutputStream,
        status: Int,
        contentType: String,
        contentLength: Long,
        headers: List<Pair<String, String>> = emptyList(),
    ) {
        val reason = mapOf(
            200 to "OK",
            302 to "Found",
            400 to "Bad Request",
            401 to "Unauthorized",
            404 to "Not Found",
            405 to "Method Not Allowed",
            503 to "Service Unavailable",
        )[status]
        val head = StringBuilder("HTTP/1.1 $status $reason\r\n")
        val all = listOf(
            "Content-Type" to contentType,
            "Content-Length" to contentLength.toString(),
            "Connection" to "close",
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "X-Frame-Options" to "DENY",
            "Referrer-Policy" to "no-referrer",
        ) + headers
        all.forEach { (name, value) -> head.append("$name: $value\r\n") }
        head.append("\r\n")
        output.write(head.toString().toByteArray())
    }

    private fun contentType(path: String) = when (path.substringAfterLast('.')) {
        "html" -> "text/html; charset=utf-8"
        "css" -> "text/css"
        "mjs", "js" -> "text/javascript"
        "png" -> "image/png"
        "ico" -> "image/x-icon"
        else -> "application/octet-stream"
    }

    private inner class WebServer(address: InetSocketAddress) : WebSocketServer(address) {
        override fun onStart() = Unit

        /** Pages without the cookie, or on a disallowed interface, are closed before anything is sent. */
        override fun onOpen(socket: WebSocket, handshake: ClientHandshake) {
            if (!isAllowed(socket.localSocketAddress?.address, socket.remoteSocketAddress?.address) ||
                !tokenMatches(cookieToken(handshake.getFieldValue("Cookie")))
            ) {
                socket.close(CloseFrame.POLICY_VALIDATION, "Unauthorized")
                return
            }
            pages += socket
            // Upstream `remoteControlWebConnected`: a new page gets the whole current state.
            delegate.connected()
        }

        override fun onClose(socket: WebSocket, code: Int, reason: String?, remote: Boolean) {
            pages -= socket
            stopPreview(socket)
        }

        override fun onMessage(socket: WebSocket, message: String) {
            if (socket !in pages) {
                return
            }
            val request = try {
                SwiftJson.decodeFromString(RemoteControlMessageToStreamer.Serializer, message) as? RemoteControlMessageToStreamer.Request
            } catch (e: Exception) {
                null
            } ?: return
            // Previews are per page (upstream connectionsRequestingPreview).
            val (result, data) = when (request.data) {
                RemoteControlRequest.StartPreview -> {
                    if (previewConnections.add(socket) && previewConnections.size == 1) onPreviewWanted(true)
                    RemoteControlResult.OK to null
                }
                RemoteControlRequest.StopPreview -> {
                    stopPreview(socket)
                    RemoteControlResult.OK to null
                }
                // Settings import is never accepted from a browser (threat model T6).
                is RemoteControlRequest.ImportSettings -> RemoteControlResult.ERROR to null
                else -> delegate.handle(request.data)
            }
            try {
                socket.send(RemoteControlMessageToAssistant.Response(request.id, result, data).toJson())
            } catch (e: Exception) {
                // Closing.
            }
        }

        override fun onError(socket: WebSocket?, error: Exception) = Unit
    }

    private fun stopPreview(socket: WebSocket) {
        if (previewConnections.remove(socket) && previewConnections.isEmpty()) {
            onPreviewWanted(false)
        }
    }

    private companion object {
        const val COOKIE = "nexusirl_token"

        /** Concurrent HTTP requests: a page load fetches about ten files, browsers six at a time. */
        const val MAX_WORKERS = 16
        const val MAX_HEADERS = 100

        /** Request line and headers together; browsers send a few KB at most to this server. */
        const val MAX_HEAD_BYTES = 16 * 1024
        private const val RECORDINGS_PREFIX = "/recordings/"
        private const val THUMBNAILS_PREFIX = "/thumbnails/"

        /** Upstream's `staticFiles`. */
        val STATIC_FILES = setOf(
            "/favicon.ico", "/golf.html", "/index.html", "/recordings.html", "/remote.html", "/scoreboard.html", "/volleyball.png",
            "/css/app.css", "/css/common.css", "/css/components.css", "/css/golf.css", "/css/recordings.css", "/css/remote.css",
            "/css/scoreboard.css", "/js/app.mjs", "/js/golf.mjs", "/js/index.mjs", "/js/components.mjs", "/js/recordings.mjs",
            "/js/remote.mjs", "/js/scoreboard.mjs", "/js/utils.mjs", "/js/vendor.mjs",
        )

        const val UNAUTHORIZED_PAGE =
            "<!doctype html><meta name=viewport content=\"width=device-width\"><title>NexusIRL</title>" +
                "<p>Open the remote control link shown in NexusIRL (Remote control settings) to sign in.</p>"
    }
}
