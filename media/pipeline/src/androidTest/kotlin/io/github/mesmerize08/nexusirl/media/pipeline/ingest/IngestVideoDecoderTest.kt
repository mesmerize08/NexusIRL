package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import android.media.MediaCodecList
import androidx.test.ext.junit.runners.AndroidJUnit4
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
     * Queues [units] whenever the decoder takes input, and releases each decoded frame with render
     * = true, waiting until [sink] receives it, until [expected] frames are decoded. Returns them.
     */
    private fun decode(
        decoder: IngestVideoDecoder,
        units: List<IngestVideoAccessUnit>,
        sink: TextureSink,
        expected: Int = units.size,
    ): List<IngestVideoDecoder.DecodedFrame> {
        val decoded = ArrayList<IngestVideoDecoder.DecodedFrame>()
        var next = 0
        val deadline = System.nanoTime() + 30_000_000_000
        while (decoded.size < expected) {
            assertTrue("decoded ${decoded.size} of $expected", System.nanoTime() < deadline)
            assertNull("failed", recorder.failures.peek())
            if (next < units.size && decoder.canQueue()) {
                decoder.queue(units[next++])
                continue
            }
            val frame = recorder.frames.poll(5, TimeUnit.MILLISECONDS) ?: continue
            decoder.release(frame, render = true)
            assertNotNull("frame ${decoded.size} not rendered", sink.timestamps.poll(5, TimeUnit.SECONDS))
            decoded += frame
        }
        return decoded
    }

    private fun assertDecodes(stream: EncodedStream) {
        val sink = sink()
        val decoder = decoder()
        decoder.configure(stream.codec, stream.config, sink.surface)
        val decoded = decode(decoder, stream.units, sink)
        assertEquals(stream.units.map { it.ptsNanos }.sorted(), decoded.map { it.ptsNanos })
        assertEquals(0, decoder.heldFrames)
        assertTrue(recorder.threads.all { it == "ingest-video-test" })
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
        val sink = sink()
        val decoder = decoder(softwareDecoding = true)
        decoder.configure(stream.codec, stream.config, sink.surface)
        // Without an end of stream, c2.android.avc.decoder keeps the last 2 frames (low latency or
        // not); a live source keeps sending, so only the frames before them are expected here.
        val decoded = decode(decoder, stream.units, sink, expected = stream.units.size - 4)
        assertEquals(stream.units.map { it.ptsNanos }.sorted().take(decoded.size), decoded.map { it.ptsNanos })
        val name = decoder.codecName
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { it.name == name }
        assertTrue("$name is not a software decoder", info.isSoftwareOnly && !info.isEncoder)
    }

    @Test
    fun newConfigRecreates() {
        val small = TestEncoder.encode(IngestVideoCodec.H264, 640, 360, frames = 30)
        val large = TestEncoder.encode(IngestVideoCodec.H264, 1280, 720, frames = 30)
        val sink = sink()
        val decoder = decoder()
        decoder.configure(small.codec, small.config, sink.surface)
        decode(decoder, small.units.take(28), sink)
        // Two decoded frames stay held over the switch.
        decoder.queue(small.units[28])
        decoder.queue(small.units[29])
        val old = listOf(recorder.frames.poll(5, TimeUnit.SECONDS)!!, recorder.frames.poll(5, TimeUnit.SECONDS)!!)
        assertEquals(2, decoder.heldFrames)
        recorder.formats.clear()
        decoder.configure(large.codec, large.config, sink.surface)
        assertEquals(0, decoder.heldFrames)
        val decoded = decode(decoder, large.units, sink)
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
        decode(decoder, stream.units.take(20), sink)
        decoder.queue(stream.units[20])
        val held = recorder.frames.poll(5, TimeUnit.SECONDS)!!
        decoder.configure(stream.codec, stream.config.copyOf(), sink.surface)
        assertEquals(1, decoder.heldFrames)
        // Same decoder: the held frame still renders, and decoding goes on without a key frame.
        decoder.release(held, render = true)
        assertNotNull(sink.timestamps.poll(5, TimeUnit.SECONDS))
        decoder.release(held, render = true)
        assertEquals(0, decoder.heldFrames)
        decode(decoder, stream.units.drop(21), sink)
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
        val hevc = IngestVideoDecoder("test", false, recorder).also { closeables += AutoCloseable { it.close() } }
        hevc.configure(IngestVideoCodec.H265, Random(3).nextBytes(64), sink.surface)
        assertNotNull(recorder.failures.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun badAccessUnitIsDropped() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 640, 360, frames = 30)
        val sink = sink()
        val decoder = decoder()
        decoder.configure(stream.codec, stream.config, sink.surface)
        decode(decoder, stream.units.take(10), sink)
        // A NAL length past the end: dropped, the decoder goes on.
        decoder.queue(IngestVideoAccessUnit(false, 0, 0, byteArrayOf(0, 0, 1, 0, 5)))
        decode(decoder, stream.units.drop(10), sink)
        assertNull(recorder.failures.peek())
    }

    @Test
    fun surfaceSwitch() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 640, 360)
        val first = sink()
        val second = sink()
        val decoder = decoder()
        decoder.configure(stream.codec, stream.config, first.surface)
        decode(decoder, stream.units.take(30), first)
        decoder.setSurface(second.surface)
        val decoded = decode(decoder, stream.units.drop(30), second)
        assertEquals(stream.units.drop(30).map { it.ptsNanos }.sorted(), decoded.map { it.ptsNanos })
        assertNull(first.timestamps.poll(200, TimeUnit.MILLISECONDS))
        assertNull(recorder.failures.peek())
    }
}
