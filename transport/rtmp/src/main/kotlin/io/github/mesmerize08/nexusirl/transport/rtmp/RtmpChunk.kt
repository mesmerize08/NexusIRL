// Port of Moblin/Media/HaishinKit/Rtmp/RtmpChunk.swift, RtmpChunkReader.swift and
// Message/*.swift @97e26d2
package io.github.mesmerize08.nexusirl.transport.rtmp

import io.github.mesmerize08.nexusirl.media.mux.ByteWriter

object RtmpMessageType {
    const val CHUNK_SIZE = 0x01
    const val ABORT = 0x02
    const val ACK = 0x03
    const val USER = 0x04
    const val WINDOW_ACK = 0x05
    const val BANDWIDTH = 0x06
    const val AUDIO = 0x08
    const val VIDEO = 0x09
    const val AMF3_DATA = 0x0F
    const val AMF3_COMMAND = 0x11
    const val AMF0_DATA = 0x12
    const val AMF0_COMMAND = 0x14
    const val AGGREGATE = 0x16
}

object RtmpChunkStreamId {
    const val CONTROL = 0x02
    const val COMMAND = 0x03
    const val DATA = 0x04
}

object RtmpUserControlEvent {
    const val STREAM_BEGIN = 0x00
    const val PING = 0x06
    const val PONG = 0x07
}

/** A complete RTMP message: header fields and raw payload. */
class RtmpMessage(val type: Int, val streamId: Long, val timestamp: Long, val payload: ByteArray)

/** An AMF0/AMF3 command message (`connect`, `_result`, `onStatus`, ...). */
data class RtmpCommand(val name: String, val transactionId: Long, val commandObject: AsObject?, val arguments: List<AsValue>) {
    fun encode(amf3: Boolean = false): ByteArray {
        val encoder = Amf0Encoder()
        if (amf3) {
            encoder.writeUInt8(0)
        }
        encoder.encode(AsValue.Str(name))
        encoder.encode(AsValue.Number(transactionId.toDouble()))
        encoder.encode(commandObject?.let { AsValue.Object(it) } ?: AsValue.Null)
        arguments.forEach { encoder.encode(it) }
        return encoder.data
    }

    companion object {
        const val CONNECT = "connect"
        const val CLOSE = "close"
        const val RESULT = "_result"
        const val ERROR = "_error"
        const val PUBLISH = "publish"
        const val CREATE_STREAM = "createStream"
        const val RELEASE_STREAM = "releaseStream"
        const val FC_PUBLISH = "FCPublish"
        const val FC_UNPUBLISH = "FCUnpublish"
        const val DELETE_STREAM = "deleteStream"
        const val CLOSE_STREAM = "closeStream"
        const val ON_STATUS = "onStatus"

        /** Returns null (upstream: `.unknown`) when the payload is not a valid command. */
        fun decode(payload: ByteArray, amf3: Boolean = false): RtmpCommand? = try {
            val decoder = Amf0Decoder(payload)
            if (amf3) {
                decoder.position = 1
            }
            val name = decoder.decodeString()
            val transactionId = decoder.decodeInt()
            val commandObject = decoder.decodeObject()
            val arguments = ArrayList<AsValue>()
            while (decoder.bytesAvailable > 0) {
                arguments.add(decoder.decode())
            }
            RtmpCommand(name, transactionId, commandObject, arguments)
        } catch (e: Exception) {
            null
        }
    }
}

/** `@setDataFrame`-style data message payload. */
fun encodeDataMessage(handlerName: String, arguments: List<AsValue>): ByteArray {
    val encoder = Amf0Encoder()
    encoder.encode(AsValue.Str(handlerName))
    arguments.forEach { encoder.encode(it) }
    return encoder.data
}

fun uint32Payload(value: Long): ByteArray {
    val writer = ByteWriter(4)
    writer.writeUInt32(value)
    return writer.toByteArray()
}

fun userControlPayload(event: Int, value: Int): ByteArray {
    val writer = ByteWriter(6)
    writer.writeUInt16(event)
    writer.writeInt32(value)
    return writer.toByteArray()
}

private const val EXTENDED_TIMESTAMP_MARKER = 0xFFFFFFL

private fun basicHeader(type: Int, chunkStreamId: Int): ByteArray = when {
    chunkStreamId <= 63 -> byteArrayOf(((type shl 6) or chunkStreamId).toByte())
    chunkStreamId <= 319 -> byteArrayOf((type shl 6).toByte(), (chunkStreamId - 64).toByte())
    else -> byteArrayOf(((type shl 6) or 1).toByte(), (chunkStreamId - 64).toByte(), ((chunkStreamId - 64) ushr 8).toByte())
}

/** Encodes a message as RTMP chunks of at most [maximumSize] payload bytes. Chunk type 0 or 1. */
fun encodeChunks(type: Int, chunkStreamId: Int, message: RtmpMessage, maximumSize: Int): List<ByteArray> {
    require(type == 0 || type == 1) { "Only chunk types 0 and 1 are written" }
    val hasExtendedTimestamp = message.timestamp >= EXTENDED_TIMESTAMP_MARKER
    val header = ByteWriter(20)
    header.writeBytes(basicHeader(type, chunkStreamId))
    header.writeUInt24(if (hasExtendedTimestamp) EXTENDED_TIMESTAMP_MARKER.toInt() else message.timestamp.toInt())
    header.writeUInt24(message.payload.size)
    header.writeUInt8(message.type)
    if (type == 0) {
        header.writeUInt32Le(message.streamId)
    }
    if (hasExtendedTimestamp) {
        header.writeUInt32(message.timestamp)
    }
    val payload = message.payload
    if (maximumSize >= payload.size) {
        return listOf(header.toByteArray() + payload)
    }
    val continuation = ByteWriter(8)
    continuation.writeBytes(basicHeader(3, chunkStreamId))
    if (hasExtendedTimestamp) {
        continuation.writeUInt32(message.timestamp)
    }
    val continuationHeader = continuation.toByteArray()
    val chunks = ArrayList<ByteArray>(payload.size / maximumSize + 1)
    chunks.add(header.toByteArray() + payload.copyOfRange(0, maximumSize))
    var offset = maximumSize
    while (offset < payload.size) {
        val end = minOf(offset + maximumSize, payload.size)
        chunks.add(continuationHeader + payload.copyOfRange(offset, end))
        offset = end
    }
    return chunks
}

/** Reassembles RTMP messages from a byte stream (port of `RtmpChunkReader`). */
class RtmpChunkReader {
    private class ChunkStream {
        var timestamp = 0L
        var timestampDelta = 0L
        var messageType: Int? = null
        var messageLength = 0
        var messageStreamId = 0L
        var hasExtendedTimestamp = false
        var payload = java.io.ByteArrayOutputStream()
    }

    var maximumChunkSize = DEFAULT_CHUNK_SIZE
    private val chunkStreams = HashMap<Int, ChunkStream>()

    fun clear() {
        maximumChunkSize = DEFAULT_CHUNK_SIZE
        chunkStreams.clear()
    }

    /** Consumes complete chunks from [data] and returns the unconsumed remainder. */
    fun read(data: ByteArray, onMessage: (RtmpMessage) -> Unit): ByteArray {
        var offset = 0
        while (true) {
            val size = readChunk(data, offset, onMessage) ?: break
            offset += size
        }
        return if (offset == 0) data else data.copyOfRange(offset, data.size)
    }

    private fun u8(data: ByteArray, i: Int) = data[i].toInt() and 0xFF

    private fun u24(data: ByteArray, i: Int) = (u8(data, i) shl 16) or (u8(data, i + 1) shl 8) or u8(data, i + 2)

    private fun readChunk(data: ByteArray, offset: Int, onMessage: (RtmpMessage) -> Unit): Int? {
        var position = offset
        fun available() = data.size - position
        if (available() < 1) {
            return null
        }
        val firstByte = u8(data, position++)
        val type = firstByte ushr 6
        val chunkStreamId = when (firstByte and 0b0011_1111) {
            0 -> {
                if (available() < 1) return null
                u8(data, position++) + 64
            }
            1 -> {
                if (available() < 2) return null
                val value = (u8(data, position) or (u8(data, position + 1) shl 8)) + 64
                position += 2
                value and 0xFFFF
            }
            else -> firstByte and 0b0011_1111
        }
        // Work on a copy so an incomplete chunk leaves the stream state untouched.
        val existing = chunkStreams[chunkStreamId]
        val stream = ChunkStream().apply {
            if (existing != null) {
                timestamp = existing.timestamp
                timestampDelta = existing.timestampDelta
                messageType = existing.messageType
                messageLength = existing.messageLength
                messageStreamId = existing.messageStreamId
                hasExtendedTimestamp = existing.hasExtendedTimestamp
                payload = existing.payload
            }
        }
        val messageHeaderSize = when (type) {
            0 -> 11
            1 -> 7
            2 -> 3
            else -> 0
        }
        if (available() < messageHeaderSize) {
            return null
        }
        var timestamp = stream.timestampDelta
        when (type) {
            0 -> {
                timestamp = u24(data, position).toLong()
                stream.messageLength = u24(data, position + 3)
                stream.messageType = u8(data, position + 6)
                stream.messageStreamId = u8(data, position + 7).toLong() or (u8(data, position + 8).toLong() shl 8) or
                    (u8(data, position + 9).toLong() shl 16) or (u8(data, position + 10).toLong() shl 24)
            }
            1 -> {
                timestamp = u24(data, position).toLong()
                stream.messageLength = u24(data, position + 3)
                stream.messageType = u8(data, position + 6)
            }
            2 -> timestamp = u24(data, position).toLong()
        }
        position += messageHeaderSize
        if (type != 3) {
            stream.hasExtendedTimestamp = timestamp == EXTENDED_TIMESTAMP_MARKER
        }
        if (stream.hasExtendedTimestamp) {
            if (available() < 4) {
                return null
            }
            timestamp = (u8(data, position).toLong() shl 24) or (u8(data, position + 1).toLong() shl 16) or
                (u8(data, position + 2).toLong() shl 8) or u8(data, position + 3).toLong()
            position += 4
        }
        val isFirstChunkOfMessage = type != 3 || stream.payload.size() == 0
        val currentPayloadSize = if (isFirstChunkOfMessage) 0 else stream.payload.size()
        val payloadSize = minOf(stream.messageLength - currentPayloadSize, maximumChunkSize)
        if (available() < payloadSize) {
            return null
        }
        // The chunk is complete: commit header state.
        if (isFirstChunkOfMessage) {
            stream.payload = java.io.ByteArrayOutputStream(stream.messageLength)
            stream.timestamp = if (type == 0) timestamp else (stream.timestamp + timestamp) and 0xFFFF_FFFFL
        }
        stream.timestampDelta = if (type == 0) 0 else timestamp
        stream.payload.write(data, position, payloadSize)
        position += payloadSize
        if (stream.payload.size() == stream.messageLength) {
            stream.messageType?.let {
                onMessage(RtmpMessage(it, stream.messageStreamId, stream.timestamp, stream.payload.toByteArray()))
            }
            stream.payload = java.io.ByteArrayOutputStream()
        }
        chunkStreams[chunkStreamId] = stream
        return position - offset
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 128
    }
}
