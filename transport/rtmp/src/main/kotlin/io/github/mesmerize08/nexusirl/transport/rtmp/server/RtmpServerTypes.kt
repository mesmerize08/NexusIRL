package io.github.mesmerize08.nexusirl.transport.rtmp.server

import java.util.UUID

/** One accepted ingest: connections publishing [streamKey] feed the stream [id]. */
data class RtmpServerStream(val id: UUID, val name: String, val streamKey: String, val latencyMs: Int)

data class RtmpServerSettings(val port: Int, val streams: List<RtmpServerStream>)

/** Bounds on what an untrusted publisher can make the server hold or wait for. */
data class RtmpServerLimits(
    val maxConnections: Int = 8,
    val maxUnauthenticated: Int = 4,
    val handshakeTimeoutMs: Long = 5_000,
    val publishTimeoutMs: Long = 10_000,
    val idleTimeoutMs: Long = 10_000,
    val readTimeoutMs: Int = 3_000,
    val maxChunkStreams: Int = 16,
    val maxMessageBeforePublish: Int = 64 * 1024,
    val maxCommandMessage: Int = 64 * 1024,
    val maxAudioMessage: Int = 64 * 1024,
    val maxVideoMessage: Int = 8 * 1024 * 1024,
    val maxPartialBytes: Int = 16 * 1024 * 1024,
    val maxFailedKeys: Int = 5,
    val lockoutMs: Long = 60_000,
)

// The media holding bytes compare them by content, like the FLV tags.
sealed interface RtmpIngestMedia {
    data class VideoConfig(val codec: IngestVideoCodec, val config: ByteArray) : RtmpIngestMedia {
        override fun equals(other: Any?): Boolean = other is VideoConfig && codec == other.codec && config.contentEquals(other.config)

        override fun hashCode(): Int = 31 * codec.hashCode() + config.contentHashCode()
    }

    data class VideoFrame(val isKeyFrame: Boolean, val ptsMs: Long, val dtsMs: Long, val data: ByteArray) : RtmpIngestMedia {
        override fun equals(other: Any?): Boolean = other is VideoFrame &&
            isKeyFrame == other.isKeyFrame &&
            ptsMs == other.ptsMs &&
            dtsMs == other.dtsMs &&
            data.contentEquals(other.data)

        override fun hashCode(): Int {
            var result = isKeyFrame.hashCode()
            result = 31 * result + ptsMs.hashCode()
            result = 31 * result + dtsMs.hashCode()
            return 31 * result + data.contentHashCode()
        }
    }

    data class AudioConfig(val audioSpecificConfig: ByteArray) : RtmpIngestMedia {
        override fun equals(other: Any?): Boolean = other is AudioConfig && audioSpecificConfig.contentEquals(other.audioSpecificConfig)

        override fun hashCode(): Int = audioSpecificConfig.contentHashCode()
    }

    data class AudioFrame(val ptsMs: Long, val data: ByteArray) : RtmpIngestMedia {
        override fun equals(other: Any?): Boolean = other is AudioFrame && ptsMs == other.ptsMs && data.contentEquals(other.data)

        override fun hashCode(): Int = 31 * ptsMs.hashCode() + data.contentHashCode()
    }
}

interface RtmpServerListener {
    fun onPublishStart(streamId: UUID)

    fun onPublishStop(streamId: UUID, reason: String)

    fun onMedia(streamId: UUID, media: RtmpIngestMedia)
}

/** The session ends with [reason], which never contains a stream key. */
class RtmpProtocolException(val reason: String) : Exception(reason)
