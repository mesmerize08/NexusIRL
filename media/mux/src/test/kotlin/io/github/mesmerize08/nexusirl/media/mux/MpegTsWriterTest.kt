package io.github.mesmerize08.nexusirl.media.mux

import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.nal.HevcNalUnit
import io.github.mesmerize08.nexusirl.media.mux.nal.SeiTimecode
import io.github.mesmerize08.nexusirl.media.mux.nal.getNalUnits
import io.github.mesmerize08.nexusirl.media.mux.ts.AdtsHeader
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsWriter
import io.github.mesmerize08.nexusirl.media.mux.ts.OpusHeader
import io.github.mesmerize08.nexusirl.media.mux.ts.TimecodeClock
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MpegTsWriterTest {
    private class Capture {
        val blocks = ArrayList<Pair<ByteArray, Boolean>>()
        val all: ByteArray
            get() = blocks.fold(ByteArray(0)) { acc, block -> acc + block.first }
    }

    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F, 0x11)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    private val random = Random(42)

    private fun accessUnit(size: Int, idr: Boolean): ByteArray =
        byteArrayOf(0, 0, 0, 1, if (idr) 0x65 else 0x41) + ByteArray(size) { (random.nextInt(4, 256)).toByte() }

    /** Feeds 2 s of 30 fps video and 48 kHz AAC (1024-sample frames), interleaved by timestamp. */
    private fun runAvStream(writer: MpegTsWriter, startSeconds: Double = 1000.0) {
        writer.startRunning()
        writer.setAudioConfig(MpegTsAudioConfig.aacLc(48000, 2))
        writer.setVideoConfig(MpegTsVideoConfig.Avc(sps, pps))
        var videoFrame = 0
        var audioFrame = 0
        while (videoFrame < 60) {
            val videoTime = startSeconds + videoFrame / 30.0
            val audioTime = startSeconds + audioFrame * 1024 / 48000.0
            if (audioTime <= videoTime) {
                val frame = ByteArray(300) { random.nextInt().toByte() }
                writer.writeAudio(frame, 0, frame.size, MediaTime.fromSeconds(audioTime))
                audioFrame++
            } else {
                val isSync = videoFrame % 30 == 0
                writer.writeVideo(accessUnit(if (isSync) 20_000 else 3_000, isSync), MediaTime.fromSeconds(videoTime), null, isSync)
                videoFrame++
            }
        }
    }

    @Test
    fun producesValidTransportStream() {
        val capture = Capture()
        runAvStream(MpegTsWriter({ data, audio -> capture.blocks.add(data to audio) }))
        val demuxer = TestTsDemuxer()
        demuxer.feed(capture.all)
        demuxer.finish()

        assertEquals(4095, demuxer.pmtPid)
        assertEquals(MpegTsWriter.AUDIO_PACKET_ID, demuxer.pcrPid)
        assertEquals(mapOf(256 to 0x1B, 257 to 0x0F), demuxer.streamTypes)
        assertTrue(demuxer.continuityErrors.isEmpty(), demuxer.continuityErrors.toString())
        // The program is written when both configs are set, then every > 2 s of audio.
        assertTrue(demuxer.patCount >= 1)

        val video = demuxer.pesPackets.filter { it.pid == 256 }
        val audio = demuxer.pesPackets.filter { it.pid == 257 }
        // Upstream holds up to two video frames: one being drained into audio datagrams and the
        // newest one. So of 60 frames, 58 or 59 have been fully written.
        assertTrue(video.size in 58..59, "video PES count ${video.size}")
        assertTrue(video.zipWithNext().all { (a, b) -> b.pts!! > a.pts!! })
        assertTrue(audio.size >= 90)
        assertTrue(video.all { it.streamId == 0xE0 && it.dts == null })
        assertTrue(audio.all { it.streamId == 0xC0 })
        // PTS is absolute host time in 90 kHz ticks (not zero based), truncated like upstream.
        // Truncation (upstream `Int64(seconds * 90000)`) can land one tick low, so allow 1 tick.
        assertEquals((1000.0 * 90000).toLong(), video[0].pts)
        assertTrue(Math.abs(((1000.0 + 1 / 30.0) * 90000).toLong() - video[1].pts!!) <= 1)
        // PCR is on the audio PID, at most every 100 ms.
        assertTrue(demuxer.pcrValues.all { it.first == 257 })
        assertTrue(demuxer.pcrValues.zipWithNext().all { (a, b) -> b.second - a.second >= 9000 })
    }

    @Test
    fun keyFramesCarryAudAndParameterSets() {
        val capture = Capture()
        runAvStream(MpegTsWriter({ data, audio -> capture.blocks.add(data to audio) }))
        val demuxer = TestTsDemuxer()
        demuxer.feed(capture.all)
        demuxer.finish()
        val video = demuxer.pesPackets.filter { it.pid == 256 }
        val key = video[0]
        assertTrue(key.randomAccess)
        val keyNalTypes = getNalUnits(key.payload).map { key.payload[it.dataOffset()].toInt() and 0x1F }
        assertEquals(listOf(9, 7, 8, 5), keyNalTypes)
        assertEquals(0x10, key.payload[5].toInt())
        val delta = video[1]
        val deltaNalTypes = getNalUnits(delta.payload).map { delta.payload[it.dataOffset()].toInt() and 0x1F }
        assertEquals(listOf(9, 1), deltaNalTypes)
        assertEquals(0x30, delta.payload[5].toInt())
    }

    @Test
    fun audioFramesHaveAdtsHeaders() {
        val capture = Capture()
        runAvStream(MpegTsWriter({ data, audio -> capture.blocks.add(data to audio) }))
        val demuxer = TestTsDemuxer()
        demuxer.feed(capture.all)
        demuxer.finish()
        for (pes in demuxer.pesPackets.filter { it.pid == 257 }) {
            assertEquals(0xFF, pes.payload[0].toInt() and 0xFF)
            assertEquals(pes.payload.size, AdtsHeader.frameLength(pes.payload))
        }
    }

    @Test
    fun blocksModeFillsAudioDatagramsWithPendingVideo() {
        val capture = Capture()
        val writer = MpegTsWriter({ data, audio -> capture.blocks.add(data to audio) }, payloadSize = 1316)
        runAvStream(writer)
        val audioBlocks = capture.blocks.filter { it.second }
        assertTrue(audioBlocks.isNotEmpty())
        assertTrue(audioBlocks.all { it.first.size <= 1316 && it.first.size % 188 == 0 })
        // While video is pending, audio blocks are topped up to exactly one datagram.
        assertTrue(audioBlocks.count { it.first.size == 1316 } > audioBlocks.size / 2)
    }

    @Test
    fun chunkedModeNeverExceedsPayloadSize() {
        val capture = Capture()
        val writer = MpegTsWriter(
            { data, audio -> capture.blocks.add(data to audio) },
            mode = MpegTsWriter.OutputMode.CHUNKED,
            payloadSize = 6 * 188,
        )
        runAvStream(writer)
        assertTrue(capture.blocks.all { it.first.size <= 6 * 188 && it.first.size % 188 == 0 })
        // Same bytes regardless of output mode.
        val blocksCapture = Capture()
        runAvStreamWithSeed(MpegTsWriter({ data, audio -> blocksCapture.blocks.add(data to audio) }, payloadSize = 6 * 188))
        val chunkedCapture = Capture()
        runAvStreamWithSeed(
            MpegTsWriter({ data, audio -> chunkedCapture.blocks.add(data to audio) }, MpegTsWriter.OutputMode.CHUNKED, 6 * 188),
        )
        assertContentEquals(blocksCapture.all, chunkedCapture.all)
    }

    private fun runAvStreamWithSeed(writer: MpegTsWriter) {
        val saved = Random(7)
        writer.startRunning()
        writer.setAudioConfig(MpegTsAudioConfig.aacLc(48000, 2))
        writer.setVideoConfig(MpegTsVideoConfig.Avc(sps, pps))
        for (i in 0 until 30) {
            writer.writeAudio(ByteArray(250) { saved.nextInt().toByte() }, 0, 250, MediaTime.fromSeconds(10.0 + i * 0.021))
            writer.writeVideo(byteArrayOf(0, 0, 0, 1, 0x41) + ByteArray(2000) { 5 }, MediaTime.fromSeconds(10.0 + i / 30.0), null, i == 0)
        }
    }

    @Test
    fun opusUsesPrivateStreamWithRegistrationDescriptor() {
        val capture = Capture()
        val writer = MpegTsWriter({ data, audio -> capture.blocks.add(data to audio) })
        writer.startRunning()
        writer.setAudioConfig(MpegTsAudioConfig.opus(48000, 2))
        writer.setVideoConfig(MpegTsVideoConfig.Avc(sps, pps))
        val packet = ByteArray(300) { 7 }
        writer.writeAudio(packet, 0, packet.size, MediaTime.fromSeconds(5.0))
        val demuxer = TestTsDemuxer()
        demuxer.feed(capture.all)
        demuxer.finish()
        assertEquals(0x06, demuxer.streamTypes[257])
        assertContentEquals(
            byteArrayOf(0x05, 4) + "Opus".toByteArray() + byteArrayOf(0x7F, 2, 0x80.toByte(), 2),
            demuxer.esDescriptors[257],
        )
        val pes = demuxer.pesPackets.single { it.pid == 257 }
        val (length, headerLength) = assertNotNull(OpusHeader.decode(pes.payload))
        assertEquals(300, length)
        assertContentEquals(packet, pes.payload.copyOfRange(headerLength, pes.payload.size))
    }

    @Test
    fun opusHeaderLengthCoding() {
        assertContentEquals(byteArrayOf(0x7F, 0xE0.toByte(), 0), OpusHeader.encode(0))
        assertContentEquals(byteArrayOf(0x7F, 0xE0.toByte(), 254.toByte()), OpusHeader.encode(254))
        assertContentEquals(byteArrayOf(0x7F, 0xE0.toByte(), 255.toByte(), 0), OpusHeader.encode(255))
        assertContentEquals(byteArrayOf(0x7F, 0xE0.toByte(), 255.toByte(), 255.toByte(), 2), OpusHeader.encode(512))
        assertEquals(512 to 5, OpusHeader.decode(OpusHeader.encode(512)))
    }

    @Test
    fun nothingIsWrittenUntilBothConfigsAreSet() {
        val capture = Capture()
        val writer = MpegTsWriter({ data, audio -> capture.blocks.add(data to audio) })
        writer.startRunning()
        writer.setAudioConfig(MpegTsAudioConfig.aacLc(48000, 2))
        writer.writeAudio(ByteArray(10), 0, 10, MediaTime.fromSeconds(1.0))
        assertTrue(capture.blocks.isEmpty())
    }

    @Test
    fun hevcKeyFramesCarryParameterSetsAndTimecodeSei() {
        val capture = Capture()
        val clock = object : TimecodeClock {
            override fun ntpNowSeconds(): Double = 1_755_864_000.0
            override fun hostNow(): MediaTime = MediaTime.fromSeconds(100.0)
        }
        val writer = MpegTsWriter({ data, audio -> capture.blocks.add(data to audio) }, timecodeClock = clock)
        writer.startRunning()
        writer.setAudioConfig(MpegTsAudioConfig.aacLc(48000, 2))
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val hevcSps = byteArrayOf(0x42, 0x01, 0x01)
        val hevcPps = byteArrayOf(0x44, 0x01, 0xC1.toByte())
        writer.setVideoConfig(MpegTsVideoConfig.Hevc(vps, hevcSps, hevcPps))
        val idr = byteArrayOf(0, 0, 0, 1, 0x26, 0x01) + ByteArray(500) { 9 }
        // Two frames stay pending inside the writer, so a third is needed to flush the first.
        writer.writeVideo(idr, MediaTime.fromSeconds(100.0), null, true)
        writer.writeVideo(idr, MediaTime.fromSeconds(100.04), null, false)
        writer.writeVideo(idr, MediaTime.fromSeconds(100.08), null, false)
        val demuxer = TestTsDemuxer()
        demuxer.feed(capture.all)
        demuxer.finish()
        assertEquals(0x24, demuxer.streamTypes[256])
        val pes = demuxer.pesPackets.first { it.pid == 256 }
        val types = getNalUnits(pes.payload).map { (pes.payload[it.dataOffset()].toInt() ushr 1) and 0x3F }
        assertEquals(listOf(32, 33, 34, 39, 19), types)
        val seiInfo = getNalUnits(pes.payload)[3]
        val sei = HevcNalUnit.decode(pes.payload.copyOfRange(seiInfo.dataOffset(), seiInfo.dataOffset() + seiInfo.dataLength))
        // Host time 100 s == NTP noon, so the first frame's timecode is 12:00:00 frame 0.
        assertEquals(SeiTimecode(12, 0, 0, 0), (sei as HevcNalUnit.PrefixSei).timeCode)
    }

    @Test
    fun stopResetsContinuityAndPendingVideo() {
        val capture = Capture()
        val writer = MpegTsWriter({ data, audio -> capture.blocks.add(data to audio) })
        runAvStream(writer)
        writer.stopRunning()
        capture.blocks.clear()
        runAvStream(writer, startSeconds = 2000.0)
        val demuxer = TestTsDemuxer()
        demuxer.feed(capture.all)
        demuxer.finish()
        assertTrue(demuxer.continuityErrors.isEmpty(), demuxer.continuityErrors.toString())
        assertEquals((2000.0 * 90000).toLong(), demuxer.pesPackets.first { it.pid == 256 }.pts)
    }
}
