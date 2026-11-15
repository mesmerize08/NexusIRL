// Port of Moblin/Media/RtmpServer/RtmpServerClient.swift and RtmpServerChunkStream.swift @97e26d2
// (handshake, acknowledgements, commands, timestamps), for one untrusted connection without a socket.
package io.github.mesmerize08.nexusirl.transport.rtmp.server

import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.media.mux.MuxException
import io.github.mesmerize08.nexusirl.transport.rtmp.Amf0Decoder
import io.github.mesmerize08.nexusirl.transport.rtmp.AmfException
import io.github.mesmerize08.nexusirl.transport.rtmp.AsObject
import io.github.mesmerize08.nexusirl.transport.rtmp.AsValue
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpChunkStreamId
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpCommand
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpMessage
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpMessageType
import io.github.mesmerize08.nexusirl.transport.rtmp.encodeChunks
import io.github.mesmerize08.nexusirl.transport.rtmp.uint32Payload
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/**
 * The server side of one RTMP connection: untrusted bytes in through [feed], replies out through
 * [send], what happened out through [events]. It holds no socket and no timer; the server owns
 * both. [feed] never throws for bad input: a protocol error closes the session with a reason that
 * never contains a stream key.
 */
class RtmpServerSession(
    private val streams: () -> List<RtmpServerStream>,
    private val limits: RtmpServerLimits,
    private val clock: HostClock,
    private val send: (ByteArray) -> Unit,
    private val events: Events,
    private val random: java.util.Random = java.security.SecureRandom(),
) {
    interface Events {
        /** The key matched [stream]; the server decides who wins. */
        fun onPublish(stream: RtmpServerStream)

        fun onWrongKey()

        fun onMedia(media: RtmpIngestMedia)

        /** Exactly once; no feed after it does anything. */
        fun onClosed(reason: String)
    }

    /** HANDSHAKE lasts until a valid `connect`, so it covers the RTMP handshake and the connect. */
    enum class State { HANDSHAKE, CONNECTED, PUBLISHING, CLOSED }

    var state: State = State.HANDSHAKE
        private set

    var stream: RtmpServerStream? = null
        private set

    private val c0c1 = ByteArray(1 + HANDSHAKE_SIZE)
    private var c0c1Received = 0
    private var c2Remaining = HANDSHAKE_SIZE

    private val decoder = RtmpChunkDecoder(limits) { typeId -> maxMessageSize(typeId) }
    private var chunkSizeToClient = DEFAULT_CHUNK_SIZE
    private var windowAcknowledgementSize = DEFAULT_WINDOW
    private var totalBytesReceived = 0L
    private var totalBytesReceivedAcked = 0L

    /** Upstream's running media timestamp per chunk stream, then unwrapped to 64 bits. */
    private class Timeline {
        var running = 0L
        var unwrapped = 0L
        var started = false
    }

    private val timelines = HashMap<Int, Timeline>()
    private var mediaZero = 0L
    private var baseMs = 0L
    private var hasMediaZero = false

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "Range outside the array" }
        if (state == State.CLOSED) {
            return
        }
        totalBytesReceived += length
        try {
            val used = handshake(data, offset, length)
            if (used < length) {
                decoder.feed(data, offset + used, length - used) { handleMessage(it) }
            }
            if (totalBytesReceived - totalBytesReceivedAcked > windowAcknowledgementSize) {
                totalBytesReceivedAcked = totalBytesReceived
                sendMessage(RtmpChunkStreamId.CONTROL, RtmpMessageType.ACK, 0, uint32Payload(totalBytesReceived and 0xFFFF_FFFFL))
            }
        } catch (e: RtmpProtocolException) {
            // A no-op when the session was closed from inside a callback.
            close(e.reason)
        }
    }

    fun close(reason: String) {
        if (state == State.CLOSED) {
            return
        }
        state = State.CLOSED
        events.onClosed(reason)
    }

    /** Consumes C0, C1 and C2 and returns how many of the bytes it used. */
    private fun handshake(data: ByteArray, offset: Int, length: Int): Int {
        var used = 0
        if (c0c1Received < c0c1.size && length > 0) {
            val count = minOf(c0c1.size - c0c1Received, length)
            System.arraycopy(data, offset, c0c1, c0c1Received, count)
            c0c1Received += count
            used += count
            val version = c0c1[0].toInt() and 0xFF
            // Upstream waits for all of C1 first; the version is known from the first byte.
            if (version != RTMP_VERSION) {
                throw RtmpProtocolException("Only version 3 is supported, not $version")
            }
            if (c0c1Received == c0c1.size) {
                sendHandshake()
            }
        }
        if (c0c1Received == c0c1.size && c2Remaining > 0) {
            val count = minOf(c2Remaining, length - used)
            c2Remaining -= count
            used += count
        }
        return used
    }

    private fun sendHandshake() {
        val reply = ByteArray(1 + 2 * HANDSHAKE_SIZE)
        reply[0] = RTMP_VERSION.toByte()
        val random = ByteArray(HANDSHAKE_SIZE - 8)
        this.random.nextBytes(random)
        System.arraycopy(random, 0, reply, 1 + 8, random.size)
        val s2 = 1 + HANDSHAKE_SIZE
        System.arraycopy(c0c1, 1, reply, s2, 4)
        System.arraycopy(c0c1, 1 + 8, reply, s2 + 8, HANDSHAKE_SIZE - 8)
        output(reply)
    }

    private fun maxMessageSize(typeId: Int): Int = if (state != State.PUBLISHING) {
        limits.maxMessageBeforePublish
    } else {
        when (typeId) {
            RtmpMessageType.AUDIO -> limits.maxAudioMessage
            RtmpMessageType.VIDEO -> limits.maxVideoMessage
            else -> limits.maxCommandMessage
        }
    }

    // Errors are thrown as RtmpProtocolException, which also stops the decoder for good.
    private fun handleMessage(message: RtmpDecodedMessage) {
        throwIfClosed()
        val timestamp = advanceTimeline(message)
        when (message.typeId) {
            RtmpMessageType.AMF0_COMMAND -> handleCommand(message)
            RtmpMessageType.CHUNK_SIZE -> handleChunkSize(message.payload)
            RtmpMessageType.WINDOW_ACK -> handleWindowAck(message.payload)
            RtmpMessageType.VIDEO -> if (state == State.PUBLISHING) handleVideo(message.payload, mediaTime(timestamp))
            RtmpMessageType.AUDIO -> if (state == State.PUBLISHING) handleAudio(message.payload, mediaTime(timestamp))
            // AMF-0 data and everything else is ignored, as upstream.
        }
    }

    /** Upstream's running timestamp (absolute sets, otherwise adds), unwrapped across 2^32. */
    private fun advanceTimeline(message: RtmpDecodedMessage): Long {
        val timeline = timelines.getOrPut(message.chunkStreamId) { Timeline() }
        val previous = timeline.running
        timeline.running = if (message.isAbsolute) {
            message.timestamp and 0xFFFF_FFFFL
        } else {
            (previous + message.timestamp) and 0xFFFF_FFFFL
        }
        if (timeline.started) {
            // The signed 32-bit difference: a step back by more than 2^31 is a wrap forwards.
            timeline.unwrapped += (timeline.running - previous).toInt()
        } else {
            timeline.unwrapped = timeline.running
            timeline.started = true
        }
        return timeline.unwrapped
    }

    /** Host milliseconds for a media timestamp; the zero and base come from the first one. */
    private fun mediaTime(timestamp: Long): Long {
        if (!hasMediaZero) {
            mediaZero = timestamp
            baseMs = clock.nanoTime() / 1_000_000
            hasMediaZero = true
        }
        return (timestamp - mediaZero) + baseMs + (stream?.latencyMs ?: 0)
    }

    private fun handleCommand(message: RtmpDecodedMessage) {
        val name: String
        val transactionId: Long
        val commandObject: AsObject
        val arguments = ArrayList<AsValue>(1)
        try {
            val decoder = Amf0Decoder(message.payload)
            name = decoder.decodeString()
            transactionId = decoder.decodeInt()
            commandObject = decoder.decodeObject()
            // Upstream decodes at most one argument.
            if (decoder.bytesAvailable > 0) {
                arguments.add(decoder.decode())
            }
        } catch (e: AmfException) {
            throw RtmpProtocolException(amfDecodeError(e))
        } catch (e: MuxException) {
            throw RtmpProtocolException(amfDecodeError(e))
        }
        when (name) {
            RtmpCommand.CONNECT -> if (state == State.HANDSHAKE) connect(message, transactionId, commandObject)
            RtmpCommand.CREATE_STREAM -> if (state != State.HANDSHAKE) createStream(message, transactionId)
            RtmpCommand.PUBLISH -> if (state == State.CONNECTED) publish(message, transactionId, arguments)
            // FCPublish, FCUnpublish, deleteStream, releaseStream and the rest are ignored.
        }
    }

    private fun connect(message: RtmpDecodedMessage, transactionId: Long, commandObject: AsObject) {
        val url = (commandObject["tcUrl"] as? AsValue.Str)?.value ?: throw RtmpProtocolException("Stream URL missing")
        val path = try {
            URI(url).rawPath
        } catch (e: URISyntaxException) {
            throw RtmpProtocolException("Invalid stream URL")
        }
        if (path != APP_PATH) {
            throw RtmpProtocolException("Not a camera path")
        }
        sendMessage(RtmpChunkStreamId.CONTROL, RtmpMessageType.WINDOW_ACK, 0, uint32Payload(SERVER_WINDOW))
        sendMessage(RtmpChunkStreamId.CONTROL, RtmpMessageType.BANDWIDTH, 0, uint32Payload(SERVER_WINDOW) + byteArrayOf(LIMIT_DYNAMIC))
        sendMessage(RtmpChunkStreamId.CONTROL, RtmpMessageType.CHUNK_SIZE, 0, uint32Payload(SERVER_CHUNK_SIZE.toLong()))
        chunkSizeToClient = SERVER_CHUNK_SIZE
        state = State.CONNECTED
        sendCommand(message, RtmpCommand.RESULT, transactionId, status("NetConnection.Connect.Success", "Connection succeeded."))
    }

    private fun createStream(message: RtmpDecodedMessage, transactionId: Long) {
        sendCommand(message, RtmpCommand.RESULT, transactionId, AsValue.Number(1.0))
    }

    private fun publish(message: RtmpDecodedMessage, transactionId: Long, arguments: List<AsValue>) {
        val argument = arguments.firstOrNull() ?: throw RtmpProtocolException("Missing publish argument")
        val key = (argument as? AsValue.Str)?.value ?: throw RtmpProtocolException("Stream key not a string")
        val matched = findStream(key)
        if (matched == null) {
            events.onWrongKey()
            // Upstream puts the key in this reason; it must never be logged.
            throw RtmpProtocolException("Stream key not configured")
        }
        sendCommand(message, RtmpCommand.ON_STATUS, transactionId, status("NetStream.Publish.Start", "Start publishing."))
        stream = matched
        state = State.PUBLISHING
        events.onPublish(matched)
    }

    /** Compares with every non-empty key in constant time, without stopping at a match. */
    private fun findStream(key: String): RtmpServerStream? {
        val candidate = key.toByteArray(Charsets.UTF_8)
        var matched: RtmpServerStream? = null
        for (configured in streams()) {
            if (configured.streamKey.isEmpty()) {
                continue
            }
            if (MessageDigest.isEqual(candidate, configured.streamKey.toByteArray(Charsets.UTF_8)) && matched == null) {
                matched = configured
            }
        }
        return matched
    }

    private fun handleChunkSize(payload: ByteArray) {
        if (payload.size != 4) {
            throw RtmpProtocolException("Not 4 bytes chunk size")
        }
        val size = uint32(payload)
        if (size < 1 || size > MAX_CHUNK_SIZE) {
            throw RtmpProtocolException("Invalid chunk size $size")
        }
        decoder.chunkSize = size.toInt()
    }

    private fun handleWindowAck(payload: ByteArray) {
        if (payload.size != 4) {
            throw RtmpProtocolException("Not 4 bytes window acknowledgement size")
        }
        windowAcknowledgementSize = uint32(payload)
    }

    private fun handleVideo(payload: ByteArray, dtsMs: Long) {
        val tag = try {
            FlvTagParser.parseVideo(payload)
        } catch (e: FlvParseException) {
            throw RtmpProtocolException(e.reason)
        }
        when (tag) {
            is FlvVideoTag.SequenceHeader -> events.onMedia(RtmpIngestMedia.VideoConfig(tag.codec, tag.config))
            is FlvVideoTag.Frame -> events.onMedia(
                RtmpIngestMedia.VideoFrame(tag.isKeyFrame, dtsMs + tag.compositionTimeMs, dtsMs, tag.data),
            )
            FlvVideoTag.SequenceEnd -> throw RtmpProtocolException("Stream ended")
            is FlvVideoTag.Ignored -> Unit
        }
    }

    private fun handleAudio(payload: ByteArray, ptsMs: Long) {
        val tag = try {
            FlvTagParser.parseAudio(payload)
        } catch (e: FlvParseException) {
            throw RtmpProtocolException(e.reason)
        }
        when (tag) {
            is FlvAudioTag.AacConfig -> events.onMedia(RtmpIngestMedia.AudioConfig(tag.audioSpecificConfig))
            is FlvAudioTag.AacFrame -> events.onMedia(RtmpIngestMedia.AudioFrame(ptsMs, tag.data))
            is FlvAudioTag.Ignored -> Unit
        }
    }

    private fun status(code: String, description: String): AsValue = AsValue.Object(
        linkedMapOf("level" to AsValue.Str("status"), "code" to AsValue.Str(code), "description" to AsValue.Str(description)),
    )

    /** A command reply on the request's chunk stream and message stream. */
    private fun sendCommand(request: RtmpDecodedMessage, name: String, transactionId: Long, argument: AsValue) {
        val payload = RtmpCommand(name, transactionId, null, listOf(argument)).encode()
        sendMessage(request.chunkStreamId, RtmpMessageType.AMF0_COMMAND, request.messageStreamId, payload)
    }

    private fun sendMessage(chunkStreamId: Int, type: Int, messageStreamId: Long, payload: ByteArray) {
        val chunks = encodeChunks(0, chunkStreamId, RtmpMessage(type, messageStreamId, 0, payload), chunkSizeToClient)
        output(if (chunks.size == 1) chunks[0] else chunks.reduce { a, b -> a + b })
    }

    /**
     * Sends, then stops if `send` closed the session (a failed socket write), so no state is
     * written over CLOSED and no event follows onClosed.
     */
    private fun output(bytes: ByteArray) {
        send(bytes)
        throwIfClosed()
    }

    /** Stops the feed when a callback closed the session; close() is then a no-op. */
    private fun throwIfClosed() {
        if (state == State.CLOSED) {
            throw RtmpProtocolException("Closed")
        }
    }

    private fun amfDecodeError(e: Exception) = "AMF-0 decode error ${e.message ?: e.javaClass.simpleName}"

    private fun uint32(payload: ByteArray): Long = ((payload[0].toLong() and 0xFF) shl 24) or
        ((payload[1].toLong() and 0xFF) shl 16) or
        ((payload[2].toLong() and 0xFF) shl 8) or
        (payload[3].toLong() and 0xFF)

    private companion object {
        const val RTMP_VERSION = 3
        const val HANDSHAKE_SIZE = 1536
        const val APP_PATH = "/live"
        const val DEFAULT_CHUNK_SIZE = 128
        const val DEFAULT_WINDOW = 2_500_000L
        const val SERVER_WINDOW = 2_500_000L
        const val SERVER_CHUNK_SIZE = 1024
        const val LIMIT_DYNAMIC: Byte = 2
        const val MAX_CHUNK_SIZE = 0xFF_FFFFL
    }
}
