// Port of Moblin/RemoteControl/RemoteControlWeb.swift @97e26d2 (v1 subset: no recordings or
// scoreboards yet). Adds docs/PORT_PLAN.md §9.6: upstream's web remote control has no login; this
// one needs an access token, local interfaces only.
package io.github.mesmerize08.nexusirl.remotecontrol

import io.github.mesmerize08.nexusirl.core.codable.SwiftJson
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
import java.util.concurrent.Executors

/**
 * The browser remote control: an HTTP server on [port] serving upstream's web frontend (static
 * files from [asset]), and its WebSocket on port + 1 speaking the same protocol as an assistant
 * (requests are answered by [delegate], like [RemoteControlStreamer]).
 *
 * Access needs [token]: the link `http://<address>:<port>/?t=<token>` sets an HttpOnly cookie,
 * which browsers also send to the WebSocket port (cookies are per host, not port), so upstream's
 * frontend works unchanged. Requests without it get 401, WebSocket pages are closed at once. Only
 * addresses [acceptLocalAddress] allows are served (LAN, hotspot, Tailscale; never mobile data).
 */
class RemoteControlWeb(
    private val port: Int,
    private val token: String,
    private val delegate: RemoteControlStreamerDelegate,
    private val asset: (String) -> ByteArray?,
    private val acceptLocalAddress: (InetAddress) -> Boolean,
    /** A JPEG of the current frame for `/snapshot`, or null. Blocking. */
    private val snapshot: () -> ByteArray? = { null },
    /** Called with true when the first page asks for previews and false when the last one stops. */
    private val onPreviewWanted: (Boolean) -> Unit = {},
) {
    private val pages: MutableSet<WebSocket> = Collections.synchronizedSet(LinkedHashSet())
    private val previewConnections: MutableSet<WebSocket> = Collections.synchronizedSet(HashSet())
    private val pool = Executors.newCachedThreadPool { Thread(it, "remote-control-web").apply { isDaemon = true } }
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
        server.bind(InetSocketAddress(port))
        http = server
        pool.execute { acceptLoop(server) }
        websocketServer = WebServer(InetSocketAddress(websocketPort)).apply {
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

    // HTTP (upstream HttpServer): GET only, a fixed set of paths, one request per connection.

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                return
            }
            pool.execute { socket.use { handleHttp(it) } }
        }
    }

    private fun isAllowed(local: InetAddress?) = local != null && acceptLocalAddress(local)

    private fun tokenMatches(value: String?) = value != null && MessageDigest.isEqual(value.toByteArray(), token.toByteArray())

    private fun cookieToken(cookieHeader: String?): String? = cookieHeader?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$COOKIE=") }
        ?.substringAfter('=')

    private fun handleHttp(socket: Socket) {
        socket.soTimeout = 10_000
        if (!isAllowed(socket.localAddress)) {
            return
        }
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) {
                break
            }
            if (headers.size < 100) {
                headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':', "").trim()
            }
        }
        val parts = requestLine.split(' ')
        val output = socket.getOutputStream()
        if (parts.size != 3) {
            return respond(output, 400, "text/plain", "Bad request".toByteArray())
        }
        val (method, target) = parts
        if (method != "GET") {
            return respond(output, 405, "text/plain", "Method not allowed".toByteArray())
        }
        val path = target.substringBefore('?')
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
            else -> {
                val file = if (path == "/") "/index.html" else path
                val body = if (file in STATIC_FILES) asset(file.removePrefix("/")) else null
                if (body == null) {
                    respond(output, 404, "text/plain", "Not found".toByteArray())
                } else {
                    respond(output, 200, contentType(file), body)
                }
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = StringBuilder()
        while (line.length < 8192) {
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
            "Content-Length" to body.size.toString(),
            "Connection" to "close",
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "X-Frame-Options" to "DENY",
            "Referrer-Policy" to "no-referrer",
        ) + headers
        all.forEach { (name, value) -> head.append("$name: $value\r\n") }
        head.append("\r\n")
        output.write(head.toString().toByteArray())
        output.write(body)
        output.flush()
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
            if (!isAllowed(socket.localSocketAddress?.address) || !tokenMatches(cookieToken(handshake.getFieldValue("Cookie")))) {
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
