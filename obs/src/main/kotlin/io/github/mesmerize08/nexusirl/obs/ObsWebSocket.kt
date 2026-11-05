// Port of Moblin/Obs/ObsWebSocket.swift @97e26d2: an obs-websocket 5 (OBS 28+) client.
package io.github.mesmerize08.nexusirl.obs

import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import io.github.mesmerize08.nexusirl.core.websocket.challengePasswordHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10

/** Upstream `obsMinimumAudioDelay` and `obsMaximumAudioDelay` (ms), OBS's audio sync offset range. */
const val OBS_MINIMUM_AUDIO_DELAY = -950
const val OBS_MAXIMUM_AUDIO_DELAY = 20000

/** obs-websocket `EventSubscription` bits. */
private object EventSubscription {
    /** General through Ui: everything but the high-volume events (obs-websocket's `All`). */
    const val ALL = 0x7FF
    const val INPUT_VOLUME_METERS = 0x10000
}

private object OpCode {
    const val HELLO = 0
    const val IDENTIFY = 1
    const val IDENTIFIED = 2
    const val REIDENTIFY = 3
    const val EVENT = 5
    const val REQUEST = 6
    const val REQUEST_RESPONSE = 7
    const val REQUEST_BATCH = 8
    const val REQUEST_BATCH_RESPONSE = 9
}

private const val RPC_VERSION = 1

/** obs-websocket `RequestStatus` codes by upstream's names, used in error messages. */
private val REQUEST_STATUS_NAMES = mapOf(
    10 to "noError",
    100 to "success",
    203 to "missingRequestType",
    204 to "unknownRequestType",
    205 to "genericError",
    206 to "unsupportedRequestBatchExecutionType",
    207 to "notReady",
    300 to "missingRequestField",
    301 to "missingRequestData",
    400 to "invalidRequestField",
    401 to "invalidRequestFieldType",
    402 to "requestFieldOutOfRange",
    403 to "requestFieldEmpty",
    404 to "tooManyRequestFields",
    500 to "outputRunning",
    501 to "outputNotRunning",
    502 to "outputPaused",
    503 to "outputNotPaused",
    504 to "outputDisabled",
    505 to "studioModeActive",
    506 to "studioModeNotActive",
    600 to "resourceNotFound",
    601 to "resourceAlreadyExists",
    602 to "invalidResourceType",
    603 to "notEnoughResources",
    604 to "invalidResourceState",
    605 to "invalidInputKind",
    606 to "resourceNotConfigurable",
    607 to "invalidFilterKind",
    700 to "resourceCreationFailed",
    701 to "resourceActionFailed",
    702 to "requestProcessingFailed",
    703 to "cannotAct",
)

private const val OUTPUT_RUNNING = 500
private const val OUTPUT_NOT_RUNNING = 501

/** Upstream `ObsOutputState`: an output's `outputState` in stream and record events. */
enum class ObsOutputState(val rawValue: String) {
    STARTING("OBS_WEBSOCKET_OUTPUT_STARTING"),
    STARTED("OBS_WEBSOCKET_OUTPUT_STARTED"),
    STOPPING("OBS_WEBSOCKET_OUTPUT_STOPPING"),
    STOPPED("OBS_WEBSOCKET_OUTPUT_STOPPED"),
    ;

    companion object {
        fun from(rawValue: String): ObsOutputState? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** Upstream `ObsSceneList`: [scenes] in the order OBS's scene list shows them. */
data class ObsSceneList(val current: String, val scenes: List<String>)

/** Upstream `GetSceneItemListItem`. */
data class ObsSceneItem(val sourceName: String, val inputKind: String?, val sceneItemEnabled: Boolean)

/** Upstream `GetSpecialInputsResponse`: the global audio devices of OBS's audio settings. */
data class ObsSpecialInputs(
    val desktop1: String?,
    val desktop2: String?,
    val mic1: String?,
    val mic2: String?,
    val mic3: String?,
    val mic4: String?,
) {
    val mics: List<String>
        get() = listOfNotNull(mic1, mic2, mic3, mic4)
}

/** Upstream `ObsMediaSourceSettings`: a media source's input (URL or file). */
data class ObsMediaSourceSettings(val input: String, val isLocalFile: Boolean)

/** Upstream `ObsAudioInputVolume`: one input's channels in dB (-Infinity when silent). */
data class ObsAudioInputVolume(val name: String, val volumes: List<Float>)

/** A failed request, with a message for the user. */
class ObsException(message: String) : Exception(message)

/** Upstream `ObsWebsocketDelegate`. Called on the connection's thread. */
interface ObsWebSocketListener {
    fun connected() {}

    fun disconnected() {}

    /** A connection attempt ended before OBS accepted it, such as with "Authentication failed.". */
    fun connectionFailed(message: String) {}

    fun sceneChanged(sceneName: String) {}

    fun inputMuteStateChanged(inputName: String, muted: Boolean) {}

    fun streamStatusChanged(active: Boolean, state: ObsOutputState?) {}

    fun recordStatusChanged(active: Boolean, state: ObsOutputState?) {}

    fun audioVolume(volumes: List<ObsAudioInputVolume>) {}
}

/** obs-websocket's authentication string: base64(sha256(base64(sha256(password + salt)) + challenge)). */
fun obsAuthentication(password: String, salt: String, challenge: String): String =
    challengePasswordHash(password = password, salt = salt, challenge = challenge)

/**
 * Remote control of OBS over obs-websocket 5. Connects (and reconnects) until [stop]; it is
 * connected once OBS accepted the Identify. Requests answer through their callback, on the
 * connection's thread, or at once on the caller's thread when not connected.
 *
 * Cleartext `ws://` is only allowed to the local network (OBS on the same Wi-Fi, or Tailscale);
 * anything else must use `wss://`.
 */
class ObsWebSocket(
    url: String,
    private val password: String,
    private val listener: ObsWebSocketListener,
    client: OkHttpClient = ReconnectingWebSocket.LOCAL_NETWORK_CLIENT,
) {
    private val nextId = AtomicInteger(0)

    // Guards the connected state and the pending requests: requests come from any thread and
    // stop() from the caller's, while OBS's messages arrive on the connection's thread.
    private val lock = Any()
    private val requests = HashMap<String, (Result<JsonObject?>) -> Unit>()
    private val batchRequests = HashMap<String, (Result<List<JsonObject?>>) -> Unit>()
    private var stopped = false

    @Volatile
    var isConnected = false
        private set

    /** Why the last connection ended, such as "Authentication failed.", or empty. */
    @Volatile
    var connectionErrorMessage = ""
        private set

    private val socket = ReconnectingWebSocket(
        url,
        object : ReconnectingWebSocket.Listener {
            override fun onConnected(socket: ReconnectingWebSocket) {
                // A new attempt: an earlier attempt's error no longer applies.
                connectionErrorMessage = ""
            }

            override fun onMessage(socket: ReconnectingWebSocket, text: String) {
                try {
                    handleMessage(text)
                } catch (e: Exception) {
                    // Upstream logs and ignores malformed messages.
                }
            }

            // onDisconnected follows.
            override fun onClosing(socket: ReconnectingWebSocket, code: Int, reason: String) {
                connectionErrorMessage = reason.ifEmpty { "Disconnected" }
            }

            override fun onDisconnected(socket: ReconnectingWebSocket) {
                if (connectionErrorMessage.isEmpty()) {
                    connectionErrorMessage = "Disconnected"
                }
                handleDisconnected()
            }

            override fun onConnectFailed(socket: ReconnectingWebSocket, error: Throwable) {
                // Upstream: "Unable to connect the OBS server (…)". A timeout is usually the
                // computer's firewall; a refusal, OBS's WebSocket server being off.
                connectionErrorMessage = "Unable to connect (${error.message ?: error.javaClass.simpleName})"
                handleDisconnected()
            }
        },
        client,
    )

    fun start() = socket.start()

    fun stop() {
        synchronized(lock) { stopped = true }
        socket.stop()
        handleDisconnected(stopping = true)
    }

    private fun handleDisconnected(stopping: Boolean = false) {
        val (wasConnected, pending, pendingBatches) = synchronized(lock) {
            val wasConnected = isConnected
            isConnected = false
            Triple(wasConnected, requests.values.toList(), batchRequests.values.toList()).also {
                requests.clear()
                batchRequests.clear()
            }
        }
        val error = Result.failure<Nothing>(ObsException("Disconnected"))
        pending.forEach { it(error) }
        pendingBatches.forEach { it(error) }
        if (wasConnected) {
            listener.disconnected()
        } else if (!stopping) {
            listener.connectionFailed(connectionErrorMessage)
        }
    }

    /** Upstream `startAudioVolume`: also subscribe to the input volume meters (sent 20 times a second). */
    fun startAudioVolume() = sendReidentify(EventSubscription.ALL or EventSubscription.INPUT_VOLUME_METERS)

    fun stopAudioVolume() = sendReidentify(EventSubscription.ALL)

    fun getSceneList(callback: (Result<ObsSceneList>) -> Unit) = request("GetSceneList", null, callback) { data ->
        ObsSceneList(
            data.string("currentProgramSceneName"),
            // OBS lists scenes bottom first.
            data.getValue("scenes").jsonArray.map { it.jsonObject.string("sceneName") }.reversed(),
        )
    }

    fun getSceneItemList(sceneName: String, callback: (Result<List<ObsSceneItem>>) -> Unit) =
        request("GetSceneItemList", buildJsonObject { put("sceneName", sceneName) }, callback) { data ->
            data.getValue("sceneItems").jsonArray.map {
                val item = it.jsonObject
                ObsSceneItem(
                    item.string("sourceName"),
                    item.stringOrNull("inputKind"),
                    item.getValue("sceneItemEnabled").jsonPrimitive.boolean,
                )
            }
        }

    fun getSpecialInputs(callback: (Result<ObsSpecialInputs>) -> Unit) = request("GetSpecialInputs", null, callback) { data ->
        ObsSpecialInputs(
            data.stringOrNull("desktop1"),
            data.stringOrNull("desktop2"),
            data.stringOrNull("mic1"),
            data.stringOrNull("mic2"),
            data.stringOrNull("mic3"),
            data.stringOrNull("mic4"),
        )
    }

    fun getInputList(callback: (Result<List<String>>) -> Unit) = request("GetInputList", null, callback) { data ->
        data.getValue("inputs").jsonArray.map { it.jsonObject.string("inputName") }
    }

    fun setCurrentProgramScene(name: String, callback: (Result<Unit>) -> Unit = {}) =
        request("SetCurrentProgramScene", buildJsonObject { put("sceneName", name) }, callback = callback)

    /** Sets a media source's input, such as the SRT URL it plays. */
    fun setMediaSourceSettings(name: String, input: String, callback: (Result<Unit>) -> Unit = {}) = request(
        "SetInputSettings",
        buildJsonObject {
            put("inputName", name)
            putJsonObject("inputSettings") { put("input", input) }
        },
        callback = callback,
    )

    /** Whether OBS is streaming. */
    fun getStreamStatus(callback: (Result<Boolean>) -> Unit) = request("GetStreamStatus", null, callback) { it.boolean("outputActive") }

    /** Whether OBS is recording. */
    fun getRecordStatus(callback: (Result<Boolean>) -> Unit) = request("GetRecordStatus", null, callback) { it.boolean("outputActive") }

    fun startStream(callback: (Result<Unit>) -> Unit = {}) = request(
        "StartStream",
        errorMessages = mapOf(
            OUTPUT_RUNNING to "Already streaming",
        ),
        callback = callback,
    )

    fun stopStream(callback: (Result<Unit>) -> Unit = {}) = request(
        "StopStream",
        errorMessages = mapOf(
            OUTPUT_NOT_RUNNING to "Not streaming",
        ),
        callback = callback,
    )

    fun startRecord(callback: (Result<Unit>) -> Unit = {}) = request(
        "StartRecord",
        errorMessages = mapOf(
            OUTPUT_RUNNING to "Already recording",
        ),
        callback = callback,
    )

    fun stopRecord(callback: (Result<Unit>) -> Unit = {}) = request(
        "StopRecord",
        errorMessages = mapOf(
            OUTPUT_NOT_RUNNING to "Not recording",
        ),
        callback = callback,
    )

    /** A small JPEG of [name] (a scene or source), as upstream: 640 wide, quality 30. */
    fun getSourceScreenshot(name: String, callback: (Result<ByteArray>) -> Unit) {
        val data = buildJsonObject {
            put("sourceName", name)
            put("imageFormat", "jpg")
            put("imageWidth", 640)
            put("imageCompressionQuality", 30)
        }
        request("GetSourceScreenshot", data, callback) { response ->
            // A data URL: "data:image/jpg;base64,...".
            val image = response.string("imageData").substringAfter("base64,")
            try {
                Base64.getDecoder().decode(image)
            } catch (e: IllegalArgumentException) {
                throw ObsException("Base64 decode failed")
            }
        }
    }

    fun setInputAudioSyncOffset(name: String, offsetMs: Int, callback: (Result<Unit>) -> Unit = {}) = request(
        "SetInputAudioSyncOffset",
        buildJsonObject {
            put("inputName", name)
            put("inputAudioSyncOffset", offsetMs)
        },
        callback = callback,
    )

    fun getInputAudioSyncOffset(name: String, callback: (Result<Int>) -> Unit) = request(
        "GetInputAudioSyncOffset",
        buildJsonObject {
            put("inputName", name)
        },
        callback,
    ) { it.getValue("inputAudioSyncOffset").jsonPrimitive.int }

    /**
     * Upstream `setInputSettings` with empty settings, used by its "Fix" button: OBS restarts the
     * source (a media source reconnects), which often fixes broken audio or video.
     */
    fun restartInput(inputName: String, callback: (Result<Unit>) -> Unit = {}) = request(
        "SetInputSettings",
        buildJsonObject {
            put("inputName", inputName)
            putJsonObject("inputSettings") {}
        },
        callback = callback,
    )

    fun setInputMute(inputName: String, muted: Boolean, callback: (Result<Unit>) -> Unit = {}) = request(
        "SetInputMute",
        buildJsonObject {
            put("inputName", inputName)
            put("inputMuted", muted)
        },
        callback = callback,
    )

    /** Each input's media source settings, null where the request failed or it is not a media source. */
    fun getMediaSourcesSettingsBatch(inputNames: List<String>, callback: (Result<List<ObsMediaSourceSettings?>>) -> Unit) =
        requestBatch("GetInputSettings", inputNames.map { buildJsonObject { put("inputName", it) } }, callback) { data ->
            val settings = data.getValue("inputSettings").jsonObject
            ObsMediaSourceSettings(settings.string("input"), settings.getValue("is_local_file").jsonPrimitive.boolean)
        }

    /** Each input's mute state, null where the request failed. */
    fun getInputMuteBatch(inputNames: List<String>, callback: (Result<List<Boolean?>>) -> Unit) =
        requestBatch("GetInputMute", inputNames.map { buildJsonObject { put("inputName", it) } }, callback) { it.boolean("inputMuted") }

    private fun request(
        type: String,
        data: JsonObject? = null,
        callback: (Result<Unit>) -> Unit,
        errorMessages: Map<Int, String> = emptyMap(),
    ) = sendRequest(type, data, errorMessages) { result -> callback(result.map { }) }

    private fun <T> request(type: String, data: JsonObject?, callback: (Result<T>) -> Unit, decode: (JsonObject) -> T) =
        sendRequest(type, data, emptyMap()) { result ->
            callback(
                result.mapCatching { response ->
                    val responseData = response ?: throw ObsException("Response data missing")
                    try {
                        decode(responseData)
                    } catch (e: ObsException) {
                        throw e
                    } catch (e: Exception) {
                        throw ObsException("JSON decode failed")
                    }
                },
            )
        }

    private fun sendRequest(type: String, data: JsonObject?, errorMessages: Map<Int, String>, callback: (Result<JsonObject?>) -> Unit) {
        val id = nextId()
        val registered = register(requests, id) { result ->
            callback(
                result.recoverCatching { error ->
                    val status = (error as? RequestFailed) ?: throw error
                    throw ObsException(
                        errorMessages[status.code] ?: buildString {
                            append("Operation failed with ${REQUEST_STATUS_NAMES[status.code] ?: "unknown"}")
                            status.comment?.let { append(" ($it)") }
                        },
                    )
                },
            )
        }
        if (!registered) {
            callback(Result.failure(ObsException("Not connected to server")))
            return
        }
        send(OpCode.REQUEST, packRequest(type, id, data))
    }

    /** Adds a pending request, unless not connected (a disconnect fails every added one). */
    private fun <T> register(pending: HashMap<String, T>, id: String, callback: T): Boolean = synchronized(lock) {
        if (isConnected) {
            pending[id] = callback
        }
        isConnected
    }

    private fun <T> requestBatch(
        type: String,
        requests: List<JsonObject>,
        callback: (Result<List<T?>>) -> Unit,
        decode: (JsonObject) -> T,
    ) {
        val id = nextId()
        val registered = register(batchRequests, id) { result: Result<List<JsonObject?>> ->
            callback(result.map { responses -> responses.map { response -> response?.let { runCatching { decode(it) }.getOrNull() } } })
        }
        if (!registered) {
            callback(Result.failure(ObsException("Not connected to server")))
            return
        }
        send(
            OpCode.REQUEST_BATCH,
            buildJsonObject {
                // A string, as obs-websocket 5 specifies (upstream sends a number).
                put("requestId", id)
                put("requests", JsonArray(requests.map { packRequest(type, nextId(), it) }))
            },
        )
    }

    private fun packRequest(type: String, id: String, data: JsonObject?) = buildJsonObject {
        put("requestType", type)
        put("requestId", id)
        data?.let { put("requestData", it) }
    }

    private fun nextId() = nextId.incrementAndGet().toString()

    /** A request OBS answered with a failure status. */
    private class RequestFailed(val code: Int, val comment: String?) : Exception()

    private fun handleMessage(text: String) {
        val message = Json.parseToJsonElement(text).jsonObject
        val op = message["op"]?.jsonPrimitive?.intOrNull ?: return
        val data = message["d"] as? JsonObject ?: return
        when (op) {
            OpCode.HELLO -> handleHello(data)
            OpCode.IDENTIFIED -> handleIdentified()
            OpCode.EVENT -> handleEvent(data)
            OpCode.REQUEST_RESPONSE -> handleRequestResponse(data)
            OpCode.REQUEST_BATCH_RESPONSE -> handleRequestBatchResponse(data)
        }
    }

    private fun handleHello(data: JsonObject) {
        val authentication = (data["authentication"] as? JsonObject)?.let {
            obsAuthentication(password, it.string("salt"), it.string("challenge"))
        }
        send(
            OpCode.IDENTIFY,
            buildJsonObject {
                put("rpcVersion", RPC_VERSION)
                authentication?.let { put("authentication", it) }
            },
        )
    }

    private fun handleIdentified() {
        synchronized(lock) {
            // Identified may have been on its way when stop() was called.
            if (stopped) {
                return
            }
            connectionErrorMessage = ""
            isConnected = true
        }
        listener.connected()
    }

    private fun handleEvent(data: JsonObject) {
        val eventData = data["eventData"] as? JsonObject ?: return
        try {
            when (data["eventType"]?.jsonPrimitive?.content) {
                "CurrentProgramSceneChanged" -> listener.sceneChanged(eventData.string("sceneName"))
                "StreamStateChanged" -> listener.streamStatusChanged(
                    eventData.boolean("outputActive"),
                    ObsOutputState.from(eventData.string("outputState")) ?: ObsOutputState.STOPPED,
                )
                // Paused and resumed recordings are still recording.
                "RecordStateChanged" -> listener.recordStatusChanged(
                    eventData.boolean("outputActive"),
                    ObsOutputState.from(eventData.string("outputState")) ?: ObsOutputState.STARTED,
                )
                "InputMuteStateChanged" -> listener.inputMuteStateChanged(eventData.string("inputName"), eventData.boolean("inputMuted"))
                "InputVolumeMeters" -> listener.audioVolume(
                    eventData.getValue("inputs").jsonArray.map { element ->
                        val input = element.jsonObject
                        ObsAudioInputVolume(
                            input.string("inputName"),
                            input.getValue("inputLevelsMul").jsonArray.mapNotNull { channel ->
                                channel.jsonArray.firstOrNull()?.jsonPrimitive?.float?.let { 20 * log10(it) }
                            },
                        )
                    },
                )
            }
        } catch (e: IllegalArgumentException) {
            // A malformed event: ignored, as upstream.
        } catch (e: NoSuchElementException) {
            // Likewise.
        }
    }

    private fun parseResult(response: JsonObject): Result<JsonObject?> {
        val status = response.getValue("requestStatus").jsonObject
        return if (status.boolean("result")) {
            Result.success(response["responseData"] as? JsonObject)
        } else {
            Result.failure(RequestFailed(status.getValue("code").jsonPrimitive.int, status.stringOrNull("comment")))
        }
    }

    private fun handleRequestResponse(data: JsonObject) {
        val id = data["requestId"]?.jsonPrimitive?.content ?: return
        val callback = synchronized(lock) { requests.remove(id) } ?: return
        callback(parseResult(data))
    }

    private fun handleRequestBatchResponse(data: JsonObject) {
        // obs-websocket echoes the id as sent; accept a number too.
        val id = data["requestId"]?.jsonPrimitive?.content ?: return
        val callback = synchronized(lock) { batchRequests.remove(id) } ?: return
        val results = data.getValue("results").jsonArray.map { parseResult(it.jsonObject).getOrNull() }
        callback(Result.success(results))
    }

    private fun sendReidentify(eventSubscriptions: Int) {
        if (isConnected) {
            send(OpCode.REIDENTIFY, buildJsonObject { put("eventSubscriptions", eventSubscriptions) })
        }
    }

    private fun send(op: Int, data: JsonObject) {
        socket.send(
            buildJsonObject {
                put("op", op)
                put("d", data)
            }.toString(),
        )
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.stringOrNull(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.booleanOrNull ?: throw IllegalArgumentException(key)
