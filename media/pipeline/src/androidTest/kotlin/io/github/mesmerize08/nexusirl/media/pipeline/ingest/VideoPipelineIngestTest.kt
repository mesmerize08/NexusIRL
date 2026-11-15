package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import io.github.mesmerize08.nexusirl.media.pipeline.MediaSink
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEncoderSettings
import io.github.mesmerize08.nexusirl.media.pipeline.VideoPipeline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pipeline showing an [IngestSource] instead of the camera. The test package has no camera
 * permission, so the camera never delivers a picture: without the source the canvas is black on
 * every device (CI's emulator has no camera at all).
 */
@RunWith(AndroidJUnit4::class)
class VideoPipelineIngestTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = VideoEncoderSettings(WIDTH, HEIGHT, 30, 1_000_000)
    private val ended = LinkedBlockingQueue<String>()
    private val errors = CopyOnWriteArrayList<String>()
    private val cleanup = ArrayList<() -> Unit>()

    @After
    fun tearDown() {
        cleanup.asReversed().forEach { it() }
        assertEquals("a source is still counted as decoding", 0, IngestSource.decodingSources)
    }

    private fun source(): IngestSource {
        val source = IngestSource("test", LATENCY, softwareDecoding = false, outputChannels = 2, onEnded = { ended.add(it) })
        cleanup += { source.close() }
        return source
    }

    private fun pipeline(): VideoPipeline = VideoPipeline(context, settings, onError = { errors.add(it) })
        .also { cleanup += { it.stop() } }

    /** Counts encoded frames. */
    private class CountingSink : MediaSink {
        val frames = AtomicInteger()

        override fun setAudioConfig(config: MpegTsAudioConfig, sampleRate: Int) {}

        override fun setVideoConfig(config: MpegTsVideoConfig) {}

        override fun writeAudio(frame: ByteArray, presentationTimeStamp: MediaTime) {}

        override fun writeVideo(accessUnit: ByteArray, presentationTimeStamp: MediaTime, decodeTimeStamp: MediaTime?, isSync: Boolean) {
            frames.incrementAndGet()
        }
    }

    private fun VideoPipeline.encoderSink(): CountingSink {
        val sink = CountingSink()
        val encoder = checkNotNull(startEncoder(settings, sink)) { "no encoder" }
        cleanup += { stopEncoder(encoder) }
        return sink
    }

    /** The stream's units moved so the first is due [LATENCY] from now, as the RTMP server stamps them. */
    private fun IngestSource.feed(stream: EncodedStream) {
        setVideoConfig(stream.codec, stream.config)
        val shift = System.nanoTime() + LATENCY - stream.units.first().ptsNanos
        stream.units.forEach { addVideo(it.isKeyFrame, it.ptsNanos + shift, it.dtsNanos + shift, it.data) }
    }

    private fun VideoPipeline.snapshot(): Bitmap? {
        val done = CountDownLatch(1)
        var result: Bitmap? = null
        takeSnapshot {
            result = it
            done.countDown()
        }
        assertTrue("no snapshot", done.await(3, TimeUnit.SECONDS))
        return result
    }

    /** Average luminance (0..1) of a column range of the snapshot, or 0 without one. */
    private fun Bitmap?.luminance(fromX: Int = 0, toX: Int = WIDTH): Double {
        this ?: return 0.0
        var sum = 0.0
        var count = 0
        for (y in 0 until height step 4) {
            for (x in fromX until toX step 4) {
                val pixel = getPixel(x, y)
                sum += (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)) / 255
                count++
            }
        }
        return sum / count
    }

    /** Waits up to [timeoutMillis] for a snapshot whose luminance passes [test]; returns the last luminance. */
    private fun VideoPipeline.awaitLuminance(timeoutMillis: Long, test: (Double) -> Boolean): Double {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        var luminance: Double
        do {
            luminance = snapshot().luminance()
            if (test(luminance)) return luminance
            Thread.sleep(50)
        } while (System.nanoTime() < deadline)
        return luminance
    }

    @Test
    fun ingestPictureReachesTheEncoder() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, WIDTH, HEIGHT, frames = 90)
        val pipeline = pipeline()
        pipeline.start()
        val sink = pipeline.encoderSink()
        val source = source()
        pipeline.showIngestSource(source)
        source.feed(stream)
        Thread.sleep(LATENCY / 1_000_000 + 1000)
        val luminance = pipeline.snapshot().luminance()
        Log.i(TAG, "luminance $luminance, ${sink.frames.get()} frames encoded")
        assertTrue("picture is black: $luminance", luminance > 0.2)
        assertTrue("source rendered nothing", source.hasVideo)
        assertTrue("only ${sink.frames.get()} frames encoded", sink.frames.get() >= 20)
        assertNull("ended", ended.peek())
        assertFalse("the pipeline closed the source", source.isEnded)
    }

    @Test
    fun blackUntilFirstFrame() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, WIDTH, HEIGHT, frames = 90)
        val pipeline = pipeline()
        pipeline.start()
        val sink = pipeline.encoderSink()
        val source = source()
        source.setVideoConfig(stream.codec, stream.config)
        pipeline.showIngestSource(source)
        Thread.sleep(700)
        val before = pipeline.snapshot()
        Log.i(TAG, "before data: luminance ${before.luminance()}, ${sink.frames.get()} frames encoded")
        assertTrue("not black before the first frame: ${before.luminance()}", before.luminance() < 0.05)
        assertTrue("black frames not encoded: ${sink.frames.get()}", sink.frames.get() >= 5)
        source.feed(stream)
        val after = pipeline.awaitLuminance(LATENCY / 1_000_000 + 1500) { it > 0.2 }
        assertTrue("no picture after the first frame: $after", after > 0.2)
        assertNull("ended", ended.peek())
    }

    /** A 4:3 stream on a 16:9 canvas: black bars left and right, the picture in the middle. */
    @Test
    fun aspectFitPillarboxes() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 480, 360, frames = 90)
        val pipeline = pipeline()
        pipeline.start()
        val source = source()
        pipeline.showIngestSource(source)
        source.feed(stream)
        assertTrue(pipeline.awaitLuminance(LATENCY / 1_000_000 + 1500) { it > 0.1 } > 0.1)
        val snapshot = pipeline.snapshot()
        // 480x360 fitted into 640x360 leaves 80 px each side.
        val left = snapshot.luminance(0, 72)
        val right = snapshot.luminance(WIDTH - 72, WIDTH)
        val middle = snapshot.luminance(96, WIDTH - 96)
        Log.i(TAG, "left $left middle $middle right $right")
        assertTrue("left bar not black: $left", left < 0.02)
        assertTrue("right bar not black: $right", right < 0.02)
        assertTrue("middle is black: $middle", middle > 0.2)
    }

    /** Spec §4: a new pipeline given the same source goes on showing it; the decoder is not restarted. */
    @Test
    fun pipelineRecreationKeepsTheSource() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, WIDTH, HEIGHT, frames = 150)
        val first = VideoPipeline(context, settings, onError = { errors.add(it) })
        var firstStopped = false
        cleanup += { if (!firstStopped) first.stop() }
        first.start()
        val source = source()
        first.showIngestSource(source)
        source.feed(stream)
        assertTrue(first.awaitLuminance(LATENCY / 1_000_000 + 1500) { it > 0.2 } > 0.2)
        first.stop()
        firstStopped = true
        val renderedAtStop = source.lastRenderedPtsNanos
        // The decoder goes on decoding what it was given meanwhile (into the source's own surface).
        Thread.sleep(500)
        assertEquals("rendered without a pipeline", renderedAtStop, source.lastRenderedPtsNanos)
        errors.clear()
        val second = pipeline()
        // Shown before start: the camera is never opened.
        second.showIngestSource(source)
        second.start()
        val sink = second.encoderSink()
        val start = System.nanoTime()
        val luminance = second.awaitLuminance(1000) { it > 0.2 && source.lastRenderedPtsNanos > renderedAtStop }
        Log.i(TAG, "second pipeline: luminance $luminance after ${(System.nanoTime() - start) / 1_000_000} ms")
        assertTrue("no picture in the second pipeline: $luminance", luminance > 0.2)
        assertTrue("source not rendering into the second pipeline", source.lastRenderedPtsNanos > renderedAtStop)
        Thread.sleep(300)
        assertTrue("only ${sink.frames.get()} frames encoded", sink.frames.get() >= 5)
        assertNull("ended", ended.peek())
        assertTrue("camera opened while showing the source: $errors", errors.isEmpty())
    }

    /** A source that ends (here: closed while shown) leaves black, not a frozen picture; frames go on. */
    @Test
    fun endedSourceShowsBlack() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, WIDTH, HEIGHT, frames = 150)
        val pipeline = pipeline()
        pipeline.start()
        val sink = pipeline.encoderSink()
        val source = source()
        pipeline.showIngestSource(source)
        source.feed(stream)
        assertTrue(pipeline.awaitLuminance(LATENCY / 1_000_000 + 1500) { it > 0.2 } > 0.2)
        source.close()
        val luminance = pipeline.awaitLuminance(1000) { it < 0.05 }
        assertTrue("not black after the source ended: $luminance", luminance < 0.05)
        val frames = sink.frames.get()
        Thread.sleep(400)
        assertTrue("no frames encoded after the source ended", sink.frames.get() > frames + 5)
    }

    /** Back to the camera, which cannot open here (no permission, or none on CI): black, no crash. */
    @Test
    fun backToCamera() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, WIDTH, HEIGHT, frames = 150)
        val pipeline = pipeline()
        pipeline.start()
        val sink = pipeline.encoderSink()
        val source = source()
        pipeline.showIngestSource(source)
        source.feed(stream)
        assertTrue(pipeline.awaitLuminance(LATENCY / 1_000_000 + 1500) { it > 0.2 } > 0.2)
        pipeline.showIngestSource(null, cameraIdWhenNull = null)
        val luminance = pipeline.awaitLuminance(1000) { it < 0.05 }
        assertTrue("not black without the source: $luminance", luminance < 0.05)
        val rendered = source.lastRenderedPtsNanos
        val frames = sink.frames.get()
        Thread.sleep(600)
        assertEquals("source still driven after detaching", rendered, source.lastRenderedPtsNanos)
        assertTrue("no black frames encoded after detaching", sink.frames.get() > frames + 5)
        assertFalse("the pipeline closed the source", source.isEnded)
        assertNull("ended", ended.peek())
    }

    private companion object {
        const val TAG = "VideoPipelineIngestTest"
        const val WIDTH = 640
        const val HEIGHT = 360
        const val LATENCY = 500_000_000L
    }
}
