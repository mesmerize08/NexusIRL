// Port of Moblin/RemoteControl/RemoteControlStreamer.swift @97e26d2 (v1 subset of the requests).
package io.github.mesmerize08.nexusirl.remotecontrol

import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import okhttp3.OkHttpClient
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * What the streamer does for an assistant (upstream `RemoteControlStreamerDelegate`). Called on
 * the connection's thread. Requests with no handler here are answered with `unknownRequest`.
 */
interface RemoteControlStreamerDelegate {
    fun connected() {}

    fun disconnected() {}

    fun wrongPassword() {}

    fun getStatus(): RemoteControlResponse.GetStatus

    fun getSettings(): RemoteControlSettings

    fun setStream(id: UUID) {}

    fun setScene(id: UUID) {}

    fun setMic(id: String) {}

    fun setBitratePreset(id: UUID) {}

    fun setRecord(on: Boolean) {}

    fun setLive(on: Boolean) {}

    fun setZoom(x: Float) {}

    fun setMute(on: Boolean) {}

    fun setStealthMode(on: Boolean) {}

    fun setTorch(on: Boolean) {}

    fun setDebugLogging(on: Boolean) {}

    fun reloadBrowserWidgets() {}

    fun startPreview() {}

    fun stopPreview() {}

    fun startStatus(interval: Int, filter: RemoteControlStartStatusFilter) {}

    fun stopStatus() {}

    fun setFilter(filter: RemoteControlFilter, on: Boolean) {}

    fun sendMessage(text: String) {}

    /**
     * Upstream applies imported settings directly. Android only does so after the user agrees on
     * the device (docs/threat-model.md T6); return whether it was accepted.
     */
    fun importSettings(data: ByteArray): Boolean = false
}

/**
 * The streamer side of remote control: connects to an assistant (`ws://host:2345` on a LAN, or a
 * relay), proves it knows the password with upstream's challenge hash, then answers requests and
 * sends events. A ping every 30 s; without a pong the connection is restarted.
 */
class RemoteControlStreamer(
    url: String,
    private val password: String,
    private val streamerId: String,
    private val delegate: RemoteControlStreamerDelegate,
    client: OkHttpClient = ReconnectingWebSocket.LOCAL_NETWORK_CLIENT,
    private val keepAliveSeconds: Long = 30,
) {
    private val encryption = RemoteControlEncryption(password)
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "remote-control").apply { isDaemon = true } }
    private var keepAlive: ScheduledFuture<*>? = null

    @Volatile
    private var gotPong = true

    @Volatile
    var isConnected = false
        private set

    @Volatile
    var wrongPassword = false
        private set

    private val socket = ReconnectingWebSocket(
        url,
        object : ReconnectingWebSocket.Listener {
            override fun onConnected(socket: ReconnectingWebSocket) = Unit

            override fun onMessage(socket: ReconnectingWebSocket, text: String) = handleMessage(text)

            override fun onDisconnected(socket: ReconnectingWebSocket) = handleDisconnected()
        },
        client,
    )

    fun start() {
        wrongPassword = false
        socket.start()
    }

    fun stop() {
        keepAlive?.cancel(false)
        timer.shutdownNow()
        socket.stop()
        handleDisconnected()
    }

    private fun handleDisconnected() {
        keepAlive?.cancel(false)
        keepAlive = null
        if (isConnected) {
            isConnected = false
            delegate.disconnected()
        }
    }

    /** Upstream `stateChanged`: send what changed. */
    fun stateChanged(state: RemoteControlAssistantStreamerState) {
        if (isConnected) {
            send(RemoteControlMessageToAssistant.Event(RemoteControlEvent.State(state)))
        }
    }

    fun log(entry: String) {
        if (isConnected) {
            send(RemoteControlMessageToAssistant.Event(RemoteControlEvent.Log(entry)))
        }
    }

    fun sendStatus(general: RemoteControlStatusGeneral?, topLeft: RemoteControlStatusTopLeft?, topRight: RemoteControlStatusTopRight?) {
        if (isConnected) {
            send(RemoteControlMessageToAssistant.Event(RemoteControlEvent.Status(general, topLeft, topRight)))
        }
    }

    /** A JPEG of the stream for the assistant's preview. */
    fun sendPreview(jpeg: ByteArray) {
        if (isConnected) {
            send(RemoteControlMessageToAssistant.Preview(jpeg))
        }
    }

    /** Upstream `twitchStart`: the token is AES-GCM encrypted with the password before sending. */
    fun twitchStart(channelName: String?, channelId: String, accessToken: String) {
        if (!isConnected) {
            return
        }
        val encrypted = Base64.getEncoder().encodeToString(encryption.encrypt(accessToken.toByteArray()))
        send(RemoteControlMessageToAssistant.TwitchStart(channelName, channelId, encrypted))
    }

    private fun send(message: RemoteControlMessageToAssistant) = socket.send(message.toJson())

    /** Handles one message from the assistant; public for tests. */
    fun handleMessage(text: String) {
        val message = try {
            RemoteControlMessageToStreamer.fromJson(text)
        } catch (e: Exception) {
            return
        }
        when (message) {
            is RemoteControlMessageToStreamer.Hello -> {
                val hash = remoteControlHashPassword(message.authentication.challenge, message.authentication.salt, password)
                send(RemoteControlMessageToAssistant.Identify(streamerId, hash))
            }
            is RemoteControlMessageToStreamer.Identified -> handleIdentified(message.result)
            is RemoteControlMessageToStreamer.Request -> if (isConnected) handleRequest(message.id, message.data)
            RemoteControlMessageToStreamer.Pong -> gotPong = true
        }
    }

    private fun handleIdentified(result: RemoteControlResult) {
        when (result) {
            RemoteControlResult.OK -> {
                isConnected = true
                wrongPassword = false
                startKeepAlive()
                delegate.connected()
            }
            RemoteControlResult.WRONG_PASSWORD -> if (!wrongPassword) {
                wrongPassword = true
                delegate.wrongPassword()
            }
            else -> Unit
        }
    }

    private fun startKeepAlive() {
        gotPong = true
        keepAlive?.cancel(false)
        keepAlive = try {
            timer.scheduleWithFixedDelay({
                if (gotPong) {
                    gotPong = false
                    send(RemoteControlMessageToAssistant.Ping)
                } else {
                    socket.restart()
                }
            }, keepAliveSeconds, keepAliveSeconds, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            null
        }
    }

    private fun handleRequest(id: Int, request: RemoteControlRequest) {
        val (result, data) = delegate.handle(request)
        send(RemoteControlMessageToAssistant.Response(id, result, data))
    }
}

/**
 * Upstream `RemoteControlStreamer.handleRequest` (and `RemoteControlWeb.handleRequest`): runs
 * [request] on the delegate and returns the result and any response data. Shared by the
 * assistant connection and the web remote control.
 */
fun RemoteControlStreamerDelegate.handle(request: RemoteControlRequest): Pair<RemoteControlResult, RemoteControlResponse?> {
    var result = RemoteControlResult.OK
    var data: RemoteControlResponse? = null
    when (request) {
        RemoteControlRequest.GetStatus -> data = getStatus()
        RemoteControlRequest.GetSettings -> data = RemoteControlResponse.GetSettings(getSettings())
        is RemoteControlRequest.SetStream -> setStream(request.id)
        is RemoteControlRequest.SetScene -> setScene(request.id)
        is RemoteControlRequest.SetMic -> setMic(request.id)
        is RemoteControlRequest.SetBitratePreset -> setBitratePreset(request.id)
        is RemoteControlRequest.SetRecord -> setRecord(request.on)
        is RemoteControlRequest.SetLive -> setLive(request.on)
        is RemoteControlRequest.SetZoom -> setZoom(request.x)
        is RemoteControlRequest.SetMute -> setMute(request.on)
        is RemoteControlRequest.SetStealthMode -> setStealthMode(request.on)
        is RemoteControlRequest.SetTorch -> setTorch(request.on)
        is RemoteControlRequest.SetDebugLogging -> setDebugLogging(request.on)
        RemoteControlRequest.ReloadBrowserWidgets -> reloadBrowserWidgets()
        RemoteControlRequest.StartPreview -> startPreview()
        RemoteControlRequest.StopPreview -> stopPreview()
        is RemoteControlRequest.StartStatus -> startStatus(request.interval, request.filter)
        RemoteControlRequest.StopStatus -> stopStatus()
        is RemoteControlRequest.SetFilter -> setFilter(request.filter, request.on)
        is RemoteControlRequest.SendMessage -> sendMessage(request.text)
        is RemoteControlRequest.ImportSettings -> if (!importSettings(request.data)) {
            result = RemoteControlResult.ERROR
        }
        RemoteControlRequest.Unknown -> result = RemoteControlResult.UNKNOWN_REQUEST
    }
    return result to data
}
