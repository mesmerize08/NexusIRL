package io.github.mesmerize08.nexusirl.transport.rtmp.server

import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.media.mux.nal.lengthPrefixedToAnnexB
import io.github.mesmerize08.nexusirl.media.mux.testing.Ffmpeg
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Publishes from a real ffmpeg to [RtmpServer] and decodes what the listener received. Skipped without ffmpeg. */
class RtmpServerInteropTest {
    private val stream = RtmpServerStream(UUID.randomUUID(), "Interop", "goodkey", 0)

    private class Recorder : RtmpServerListener {
        val events = LinkedBlockingQueue<String>()
        val media: MutableList<RtmpIngestMedia> = Collections.synchronizedList(ArrayList())

        override fun onPublishStart(streamId: UUID) {
            events += "start"
        }

        override fun onPublishStop(streamId: UUID, reason: String) {
            events += "stop"
        }

        override fun onMedia(streamId: UUID, media: RtmpIngestMedia) {
            this.media += media
        }

        fun snapshot() = synchronized(media) { media.toList() }
    }

    private val recorder = Recorder()

    private fun <T> withServer(body: (RtmpServer) -> T): T {
        val server = RtmpServer(RtmpServerSettings(0, listOf(stream)), recorder, { _, _ -> true }, RtmpServerLimits(), HostClock.system)
        server.start()
        try {
            return body(server)
        } finally {
            server.stop()
        }
    }

    private fun publisher(port: Int, key: String, videoArgs: List<String>) = Ffmpeg.start(
        *(
            listOf(
                "ffmpeg", "-y", "-v", "error", "-re", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "3",
            ) + videoArgs + listOf("-c:a", "aac", "-f", "flv", "rtmp://127.0.0.1:$port/live/$key")
            ).toTypedArray(),
    )

    @Test
    fun ffmpegPublishesH264WithBFramesAndAac() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val frames = publishAndCollect(
            listOf("-c:v", "libx264", "-bf", "2", "-g", "30", "-pix_fmt", "yuv420p"),
            codec = IngestVideoCodec.H264,
            outputName = "video.h264",
            format = "h264",
        )
        assertTrue(frames.any { it.ptsMs != it.dtsMs }, "expected B-frames (pts != dts)")
    }

    @Test
    fun ffmpegPublishesEnhancedRtmpHevc() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        publishAndCollect(
            listOf("-c:v", "libx265", "-pix_fmt", "yuv420p", "-x265-params", "log-level=error"),
            codec = IngestVideoCodec.H265,
            outputName = "video.hevc",
            format = "hevc",
        )
    }

    @Test
    fun ffmpegWithWrongKeyIsRefused() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        withServer { server ->
            val process = publisher(server.port, "wrongkey", listOf("-c:v", "libx264"))
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "ffmpeg did not exit\n${process.log}")
                assertTrue(process.process.exitValue() != 0, "ffmpeg should fail with a wrong key\n${process.log}")
            } finally {
                process.destroy()
            }
            assertTrue(recorder.events.isEmpty(), "listener saw ${recorder.events}")
            assertTrue(recorder.snapshot().isEmpty())
        }
    }

    private fun publishAndCollect(
        videoArgs: List<String>,
        codec: IngestVideoCodec,
        outputName: String,
        format: String,
    ): List<RtmpIngestMedia.VideoFrame> {
        val directory = Files.createTempDirectory("rtmp-server-interop").toFile()
        try {
            if (codec == IngestVideoCodec.H265) {
                assumeHevcInFlv(directory)
            }
            return publishAndCheck(directory, videoArgs, codec, outputName, format)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Skips when this ffmpeg cannot even write HEVC into FLV locally (no libx265 or no enhanced RTMP). */
    private fun assumeHevcInFlv(directory: File) {
        val (exit, log) = Ffmpeg.run(
            "ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30", "-t", "1",
            "-c:v", "libx265", "-pix_fmt", "yuv420p", "-x265-params", "log-level=error",
            "-f", "flv", File(directory, "probe.flv").path,
        )
        if (exit != 0) {
            val version = Ffmpeg.run("ffmpeg", "-version").second.lineSequence().first()
            assumeTrue(false, "$version cannot write HEVC in FLV (enhanced RTMP): $log")
        }
    }

    private fun publishAndCheck(
        directory: File,
        videoArgs: List<String>,
        codec: IngestVideoCodec,
        outputName: String,
        format: String,
    ): List<RtmpIngestMedia.VideoFrame> {
        withServer { server ->
            val process = publisher(server.port, stream.streamKey, videoArgs)
            try {
                val started = recorder.events.poll(20, TimeUnit.SECONDS) ?: fail("no publish start\n${process.log}")
                assertEquals("start", started)
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "ffmpeg did not finish\n${process.log}")
                assertEquals(0, process.process.exitValue(), process.log)
                assertEquals("stop", recorder.events.poll(10, TimeUnit.SECONDS))
            } finally {
                process.destroy()
            }
        }
        val media = recorder.snapshot()
        val config = media.filterIsInstance<RtmpIngestMedia.VideoConfig>().firstOrNull() ?: fail("no video config")
        assertEquals(codec, config.codec)
        val (nalLengthSize, parameterSets) = when (codec) {
            IngestVideoCodec.H264 -> {
                val avc = MpegTsVideoConfig.Avc.fromAvcC(config.config)
                MpegTsVideoConfig.Avc.nalLengthSize(config.config)!! to listOfNotNull(avc.sequenceParameterSet, avc.pictureParameterSet)
            }
            IngestVideoCodec.H265 -> {
                val hevc = MpegTsVideoConfig.Hevc.fromHvcC(config.config)
                MpegTsVideoConfig.Hevc.nalLengthSize(config.config)!! to
                    listOfNotNull(hevc.videoParameterSet, hevc.sequenceParameterSet, hevc.pictureParameterSet)
            }
        }
        val frames = media.filterIsInstance<RtmpIngestMedia.VideoFrame>()
        assertTrue(frames.size >= 80, "only ${frames.size} video frames")
        val file = File(directory, outputName)
        ByteArrayOutputStream().use { out ->
            for (set in parameterSets) {
                out.write(byteArrayOf(0, 0, 0, 1))
                out.write(set)
            }
            for (frame in frames) {
                out.write(lengthPrefixedToAnnexB(frame.data, nalLengthSize) ?: fail("bad frame data"))
            }
            file.writeBytes(out.toByteArray())
        }
        val (exit, log) = Ffmpeg.run("ffmpeg", "-v", "error", "-xerror", "-f", format, "-i", file.path, "-f", "null", "-")
        assertEquals(0, exit, log)
        var previous = Long.MIN_VALUE
        for (frame in frames) {
            assertTrue(frame.dtsMs >= previous, "dts decreased: ${frame.dtsMs} after $previous")
            previous = frame.dtsMs
        }
        assertTrue(media.any { it is RtmpIngestMedia.AudioConfig }, "no audio config")
        assertTrue(media.count { it is RtmpIngestMedia.AudioFrame } >= 100, "too few audio frames")
        return frames
    }
}
