// Port of Moblin/Media/HaishinKit/Rtmp/RtmpStream.swift, RtmpConnection.swift, RtmpSocket.swift
// and RtmpHandshake.swift @97e26d2 (one class; NWConnection replaced by a blocking socket with a
// writer thread, the queue by a SerialExecutor).
package io.github.mesmerize08.nexusirl.transport.rtmp

import io.github.mesmerize08.nexusirl.core.clock.Cancellable
import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.media.mux.nal.annexBToLengthPrefixed
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

private const val HANDSHAKE_SIG_SIZE = 1536
private const val HANDSHAKE_PROTOCOL_VERSION = 3
private const val MAXIMUM_UNSENT_BYTES = 100_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L

/** How long audio waits for video to start before going alone (audio-only streams). */
private const val VIDEO_START_GRACE_NANOS = NANOS_PER_SECOND

/** Splits a stream URL into connection URL and stream key (upstream `makeRtmpUri`/`makeRtmpStreamKey`). */
object RtmpUrl {
    fun uri(url: String): String {
        val parts = url.split('/')
        return parts.dropLast(1).joinToString("/")
    }

    fun streamKey(url: String): String = url.split('/').lastOrNull { it.isNotEmpty() } ?: ""
}

/** Stream metadata for `onMetaData` (upstream reads it from the encoders). */
data class RtmpMetadata(
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val isHevc: Boolean,
    val videoBitrate: Long,
    val audioBitrate: Long,
    val audioSampleRate: Double?,
)

/** RTMP connection status codes (upstream `RtmpConnectionCode` / `RtmpStreamCode`). */
object RtmpCode {
    const val CONNECT_CLOSED = "NetConnection.Connect.Closed"
    const val CONNECT_FAILED = "NetConnection.Connect.Failed"
    const val CONNECT_REJECTED = "NetConnection.Connect.Rejected"
    const val CONNECT_SUCCESS = "NetConnection.Connect.Success"
    const val PUBLISH_START = "NetStream.Publish.Start"
}

/** Opens the TCP (or TLS) connection; replaceable for Android network binding and tests. */
fun interface RtmpSocketFactory {
    fun connect(host: String, port: Int, tls: Boolean): Socket

    companion object {
        /** Plain or TLS socket with full certificate *and hostname* verification (plan §9.5). */
        val DEFAULT = RtmpSocketFactory { host, port, tls ->
            if (tls) {
                val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.connect(InetSocketAddress(host, port), 10_000)
                socket.startHandshake()
                socket
            } else {
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, port), 10_000)
                }
            }
        }
    }
}

/**
 * Publishes FLV audio/video over RTMP(S), with enhanced RTMP for HEVC and Opus.
 *
 * Threading: protocol state is confined to [executor] (upstream's RTMP queue). Encoders may call
 * [setAudioConfig]/[setVideoConfig]/[writeAudio]/[writeVideo] from any thread.
 */
class RtmpStream(
    url: String,
    private val executor: SerialExecutor,
    private val clock: HostClock,
    private val listener: Listener,
    private val socketFactory: RtmpSocketFactory = RtmpSocketFactory.DEFAULT,
) {
    interface Listener {
        /** Every status code, e.g. [RtmpCode.CONNECT_FAILED] or [RtmpCode.CONNECT_CLOSED] on loss. */
        fun onStatus(code: String)

        /** Publishing started; media is now sent. */
        fun onConnected()
    }

    private enum class State { INITIALIZED, OPEN, PUBLISHING }

    private enum class SocketState { UNINITIALIZED, VERSION_SENT, ACK_SENT, HANDSHAKE_DONE, CLOSED }

    private val streamKey = RtmpUrl.streamKey(url)
    private var uri: URI = parseUri(RtmpUrl.uri(url))
    val info = RtmpStreamInfo(clock)
    private var streamId = 0L
    private var state = State.INITIALIZED
    private var startedAt = clock.nanoTime()
    private var audioChunkType = 0
    private var videoChunkType = 0
    private val dataTimeStamps = HashMap<String, Long>()
    private var connectTimer: Cancellable? = null
    private var periodicTimer: Cancellable? = null
    private var metadata: RtmpMetadata? = null

    // Connection
    private val callCompletions = HashMap<Long, (List<AsValue>) -> Unit>()
    private var nextTransactionId = 0L
    private val chunkReader = RtmpChunkReader()

    // Socket
    private var socketState = SocketState.UNINITIALIZED
    private var connection: Connection? = null
    private var maximumChunkSizeToServer = RtmpChunkReader.DEFAULT_CHUNK_SIZE
    private var inputBuffer = ByteArray(0)
    private var totalBytesSending = 0L
    private var totalBytesSent = 0L

    // Outbound media
    private var baseTimeStamp = -1.0
    private var audioTimeStampDelta = 0.0
    private var videoTimeStampDelta = 0.0
    private var prevRebasedAudioTimeStamp: Double? = null
    private var prevRebasedVideoTimeStamp: Double? = null
    private var audioConfig: MpegTsAudioConfig? = null
    private var audioSampleRate = 48_000
    private var videoConfig: MpegTsVideoConfig? = null

    fun setMetadata(metadata: RtmpMetadata) {
        executor.execute { this.metadata = metadata }
    }

    fun connect() {
        executor.execute { connectInternal() }
    }

    fun disconnect() {
        executor.execute { disconnectInternal() }
    }

    /** Reconnects after 5 s (multi-destination streams, upstream `reconnectSoon`). */
    fun reconnectSoon() {
        executor.schedule(5 * NANOS_PER_SECOND) { connectInternal() }
    }

    // Encoder side

    fun setAudioConfig(config: MpegTsAudioConfig, sampleRate: Int) {
        executor.execute {
            audioConfig = config
            audioSampleRate = sampleRate
            if (state == State.PUBLISHING) {
                sendAudioSequenceHeader()
            }
        }
    }

    fun setVideoConfig(config: MpegTsVideoConfig) {
        executor.execute {
            videoConfig = config
            if (state == State.PUBLISHING) {
                sendVideoSequenceHeader()
            }
        }
    }

    fun writeAudio(frame: ByteArray, offset: Int, length: Int, presentationTimeStamp: MediaTime) {
        val data = frame.copyOfRange(offset, offset + length)
        executor.execute { handleAudioFrame(data, presentationTimeStamp) }
    }

    /** [accessUnit] is Annex-B; it is converted to length-prefixed NAL units for FLV. */
    fun writeVideo(accessUnit: ByteArray, presentationTimeStamp: MediaTime, decodeTimeStamp: MediaTime?, isSync: Boolean) {
        // Copied because the work is queued and encoders reuse their output buffers.
        val data = accessUnit.copyOf()
        executor.execute { handleVideoFrame(data, presentationTimeStamp, decodeTimeStamp, isSync) }
    }

    // Stream state

    private fun connectInternal() {
        connectTimer?.cancel()
        connectTimer = executor.schedule(20 * NANOS_PER_SECOND) { closeSocket(isDisconnected = true) }
        connectSocket(uri)
    }

    private fun disconnectInternal() {
        setState(State.INITIALIZED)
        connectTimer?.cancel()
        periodicTimer?.cancel()
        closeSocket(isDisconnected = false)
    }

    private fun setState(newState: State) {
        if (state == newState) {
            return
        }
        val oldState = state
        state = newState
        if (oldState == State.PUBLISHING) {
            call(RtmpCommand.FC_UNPUBLISH, listOf(AsValue.Str(streamKey)))
            writeCommand(RtmpCommand(RtmpCommand.DELETE_STREAM, 0, null, listOf(AsValue.Number(streamId.toDouble()))), streamId)
            writeCommand(RtmpCommand(RtmpCommand.CLOSE_STREAM, 0, null, listOf(AsValue.Number(streamId.toDouble()))), 0)
        }
        when (newState) {
            State.OPEN -> handleStateChangeToOpen()
            State.PUBLISHING -> handleStateChangeToPublishing()
            State.INITIALIZED -> Unit
        }
    }

    private fun handleStatus(data: AsObject) {
        val code = (data["code"] as? AsValue.Str)?.value ?: return
        when (code) {
            RtmpCode.CONNECT_SUCCESS -> {
                maximumChunkSizeToServer = 8 * 1024
                writeChunk(
                    0,
                    RtmpChunkStreamId.CONTROL,
                    RtmpMessage(RtmpMessageType.CHUNK_SIZE, 0, 0, uint32Payload(maximumChunkSizeToServer.toLong())),
                )
            }
            RtmpCode.CONNECT_REJECTED -> handleConnectRejected(data)
            RtmpCode.CONNECT_CLOSED -> disconnectInternal()
        }
        listener.onStatus(code)
        when (code) {
            RtmpCode.CONNECT_SUCCESS -> {
                setState(State.INITIALIZED)
                call(RtmpCommand.RELEASE_STREAM, listOf(AsValue.Str(streamKey)))
                call(RtmpCommand.FC_PUBLISH, listOf(AsValue.Str(streamKey)))
                call(RtmpCommand.CREATE_STREAM, emptyList()) { arguments ->
                    val id = (arguments.firstOrNull() as? AsValue.Number)?.value
                    if (id != null && id >= 0 && id <= 0xFFFF_FFFFL.toDouble()) {
                        streamId = id.toLong()
                        setState(State.OPEN)
                    }
                }
            }
            RtmpCode.PUBLISH_START -> if (state != State.INITIALIZED) {
                setState(State.PUBLISHING)
            }
        }
    }

    private fun handleStateChangeToOpen() {
        info.clear()
        writeCommand(
            RtmpCommand(RtmpCommand.PUBLISH, nextTransactionId(), null, listOf(AsValue.Str(streamKey), AsValue.Str("live"))),
            streamId,
        )
        startedAt = clock.nanoTime()
        baseTimeStamp = -1.0
        audioTimeStampDelta = 0.0
        videoTimeStampDelta = 0.0
        prevRebasedAudioTimeStamp = null
        prevRebasedVideoTimeStamp = null
        videoChunkType = 0
        audioChunkType = 0
        dataTimeStamps.clear()
    }

    private fun handleStateChangeToPublishing() {
        sendData("@setDataFrame", listOf(AsValue.Str("onMetaData"), AsValue.Object(createOnMetaData())))
        connectTimer?.cancel()
        listener.onConnected()
        // Upstream: processor.startEncoding(self) makes the encoders re-emit their formats.
        sendAudioSequenceHeader()
        sendVideoSequenceHeader()
    }

    private fun createOnMetaData(): AsObject {
        val metadata = this.metadata ?: return emptyMap()
        val result = LinkedHashMap<String, AsValue>()
        result["width"] = AsValue.Number(metadata.width.toDouble())
        result["height"] = AsValue.Number(metadata.height.toDouble())
        result["framerate"] = AsValue.Number(metadata.frameRate)
        result["videocodecid"] = AsValue.Number(if (metadata.isHevc) Flv.FOURCC_HVC1.toDouble() else Flv.VIDEO_CODEC_AVC.toDouble())
        result["videodatarate"] = AsValue.Number((metadata.videoBitrate / 1000).toDouble())
        result["audiocodecid"] = AsValue.Number(Flv.AUDIO_CODEC_AAC.toDouble())
        result["audiodatarate"] = AsValue.Number((metadata.audioBitrate / 1000).toDouble())
        metadata.audioSampleRate?.let { result["audiosamplerate"] = AsValue.Number(it) }
        return result
    }

    private fun sendData(handlerName: String, arguments: List<AsValue>) {
        if (state != State.PUBLISHING) {
            return
        }
        val now = clock.nanoTime()
        val previous = dataTimeStamps[handlerName]
        val timestamp = ((now - (previous ?: startedAt)) / 1_000_000).coerceAtLeast(0)
        val length = writeChunk(
            if (previous != null) 1 else 0,
            RtmpChunkStreamId.DATA,
            RtmpMessage(RtmpMessageType.AMF0_DATA, streamId, timestamp, encodeDataMessage(handlerName, arguments)),
        )
        dataTimeStamps[handlerName] = now
        info.bitrateStats.add(length)
    }

    // Media

    private fun sendAudioSequenceHeader() {
        val config = audioConfig ?: return
        val payload = if (config.isOpus) {
            Flv.opusSequenceHeader(config.channels, audioSampleRate)
        } else {
            Flv.aacSequenceHeader(config)
        }
        sendAudio(payload, 0)
    }

    private fun sendVideoSequenceHeader() {
        val payload = when (val config = videoConfig ?: return) {
            is MpegTsVideoConfig.Avc -> {
                val sps = config.sequenceParameterSet ?: return
                val pps = config.pictureParameterSet ?: return
                Flv.avcVideoTagHeader(Flv.FRAME_TYPE_KEY, Flv.AVC_PACKET_TYPE_SEQ) + byteArrayOf(0, 0, 0) + Flv.makeAvcC(sps, pps)
            }
            is MpegTsVideoConfig.Hevc -> {
                val vps = config.videoParameterSet ?: return
                val sps = config.sequenceParameterSet ?: return
                val pps = config.pictureParameterSet ?: return
                Flv.hevcExtendedTagHeader(Flv.FRAME_TYPE_KEY, Flv.VIDEO_PACKET_TYPE_SEQUENCE_START) + Flv.makeHvcC(vps, sps, pps)
            }
        }
        sendVideo(payload, 0)
    }

    private fun rebaseTimeStamp(timestamp: Double): Double? {
        if (baseTimeStamp == -1.0) {
            baseTimeStamp = timestamp
        }
        val rebased = timestamp - baseTimeStamp
        return if (rebased >= 0) rebased else null
    }

    private fun handleAudioFrame(data: ByteArray, presentationTimeStamp: MediaTime) {
        if (state != State.PUBLISHING) {
            return
        }
        val config = audioConfig ?: return
        // With video, the stream starts on the first video frame (a key frame). Video timestamps
        // are camera capture times, older than the audio arriving with them; had audio set the
        // base, the first key frame would be "before the start" and dropped, leaving viewers
        // without a picture until the next periodic key frame, seconds later.
        // Video may still be starting (no config yet) for a moment after publishing begins.
        if (baseTimeStamp == -1.0 && (videoConfig != null || clock.nanoTime() - startedAt < VIDEO_START_GRACE_NANOS)) {
            return
        }
        val rebased = rebaseTimeStamp(presentationTimeStamp.seconds) ?: return
        val delta = prevRebasedAudioTimeStamp?.let { (rebased - it) * 1000 } ?: 0.0
        if (delta < 0) {
            return
        }
        val header = if (config.isOpus) Flv.opusFrameHeader() else Flv.aacFrameHeader()
        prevRebasedAudioTimeStamp = rebased
        audioTimeStampDelta += delta
        sendAudio(header + data, audioTimeStampDelta.toLong())
        audioTimeStampDelta -= Math.floor(audioTimeStampDelta)
    }

    private fun handleVideoFrame(accessUnit: ByteArray, presentationTimeStamp: MediaTime, decodeTimeStamp: MediaTime?, isSync: Boolean) {
        if (state != State.PUBLISHING) {
            return
        }
        val config = videoConfig ?: return
        val rebased = rebaseTimeStamp((decodeTimeStamp ?: presentationTimeStamp).seconds) ?: return
        val delta = prevRebasedVideoTimeStamp?.let { (rebased - it) * 1000 } ?: 0.0
        if (delta < 0) {
            return
        }
        val frameType = if (isSync) Flv.FRAME_TYPE_KEY else Flv.FRAME_TYPE_INTER
        val header = when (config) {
            is MpegTsVideoConfig.Avc -> Flv.avcVideoTagHeader(frameType, Flv.AVC_PACKET_TYPE_NAL)
            is MpegTsVideoConfig.Hevc -> Flv.hevcExtendedTagHeader(frameType, Flv.VIDEO_PACKET_TYPE_CODED_FRAMES)
        }
        val compositionTime = if (decodeTimeStamp != null) {
            ((presentationTimeStamp.seconds - decodeTimeStamp.seconds) * 1000).toInt()
        } else {
            0
        }
        val compositionBytes = byteArrayOf((compositionTime ushr 16).toByte(), (compositionTime ushr 8).toByte(), compositionTime.toByte())
        prevRebasedVideoTimeStamp = rebased
        videoTimeStampDelta += delta
        sendVideo(header + compositionBytes + annexBToLengthPrefixed(accessUnit), videoTimeStampDelta.toLong())
        videoTimeStampDelta -= Math.floor(videoTimeStampDelta)
    }

    private fun sendAudio(payload: ByteArray, timestamp: Long) {
        if (state != State.PUBLISHING) {
            return
        }
        val length = writeChunk(audioChunkType, Flv.AUDIO_CHUNK_STREAM_ID, RtmpMessage(RtmpMessageType.AUDIO, streamId, timestamp, payload))
        audioChunkType = 1
        info.bitrateStats.add(length)
    }

    private fun sendVideo(payload: ByteArray, timestamp: Long) {
        if (state != State.PUBLISHING) {
            return
        }
        val length = writeChunk(videoChunkType, Flv.VIDEO_CHUNK_STREAM_ID, RtmpMessage(RtmpMessageType.VIDEO, streamId, timestamp, payload))
        videoChunkType = 1
        info.bitrateStats.add(length)
    }

    // Connection (commands)

    private fun nextTransactionId(): Long = ++nextTransactionId

    private fun call(name: String, arguments: List<AsValue>, onCompleted: ((List<AsValue>) -> Unit)? = null) {
        val command = RtmpCommand(name, nextTransactionId(), null, arguments)
        onCompleted?.let { callCompletions[command.transactionId] = it }
        writeCommand(command, 0)
    }

    private fun writeCommand(command: RtmpCommand, messageStreamId: Long) {
        writeChunk(0, RtmpChunkStreamId.COMMAND, RtmpMessage(RtmpMessageType.AMF0_COMMAND, messageStreamId, 0, command.encode()))
    }

    private fun makeConnectCommand(): RtmpCommand {
        var app = uri.path.trimStart('/')
        uri.rawQuery?.let { app += "?$it" }
        val commandObject = LinkedHashMap<String, AsValue>()
        commandObject["app"] = AsValue.Str(app)
        commandObject["flashVer"] = AsValue.Str("FMLE/3.0 (compatible; FMSc/1.0)")
        commandObject["swfUrl"] = AsValue.Null
        commandObject["tcUrl"] = AsValue.Str(uriWithoutUserInfo(uri))
        commandObject["fpad"] = AsValue.Bool(false)
        commandObject["capabilities"] = AsValue.Number(239.0)
        commandObject["audioCodecs"] = AsValue.Number(0x0400.toDouble())
        commandObject["videoCodecs"] = AsValue.Number(0x0080.toDouble())
        commandObject["videoFunction"] = AsValue.Number(1.0)
        commandObject["pageUrl"] = AsValue.Null
        commandObject["objectEncoding"] = AsValue.Number(0.0)
        return RtmpCommand(RtmpCommand.CONNECT, nextTransactionId(), commandObject, emptyList())
    }

    private fun handleConnectRejected(data: AsObject) {
        val description = (data["description"] as? AsValue.Str)?.value ?: return
        val userInfo = uri.rawUserInfo ?: return
        val user = URLDecoder.decode(userInfo.substringBefore(':'), Charsets.UTF_8)
        val password = URLDecoder.decode(userInfo.substringAfter(':', ""), Charsets.UTF_8)
        closeSocket(isDisconnected = false)
        when {
            "reason=nosuchuser" in description || "reason=authfailed" in description -> Unit
            "reason=needauth" in description -> connectSocket(parseUri(makeSanJoseAuthCommand(description, user, password)))
            "authmod=adobe" in description -> if (user.isEmpty() || password.isEmpty()) {
                disconnectInternal()
            } else {
                val query = uri.rawQuery ?: ""
                connectSocket(parseUri(uri.toString() + (if (query.isEmpty()) "?" else "&") + "authmod=adobe&user=$user"))
            }
        }
    }

    private fun makeSanJoseAuthCommand(description: String, user: String, password: String): String {
        var command = uri.toString()
        val index = description.indexOf('?')
        if (index < 0) {
            return command
        }
        val query = description.substring(index + 1).split('&').associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        }
        val salt = query["salt"] ?: return command
        val challenge = "%08x".format(Random.nextInt())
        var response = md5Base64("$user$salt$password")
        val opaque = query["opaque"]
        val serverChallenge = query["challenge"]
        if (opaque != null) {
            command += "&opaque=$opaque"
            response += opaque
        } else if (serverChallenge != null) {
            response += serverChallenge
        }
        response = md5Base64("$response$challenge")
        command += "&challenge=$challenge&response=$response"
        return command
    }

    private fun md5Base64(value: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)))

    private fun processMessage(message: RtmpMessage) {
        when (message.type) {
            RtmpMessageType.CHUNK_SIZE -> if (message.payload.size >= 4) {
                chunkReader.maximumChunkSize = readUInt32(message.payload).toInt()
            }
            RtmpMessageType.ACK -> if (message.payload.size >= 4) {
                info.onAck(readUInt32(message.payload))
            }
            RtmpMessageType.USER -> if (message.payload.size >= 6 && (message.payload[1].toInt() and 0xFF) == RtmpUserControlEvent.PING) {
                val value = readUInt32(message.payload, 2).toInt()
                writeChunk(
                    0,
                    RtmpChunkStreamId.CONTROL,
                    RtmpMessage(RtmpMessageType.USER, 0, 0, userControlPayload(RtmpUserControlEvent.PONG, value)),
                )
            }
            RtmpMessageType.WINDOW_ACK -> writeChunk(
                0,
                RtmpChunkStreamId.CONTROL,
                RtmpMessage(RtmpMessageType.WINDOW_ACK, 0, 0, uint32Payload(100_000)),
            )
            RtmpMessageType.AMF0_COMMAND, RtmpMessageType.AMF3_COMMAND -> {
                val command = RtmpCommand.decode(message.payload, message.type == RtmpMessageType.AMF3_COMMAND) ?: return
                val completion = callCompletions.remove(command.transactionId)
                if (completion == null) {
                    if (command.name == RtmpCommand.CLOSE) {
                        disconnectInternal()
                    } else {
                        (command.arguments.firstOrNull() as? AsValue.Object)?.let { handleStatus(it.value) }
                    }
                } else if (command.name == RtmpCommand.RESULT) {
                    completion(command.arguments)
                }
            }
            RtmpMessageType.AMF0_DATA, RtmpMessageType.AMF3_DATA -> info.bitrateStats.add(message.payload.size)
        }
    }

    private fun readUInt32(data: ByteArray, offset: Int = 0): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or (data[offset + 3].toLong() and 0xFF)

    // Socket

    /** One TCP connection: a reader thread and a writer thread; events are posted to [executor]. */
    private inner class Connection(host: String, port: Int, tls: Boolean) {
        @Volatile
        var closed = false
        private val outgoing = LinkedBlockingQueue<ByteArray>()
        private var socket: Socket? = null

        init {
            Thread({ run(host, port, tls) }, "rtmp-io").apply {
                isDaemon = true
                start()
            }
        }

        private fun run(host: String, port: Int, tls: Boolean) {
            val socket = try {
                socketFactory.connect(host, port, tls)
            } catch (e: Exception) {
                post { if (connection === this) closeSocket(isDisconnected = true) }
                return
            }
            synchronized(this) {
                if (closed) {
                    socket.close()
                    return
                }
                this.socket = socket
            }
            val output = socket.getOutputStream()
            Thread({ writeLoop(output) }, "rtmp-writer").apply {
                isDaemon = true
                start()
            }
            post { if (connection === this) handleConnected() }
            readLoop(socket.getInputStream())
        }

        private fun readLoop(input: InputStream) {
            val buffer = ByteArray(16 * 1024)
            while (!closed) {
                val count = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    -1
                }
                if (count < 0) {
                    post { if (connection === this) closeSocket(isDisconnected = true) }
                    return
                }
                val data = buffer.copyOf(count)
                post { if (connection === this) handleInput(data) }
            }
        }

        private fun writeLoop(output: OutputStream) {
            try {
                while (!closed) {
                    val data = outgoing.take()
                    if (data.isEmpty()) {
                        break
                    }
                    output.write(data)
                    output.flush()
                    post { if (connection === this) totalBytesSent += data.size }
                }
            } catch (e: Exception) {
                post { if (connection === this) closeSocket(isDisconnected = true) }
            }
        }

        fun send(data: ByteArray) {
            outgoing.add(data)
        }

        fun close() {
            synchronized(this) {
                closed = true
                outgoing.add(ByteArray(0))
                val socket = this.socket ?: return
                // Like upstream's workaround: let closeStream etc. go out before closing.
                Thread({
                    Thread.sleep(1_000)
                    socket.close()
                }, "rtmp-close").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        private fun post(task: () -> Unit) = executor.execute(task)
    }

    private fun connectSocket(target: URI) {
        uri = target
        connection?.close()
        chunkReader.clear()
        setSocketState(SocketState.UNINITIALIZED)
        maximumChunkSizeToServer = RtmpChunkReader.DEFAULT_CHUNK_SIZE
        totalBytesSending = 0
        totalBytesSent = 0
        inputBuffer = ByteArray(0)
        val tls = target.scheme.equals("rtmps", ignoreCase = true)
        val port = if (target.port >= 0) target.port else if (tls) 443 else 1935
        connection = Connection(target.host ?: "", port, tls)
    }

    private fun handleConnected() {
        writeData(createC0C1Packet())
        setSocketState(SocketState.VERSION_SENT)
    }

    private fun closeSocket(isDisconnected: Boolean) {
        connection?.close()
        connection = null
        val wasHandshakeDone = socketState == SocketState.HANDSHAKE_DONE
        setSocketState(SocketState.CLOSED)
        if (isDisconnected) {
            handleStatus(mapOf("code" to AsValue.Str(if (wasHandshakeDone) RtmpCode.CONNECT_CLOSED else RtmpCode.CONNECT_FAILED)))
            setState(State.INITIALIZED)
            connectTimer?.cancel()
        }
    }

    private fun setSocketState(newState: SocketState) {
        if (socketState == newState) {
            return
        }
        socketState = newState
        when (newState) {
            SocketState.HANDSHAKE_DONE -> {
                writeChunk(0, RtmpChunkStreamId.COMMAND, RtmpMessage(RtmpMessageType.AMF0_COMMAND, 0, 0, makeConnectCommand().encode()))
                periodicTimer?.cancel()
                periodicTimer = executor.scheduleRepeating(NANOS_PER_SECOND) { info.onTimeout() }
            }
            SocketState.CLOSED -> {
                nextTransactionId = 0
                callCompletions.clear()
                chunkReader.clear()
                periodicTimer?.cancel()
            }
            else -> Unit
        }
    }

    private fun writeChunk(type: Int, chunkStreamId: Int, message: RtmpMessage): Int {
        var length = 0
        for (data in encodeChunks(type, chunkStreamId, message, maximumChunkSizeToServer)) {
            writeData(data)
            length += data.size
        }
        return length
    }

    private fun writeData(data: ByteArray) {
        val connection = this.connection ?: return
        totalBytesSending += data.size
        connection.send(data)
        info.onWritten(totalBytesSending)
        if (totalBytesSending - totalBytesSent > MAXIMUM_UNSENT_BYTES) {
            executor.execute { closeSocket(isDisconnected = true) }
        }
    }

    private fun handleInput(data: ByteArray) {
        inputBuffer += data
        when (socketState) {
            SocketState.VERSION_SENT -> {
                if (inputBuffer.size < HANDSHAKE_SIG_SIZE + 1) {
                    return
                }
                writeData(createC2Packet(inputBuffer))
                inputBuffer = inputBuffer.copyOfRange(HANDSHAKE_SIG_SIZE + 1, inputBuffer.size)
                setSocketState(SocketState.ACK_SENT)
                handleInput(ByteArray(0))
            }
            SocketState.ACK_SENT -> {
                if (inputBuffer.size < HANDSHAKE_SIG_SIZE) {
                    return
                }
                // Consume exactly S2 (upstream discards the whole buffer; servers send nothing more yet).
                inputBuffer = inputBuffer.copyOfRange(HANDSHAKE_SIG_SIZE, inputBuffer.size)
                setSocketState(SocketState.HANDSHAKE_DONE)
                if (inputBuffer.isNotEmpty()) {
                    handleInput(ByteArray(0))
                }
            }
            SocketState.HANDSHAKE_DONE -> {
                if (inputBuffer.isNotEmpty()) {
                    inputBuffer = chunkReader.read(inputBuffer) { processMessage(it) }
                }
            }
            else -> Unit
        }
    }

    private fun createC0C1Packet(): ByteArray {
        val packet = ByteArray(1 + HANDSHAKE_SIG_SIZE)
        packet[0] = HANDSHAKE_PROTOCOL_VERSION.toByte()
        // Time 0 and 4 zero bytes, then random bytes.
        Random.nextBytes(packet, 9, packet.size)
        return packet
    }

    private fun createC2Packet(s0s1: ByteArray): ByteArray {
        val packet = ByteArray(HANDSHAKE_SIG_SIZE)
        s0s1.copyInto(packet, 0, 1, 5)
        val now = (System.currentTimeMillis() / 1000) and 0xFFFF_FFFFL
        packet[4] = (now ushr 24).toByte()
        packet[5] = (now ushr 16).toByte()
        packet[6] = (now ushr 8).toByte()
        packet[7] = now.toByte()
        s0s1.copyInto(packet, 8, 9, HANDSHAKE_SIG_SIZE + 1)
        return packet
    }

    /** Test hook: feed raw server bytes as if received after the handshake. */
    internal fun handleChunkDataForTesting(data: ByteArray, onMessage: (RtmpMessage) -> Unit): ByteArray = chunkReader.read(data, onMessage)

    companion object {
        private fun parseUri(value: String): URI = try {
            URI(value)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed RTMP URL")
        }

        private fun uriWithoutUserInfo(uri: URI): String = URI(
            uri.scheme,
            null,
            uri.host,
            uri.port,
            uri.path,
            uri.query,
            uri.fragment,
        ).toString()
    }
}
