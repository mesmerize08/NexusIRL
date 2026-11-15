package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import io.github.mesmerize08.nexusirl.media.pipeline.MediaSink
import io.github.mesmerize08.nexusirl.media.pipeline.SceneSwitchTransitionKind
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEncoderSettings
import io.github.mesmerize08.nexusirl.media.pipeline.VideoPipeline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pipeline showing an [IngestSource] instead of the camera. The test package declares the
 * camera permission but only [frozenCameraImageEndsWithTheTransition] grants it, so in the other
 * tests the camera usually cannot open (and CI's emulator has none). They use the Freeze
 * transition, so a camera image that did arrive is never shown while the source has no picture,
 * and [backToCamera] checks for black only when no camera can deliver one.
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
        .also {
            it.sceneSwitchTransition = SceneSwitchTransitionKind.FREEZE
            cleanup += { it.stop() }
        }

    /** The camera can deliver a picture here: permission granted (by an earlier test) and a camera present. */
    private fun cameraUsable(): Boolean = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        context.getSystemService(CameraManager::class.java).cameraIdList.isNotEmpty()

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

    /** Average luminance (0..1) of a region of the snapshot, or 0 without one. */
    private fun Bitmap?.luminance(fromX: Int = 0, toX: Int = WIDTH, fromY: Int = 0, toY: Int = HEIGHT): Double {
        this ?: return 0.0
        var sum = 0.0
        var count = 0
        for (y in fromY until toY step 4) {
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
        first.sceneSwitchTransition = SceneSwitchTransitionKind.FREEZE
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

    /** A source whose updateVideo throws does not stop the GL thread: black, and frames go on. */
    @Test
    fun throwingSourceShowsBlack() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, WIDTH, HEIGHT, frames = 150)
        val pipeline = pipeline()
        pipeline.start()
        val sink = pipeline.encoderSink()
        val source = source()
        pipeline.showIngestSource(source)
        source.feed(stream)
        assertTrue(pipeline.awaitLuminance(LATENCY / 1_000_000 + 1500) { it > 0.2 } > 0.2)
        source.updateVideoFailureForTest = IllegalStateException("test")
        val luminance = pipeline.awaitLuminance(1000) { it < 0.05 }
        assertTrue("not black after the source threw: $luminance", luminance < 0.05)
        val frames = sink.frames.get()
        Thread.sleep(400)
        assertTrue("no frames encoded after the source threw", sink.frames.get() > frames + 5)
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
        // A round trip through the GL thread: the detach has run before the last render is read.
        pipeline.snapshot()
        if (!cameraUsable()) {
            val luminance = pipeline.awaitLuminance(1000) { it < 0.05 }
            assertTrue("not black without the source: $luminance", luminance < 0.05)
        }
        val rendered = source.lastRenderedPtsNanos
        val frames = sink.frames.get()
        Thread.sleep(600)
        assertEquals("source still driven after detaching", rendered, source.lastRenderedPtsNanos)
        // With a usable camera its frames (or black until its first) keep coming as well.
        assertTrue("no frames encoded after detaching", sink.frames.get() > frames + 5)
        assertFalse("the pipeline closed the source", source.isEnded)
        assertNull("ended", ended.peek())
    }

    /**
     * The camera had a picture, then a source that never sends data is shown: the camera's last
     * image stays for the transition (Blur: 2 s) only, then black, and the switching state ends.
     */
    @Test
    fun frozenCameraImageEndsWithTheTransition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        assumeTrue("no camera", context.getSystemService(CameraManager::class.java).cameraIdList.isNotEmpty())
        val pipeline = pipeline()
        pipeline.sceneSwitchTransition = SceneSwitchTransitionKind.BLUR
        pipeline.start()
        val deadline = System.nanoTime() + 5_000_000_000
        var cameraImage = pipeline.snapshot()
        while (cameraImage == null && System.nanoTime() < deadline) {
            Thread.sleep(100)
            cameraImage = pipeline.snapshot()
        }
        assumeTrue("the camera gave no picture: $errors", cameraImage != null)
        Log.i(TAG, "camera luminance ${cameraImage.luminance()}")
        val source = source()
        val start = System.nanoTime()
        pipeline.showIngestSource(source)
        Thread.sleep(300)
        assertTrue("no transition while the source has no picture", pipeline.isTransitionRunning)
        Log.i(TAG, "during the transition: luminance ${pipeline.snapshot().luminance()}")
        val end = start + (SceneSwitchTransitionKind.BLUR.durationSeconds * 1e9).toLong() + 200_000_000
        Thread.sleep(maxOf(0, (end - System.nanoTime()) / 1_000_000))
        val luminance = pipeline.snapshot().luminance()
        assertTrue("not black after the transition: $luminance", luminance < 0.02)
        assertFalse("still switching after the transition", pipeline.isTransitionRunning)
        assertNull("ended", ended.peek())
    }

    /** A picture with only its top-left quadrant bright arrives upright and not mirrored. */
    @Test
    fun orientationIsKept() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, WIDTH, HEIGHT, frames = 90) { x, y ->
            if (x < WIDTH / 2 && y < HEIGHT / 2) 235 else 16
        }
        val pipeline = pipeline()
        val source = source()
        pipeline.showIngestSource(source)
        pipeline.start()
        source.feed(stream)
        assertTrue(pipeline.awaitLuminance(LATENCY / 1_000_000 + 1500) { it > 0.1 } > 0.1)
        val snapshot = pipeline.snapshot()
        val margin = 16
        val topLeft = snapshot.luminance(margin, WIDTH / 2 - margin, margin, HEIGHT / 2 - margin)
        val topRight = snapshot.luminance(WIDTH / 2 + margin, WIDTH - margin, margin, HEIGHT / 2 - margin)
        val bottomLeft = snapshot.luminance(margin, WIDTH / 2 - margin, HEIGHT / 2 + margin, HEIGHT - margin)
        Log.i(TAG, "top left $topLeft, top right $topRight, bottom left $bottomLeft")
        assertTrue("top left is dark: $topLeft", topLeft > 0.7)
        assertTrue("flipped: bottom left $bottomLeft", bottomLeft < 0.15)
        assertTrue("mirrored: top right $topRight", topRight < 0.15)
    }

    private companion object {
        const val TAG = "VideoPipelineIngestTest"
        const val WIDTH = 640
        const val HEIGHT = 360
        const val LATENCY = 500_000_000L
    }
}
