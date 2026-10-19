package io.github.mesmerize08.nexusirl.media.mux.testing

import io.github.mesmerize08.nexusirl.media.mux.nal.getNalUnits
import io.github.mesmerize08.nexusirl.media.mux.ts.AdtsHeader
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** Helpers for interop tests that use ffmpeg/ffprobe as reference implementations. */
object Ffmpeg {
    fun run(vararg command: String, timeoutSeconds: Long = 120): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return -1 to "$output\n(timed out)"
        }
        return process.exitValue() to output
    }

    /**
     * Starts a long-running process (e.g. an ffmpeg receiver). Output goes to a log file, not a
     * pipe, so a chatty process can never block on a full pipe buffer.
     */
    fun start(vararg command: String): BackgroundProcess {
        val logFile = File.createTempFile("ffmpeg-", ".log")
        logFile.deleteOnExit()
        val process = ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(logFile).start()
        return BackgroundProcess(process, logFile)
    }

    val available: Boolean by lazy {
        try {
            run("ffmpeg", "-version").first == 0 && run("ffprobe", "-version").first == 0
        } catch (e: Exception) {
            false
        }
    }

    /** Encodes [seconds] of a 640x360@30 H.264 test pattern and a stereo 48 kHz AAC tone. */
    fun generateElementaryStreams(directory: File, seconds: Int = 3): ElementaryStreams {
        val videoFile = File(directory, "video.h264")
        val audioFile = File(directory, "audio.aac")
        val (videoExit, videoLog) = run(
            "ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30", "-t", "$seconds",
            "-c:v", "libx264", "-bf", "0", "-g", "30", "-pix_fmt", "yuv420p", "-x264-params", "aud=1",
            "-f", "h264", videoFile.path,
        )
        check(videoExit == 0) { videoLog }
        val (audioExit, audioLog) = run(
            "ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "$seconds",
            "-ac", "2", "-c:a", "aac", "-b:a", "128k", "-f", "adts", audioFile.path,
        )
        check(audioExit == 0) { audioLog }
        return ElementaryStreams.from(videoFile.readBytes(), audioFile.readBytes())
    }

    /** Like [generateElementaryStreams] but HEVC (x265, Main profile, access unit delimiters). */
    fun generateHevcElementaryStreams(directory: File, seconds: Int = 3): ElementaryStreams {
        val videoFile = File(directory, "video.hevc")
        val audioFile = File(directory, "audio.aac")
        val (videoExit, videoLog) = run(
            "ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30", "-t", "$seconds",
            "-c:v", "libx265", "-pix_fmt", "yuv420p", "-x265-params", "aud=1:bframes=0:keyint=30:log-level=error",
            "-f", "hevc", videoFile.path,
        )
        check(videoExit == 0) { videoLog }
        val (audioExit, audioLog) = run(
            "ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "$seconds",
            "-ac", "2", "-c:a", "aac", "-b:a", "128k", "-f", "adts", audioFile.path,
        )
        check(audioExit == 0) { audioLog }
        return ElementaryStreams.fromHevc(videoFile.readBytes(), audioFile.readBytes())
    }
}

class BackgroundProcess(val process: Process, private val logFile: File) {
    fun waitFor(timeout: Long, unit: TimeUnit): Boolean = process.waitFor(timeout, unit)

    fun destroy() {
        process.destroy()
    }

    val log: String
        get() = logFile.readText()
}

class AccessUnit(val data: ByteArray, val isIdr: Boolean)

/**
 * Video access units (Annex-B, without AUD and parameter sets) plus raw AAC frames, at 30 fps and
 * 1024-sample frames. [vps] is set for HEVC streams.
 */
class ElementaryStreams(
    val sps: ByteArray,
    val pps: ByteArray,
    val accessUnits: List<AccessUnit>,
    val audioFrames: List<ByteArray>,
    val vps: ByteArray? = null,
) {
    /** Calls [video] and [audio] interleaved in timestamp order, with times in seconds from 0. */
    fun forEachInterleaved(video: (AccessUnit, Double) -> Unit, audio: (ByteArray, Double) -> Unit) {
        var videoIndex = 0
        var audioIndex = 0
        while (videoIndex < accessUnits.size || audioIndex < audioFrames.size) {
            val videoTime = videoIndex / 30.0
            val audioTime = audioIndex * 1024 / 48000.0
            if (audioIndex < audioFrames.size && (videoIndex >= accessUnits.size || audioTime <= videoTime)) {
                audio(audioFrames[audioIndex++], audioTime)
            } else {
                video(accessUnits[videoIndex++], videoTime)
            }
        }
    }

    companion object {
        fun from(video: ByteArray, audio: ByteArray): ElementaryStreams {
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            val units = splitAccessUnits(video, typeOf = { it[0].toInt() and 0x1F }, aud = 9, isKey = { it == 5 }) { type, data ->
                when (type) {
                    7 -> sps = sps ?: data
                    8 -> pps = pps ?: data
                    else -> return@splitAccessUnits false
                }
                true
            }
            return ElementaryStreams(sps!!, pps!!, units, splitAdts(audio))
        }

        fun fromHevc(video: ByteArray, audio: ByteArray): ElementaryStreams {
            var vps: ByteArray? = null
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            val units =
                splitAccessUnits(video, typeOf = { (it[0].toInt() ushr 1) and 0x3F }, aud = 35, isKey = { it in 16..23 }) { type, data ->
                    when (type) {
                        32 -> vps = vps ?: data
                        33 -> sps = sps ?: data
                        34 -> pps = pps ?: data
                        else -> return@splitAccessUnits false
                    }
                    true
                }
            return ElementaryStreams(sps!!, pps!!, units, splitAdts(audio), vps!!)
        }

        /** Splits on AUD NAL units; [parameterSet] returns true for NAL units it consumed. */
        private fun splitAccessUnits(
            video: ByteArray,
            typeOf: (ByteArray) -> Int,
            aud: Int,
            isKey: (Int) -> Boolean,
            parameterSet: (Int, ByteArray) -> Boolean,
        ): List<AccessUnit> {
            val units = ArrayList<AccessUnit>()
            var current = ByteArrayOutputStream()
            var key = false
            fun finish() {
                if (current.size() > 0) {
                    units.add(AccessUnit(current.toByteArray(), key))
                }
                current = ByteArrayOutputStream()
                key = false
            }
            for (nal in getNalUnits(video)) {
                val data = video.copyOfRange(nal.dataOffset(), nal.dataOffset() + nal.dataLength)
                val type = typeOf(data)
                if (type == aud) {
                    finish()
                } else if (!parameterSet(type, data)) {
                    if (isKey(type)) {
                        key = true
                    }
                    current.write(byteArrayOf(0, 0, 0, 1))
                    current.write(data)
                }
            }
            finish()
            return units
        }

        private fun splitAdts(audio: ByteArray): List<ByteArray> {
            val frames = ArrayList<ByteArray>()
            var offset = 0
            while (offset < audio.size) {
                val length = AdtsHeader.frameLength(audio, offset) ?: break
                frames.add(audio.copyOfRange(offset + AdtsHeader.SIZE, offset + length))
                offset += length
            }
            return frames
        }
    }
}

/** Probes a recorded file: (codec, frames) per stream, via ffprobe. */
fun probeStreams(file: File): List<Pair<String, Int>> {
    val (exit, output) = Ffmpeg.run(
        "ffprobe", "-v", "error", "-count_frames", "-show_entries", "stream=codec_name,nb_read_frames",
        "-of", "csv=p=0", file.path,
    )
    check(exit == 0) { output }
    return output.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().map {
        it.substringBefore(',') to (it.substringAfterLast(',').toIntOrNull() ?: 0)
    }
}

/**
 * Cuts a transport stream where the last video PES starts. The writer interleaves video into
 * audio datagrams, so when a stream stops the final video frame is only partly sent (upstream
 * behaves the same); strict decoder checks should cover only fully sent frames.
 */
fun cutBeforeLastVideoPes(file: File, videoPid: Int = 256) {
    val ts = file.readBytes()
    val start = ts.size - ts.size % 188
    val lastVideoStart = (0 until start step 188).lastOrNull { offset ->
        ts[offset].toInt() == 0x47 &&
            (((ts[offset + 1].toInt() and 0x1F) shl 8) or (ts[offset + 2].toInt() and 0xFF)) == videoPid &&
            (ts[offset + 1].toInt() and 0x40) != 0
    } ?: return
    file.writeBytes(ts.copyOf(lastVideoStart))
}
