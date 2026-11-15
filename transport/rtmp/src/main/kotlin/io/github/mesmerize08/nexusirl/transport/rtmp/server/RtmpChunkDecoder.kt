// Port of Moblin/Media/RtmpServer/RtmpServerClient.swift and RtmpServerChunkStream.swift @97e26d2
// (chunk state machine and message body assembly), with bounds for untrusted input.
package io.github.mesmerize08.nexusirl.transport.rtmp.server

/** [timestamp] is the 32-bit header value (or extended timestamp); [isAbsolute] is true after a type-0 header. */
class RtmpDecodedMessage(
    val chunkStreamId: Int,
    val typeId: Int,
    val messageStreamId: Long,
    val timestamp: Long,
    val isAbsolute: Boolean,
    val payload: ByteArray,
)

/**
 * Reassembles messages from the client's chunks. Only incomplete headers (at most 16 bytes) are
 * buffered; a message's announced length is checked against [maxMessageSize] and the connection's
 * partial-data budget before any of its payload is stored, and the payload array grows with the
 * bytes actually received. The budget ([RtmpServerLimits.maxPartialBytes]) is charged for the
 * capacity of the arrays held, not the bytes received. After a [RtmpProtocolException] the decoder
 * is failed: later feeds throw it again.
 */
class RtmpChunkDecoder(private val limits: RtmpServerLimits, private val maxMessageSize: (typeId: Int) -> Int) {
    /** The client's chunk size, set by the session from SetChunkSize. */
    var chunkSize: Int = 128
        set(value) {
            require(value >= 1) { "Chunk size must be positive" }
            field = value
        }

    private class ChunkStream(val id: Int) {
        var timestamp = 0L
        var length = 0
        var typeId = 0
        var messageStreamId = 0L
        var isAbsolute = false
        var extendedInType3 = false
        var payload: ByteArray? = null
        var received = 0
    }

    private val chunkStreams = HashMap<Int, ChunkStream>()
    private val header = ByteArray(MAX_HEADER_SIZE)
    private var headerSize = 0
    private var current: ChunkStream? = null
    private var chunkRemaining = 0
    private var partialBytes = 0L

    private var failure: RtmpProtocolException? = null

    /**
     * Consumes [length] bytes. A protocol violation throws [RtmpProtocolException] and fails the
     * decoder for good: every later call throws the same exception without touching any state.
     */
    fun feed(data: ByteArray, offset: Int, length: Int, onMessage: (RtmpDecodedMessage) -> Unit) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "Range outside the array" }
        failure?.let { throw it }
        try {
            process(data, offset, length, onMessage)
        } catch (e: RtmpProtocolException) {
            failure = e
            throw e
        }
    }

    private fun process(data: ByteArray, offset: Int, length: Int, onMessage: (RtmpDecodedMessage) -> Unit) {
        var position = offset
        val end = offset + length
        while (position < end) {
            val stream = current
            if (stream == null) {
                header[headerSize++] = data[position++]
                if (parseHeader()) {
                    headerSize = 0
                }
            } else {
                val count = minOf(chunkRemaining, end - position)
                append(stream, data, position, count)
                position += count
                chunkRemaining -= count
                if (chunkRemaining == 0) {
                    current = null
                    if (stream.received == stream.length) {
                        complete(stream, onMessage)
                    }
                }
            }
        }
    }

    private fun u8(i: Int) = header[i].toInt() and 0xFF

    private fun u24(i: Int) = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)

    /** Returns true when the buffered bytes form a whole header, which is then applied. */
    private fun parseHeader(): Boolean {
        val first = u8(0)
        val format = first ushr 6
        val chunkStreamId = first and 0x3F
        if (chunkStreamId == 0) {
            throw RtmpProtocolException("Two bytes basic header is not implemented")
        }
        if (chunkStreamId == 1) {
            throw RtmpProtocolException("Three bytes basic header is not implemented")
        }
        val existing = chunkStreams[chunkStreamId]
        if (existing == null && chunkStreams.size >= limits.maxChunkStreams) {
            throw RtmpProtocolException("Too many chunk streams")
        }
        val messageHeaderSize = when (format) {
            0 -> 11
            1 -> 7
            2 -> 3
            else -> 0
        }
        if (headerSize < 1 + messageHeaderSize) {
            return false
        }
        val hasExtended = if (format == 3) {
            existing?.extendedInType3 ?: false
        } else {
            u24(1) == EXTENDED_TIMESTAMP_MARKER
        }
        if (headerSize < 1 + messageHeaderSize + (if (hasExtended) 4 else 0)) {
            return false
        }
        apply(existing ?: ChunkStream(chunkStreamId).also { chunkStreams[chunkStreamId] = it }, format, hasExtended)
        return true
    }

    private fun apply(stream: ChunkStream, format: Int, hasExtended: Boolean) {
        val inProgress = stream.payload != null
        if (inProgress && format != 3) {
            throw RtmpProtocolException("Unexpected data")
        }
        if (format != 3) {
            stream.timestamp = u24(1).toLong()
            stream.isAbsolute = format == 0
            stream.extendedInType3 = hasExtended
            if (format <= 1) {
                stream.length = u24(4)
                stream.typeId = u8(7)
            }
            if (format == 0) {
                stream.messageStreamId = u8(8).toLong() or (u8(9).toLong() shl 8) or (u8(10).toLong() shl 16) or (u8(11).toLong() shl 24)
            }
        }
        if (hasExtended) {
            val at = headerSize - 4
            stream.timestamp =
                (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()
        }
        if (!inProgress) {
            startMessage(stream)
        }
        chunkRemaining = minOf(stream.length - stream.received, chunkSize)
        current = stream
    }

    private fun startMessage(stream: ChunkStream) {
        if (stream.length <= 0) {
            throw RtmpProtocolException("Unexpected data")
        }
        if (stream.length > maxMessageSize(stream.typeId)) {
            throw RtmpProtocolException("Message of ${stream.length} bytes is too large")
        }
        val capacity = minOf(stream.length, INITIAL_CAPACITY)
        chargeCapacity(capacity)
        stream.payload = ByteArray(capacity)
        stream.received = 0
        partialBytes += capacity
    }

    /** The budget covers memory held, so a new array is checked while the old one is still held. */
    private fun chargeCapacity(newBytes: Int) {
        if (partialBytes + newBytes > limits.maxPartialBytes) {
            throw RtmpProtocolException("Too much incomplete data")
        }
    }

    private fun append(stream: ChunkStream, data: ByteArray, position: Int, count: Int) {
        var payload = stream.payload!!
        val needed = stream.received + count
        if (needed > payload.size) {
            val capacity = minOf(stream.length, maxOf(needed, payload.size * 2))
            chargeCapacity(capacity)
            partialBytes += capacity - payload.size
            payload = payload.copyOf(capacity)
            stream.payload = payload
        }
        System.arraycopy(data, position, payload, stream.received, count)
        stream.received += count
    }

    private fun complete(stream: ChunkStream, onMessage: (RtmpDecodedMessage) -> Unit) {
        // Capacity is capped at the length, so a complete message's array is exactly its size.
        val payload = stream.payload!!
        stream.payload = null
        stream.received = 0
        partialBytes -= payload.size
        onMessage(
            RtmpDecodedMessage(stream.id, stream.typeId, stream.messageStreamId, stream.timestamp, stream.isAbsolute, payload),
        )
    }

    private companion object {
        const val MAX_HEADER_SIZE = 16
        const val EXTENDED_TIMESTAMP_MARKER = 0xFFFFFF
        const val INITIAL_CAPACITY = 1024
    }
}
