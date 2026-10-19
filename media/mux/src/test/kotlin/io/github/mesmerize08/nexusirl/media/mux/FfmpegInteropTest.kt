package io.github.mesmerize08.nexusirl.media.mux

import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.nal.getNalUnits
import io.github.mesmerize08.nexusirl.media.mux.ts.AdtsHeader
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsWriter
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end check against a real demuxer/decoder: ffmpeg encodes H.264 + AAC elementary streams,
 * [MpegTsWriter] muxes them, and ffmpeg must decode the transport stream without any errors.
 * Skipped when ffmpeg/ffprobe are not on PATH (e.g. CI).
 */
class FfmpegInteropTest {
    private fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(120, TimeUnit.SECONDS)
        return process.exitValue() to output
    }

    private fun available(tool: String): Boolean = try {
        run(tool, "-version").first == 0
    } catch (e: Exception) {
        false
    }

    @Test
    fun ffmpegDecodesWriterOutputWithoutErrors() {
        assumeTrue(available("ffmpeg") && available("ffprobe"), "ffmpeg/ffprobe not installed")
        val directory = Files.createTempDirectory("mux-interop").toFile()
        val videoFile = File(directory, "video.h264")
        val audioFile = File(directory, "audio.aac")
        val (videoExit, videoLog) = run(
            "ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30", "-t", "3",
            "-c:v", "libx264", "-bf", "0", "-g", "30", "-pix_fmt", "yuv420p", "-x264-params", "aud=1",
            "-f", "h264", videoFile.path,
        )
        assertEquals(0, videoExit, videoLog)
        val (audioExit, audioLog) = run(
            "ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "3",
            "-ac", "2", "-c:a", "aac", "-b:a", "128k", "-f", "adts", audioFile.path,
        )
        assertEquals(0, audioExit, audioLog)

        val accessUnits = splitAccessUnits(videoFile.readBytes())
        val audioFrames = splitAdtsFrames(audioFile.readBytes())
        assertEquals(90, accessUnits.size)

        val tsFile = File(directory, "out.ts")
        tsFile.outputStream().use { output ->
            val writer = MpegTsWriter({ data, _ -> output.write(data) })
            writer.startRunning()
            writer.setAudioConfig(MpegTsAudioConfig.aacLc(48000, 2))
            writer.setVideoConfig(MpegTsVideoConfig.Avc(accessUnits.sps, accessUnits.pps))
            val start = 1000.0
            var videoIndex = 0
            var audioIndex = 0
            while (videoIndex < accessUnits.size || audioIndex < audioFrames.size) {
                val videoTime = start + videoIndex / 30.0
                val audioTime = start + audioIndex * 1024 / 48000.0
                if (audioIndex < audioFrames.size && (videoIndex >= accessUnits.size || audioTime <= videoTime)) {
                    val frame = audioFrames[audioIndex++]
                    writer.writeAudio(frame, 0, frame.size, MediaTime.fromSeconds(audioTime))
                } else {
                    val unit = accessUnits[videoIndex++]
                    writer.writeVideo(unit.data, MediaTime.fromSeconds(videoTime), null, unit.isIdr)
                }
            }
        }

        // The writer interleaves video into audio datagrams, so when input stops the last video
        // frame is only partly written (upstream behaves the same; the receiver sees a truncated
        // final frame). Cut the file where that frame's PES starts so the check covers everything
        // the writer fully emitted, strictly.
        val ts = tsFile.readBytes()
        val lastVideoStart = (ts.indices step 188).last { offset ->
            val pid = ((ts[offset + 1].toInt() and 0x1F) shl 8) or (ts[offset + 2].toInt() and 0xFF)
            pid == MpegTsWriter.VIDEO_PACKET_ID && (ts[offset + 1].toInt() and 0x40) != 0
        }
        tsFile.writeBytes(ts.copyOf(lastVideoStart))

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
        val (flagsExit, flags) = run(
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "packet=flags",
            "-of",
            "csv=p=0",
            tsFile.path,
        )
        assertEquals(0, flagsExit, flags)
        assertTrue(flags.lines().none { 'C' in it }, "packets flagged corrupt:\n$flags")
        assertTrue(audioLine.startsWith("aac,48000,2"), audioLine)
        directory.deleteRecursively()
    }

    private class AccessUnit(val data: ByteArray, val isIdr: Boolean)

    private class AccessUnits(val sps: ByteArray, val pps: ByteArray, units: List<AccessUnit>) : List<AccessUnit> by units

    /** Splits an Annex-B stream on AUD NAL units, dropping AUD/SPS/PPS (the writer adds them). */
    private fun splitAccessUnits(stream: ByteArray): AccessUnits {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val units = ArrayList<AccessUnit>()
        var current = java.io.ByteArrayOutputStream()
        var isIdr = false
        fun finish() {
            if (current.size() > 0) {
                units.add(AccessUnit(current.toByteArray(), isIdr))
            }
            current = java.io.ByteArrayOutputStream()
            isIdr = false
        }
        for (nal in getNalUnits(stream)) {
            val data = stream.copyOfRange(nal.dataOffset(), nal.dataOffset() + nal.dataLength)
            when (data[0].toInt() and 0x1F) {
                9 -> finish()
                7 -> sps = sps ?: data
                8 -> pps = pps ?: data
                else -> {
                    if (data[0].toInt() and 0x1F == 5) {
                        isIdr = true
                    }
                    current.write(byteArrayOf(0, 0, 0, 1))
                    current.write(data)
                }
            }
        }
        finish()
        return AccessUnits(sps!!, pps!!, units)
    }

    /** Splits an ADTS stream into raw AAC frames (headers stripped; ffmpeg writes no CRC). */
    private fun splitAdtsFrames(stream: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        var offset = 0
        while (offset < stream.size) {
            val length = AdtsHeader.frameLength(stream, offset) ?: break
            frames.add(stream.copyOfRange(offset + AdtsHeader.SIZE, offset + length))
            offset += length
        }
        return frames
    }
}
