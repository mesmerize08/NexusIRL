package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import android.media.MediaCodecList
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mesmerize08.nexusirl.media.mux.nal.NalUnitWriter
import io.github.mesmerize08.nexusirl.media.mux.nal.writeRbspTrailingBits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class IngestVideoDecoderTest {
    private class Recorder : IngestVideoDecoder.Listener {
        val frames = LinkedBlockingQueue<IngestVideoDecoder.DecodedFrame>()
        val formats = LinkedBlockingQueue<Pair<Int, Int>>()
        val failures = LinkedBlockingQueue<String>()
        val threads = LinkedBlockingQueue<String>()

        override fun onFrame(frame: IngestVideoDecoder.DecodedFrame) {
            threads.add(Thread.currentThread().name)
            frames.add(frame)
        }

        override fun onFormat(width: Int, height: Int) {
            threads.add(Thread.currentThread().name)
            formats.add(width to height)
        }

        override fun onFailed(reason: String) {
            threads.add(Thread.currentThread().name)
            failures.add(reason)
        }
    }

    private val recorder = Recorder()
    private val closeables = ArrayList<AutoCloseable>()

    private fun sink(): TextureSink = TextureSink().also { closeables += it }

    private fun decoder(softwareDecoding: Boolean = false): IngestVideoDecoder =
        IngestVideoDecoder("test", softwareDecoding, recorder).also { closeables += AutoCloseable { it.close() } }

    @After
    fun tearDown() {
        closeables.asReversed().forEach { it.close() }
    }

    /**
     * Feeds [stream] to [decoder] in order, as a live publisher does. A decoder may keep the last
     * frames until more input arrives (c2.android.* keeps 2 without an end of stream), so when no
     * frame comes for a while the feeder queues the next units anyway, and after the stream it
     * repeats it from its first key frame, each lap 100 s later. Frames decoded ahead of what a
     * test waits for are kept for the next [await]; repeated frames are dropped.
     */
    private inner class Feeder(private val decoder: IngestVideoDecoder, stream: EncodedStream) {
        private val units = stream.units
        private val streamPts = units.map { it.ptsNanos }.toSet()
        private val ahead = HashMap<Long, IngestVideoDecoder.DecodedFrame>()
        private var next = 0

        private fun unitAt(k: Int): IngestVideoAccessUnit {
            val unit = units[k % units.size]
            val shift = (k / units.size) * 100_000_000_000L
            return if (shift ==
                0L
            ) {
                unit
            } else {
                IngestVideoAccessUnit(unit.isKeyFrame, unit.ptsNanos + shift, unit.dtsNanos + shift, unit.data)
            }
        }

        /** Sorts a decoded frame that is not wanted now: kept if it comes later in the stream, else dropped. */
        private fun putAside(frame: IngestVideoDecoder.DecodedFrame, latestWanted: Long) {
            if (frame.ptsNanos in streamPts && frame.ptsNanos > latestWanted) {
                ahead[frame.ptsNanos] = frame
            } else {
                decoder.release(frame, render = false)
            }
        }

        /**
         * Returns the frames of [wanted] (units of the stream, in order) in output order. With a
         * [sink], each is released with render = true and must reach the sink; without, they stay held.
         */
        fun await(wanted: List<IngestVideoAccessUnit>, sink: TextureSink?): List<IngestVideoDecoder.DecodedFrame> {
            val pending = wanted.map { it.ptsNanos }.toMutableSet()
            val latestWanted = pending.max()
            val last = units.indexOf(wanted.last())
            val decoded = ArrayList<IngestVideoDecoder.DecodedFrame>()
            fun take(frame: IngestVideoDecoder.DecodedFrame) {
                if (sink != null) {
                    decoder.release(frame, render = true)
                    assertNotNull("frame ${decoded.size} not rendered", sink.timestamps.poll(5, TimeUnit.SECONDS))
                }
                decoded += frame
                pending -= frame.ptsNanos
            }
            val deadline = System.nanoTime() + 30_000_000_000
            var idleSince = System.nanoTime()
            while (pending.isNotEmpty()) {
                assertTrue("decoded ${decoded.size} of ${wanted.size}", System.nanoTime() < deadline)
                assertNull("failed", recorder.failures.peek())
                ahead.keys.filter { it in pending }.sorted().forEach { take(ahead.remove(it)!!) }
                if (pending.isEmpty()) break
                if (next <= last && decoder.canQueue()) {
                    decoder.queue(unitAt(next++))
                    continue
                }
                val frame = recorder.frames.poll(5, TimeUnit.MILLISECONDS)
                if (frame == null) {
                    if (System.nanoTime() - idleSince > 50_000_000 && decoder.canQueue()) {
                        decoder.queue(unitAt(next++))
                        idleSince = System.nanoTime()
                    }
                    continue
                }
                idleSince = System.nanoTime()
                if (frame.ptsNanos in pending) take(frame) else putAside(frame, latestWanted)
            }
            return decoded
        }

        /** Queues a unit that is not part of the stream, once the decoder takes input. */
        fun queueExtra(unit: IngestVideoAccessUnit) {
            val deadline = System.nanoTime() + 10_000_000_000
            while (!decoder.canQueue()) {
                assertTrue("no input buffer", System.nanoTime() < deadline)
                recorder.frames.poll(5, TimeUnit.MILLISECONDS)?.let { putAside(it, Long.MIN_VALUE) }
            }
            decoder.queue(unit)
        }
    }

    private fun assertDecodes(stream: EncodedStream, softwareDecoding: Boolean = false): IngestVideoDecoder {
        val sink = sink()
        val decoder = decoder(softwareDecoding)
        decoder.configure(stream.codec, stream.config, sink.surface)
        val decoded = Feeder(decoder, stream).await(stream.units, sink)
        assertEquals(stream.units.map { it.ptsNanos }.sorted(), decoded.map { it.ptsNanos })
        assertTrue(recorder.threads.all { it == "ingest-video-test" })
        return decoder
    }

    @Test
    fun decodesH264IntoSurface() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 640, 360)
        assertEquals(60, stream.units.size)
        assertDecodes(stream)
        assertEquals(640 to 360, recorder.formats.poll())
    }

    @Test
    fun decodesHevc() {
        assumeTrue("no HEVC encoder", TestEncoder.has(IngestVideoCodec.H265))
        val stream = TestEncoder.encode(IngestVideoCodec.H265, 640, 360)
        assertEquals(60, stream.units.size)
        assertDecodes(stream)
        assertEquals(640 to 360, recorder.formats.poll())
    }

    @Test
    fun softwareDecoderWhenAsked() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 640, 360)
        val name = assertDecodes(stream, softwareDecoding = true).codecName
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { it.name == name }
        assertTrue("$name is not a software decoder", info.isSoftwareOnly && !info.isEncoder)
    }

    @Test
    fun newConfigRecreates() {
        val small = TestEncoder.encode(IngestVideoCodec.H264, 640, 360, frames = 30)
        val large = TestEncoder.encode(IngestVideoCodec.H264, 1280, 720, frames = 30, startMicros = 50_000_000)
        val sink = sink()
        val decoder = decoder()
        decoder.configure(small.codec, small.config, sink.surface)
        val smallFeeder = Feeder(decoder, small)
        smallFeeder.await(small.units.take(28), sink)
        // Two decoded frames stay held over the switch.
        val old = smallFeeder.await(small.units.drop(28), sink = null)
        assertTrue(decoder.heldFrames >= 2)
        recorder.formats.clear()
        decoder.configure(large.codec, large.config, sink.surface)
        assertEquals(0, decoder.heldFrames)
        val decoded = Feeder(decoder, large).await(large.units, sink)
        assertEquals(large.units.map { it.ptsNanos }.sorted(), decoded.map { it.ptsNanos })
        assertEquals(1280 to 720, recorder.formats.poll())
        decoder.release(old[0], render = true)
        decoder.release(old[1], render = false)
        assertNull(sink.timestamps.poll(200, TimeUnit.MILLISECONDS))
        assertNull(recorder.failures.peek())
    }

    @Test
    fun sameConfigIsIgnored() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 640, 360, frames = 30)
        val sink = sink()
        val decoder = decoder()
        decoder.configure(stream.codec, stream.config, sink.surface)
        val feeder = Feeder(decoder, stream)
        feeder.await(stream.units.take(20), sink)
        val held = feeder.await(listOf(stream.units[20]), sink = null).single()
        decoder.configure(stream.codec, stream.config.copyOf(), sink.surface)
        // Same decoder: the held frame still renders (once), and decoding goes on without a key frame.
        decoder.release(held, render = true)
        assertEquals(stream.units[20].ptsNanos / 1000, sink.timestamps.poll(5, TimeUnit.SECONDS)!! / 1000)
        decoder.release(held, render = true)
        assertNull(sink.timestamps.poll(200, TimeUnit.MILLISECONDS))
        feeder.await(stream.units.drop(21), sink)
        assertNull(recorder.failures.peek())
    }

    @Test
    fun badDataFails() {
        val sink = sink()
        val decoder = decoder()
        decoder.configure(IngestVideoCodec.H264, Random(1).nextBytes(64), sink.surface)
        assertNotNull(recorder.failures.poll(5, TimeUnit.SECONDS))
        assertTrue(!decoder.canQueue())
        decoder.queue(IngestVideoAccessUnit(true, 0, 0, Random(2).nextBytes(100)))
        decoder.close()
        val hevc = decoder()
        hevc.configure(IngestVideoCodec.H265, Random(3).nextBytes(64), sink.surface)
        assertNotNull(recorder.failures.poll(5, TimeUnit.SECONDS))
    }

    /** A baseline SPS (id 0) for a [widthMbs]×[heightMbs] macroblock picture at level 6.2. */
    private fun sps(widthMbs: Int, heightMbs: Int): ByteArray {
        val writer = NalUnitWriter()
        fun ue(value: Int) {
            val bits = 32 - Integer.numberOfLeadingZeros(value + 1)
            writer.writeBits(0, bits - 1)
            writer.writeBits(value + 1, bits)
        }
        writer.writeBits(0x67, 8)
        writer.writeBits(66, 8) // profile_idc: baseline
        writer.writeBits(0xC0, 8) // constraint flags
        writer.writeBits(62, 8) // level_idc
        ue(0) // seq_parameter_set_id
        ue(0) // log2_max_frame_num_minus4
        ue(2) // pic_order_cnt_type
        ue(1) // max_num_ref_frames
        writer.writeBit(false) // gaps_in_frame_num_value_allowed_flag
        ue(widthMbs - 1)
        ue(heightMbs - 1)
        writer.writeBit(true) // frame_mbs_only_flag
        writer.writeBit(true) // direct_8x8_inference_flag
        writer.writeBit(false) // frame_cropping_flag
        writer.writeBit(false) // vui_parameters_present_flag
        writeRbspTrailingBits(writer)
        return writer.data
    }

    /** Length-prefixed (4-byte) H.264 data without its SPS and PPS NAL units. */
    private fun withoutParameterSets(data: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        var index = 0
        while (index + 4 <= data.size) {
            val length = java.nio.ByteBuffer.wrap(data, index, 4).int
            val type = data[index + 4].toInt() and 0x1F
            if (type != 7 && type != 8) output.write(data, index, 4 + length)
            index += 4 + length
        }
        return output.toByteArray()
    }

    @Test
    fun oversizedStreamFails() {
        // A well-formed avcC for 7680×4320: only the codec can refuse it. The software decoder does so
        // with a CodecException everywhere (S26: c2.qti.avc.decoder reports the size instead, which
        // the size check refuses; the emulator's goldfish decoder accepts any parameter sets, and so
        // do all three with junk SPS bytes, so neither is a reliable test).
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 640, 360, frames = 30)
        val config = TestEncoder.avcC(sps = sps(480, 270), pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte()))
        val sink = sink()
        val decoder = decoder(softwareDecoding = true)
        decoder.configure(IngestVideoCodec.H264, config, sink.surface)
        // Some codecs only complain once data arrives: keep feeding the real stream's slices,
        // without the in-band parameter sets some encoders add (a decoder would use those instead).
        val units = stream.units.map { IngestVideoAccessUnit(it.isKeyFrame, it.ptsNanos, it.dtsNanos, withoutParameterSets(it.data)) }
        val deadline = System.nanoTime() + 15_000_000_000
        var next = 0
        var frames = 0
        while (recorder.failures.isEmpty()) {
            assertTrue(
                "no failure; decoder ${decoder.codecName}, $frames frames, formats ${recorder.formats}",
                System.nanoTime() < deadline,
            )
            if (decoder.canQueue()) decoder.queue(units[next++ % units.size])
            recorder.frames.poll(5, TimeUnit.MILLISECONDS)?.let {
                frames++
                decoder.release(it, render = false)
            }
        }
        Log.i("IngestVideoDecoderTest", "oversized: ${recorder.failures.peek()}")
        assertTrue(!decoder.canQueue())
    }

    @Test
    fun badAccessUnitIsDropped() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 640, 360, frames = 30)
        val sink = sink()
        val decoder = decoder()
        decoder.configure(stream.codec, stream.config, sink.surface)
        val feeder = Feeder(decoder, stream)
        feeder.await(stream.units.take(10), sink)
        // A NAL length past the end: dropped, the decoder goes on.
        feeder.queueExtra(IngestVideoAccessUnit(false, 0, 0, byteArrayOf(0, 0, 1, 0, 5)))
        feeder.await(stream.units.drop(10), sink)
        assertNull(recorder.failures.peek())
    }

    @Test
    fun surfaceSwitch() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 640, 360)
        val first = sink()
        val second = sink()
        val decoder = decoder()
        decoder.configure(stream.codec, stream.config, first.surface)
        val feeder = Feeder(decoder, stream)
        feeder.await(stream.units.take(30), first)
        decoder.setSurface(second.surface)
        val decoded = feeder.await(stream.units.drop(30), second)
        assertEquals(stream.units.drop(30).map { it.ptsNanos }.sorted(), decoded.map { it.ptsNanos })
        assertNull(first.timestamps.poll(200, TimeUnit.MILLISECONDS))
        assertNull(recorder.failures.peek())
    }
}
