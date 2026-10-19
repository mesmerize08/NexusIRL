// Port of Moblin/Media/HaishinKit/Srt/SrtStreamMoblin.swift and the SRT parts of
// Moblin/Various/Media.swift (srtStartStream, SrtlaDelegate, SrtStreamMoblinDelegate) @97e26d2
package io.github.mesmerize08.nexusirl.transport.srt

import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsPacket
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsWriter
import io.github.mesmerize08.nexusirl.media.mux.ts.TimecodeClock
import io.github.mesmerize08.nexusirl.transport.srt.net.BondingInterface
import io.github.mesmerize08.nexusirl.transport.srt.net.UdpLinkFactory
import io.github.mesmerize08.nexusirl.transport.srt.srtla.BondingConnection
import io.github.mesmerize08.nexusirl.transport.srt.srtla.ConnectionPriorities
import io.github.mesmerize08.nexusirl.transport.srt.srtla.SrtlaClient
import io.github.mesmerize08.nexusirl.transport.srt.srtla.SrtlaClientListener
import java.net.URI
import java.net.URLDecoder

/** Parsed `srt://` or `srtla://` stream URL. */
data class SrtUrl(
    val isSrtla: Boolean,
    val host: String,
    val port: Int,
    val streamId: String?,
    val passphrase: String?,
    /** libsrt `pbkeylen`: AES key length in bytes (16, 24 or 32). */
    val keyLength: Int = 16,
) {
    companion object {
        fun parse(url: String): SrtUrl {
            val uri = try {
                URI(url)
            } catch (e: Exception) {
                throw SrtException("Malformed URL")
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "srt" && scheme != "srtla") {
                throw SrtException("Not an SRT URL")
            }
            val host = uri.host ?: throw SrtException("Malformed URL")
            if (uri.port < 0) {
                throw SrtException("Malformed URL")
            }
            val query = parseQuery(uri.rawQuery)
            val keyLength = query["pbkeylen"]?.toIntOrNull() ?: 16
            if (keyLength !in setOf(16, 24, 32)) {
                throw SrtException("pbkeylen must be 16, 24 or 32")
            }
            return SrtUrl(scheme == "srtla", host, uri.port, query["streamid"], query["passphrase"], keyLength)
        }

        private fun parseQuery(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrEmpty()) {
                return emptyMap()
            }
            return rawQuery.split('&').filter { it.isNotEmpty() }.associate { part ->
                val key = part.substringBefore('=')
                val value = part.substringAfter('=', "")
                URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
            }
        }
    }
}

/** Settings for one SRT(LA) stream (subset of upstream `SettingsStreamSrt`). */
data class SrtStreamConfig(
    val url: String,
    val latency: Int = DEFAULT_SRT_LATENCY,
    val experimental: Boolean = false,
    val mpegtsPacketsPerPacket: Int = 7,
    val packetPadding: Boolean = false,
    val connectTimeoutSeconds: Double = 6.0,
    val networkInterfaceNames: Map<String, String> = emptyMap(),
    val connectionPriorities: ConnectionPriorities = ConnectionPriorities(),
) {
    companion object {
        /** Upstream `defaultSrtLatency`. */
        const val DEFAULT_SRT_LATENCY = 3000
    }
}

/**
 * Streams MPEG-TS over SRT, or over SRTLA bonding, with Moblin's own sender.
 *
 * Encoders call [setAudioConfig]/[setVideoConfig]/[writeAudio]/[writeVideo] from their own
 * thread. Muxed output is handed to [executor], which owns the sender and the network paths.
 */
class SrtStream(
    private val config: SrtStreamConfig,
    private val linkFactory: UdpLinkFactory,
    private val executor: SerialExecutor,
    private val clock: HostClock,
    private val listener: Listener,
    timecodeClock: TimecodeClock? = null,
) {
    interface Listener {
        fun onConnected()

        fun onDisconnected(message: String)
    }

    private val url = SrtUrl.parse(config.url)
    private val payloadSize = config.mpegtsPacketsPerPacket * MpegTsPacket.SIZE
    private val writerLock = Any()
    private val writer = MpegTsWriter({ data, containsAudio -> executor.execute { write(data, containsAudio) } }, payloadSize = payloadSize, timecodeClock = timecodeClock)
    private var audioConfig: MpegTsAudioConfig? = null
    private var videoConfig: MpegTsVideoConfig? = null
    private var writerRunning = false
    private var sender: SrtSender? = null
    private var srtlaClient: SrtlaClient? = null
    private var stopped = false
    private var interfaces: List<BondingInterface> = emptyList()

    init {
        // Validate early so a bad passphrase fails at configuration time, not mid-handshake.
        url.passphrase?.let {
            if (it.length !in 10..79) {
                throw SrtException("SRT passphrase must be 10 to 79 characters")
            }
        }
    }

    fun start() {
        val client = SrtlaClient(
            srtlaListener,
            passThrough = !url.isSrtla,
            mpegtsPacketsPerPacket = config.mpegtsPacketsPerPacket,
            packetPadding = config.packetPadding,
            linkFactory = linkFactory,
            executor = executor,
            clock = clock,
            networkInterfaceNames = config.networkInterfaceNames,
            connectionPriorities = config.connectionPriorities,
        )
        executor.execute {
            stopped = false
            srtlaClient = client
            client.setInterfaces(interfaces)
        }
        client.start(url.host, url.port, config.connectTimeoutSeconds)
    }

    fun stop() {
        executor.execute {
            stopped = true
            sender?.stop()
            sender = null
            srtlaClient?.stop()
            srtlaClient = null
        }
        stopWriter()
    }

    /** Available network paths for SRTLA bonding (from the connectivity layer). */
    fun setInterfaces(interfaces: List<BondingInterface>) {
        executor.execute {
            this.interfaces = interfaces
            srtlaClient?.setInterfaces(interfaces)
        }
    }

    fun setConnectionPriorities(priorities: ConnectionPriorities) {
        executor.execute { srtlaClient?.setConnectionPriorities(priorities) }
    }

    fun getPerformanceData(): SrtPerformanceData? = sender?.getPerformanceData()

    /** Must be called on [executor]. */
    fun connectionStatistics(): List<BondingConnection> = srtlaClient?.connectionStatistics() ?: emptyList()

    /** Total bytes through the transport (both directions, upstream semantics). Must be called on [executor]. */
    fun getTotalByteCount(): Long = srtlaClient?.getTotalByteCount() ?: 0

    /** Must be called on [executor]. */
    val isConnected: Boolean
        get() = sender?.isConnected == true

    /** The configured SRT latency in milliseconds. */
    val latency: Int
        get() = config.latency

    // Encoder side

    fun setAudioConfig(config: MpegTsAudioConfig) = synchronized(writerLock) {
        audioConfig = config
        if (writerRunning) {
            writer.setAudioConfig(config)
        }
    }

    fun setVideoConfig(config: MpegTsVideoConfig) = synchronized(writerLock) {
        videoConfig = config
        if (writerRunning) {
            writer.setVideoConfig(config)
        }
    }

    fun writeAudio(frame: ByteArray, offset: Int, length: Int, presentationTimeStamp: MediaTime) = synchronized(writerLock) {
        if (writerRunning) {
            writer.writeAudio(frame, offset, length, presentationTimeStamp)
        }
    }

    fun writeVideo(accessUnit: ByteArray, presentationTimeStamp: MediaTime, decodeTimeStamp: MediaTime?, isSync: Boolean) =
        synchronized(writerLock) {
            if (writerRunning) {
                writer.writeVideo(accessUnit, presentationTimeStamp, decodeTimeStamp, isSync)
            }
        }

    /** Like `processor.startEncoding(writer)`: the encoders' current formats are re-applied. */
    private fun startWriter() = synchronized(writerLock) {
        writer.startRunning()
        writerRunning = true
        audioConfig?.let { writer.setAudioConfig(it) }
        videoConfig?.let { writer.setVideoConfig(it) }
    }

    private fun stopWriter() = synchronized(writerLock) {
        writerRunning = false
        writer.stopRunning()
    }

    // Transport side (executor)

    private fun write(data: ByteArray, containsAudio: Boolean) {
        val sender = this.sender ?: return
        val now = clock.nanoTime()
        var offset = 0
        while (offset < data.size) {
            val length = minOf(payloadSize, data.size - offset)
            val packet = sender.newDataPacket(data, offset, length)
            packet.containsAudio = containsAudio
            sender.enqueue(packet, now)
            offset += length
        }
        sender.send(now)
    }

    private val srtlaListener = object : SrtlaClientListener {
        override fun onReady() {
            if (stopped) {
                return
            }
            // A fresh salt and stream key for every connection.
            val crypto = url.passphrase?.let { SrtCrypto(it, url.keyLength) }
            val newSender = SrtSender(url.streamId, config.latency.coerceIn(0, 0xFFFF), config.experimental, executor, clock, senderListener, crypto)
            sender = newSender
            newSender.start()
        }

        override fun onError(message: String) {
            listener.onDisconnected("SRT error: $message")
        }

        override fun onPacketFromServer(packet: ByteArray) {
            sender?.input(packet)
        }
    }

    private val senderListener = object : SrtSender.Listener {
        override fun onConnected() {
            startWriter()
            listener.onConnected()
        }

        override fun onDisconnected() {
            stopWriter()
            if (!stopped) {
                listener.onDisconnected("SRT error: " + (sender?.disconnectReason ?: "SRT disconnected"))
            }
        }

        override fun onOutput(packet: ByteArray) {
            srtlaClient?.handleLocalPacket(packet)
        }
    }
}
