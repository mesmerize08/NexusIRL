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
 * An MPEG-TS stream with Opus audio (what an Opus SRT/SRTLA stream sends): ffmpeg encodes 20 ms
 * Opus packets like the app's encoder, [MpegTsWriter] muxes them with H.264, and ffmpeg must
 * decode the result without errors. Skipped without ffmpeg unless NEXUSIRL_REQUIRE_FFMPEG=1.
 */
class FfmpegOpusInteropTest {
    @Test
    fun ffmpegDecodesOpusInMpegTs() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val directory = Files.createTempDirectory("mux-opus").toFile()
        val streams = Ffmpeg.generateElementaryStreams(directory)
        val packets = Ffmpeg.generateOpusPackets(directory)
        assertTrue(packets.size in 150..152, "Opus packets: ${packets.size}")

        val tsFile = File(directory, "out.ts")
        tsFile.outputStream().use { output ->
            val writer = MpegTsWriter({ data, _ -> output.write(data) })
            writer.startRunning()
            writer.setAudioConfig(MpegTsAudioConfig.opus(48000, 2))
            writer.setVideoConfig(MpegTsVideoConfig.Avc(streams.sps, streams.pps))
            val start = 1000.0
            var videoIndex = 0
            var audioIndex = 0
            while (videoIndex < streams.accessUnits.size || audioIndex < packets.size) {
                val videoTime = start + videoIndex / 30.0
                val audioTime = start + audioIndex * 960 / 48000.0
                if (audioIndex < packets.size && (videoIndex >= streams.accessUnits.size || audioTime <= videoTime)) {
                    val packet = packets[audioIndex++]
                    writer.writeAudio(packet, 0, packet.size, MediaTime.fromSeconds(audioTime))
                } else {
                    val unit = streams.accessUnits[videoIndex++]
                    writer.writeVideo(unit.data, MediaTime.fromSeconds(videoTime), null, unit.isIdr)
                }
            }
        }
        cutBeforeLastVideoPes(tsFile, MpegTsWriter.VIDEO_PACKET_ID)

        val (decodeExit, decodeLog) = Ffmpeg.run("ffmpeg", "-v", "error", "-xerror", "-i", tsFile.path, "-f", "null", "-")
        assertEquals(0, decodeExit, decodeLog)
        assertTrue(decodeLog.isBlank(), "ffmpeg reported errors:\n$decodeLog")

        val (probeExit, probe) = Ffmpeg.run(
            "ffprobe", "-v", "error", "-count_frames", "-select_streams", "a", "-show_entries",
            "stream=codec_name,sample_rate,channels,nb_read_frames", "-of", "csv=p=0", tsFile.path,
        )
        assertEquals(0, probeExit, probe)
        val audioLine = probe.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct().single()
        assertTrue(audioLine.startsWith("opus,48000,2,"), audioLine)
        // All packets decode (the writer may still hold back the last one or two).
        val frames = audioLine.substringAfterLast(',').toInt()
        assertTrue(frames >= packets.size - 2, "decoded Opus frames: $frames of ${packets.size}")
        directory.deleteRecursively()
    }
}
