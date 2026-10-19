package io.github.mesmerize08.nexusirl.stream

import android.content.Context
import android.os.PowerManager
import android.view.Surface
import io.github.mesmerize08.nexusirl.core.clock.Cancellable
import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.core.secrets.Redactor
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import io.github.mesmerize08.nexusirl.media.pipeline.AudioPipeline
import io.github.mesmerize08.nexusirl.media.pipeline.FanOutSink
import io.github.mesmerize08.nexusirl.media.pipeline.MediaSink
import io.github.mesmerize08.nexusirl.media.pipeline.MicrophoneManager
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEncoderSettings
import io.github.mesmerize08.nexusirl.media.pipeline.VideoPipeline
import io.github.mesmerize08.nexusirl.net.MultiNetworkManager
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSettings
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSrtFight
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpCode
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpMetadata
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpStream
import io.github.mesmerize08.nexusirl.transport.srt.SrtAdaptiveBitrateDriver
import io.github.mesmerize08.nexusirl.transport.srt.SrtStream
import io.github.mesmerize08.nexusirl.transport.srt.SrtStreamConfig
import io.github.mesmerize08.nexusirl.transport.srt.net.DatagramUdpLinkFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Device thermal state (upstream `ProcessInfo.ThermalState`), from PowerManager's status. */
enum class ThermalState {
    NORMAL,
    FAIR,
    SERIOUS,
    CRITICAL,
    ;

    companion object {
        fun fromPowerManager(status: Int): ThermalState = when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> CRITICAL
            status >= PowerManager.THERMAL_STATUS_SEVERE -> SERIOUS
            status >= PowerManager.THERMAL_STATUS_MODERATE -> FAIR
            else -> NORMAL
        }
    }
}

/** Stream state shown to the user (upstream `StreamState`). */
enum class StreamState { IDLE, CONNECTING, LIVE, RECONNECTING, STOPPED }

/** Snapshot of the live state for the UI (upstream: the status bar sub-models). */
data class StreamStatus(
    val state: StreamState = StreamState.IDLE,
    val fps: Int = 0,
    val videoBitrateKbps: Long = 0,
    val rttMs: Double = 0.0,
    val audioLevelDbfs: Float = -160f,
    /** Seconds since this connection went live (upstream stream uptime). */
    val uptimeSeconds: Long = 0,
    val reconnects: Int = 0,
    val thermal: ThermalState = ThermalState.NORMAL,
    /** Selected microphone name. */
    val microphone: String = "",
    /** Video encoder in use, e.g. "c2.qti.avc.encoder (HW)". */
    val encoder: String = "",
    val detail: String = "",
) {
    val live: Boolean
        get() = state == StreamState.LIVE

    /** True while the user wants to be live (connecting, live or retrying). */
    val active: Boolean
        get() = state == StreamState.CONNECTING || state == StreamState.LIVE || state == StreamState.RECONNECTING
}

/**
 * One streaming session: capture + encode (video and audio) for its whole lifetime, and a
 * destination (SRT/SRTLA or RTMP(S)) that is recreated on every reconnect.
 *
 * Upstream equivalents: Media.swift's srtStartStream/rtmpStartStream with the Processor, and
 * ModelStream.swift's onConnected/onDisconnected: on a disconnect or failed connect the network
 * stream is stopped and started again after 5 seconds, while capture keeps running.
 *
 * Destination state is confined to [executor], which is also where the transports deliver
 * their callbacks.
 */
class StreamSession(
    private val context: Context,
    private val url: String,
    private val settings: VideoEncoderSettings,
    private val onStatus: (StreamStatus) -> Unit,
) {
    private val fanOut = FanOutSink()
    private val video = VideoPipeline(context, settings, fanOut) { report("Camera: $it") }
    private val audio = AudioPipeline(fanOut)
    private val executor = SerialExecutor.create("stream")
    private val clock = HostClock.system
    private var destination: Destination? = null
    private var generation = 0
    private var reconnectTimer: Cancellable? = null
    private var stopped = false
    private var liveSince: Long? = null
    private var reconnects = 0
    private var lastEncodedFrames = 0L
    private val microphones = MicrophoneManager(context)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { thermalStatus ->
        update(status.copy(thermal = ThermalState.fromPowerManager(thermalStatus)))
    }

    @Volatile
    private var status = StreamStatus(state = StreamState.CONNECTING)

    private interface Destination : MediaSink {
        fun stop()

        fun rttMs(): Double

        fun debugStats(): String = ""
    }

    fun start() {
        video.start()
        microphones.start { _, selected ->
            audio.setPreferredDevice(selected?.device)
            update(status.copy(microphone = selected?.name ?: ""))
        }
        audio.start()
        powerManager.addThermalStatusListener({ task -> executor.execute { task.run() } }, thermalListener)
        executor.execute { startDestination() }
        executor.scheduleRepeating(1_000_000_000) { publishStatus() }
    }

    fun setPreviewSurface(surface: Surface?) = video.setPreviewSurface(surface)

    fun setDisplayRotation(degrees: Int) {
        video.displayRotationDegrees = degrees
    }

    fun stop() {
        val done = CountDownLatch(1)
        executor.execute {
            stopped = true
            reconnectTimer?.cancel()
            stopDestination()
            done.countDown()
        }
        done.await(3, TimeUnit.SECONDS)
        powerManager.removeThermalStatusListener(thermalListener)
        microphones.stop()
        audio.stop()
        video.stop()
        executor.shutdown()
        update(status.copy(state = StreamState.STOPPED))
    }

    // Destination lifecycle (executor)

    private fun startDestination() {
        if (stopped) {
            return
        }
        generation += 1
        val current = generation
        update(status.copy(state = if (reconnects == 0) StreamState.CONNECTING else StreamState.RECONNECTING))
        destination = try {
            when (url.substringBefore("://").lowercase()) {
                "srt", "srtla" -> startSrt(current)
                "rtmp", "rtmps" -> startRtmp(current)
                else -> {
                    report("Unsupported URL scheme")
                    null
                }
            }
        } catch (e: Exception) {
            // E.g. an invalid passphrase: configuration errors are not retried.
            report(e.message ?: "Invalid stream settings")
            update(status.copy(state = StreamState.STOPPED))
            null
        }
    }

    private fun stopDestination() {
        destination?.let {
            fanOut.remove(it)
            it.stop()
        }
        destination = null
        liveSince = null
    }

    private fun handleConnected(eventGeneration: Int) {
        if (eventGeneration != generation || stopped) {
            return
        }
        // Like processor.startEncoding(stream): attach, replay formats, and start on a key frame.
        destination?.let { fanOut.add(it) }
        video.encoder?.requestKeyFrame()
        liveSince = clock.nanoTime()
        update(status.copy(state = StreamState.LIVE, detail = ""))
    }

    private fun handleDisconnected(eventGeneration: Int, message: String) {
        // Transports may report one failure more than once; only act on the current destination.
        if (eventGeneration != generation || stopped || reconnectTimer != null) {
            return
        }
        stopDestination()
        reconnects += 1
        update(
            status.copy(
                state = StreamState.RECONNECTING,
                reconnects = reconnects,
                detail = REDACTOR.redact(message) + " - attempting again in 5 seconds",
            ),
        )
        reconnectTimer = executor.schedule(RECONNECT_DELAY_NANOS) {
            reconnectTimer = null
            startDestination()
        }
    }

    private fun startSrt(eventGeneration: Int): Destination {
        val stream = SrtStream(
            SrtStreamConfig(url = url, latency = 2000),
            DatagramUdpLinkFactory(),
            executor,
            clock,
            object : SrtStream.Listener {
                override fun onConnected() = handleConnected(eventGeneration)

                override fun onDisconnected(message: String) = handleDisconnected(eventGeneration, message)
            },
        )
        val adaptiveBitrate = AdaptiveBitrateSrtFight(settings.bitrate, { video.encoder?.setBitrate(it) }, clock)
            .apply { setSettings(AdaptiveBitrateSettings.FAST) }
        val driver = SrtAdaptiveBitrateDriver(stream, adaptiveBitrate, executor, settings.bitrate)
        // SRTLA bonds every available network (cellular, Wi-Fi, Ethernet) at once.
        val networks = if (url.startsWith("srtla", ignoreCase = true)) MultiNetworkManager(context) else null
        networks?.start { stream.setInterfaces(it) }
        stream.start()
        driver.start()
        return object : Destination {
            override fun setAudioConfig(config: MpegTsAudioConfig, sampleRate: Int) = stream.setAudioConfig(config)

            override fun setVideoConfig(config: MpegTsVideoConfig) = stream.setVideoConfig(config)

            override fun writeAudio(frame: ByteArray, presentationTimeStamp: MediaTime) = stream.writeAudio(
                frame,
                0,
                frame.size,
                presentationTimeStamp,
            )

            override fun writeVideo(accessUnit: ByteArray, presentationTimeStamp: MediaTime, decodeTimeStamp: MediaTime?, isSync: Boolean) =
                stream.writeVideo(accessUnit, presentationTimeStamp, decodeTimeStamp, isSync)

            override fun stop() {
                driver.stop()
                stream.stop()
                networks?.stop()
            }

            override fun rttMs(): Double = stream.getPerformanceData()?.msRtt ?: 0.0

            override fun debugStats(): String {
                val performance = stream.getPerformanceData() ?: return ""
                // Runs on the stream executor (publishStatus), as connectionStatistics requires.
                val paths = stream.connectionStatistics().joinToString(" ") { "${it.name}:${it.usage / 1024}KiB/${it.rtt}ms" }
                return "srt drop=${performance.pktSndDropTotal} retrans=${performance.pktRetransTotal} " +
                    "nak=${performance.pktRecvNakTotal} pif=${performance.pktFlightSize} rtt=${performance.msRtt} paths[$paths]"
            }
        }
    }

    private fun startRtmp(eventGeneration: Int): Destination {
        val stream = RtmpStream(
            url,
            executor,
            clock,
            object : RtmpStream.Listener {
                override fun onStatus(code: String) {
                    if (code == RtmpCode.CONNECT_FAILED || code == RtmpCode.CONNECT_CLOSED || code == RtmpCode.CONNECT_REJECTED) {
                        handleDisconnected(eventGeneration, "RTMP disconnected with message $code")
                    }
                }

                override fun onConnected() = handleConnected(eventGeneration)
            },
        )
        stream.setMetadata(
            RtmpMetadata(settings.width, settings.height, settings.fps.toDouble(), settings.hevc, settings.bitrate, 128_000, 48_000.0),
        )
        stream.connect()
        return object : Destination {
            override fun setAudioConfig(config: MpegTsAudioConfig, sampleRate: Int) = stream.setAudioConfig(config, sampleRate)

            override fun setVideoConfig(config: MpegTsVideoConfig) = stream.setVideoConfig(config)

            override fun writeAudio(frame: ByteArray, presentationTimeStamp: MediaTime) = stream.writeAudio(
                frame,
                0,
                frame.size,
                presentationTimeStamp,
            )

            override fun writeVideo(accessUnit: ByteArray, presentationTimeStamp: MediaTime, decodeTimeStamp: MediaTime?, isSync: Boolean) =
                stream.writeVideo(accessUnit, presentationTimeStamp, decodeTimeStamp, isSync)

            override fun stop() = stream.disconnect()

            override fun rttMs(): Double = stream.info.stats.rttMs
        }
    }

    private fun report(message: String) {
        update(status.copy(detail = REDACTOR.redact(message)))
    }

    private fun publishStatus() {
        val encoded = video.encoder?.encodedFrames ?: 0
        android.util.Log.d(
            "StreamSession",
            "Encoded ${encoded - lastEncodedFrames} frames/s state=${status.state} encoder=${status.encoder} " +
                "mic=${status.microphone} thermal=${status.thermal} ${destination?.debugStats() ?: ""}",
        )
        lastEncodedFrames = encoded
        update(
            status.copy(
                fps = video.outputFps,
                videoBitrateKbps = (video.encoder?.currentBitrate ?: 0) / 1000,
                rttMs = destination?.rttMs() ?: 0.0,
                audioLevelDbfs = audio.levelDbfs,
                uptimeSeconds = liveSince?.let { (clock.nanoTime() - it) / 1_000_000_000 } ?: 0,
                encoder = video.encoder?.info?.let { it.name + if (it.hardware) " (HW)" else " (SW)" } ?: "",
            ),
        )
    }

    private fun update(newStatus: StreamStatus) {
        status = newStatus
        onStatus(newStatus)
    }

    private companion object {
        val REDACTOR = Redactor()

        /** Upstream: "Attempting again in 5 seconds." */
        const val RECONNECT_DELAY_NANOS = 5_000_000_000L
    }
}
