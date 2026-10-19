package io.github.mesmerize08.nexusirl.stream

import android.content.Context
import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.core.secrets.Redactor
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import io.github.mesmerize08.nexusirl.media.pipeline.AudioPipeline
import io.github.mesmerize08.nexusirl.media.pipeline.FanOutSink
import io.github.mesmerize08.nexusirl.media.pipeline.MediaSink
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEncoderSettings
import io.github.mesmerize08.nexusirl.media.pipeline.VideoPipeline
import io.github.mesmerize08.nexusirl.net.MultiNetworkManager
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSettings
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSrtFight
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpMetadata
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpStream
import io.github.mesmerize08.nexusirl.transport.srt.SrtAdaptiveBitrateDriver
import io.github.mesmerize08.nexusirl.transport.srt.SrtStream
import io.github.mesmerize08.nexusirl.transport.srt.SrtStreamConfig
import io.github.mesmerize08.nexusirl.transport.srt.net.DatagramUdpLinkFactory
import android.view.Surface

/** Snapshot of the live state for the UI (upstream: the status bar sub-models). */
data class StreamStatus(
    val state: String = "Idle",
    val live: Boolean = false,
    val fps: Int = 0,
    val videoBitrateKbps: Long = 0,
    val rttMs: Double = 0.0,
    val audioLevelDbfs: Float = -160f,
    val detail: String = "",
)

/**
 * One streaming session: capture + encode (video and audio), and one destination (SRT/SRTLA or
 * RTMP(S)). Upstream equivalent: Media.swift's srtStartStream/rtmpStartStream with the Processor.
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

    @Volatile
    private var status = StreamStatus(state = "Starting")

    private interface Destination : MediaSink {
        fun stop()

        fun rttMs(): Double

        fun debugStats(): String = ""
    }

    private var lastEncodedFrames = 0L

    fun start() {
        video.start()
        audio.start()
        destination = when (url.substringBefore("://").lowercase()) {
            "srt", "srtla" -> startSrt()
            "rtmp", "rtmps" -> startRtmp()
            else -> {
                report("Unsupported URL scheme")
                null
            }
        }
        executor.scheduleRepeating(1_000_000_000) { publishStatus() }
    }

    fun setPreviewSurface(surface: Surface?) = video.setPreviewSurface(surface)

    fun setDisplayRotation(degrees: Int) {
        video.displayRotationDegrees = degrees
    }

    fun stop() {
        destination?.stop()
        destination = null
        audio.stop()
        video.stop()
        executor.shutdown()
        update(status.copy(state = "Stopped", live = false))
    }

    private fun startSrt(): Destination {
        val stream = SrtStream(
            SrtStreamConfig(url = url, latency = 2000),
            DatagramUdpLinkFactory(),
            executor,
            clock,
            object : SrtStream.Listener {
                override fun onConnected() = handleConnected()

                override fun onDisconnected(message: String) = handleDisconnected(message)
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

            override fun writeAudio(frame: ByteArray, presentationTimeStamp: MediaTime) =
                stream.writeAudio(frame, 0, frame.size, presentationTimeStamp)

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

    private fun startRtmp(): Destination {
        val stream = RtmpStream(
            url,
            executor,
            clock,
            object : RtmpStream.Listener {
                override fun onStatus(code: String) {
                    if (code.endsWith("Failed") || code.endsWith("Closed") || code.endsWith("Rejected")) {
                        handleDisconnected(code)
                    }
                }

                override fun onConnected() = handleConnected()
            },
        )
        stream.setMetadata(RtmpMetadata(settings.width, settings.height, settings.fps.toDouble(), settings.hevc, settings.bitrate, 128_000, 48_000.0))
        stream.connect()
        return object : Destination {
            override fun setAudioConfig(config: MpegTsAudioConfig, sampleRate: Int) = stream.setAudioConfig(config, sampleRate)

            override fun setVideoConfig(config: MpegTsVideoConfig) = stream.setVideoConfig(config)

            override fun writeAudio(frame: ByteArray, presentationTimeStamp: MediaTime) =
                stream.writeAudio(frame, 0, frame.size, presentationTimeStamp)

            override fun writeVideo(accessUnit: ByteArray, presentationTimeStamp: MediaTime, decodeTimeStamp: MediaTime?, isSync: Boolean) =
                stream.writeVideo(accessUnit, presentationTimeStamp, decodeTimeStamp, isSync)

            override fun stop() = stream.disconnect()

            override fun rttMs(): Double = stream.info.stats.rttMs
        }
    }

    private fun handleConnected() {
        // Like processor.startEncoding(stream): attach, replay formats, and start on a key frame.
        destination?.let { fanOut.add(it) }
        video.encoder?.requestKeyFrame()
        update(status.copy(state = "Live", live = true, detail = ""))
    }

    private fun handleDisconnected(message: String) {
        destination?.let { fanOut.remove(it) }
        update(status.copy(state = "Disconnected", live = false, detail = REDACTOR.redact(message)))
    }

    private fun report(message: String) {
        update(status.copy(detail = REDACTOR.redact(message)))
    }

    private fun publishStatus() {
        val encoded = video.encoder?.encodedFrames ?: 0
        android.util.Log.d("StreamSession", "Encoded ${encoded - lastEncodedFrames} frames/s ${destination?.debugStats() ?: ""}")
        lastEncodedFrames = encoded
        update(
            status.copy(
                fps = video.outputFps,
                videoBitrateKbps = (video.encoder?.currentBitrate ?: 0) / 1000,
                rttMs = destination?.rttMs() ?: 0.0,
                audioLevelDbfs = audio.levelDbfs,
            ),
        )
    }

    private fun update(newStatus: StreamStatus) {
        status = newStatus
        onStatus(newStatus)
    }

    private companion object {
        val REDACTOR = Redactor()
    }
}
