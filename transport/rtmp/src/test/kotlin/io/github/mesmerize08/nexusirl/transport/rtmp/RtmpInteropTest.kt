package io.github.mesmerize08.nexusirl.transport.rtmp

import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.media.mux.testing.ElementaryStreams
import io.github.mesmerize08.nexusirl.media.mux.testing.Ffmpeg
import io.github.mesmerize08.nexusirl.media.mux.testing.probeStreams
import io.github.mesmerize08.nexusirl.media.mux.testing.waitUntil
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Publishes real H.264 or HEVC + AAC or Opus with [RtmpStream] to ffmpeg's RTMP server. Skipped without ffmpeg. */
class RtmpInteropTest {
    @Test
    fun ffmpegRtmpServerReceivesH264Stream() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val directory = Files.createTempDirectory("rtmp-interop").toFile()
        val streams = Ffmpeg.generateElementaryStreams(directory, seconds = 3)
        publishAndVerify(directory, streams, MpegTsVideoConfig.Avc(streams.sps, streams.pps), expectedCodec = "h264")
    }

    /**
     * Like a phone: video carries its camera capture time, about 100 ms older than the audio sent
     * with it. The first key frame must not be dropped as "before the start" (found on a Galaxy
     * S26 Ultra: no picture for one key frame interval).
     */
    @Test
    fun firstKeyFrameSurvivesVideoOlderThanAudio() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val directory = Files.createTempDirectory("rtmp-interop").toFile()
        val streams = Ffmpeg.generateElementaryStreams(directory, seconds = 3)
        publishAndVerify(directory, streams, MpegTsVideoConfig.Avc(streams.sps, streams.pps), expectedCodec = "h264", videoLagSeconds = 0.1)
    }

    /** Enhanced RTMP (`hvc1` FourCC) with an hvcC built from raw VPS/SPS/PPS. */
    @Test
    fun ffmpegRtmpServerReceivesEnhancedRtmpHevcStream() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val directory = Files.createTempDirectory("rtmp-interop").toFile()
        val streams = Ffmpeg.generateHevcElementaryStreams(directory, seconds = 3)
        publishAndVerify(directory, streams, MpegTsVideoConfig.Hevc(streams.vps, streams.sps, streams.pps), expectedCodec = "hevc")
    }

    /** Enhanced RTMP Opus (ex-header, `Opus` FourCC and an OpusHead), as upstream streams it. */
    @Test
    fun ffmpegRtmpServerReceivesEnhancedRtmpOpusAudio() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val directory = Files.createTempDirectory("rtmp-interop").toFile()
        val streams = Ffmpeg.generateElementaryStreams(directory, seconds = 3).withOpus(Ffmpeg.generateOpusPackets(directory, seconds = 3))
        publishAndVerify(
            directory,
            streams,
            MpegTsVideoConfig.Avc(streams.sps, streams.pps),
            expectedCodec = "h264",
            audioConfig = MpegTsAudioConfig.opus(48000, 2),
            // ffmpeg's FLV muxer cannot store Opus; NUT can. Not Matroska: ffmpeg 8.0.1 (Ubuntu
            // 26.04, CI) reports "Error parsing Opus packet header" decoding any Opus in Matroska,
            // even its own Ogg Opus copied in; later ffmpeg fixed it. The stream itself is fine.
            container = "nut",
        )
    }

    private fun publishAndVerify(
        directory: File,
        streams: ElementaryStreams,
        videoConfig: MpegTsVideoConfig,
        expectedCodec: String,
        videoLagSeconds: Double = 0.0,
        audioConfig: MpegTsAudioConfig = MpegTsAudioConfig.aacLc(48000, 2),
        container: String = "flv",
    ) {
        val port = ServerSocket(0).use { it.localPort }
        val recording = File(directory, "received.$container")
        val server = Ffmpeg.start(
            // An explicit listen timeout: ffmpeg's default (-1) fails to listen on Windows.
            "ffmpeg", "-y", "-v", "error", "-listen", "1", "-timeout", "30", "-i", "rtmp://127.0.0.1:$port/live/testkey",
            "-c", "copy", "-f", container, recording.path,
        )
        Thread.sleep(1_000)

        val executor = SerialExecutor.create("rtmp-test")
        val connected = CountDownLatch(1)
        val statuses = ArrayList<String>()
        val stream = RtmpStream(
            "rtmp://127.0.0.1:$port/live/testkey",
            executor,
            HostClock.system,
            object : RtmpStream.Listener {
                override fun onStatus(code: String) {
                    synchronized(statuses) { statuses.add(code) }
                }

                override fun onConnected() = connected.countDown()
            },
        )
        stream.setMetadata(RtmpMetadata(640, 360, 30.0, videoConfig is MpegTsVideoConfig.Hevc, 2_000_000, 128_000, 48_000.0))
        stream.setAudioConfig(audioConfig, 48000)
        stream.setVideoConfig(videoConfig)
        stream.connect()
        if (!connected.await(15, TimeUnit.SECONDS)) {
            server.destroy()
            throw AssertionError("publish did not start: $statuses\n${server.log}")
        }
        assertTrue(RtmpCode.CONNECT_SUCCESS in statuses, "statuses: $statuses")

        val startNanos = System.nanoTime()
        val base = MediaTime(startNanos, MediaTime.NANOSECONDS)
        streams.forEachInterleaved(
            video = { unit, time ->
                waitUntil(startNanos, time)
                stream.writeVideo(unit.data, base + MediaTime.fromSeconds(time - videoLagSeconds), null, unit.isIdr)
            },
            audio = { frame, time ->
                waitUntil(startNanos, time)
                stream.writeAudio(frame, 0, frame.size, base + MediaTime.fromSeconds(time))
            },
        )
        Thread.sleep(500)
        stream.disconnect()
        assertTrue(server.waitFor(20, TimeUnit.SECONDS), "ffmpeg did not finish")
        val serverLog = server.log
        executor.shutdown()

        assertTrue(recording.exists() && recording.length() > 50_000, "nothing recorded: $serverLog")
        val (decodeExit, decodeLog) = Ffmpeg.run("ffmpeg", "-v", "error", "-xerror", "-i", recording.path, "-f", "null", "-")
        assertEquals(0, decodeExit, decodeLog)
        assertTrue(decodeLog.isBlank(), "decode errors:\n$decodeLog")
        val probed = probeStreams(recording).toMap()
        // RTMP is reliable and nothing is held back, so every frame arrives.
        assertEquals(90, probed[expectedCodec], "video frames: $probed")
        // Audio sent before the first video frame waits for it (a frame or two with a lag).
        val audioCodec = if (audioConfig.isOpus) "opus" else "aac"
        assertTrue((probed[audioCodec] ?: 0) >= streams.audioFrames.size - 3, "audio frames: $probed")
        directory.deleteRecursively()
    }
}
