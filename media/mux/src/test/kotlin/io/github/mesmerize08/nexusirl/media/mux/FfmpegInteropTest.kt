package io.github.mesmerize08.nexusirl.media.mux

import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.testing.Ffmpeg
import io.github.mesmerize08.nexusirl.media.mux.testing.cutBeforeLastVideoPes
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsWriter
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end check against a real demuxer/decoder: ffmpeg encodes H.264 + AAC elementary streams,
 * [MpegTsWriter] muxes them, and ffmpeg must decode the transport stream without any errors.
 * Skipped when ffmpeg/ffprobe are not on PATH, unless NEXUSIRL_REQUIRE_FFMPEG=1 (see [Ffmpeg.available]).
 */
class FfmpegInteropTest {
    private fun run(vararg command: String): Pair<Int, String> = Ffmpeg.run(*command)

    @Test
    fun ffmpegDecodesWriterOutputWithoutErrors() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val directory = Files.createTempDirectory("mux-interop").toFile()
        val streams = Ffmpeg.generateElementaryStreams(directory)
        assertEquals(90, streams.accessUnits.size)

        val tsFile = File(directory, "out.ts")
        tsFile.outputStream().use { output ->
            val writer = MpegTsWriter({ data, _ -> output.write(data) })
            writer.startRunning()
            writer.setAudioConfig(MpegTsAudioConfig.aacLc(48000, 2))
            writer.setVideoConfig(MpegTsVideoConfig.Avc(streams.sps, streams.pps))
            val start = 1000.0
            streams.forEachInterleaved(
                video = { unit, time -> writer.writeVideo(unit.data, MediaTime.fromSeconds(start + time), null, unit.isIdr) },
                audio = { frame, time -> writer.writeAudio(frame, 0, frame.size, MediaTime.fromSeconds(start + time)) },
            )
        }

        // The writer interleaves video into audio datagrams, so when input stops the last video
        // frame is only partly written (upstream behaves the same; the receiver sees a truncated
        // final frame). Cut the file where that frame's PES starts so the check covers everything
        // the writer fully emitted, strictly.
        cutBeforeLastVideoPes(tsFile, MpegTsWriter.VIDEO_PACKET_ID)

        val (decodeExit, decodeLog) = run("ffmpeg", "-v", "error", "-xerror", "-i", tsFile.path, "-f", "null", "-")
        assertEquals(0, decodeExit, decodeLog)
        assertTrue(decodeLog.isBlank(), "ffmpeg reported errors:\n$decodeLog")

        val (probeExit, probe) = run(
            "ffprobe", "-v", "error", "-count_frames", "-show_entries",
            "stream=codec_name,width,height,sample_rate,channels,nb_read_frames", "-of", "csv=p=0", tsFile.path,
        )
        assertEquals(0, probeExit, probe)
        // ffprobe lists each stream under both the program and the stream sections.
        val lines = probe.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val videoLine = lines.single { it.startsWith("h264") }
        val audioLine = lines.single { it.startsWith("aac") }
        assertTrue(videoLine.startsWith("h264,640,360,"), videoLine)
        // Up to two video frames remain buffered inside the writer at the end (upstream design),
        // and the cut drops the partly written one.
        val videoFrames = videoLine.substringAfterLast(',').trim().toInt()
        assertTrue(videoFrames in 87..89, "decoded video frames: $videoFrames")
        val (flagsExit, flags) = run("ffprobe", "-v", "error", "-show_entries", "packet=flags", "-of", "csv=p=0", tsFile.path)
        assertEquals(0, flagsExit, flags)
        assertTrue(flags.lines().none { 'C' in it }, "packets flagged corrupt:\n$flags")
        assertTrue(audioLine.startsWith("aac,48000,2"), audioLine)
        directory.deleteRecursively()
    }
}
