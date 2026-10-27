// Video path from camera to encoder, modelled on upstream Moblin/Media/HaishinKit/Media/Video/
// VideoUnit.swift @97e26d2 for built-in cameras: render on each camera frame, a gap filler that
// re-emits the last frame (PTS advanced) after 50 ms without input, black frames when no source
// is available, and a monotonic PTS guard.
//
// The pipeline runs whenever the camera is needed (preview while the app is open, or streaming);
// encoders are attached while live or recording (startEncoder/stopEncoder), one per consumer.
package io.github.mesmerize08.nexusirl.media.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import io.github.mesmerize08.nexusirl.media.pipeline.gl.EffectRenderer
import io.github.mesmerize08.nexusirl.media.pipeline.gl.EglCore
import io.github.mesmerize08.nexusirl.media.pipeline.gl.Framebuffer
import io.github.mesmerize08.nexusirl.media.pipeline.gl.GlPrograms
import io.github.mesmerize08.nexusirl.media.pipeline.gl.OesRectRenderer
import io.github.mesmerize08.nexusirl.media.pipeline.gl.OesRenderer
import io.github.mesmerize08.nexusirl.media.pipeline.gl.TextureRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * A widget image placed on the canvas (pixels, top-left origin). A null [bitmap] keeps the image
 * already uploaded for [id] and only moves it. The pipeline takes ownership of bitmaps it is given.
 */
class Overlay(val id: String, val bitmap: Bitmap?, val x: Float, val y: Float, val width: Float, val height: Float)

class VideoPipeline(
    private val context: Context,
    /** Canvas size and frame rate. An attached encoder must use the same size and rate. */
    val settings: VideoEncoderSettings,
    private val onError: (String) -> Unit = {},
    /** A camera delivered its first image after starting: earlier camera errors are over (GL thread). */
    private val onCameraRunning: () -> Unit = {},
) {
    private val glThread = HandlerThread("video-gl").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private var egl: EglCore? = null
    private var renderer: OesRenderer? = null
    private var textureRenderer: TextureRenderer? = null

    /** The composited canvas (camera + widgets), copied to every output (GL thread only). */
    private var canvas: Framebuffer? = null

    /** Second canvas buffer: effects render from one into the other (GL thread only). */
    private var scratch: Framebuffer? = null

    /** The buffer holding the finished frame, for outputs and snapshots (GL thread only). */
    private var composed: Framebuffer? = null

    /** Small buffer that scaled-down snapshots are drawn into (GL thread only). */
    private var snapshotTarget: Framebuffer? = null

    /** Turns read-back pixels into bitmaps and runs snapshot callbacks, off the GL thread. */
    private val snapshotExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "video-snapshot") }
    private var effectRenderer: EffectRenderer? = null

    /** Global effects to apply to the camera image, before widgets (upstream order). */
    @Volatile
    var effects: Set<VideoEffect> = emptySet()

    @Volatile
    var effectSettings = EffectSettings()

    /** A LUT as an RGBA strip ([dimension]^2 x [dimension]), see `Luts.toStrip`. */
    class LutStrip(val id: String, val dimension: Int, val rgba: ByteArray)

    private class LutTexture(val id: String, val textureId: Int, val dimension: Int)

    /** Enabled LUTs in order, applied first like upstream (GL thread only). */
    private var luts: List<LutTexture> = emptyList()

    /** Replaces the LUTs applied to the camera image. Strips with an id already uploaded are reused. */
    fun setLuts(strips: List<LutStrip>) {
        glHandler.post {
            if (egl == null) {
                return@post
            }
            egl?.makeNothingCurrent()
            val existing = luts.associateBy { it.id }
            val updated = strips.map { strip ->
                existing[strip.id]?.takeIf { it.dimension == strip.dimension } ?: run {
                    val textureId = GlPrograms.createTexture()
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                    val buffer = ByteBuffer.allocateDirect(strip.rgba.size).order(ByteOrder.nativeOrder()).put(strip.rgba)
                    buffer.position(0)
                    val n = strip.dimension
                    val format = GLES20.GL_RGBA
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, n * n, n, 0, format, GLES20.GL_UNSIGNED_BYTE, buffer)
                    LutTexture(strip.id, textureId, n)
                }
            }
            val keep = updated.map { it.textureId }.toSet()
            luts.filter { it.textureId !in keep }.forEach { GLES20.glDeleteTextures(1, intArrayOf(it.textureId), 0) }
            luts = updated
        }
    }

    /**
     * Upstream scene switch transitions: while a new camera starts, the last frame is shown
     * (blurred, frozen, or blurred and zooming in) until the new camera's first frame arrives.
     */
    @Volatile
    var sceneSwitchTransition = SceneSwitchTransitionKind.BLUR

    // GL thread only.
    private var transitionStartNanos: Long? = null

    private class OverlayTexture(val textureId: Int, var x: Float, var y: Float, var width: Float, var height: Float)

    private val overlays = LinkedHashMap<String, OverlayTexture>()

    /** A live overlay fed through a Surface (browser widgets), drawn from an OES texture. */
    private class SurfaceOverlay(val textureId: Int, val surfaceTexture: SurfaceTexture, val surface: Surface) {
        val matrix = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

        @Volatile
        var frameAvailable = false
    }

    private val surfaceOverlays = HashMap<String, SurfaceOverlay>()

    /** Ids and rectangles to draw, in scene order (bitmap and surface overlays mixed). */
    private var drawOrder: List<Overlay> = emptyList()
    private var oesRectRenderer: OesRectRenderer? = null
    private var surfaceTexture: SurfaceTexture? = null

    /** Attached encoders and their EGL surfaces (GL thread only). */
    private val encoders = mutableListOf<Pair<VideoEncoder, EGLSurface>>()
    private var previewSurface: EGLSurface? = null
    private var cameraSource: CameraSource? = null
    private var sourceAspect = settings.width.toFloat() / settings.height
    private val textureMatrix = FloatArray(16)
    private val drawMatrix = FloatArray(16)
    private var cameraAspect = sourceAspect

    /** Display rotation in degrees (0/90/180/270), from the UI; content is rotated to stay upright. */
    @Volatile
    var displayRotationDegrees = 0
    private var hasFrame = false
    private var lastPresentationNanos = 0L
    private var lastCaptureNanos = 0L

    /** How long camera frames take to arrive after capture, smoothed (GL thread only). */
    private var cameraLatencyNanos = 0L
    private var nextTickNanos = 0L
    private var running = false

    /** Frames rendered per second (output side), for the status line. */
    @Volatile
    var outputFps = 0
        private set
    private var framesThisSecond = 0
    private var fpsWindowStart = 0L

    private val frameIntervalNanos = 1_000_000_000L / settings.fps

    /** Output times on an exact frame grid in the camera's capture-time domain (upstream frame timer). */
    private val frameClock = FrameClock(frameIntervalNanos)

    private companion object {
        const val TAG = "VideoPipeline"
        const val BLACK_FRAME_LAG_NANOS = 250_000_000L
    }

    /** Camera in use, for the UI's camera controls. */
    val camera: CameraInfo?
        get() = cameraSource?.current

    val zoom: Float
        get() = cameraSource?.zoom ?: 1f

    val torch: Boolean
        get() = cameraSource?.torch ?: false

    /** Starts the camera and the preview path. [cameraId] null picks the first back camera. */
    fun start(cameraId: String? = null) {
        glHandler.post {
            val egl = EglCore()
            this.egl = egl
            val renderer = OesRenderer()
            this.renderer = renderer
            textureRenderer = TextureRenderer()
            oesRectRenderer = OesRectRenderer()
            canvas = Framebuffer(settings.width, settings.height)
            scratch = Framebuffer(settings.width, settings.height)
            effectRenderer = EffectRenderer()
            val texture = SurfaceTexture(renderer.textureId)
            surfaceTexture = texture
            texture.setOnFrameAvailableListener({ handleFrameAvailable() }, glHandler)
            running = true
            startCamera(texture, cameraId)
            nextTickNanos = System.nanoTime()
            scheduleFrameTimer()
        }
    }

    /**
     * Attaches an encoder (going live, or recording) and returns it. [encoderSettings] must match
     * [settings] in size and rate; codec and bitrate may differ per encoder.
     */
    fun startEncoder(encoderSettings: VideoEncoderSettings, sink: MediaSink): VideoEncoder? {
        require(
            encoderSettings.width == settings.width && encoderSettings.height == settings.height &&
                encoderSettings.fps == settings.fps,
        ) { "Encoder settings do not match the pipeline" }
        var created: VideoEncoder? = null
        runOnGlThread {
            val egl = egl ?: return@runOnGlThread
            // An unsupported size, profile or bitrate makes MediaCodec throw; on this thread that
            // would take the whole app down.
            val encoder = try {
                VideoEncoder(encoderSettings, sink)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Video encoder failed: $e")
                return@runOnGlThread
            }
            encoders += encoder to egl.createWindowSurface(encoder.inputSurface)
            created = encoder
        }
        return created
    }

    /** Detaches and releases [encoder]; the preview and other encoders keep running. */
    fun stopEncoder(encoder: VideoEncoder) {
        runOnGlThread {
            val entry = encoders.firstOrNull { it.first === encoder } ?: return@runOnGlThread
            encoders.remove(entry)
            egl?.makeNothingCurrent()
            egl?.releaseSurface(entry.second)
            encoder.release()
        }
    }

    /**
     * Renders the current frame into a bitmap (upstream `takeSnapshot` with age 0), at canvas size
     * or, with [maxSide], scaled so its long side is at most that many pixels. [onDone] runs on a
     * background thread, with null when there is no frame yet.
     *
     * The GL thread only scales (on the GPU) and reads back; building the bitmap happens elsewhere.
     * A full-size read back plus bitmap work on the GL thread took longer than a frame, so remote
     * control previews (one a second) dropped a stream frame each time.
     */
    fun takeSnapshot(maxSide: Int = 0, onDone: (Bitmap?) -> Unit) {
        glHandler.post {
            val egl = egl
            val canvas = composed
            val textureRenderer = textureRenderer
            if (egl == null || canvas == null || textureRenderer == null || !hasFrame) {
                snapshotExecutor.execute { onDone(null) }
                return@post
            }
            val scale = if (maxSide > 0) minOf(1f, maxSide.toFloat() / maxOf(canvas.width, canvas.height)) else 1f
            val width = maxOf(1, (canvas.width * scale).toInt())
            val height = maxOf(1, (canvas.height * scale).toInt())
            val pixels = try {
                egl.makeNothingCurrent()
                val source = if (scale < 1f) {
                    val target = snapshotTarget?.takeIf { it.width == width && it.height == height }
                        ?: Framebuffer(width, height).also {
                            snapshotTarget?.release()
                            snapshotTarget = it
                        }
                    target.bind()
                    textureRenderer.drawFill(canvas.textureId, width, height, canvas.width.toFloat() / canvas.height)
                    target
                } else {
                    canvas
                }
                source.bind()
                ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder()).also {
                    GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, it)
                }
            } catch (e: Exception) {
                null
            } finally {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            }
            snapshotExecutor.execute { onDone(pixels?.let { toBitmap(it, width, height) }) }
        }
    }

    private fun toBitmap(pixels: ByteBuffer, width: Int, height: Int): Bitmap? = try {
        val upsideDown = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        upsideDown.copyPixelsFromBuffer(pixels)
        // GL rows start at the bottom.
        val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
        Bitmap.createBitmap(upsideDown, 0, 0, width, height, flip, false).also { upsideDown.recycle() }
    } catch (e: Exception) {
        null
    }

    /**
     * Replaces the widget images drawn over the camera (upstream: the scene's effects). Images
     * missing from [list] are removed. Drawn in list order, so later ones are on top.
     */
    fun setOverlays(list: List<Overlay>) {
        glHandler.post {
            if (egl == null) {
                list.forEach { it.bitmap?.recycle() }
                return@post
            }
            egl?.makeNothingCurrent()
            val keep = list.map { it.id }.toSet()
            overlays.keys.filter { it !in keep }.forEach { id ->
                overlays.remove(id)?.let { GLES20.glDeleteTextures(1, intArrayOf(it.textureId), 0) }
            }
            val ordered = LinkedHashMap<String, OverlayTexture>()
            for (overlay in list) {
                var texture = overlays[overlay.id]
                if (overlay.bitmap != null) {
                    if (texture == null) {
                        texture = OverlayTexture(GlPrograms.createTexture(), 0f, 0f, 0f, 0f)
                    }
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.textureId)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlay.bitmap, 0)
                    overlay.bitmap.recycle()
                }
                if (texture == null) {
                    continue
                }
                texture.x = overlay.x
                texture.y = overlay.y
                texture.width = overlay.width
                texture.height = overlay.height
                ordered[overlay.id] = texture
            }
            overlays.clear()
            overlays.putAll(ordered)
            drawOrder = list.map { Overlay(it.id, null, it.x, it.y, it.width, it.height) }
        }
    }

    /**
     * Creates a [width]x[height] surface whose content is drawn as overlay [id] wherever
     * [setOverlays] places it (browser widgets render into it through a virtual display).
     * [onReady] runs on the GL thread.
     */
    fun createSurfaceOverlay(id: String, width: Int, height: Int, onReady: (Surface) -> Unit) {
        glHandler.post {
            val oes = oesRectRenderer ?: return@post
            egl?.makeNothingCurrent()
            surfaceOverlays.remove(id)?.let { releaseSurfaceOverlay(it) }
            val textureId = oes.createTexture()
            val surfaceTexture = SurfaceTexture(textureId).apply { setDefaultBufferSize(width, height) }
            val overlay = SurfaceOverlay(textureId, surfaceTexture, Surface(surfaceTexture))
            surfaceTexture.setOnFrameAvailableListener({ overlay.frameAvailable = true }, glHandler)
            surfaceOverlays[id] = overlay
            onReady(overlay.surface)
        }
    }

    fun releaseSurfaceOverlay(id: String) {
        glHandler.post {
            egl?.makeNothingCurrent()
            surfaceOverlays.remove(id)?.let { releaseSurfaceOverlay(it) }
        }
    }

    private fun releaseSurfaceOverlay(overlay: SurfaceOverlay) {
        overlay.surface.release()
        overlay.surfaceTexture.release()
        GLES20.glDeleteTextures(1, intArrayOf(overlay.textureId), 0)
    }

    /**
     * Switches to another camera (upstream `attachCamera`), keeping the pipeline running. With
     * [force] the same camera is restarted too, so the transition shows (upstream
     * `forceSceneSwitchTransition`).
     */
    fun switchCamera(cameraId: String, force: Boolean = false) {
        glHandler.post {
            val texture = surfaceTexture ?: return@post
            val currentId = if (cameraClosing) nextCameraId else cameraSource?.current?.id
            if (currentId == cameraId && !force) {
                return@post
            }
            if (hasFrame) {
                transitionStartNanos = System.nanoTime()
            }
            replaceCamera(texture, cameraId)
        }
    }

    /** True while the previous camera closes; [nextCameraId] opens after it (GL thread only). */
    private var cameraClosing = false
    private var nextCameraId: String? = null

    /**
     * Closes the current camera and opens [cameraId] once it has fully closed (two cameras cannot
     * feed one surface). Requests while a camera closes replace each other, so rapid Back/Front
     * taps end on the last one. GL thread only.
     */
    private fun replaceCamera(texture: SurfaceTexture, cameraId: String?) {
        nextCameraId = cameraId
        if (cameraClosing) {
            return
        }
        val old = cameraSource ?: return startCamera(texture, cameraId)
        cameraSource = null
        cameraClosing = true
        old.release {
            glHandler.post {
                cameraClosing = false
                if (running && surfaceTexture === texture) {
                    startCamera(texture, nextCameraId)
                }
            }
        }
    }

    fun setZoom(factor: Float) {
        cameraSource?.setZoom(factor)
    }

    fun setTorch(on: Boolean) {
        cameraSource?.setTorch(on)
    }

    fun setAutoFocus() {
        cameraSource?.setAutoFocus()
    }

    fun setManualFocus(position: Float) {
        cameraSource?.setManualFocus(position)
    }

    fun setManualExposure(exposureNs: Long?, iso: Int?) {
        cameraSource?.setManualExposure(exposureNs, iso)
    }

    fun setAutoExposure() {
        cameraSource?.setAutoExposure()
    }

    fun setExposureBias(bias: Float) {
        cameraSource?.setExposureBias(bias)
    }

    fun setManualWhiteBalance(kelvin: Float) {
        cameraSource?.setManualWhiteBalance(kelvin)
    }

    fun setAutoWhiteBalance() {
        cameraSource?.setAutoWhiteBalance()
    }

    /** Stabilization for this and later cameras. */
    @Volatile
    var stabilization = false
        set(value) {
            field = value
            cameraSource?.setStabilization(value)
        }

    /** What the camera is using now (auto or manual): exposure (ns), ISO, focus (diopters). */
    val currentExposureNs: Long
        get() = cameraSource?.currentExposureNs ?: 0

    val currentIso: Int
        get() = cameraSource?.currentIso ?: 0

    val currentFocusDistance: Float
        get() = cameraSource?.currentFocusDistance ?: 0f

    /**
     * Focuses at a tap on the preview, given in normalized preview coordinates (0..1, origin top
     * left). The preview shows the upright frame aspect-filled, so the point is first mapped into
     * the frame, then rotated back into sensor coordinates.
     */
    fun focusAtPreviewPoint(x: Float, y: Float, previewAspect: Float) {
        val info = cameraSource?.current ?: return
        val contentAspect = sourceAspect
        var u = x
        var v = y
        if (contentAspect > previewAspect) {
            u = 0.5f + (x - 0.5f) * (previewAspect / contentAspect)
        } else {
            v = 0.5f + (y - 0.5f) * (contentAspect / previewAspect)
        }
        if (info.isFront) {
            u = 1f - u
        }
        val rotation = if (info.isFront) {
            (info.sensorOrientation + displayRotationDegrees) % 360
        } else {
            (info.sensorOrientation - displayRotationDegrees + 360) % 360
        }
        val (sensorX, sensorY) = when (rotation) {
            90 -> v to 1f - u
            180 -> 1f - u to 1f - v
            270 -> 1f - v to u
            else -> u to v
        }
        cameraSource?.focusAt(sensorX, sensorY)
    }

    private fun runOnGlThread(block: () -> Unit) {
        if (Thread.currentThread() === glThread) {
            block()
            return
        }
        val done = java.util.concurrent.CountDownLatch(1)
        glHandler.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        done.await(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** Opens the current camera again, e.g. after the camera permission was granted. */
    fun restartCamera() {
        glHandler.post {
            val texture = surfaceTexture ?: return@post
            replaceCamera(texture, if (cameraClosing) nextCameraId else cameraSource?.current?.id)
        }
    }

    /** Set when a camera starts, cleared on its first image (GL thread only). */
    private var cameraStarting = false

    private fun startCamera(texture: SurfaceTexture, requestedId: String?) {
        cameraStarting = true
        val camera = CameraSource(context)
        this.cameraSource = camera
        camera.setStabilization(stabilization)
        val cameraId = requestedId?.takeIf { id -> camera.cameras().any { it.id == id } } ?: camera.backCameraId()
        if (cameraId == null) {
            onError("No camera")
            return
        }
        val size = camera.chooseSize(cameraId, settings.width, settings.height)
        cameraAspect = size.width.toFloat() / size.height
        android.util.Log.i(
            TAG,
            "Camera $cameraId size ${size.width}x${size.height} orientation ${camera.sensorOrientation(
                cameraId,
            )} for ${settings.width}x${settings.height}@${settings.fps}",
        )
        camera.open(
            cameraId,
            texture,
            size,
            settings.fps,
            object : CameraSource.Listener {
                override fun onError(message: String) = this@VideoPipeline.onError(message)
            },
        )
    }

    /** Sets (or clears, with null) the on-screen preview surface. */
    fun setPreviewSurface(surface: Surface?) {
        val done = java.util.concurrent.CountDownLatch(1)
        val posted = glHandler.post {
            try {
                setPreviewSurfaceOnGlThread(surface)
            } finally {
                done.countDown()
            }
        }
        // surfaceDestroyed: the surface must not be touched once it returns, so wait until the GL
        // thread has let go of it (Android logged "Surface failed to disconnect" otherwise).
        if (surface == null && posted && Looper.myLooper() != glThread.looper) {
            done.await(1, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun setPreviewSurfaceOnGlThread(surface: Surface?) {
        val egl = egl ?: return
        previewSurface?.let {
            egl.makeNothingCurrent()
            egl.releaseSurface(it)
        }
        // The view may have destroyed the surface before this ran (screen off, app in the
        // background): skip it, the next surfaceCreated brings a new one. Seen on a Galaxy S26.
        previewSurface = surface?.let {
            try {
                egl.createWindowSurface(it)
            } catch (e: IllegalArgumentException) {
                android.util.Log.w(TAG, "Preview surface gone: ${e.message}")
                null
            }
        }
    }

    private fun toHostClock(sensorNanos: Long): Long = CameraClock.toHostClock(
        sensorNanos,
        cameraSource?.timestampsAreRealtime == true,
        System.nanoTime(),
        SystemClock.elapsedRealtimeNanos(),
    )

    private fun handleFrameAvailable() {
        val texture = surfaceTexture ?: return
        if (!running) {
            return
        }
        // The latest camera image is now in the texture; the frame timer outputs it.
        texture.updateTexImage()
        texture.getTransformMatrix(textureMatrix)
        val captureNanos = toHostClock(texture.timestamp)
        if (captureNanos <= lastCaptureNanos) {
            return
        }
        lastCaptureNanos = captureNanos
        val latency = (System.nanoTime() - captureNanos).coerceAtLeast(0)
        cameraLatencyNanos = if (hasFrame) (cameraLatencyNanos * 7 + latency) / 8 else latency
        // The new camera delivered a frame: the transition ends.
        transitionStartNanos = null
        if (cameraStarting) {
            cameraStarting = false
            onCameraRunning()
        }
        if (!hasFrame) {
            android.util.Log.i(TAG, "First frame, transform ${textureMatrix.joinToString(",") { "%.2f".format(it) }}")
            hasFrame = true
            render(frameClock.next(captureNanos))
        }
    }

    /**
     * Upstream's frame timer: one output frame per frame interval, on an exact time grid, showing
     * the latest camera image (repeated if the camera skipped one). Its clock is "now" moved back
     * by the camera's delivery latency, so video stays in the capture-time domain audio uses.
     */
    private fun handleFrameTimer() {
        if (!running) {
            return
        }
        val now = System.nanoTime()
        if (!hasFrame) {
            // Camera frames arrive some time after capture (about 85 ms on a Galaxy S26 Ultra) and
            // keep their capture time; stamping black frames "now" would make every camera frame
            // look older than the last output, and the monotonic-PTS rule would drop them all.
            renderBlack(now - BLACK_FRAME_LAG_NANOS)
        } else {
            render(frameClock.next(now - cameraLatencyNanos))
        }
        scheduleFrameTimer()
    }

    /** Ticks on an absolute schedule, so millisecond-rounded delays do not add up to drift. */
    private fun scheduleFrameTimer() {
        val now = System.nanoTime()
        nextTickNanos += frameIntervalNanos
        if (nextTickNanos < now - frameIntervalNanos) {
            // Far behind (the thread was busy): skip the missed ticks instead of bursting.
            nextTickNanos = now + frameIntervalNanos
        }
        glHandler.postDelayed(::handleFrameTimer, ((nextTickNanos - now) / 1_000_000).coerceAtLeast(0))
    }

    private fun render(presentationNanos: Long) {
        if (presentationNanos <= lastPresentationNanos) {
            return
        }
        lastPresentationNanos = presentationNanos
        updateDrawMatrix()
        compose(camera = true)
        output(presentationNanos)
    }

    private fun renderBlack(presentationNanos: Long) {
        if ((encoders.isEmpty() && previewSurface == null) || presentationNanos <= lastPresentationNanos) {
            return
        }
        lastPresentationNanos = presentationNanos
        compose(camera = false)
        output(presentationNanos)
    }

    /**
     * Builds the frame like upstream's effect chain: the camera (or black), then the global
     * effects in upstream order, then the scene's widgets on top.
     */
    private fun compose(camera: Boolean) {
        val egl = egl ?: return
        var target = canvas ?: return
        var other = scratch ?: return
        val renderer = renderer ?: return
        val textureRenderer = textureRenderer ?: return
        val effectRenderer = effectRenderer ?: return
        egl.makeNothingCurrent()
        target.bind()
        if (camera) {
            renderer.draw(drawMatrix, target.width, target.height, sourceAspect)
        } else {
            renderer.drawBlack(target.width, target.height)
        }
        for (lut in luts) {
            other.bind()
            effectRenderer.applyLut(target.textureId, lut.textureId, lut.dimension, target.width, target.height)
            val previous = target
            target = other
            other = previous
        }
        transitionStartNanos?.let { start ->
            val seconds = (System.nanoTime() - start) / 1e9
            val transition = sceneSwitchTransition
            val radius = transition.blurRadius(seconds, maxOf(target.width, target.height))
            if (radius > 0f) {
                for (horizontal in listOf(true, false)) {
                    other.bind()
                    effectRenderer.blur(target.textureId, target.width, target.height, radius, horizontal)
                    val previous = target
                    target = other
                    other = previous
                }
            }
            val scale = transition.zoom(seconds)
            if (scale > 1f) {
                val width = target.width * scale
                val height = target.height * scale
                other.bind()
                textureRenderer.drawRect(
                    target.textureId,
                    (target.width - width) / 2,
                    (target.height - height) / 2,
                    width,
                    height,
                    target.width,
                    target.height,
                    flipY = false,
                )
                val previous = target
                target = other
                other = previous
            }
        }
        val active = effects
        val parameters = effectSettings
        for (effect in VideoEffect.entries.filter { it in active }) {
            other.bind()
            val parameter = when (effect) {
                VideoEffect.PIXELLATE -> parameters.pixellateStrength
                VideoEffect.WHIRLPOOL -> parameters.whirlpoolAngle
                VideoEffect.PINCH -> parameters.pinchScale
                else -> 0f
            }
            effectRenderer.apply(effect, target.textureId, target.width, target.height, parameter)
            val previous = target
            target = other
            other = previous
        }
        // Latch new browser frames before binding the canvas (updateTexImage binds the OES texture).
        for (overlay in surfaceOverlays.values) {
            if (overlay.frameAvailable) {
                overlay.frameAvailable = false
                overlay.surfaceTexture.updateTexImage()
                overlay.surfaceTexture.getTransformMatrix(overlay.matrix)
            }
        }
        target.bind()
        for (placement in drawOrder) {
            val surfaceOverlay = surfaceOverlays[placement.id]
            if (surfaceOverlay != null) {
                oesRectRenderer?.drawRect(
                    surfaceOverlay.textureId,
                    surfaceOverlay.matrix,
                    placement.x,
                    placement.y,
                    placement.width,
                    placement.height,
                    target.width,
                    target.height,
                )
                continue
            }
            val overlay = overlays[placement.id] ?: continue
            textureRenderer.drawRect(
                overlay.textureId,
                overlay.x,
                overlay.y,
                overlay.width,
                overlay.height,
                target.width,
                target.height,
                flipY = true,
            )
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        composed = target
    }

    /** Copies the composed frame to every encoder and the preview. */
    private fun output(presentationNanos: Long) {
        val egl = egl ?: return
        val canvas = composed ?: return
        val textureRenderer = textureRenderer ?: return
        val canvasAspect = canvas.width.toFloat() / canvas.height
        for ((_, encoderSurface) in encoders) {
            egl.makeCurrent(encoderSurface)
            textureRenderer.drawFill(canvas.textureId, settings.width, settings.height, canvasAspect)
            egl.setPresentationTime(encoderSurface, presentationNanos)
            egl.swapBuffers(encoderSurface)
        }
        previewSurface?.let { preview ->
            egl.makeCurrent(preview)
            val (width, height) = egl.surfaceSize(preview)
            textureRenderer.drawFill(canvas.textureId, width, height, canvasAspect)
            egl.swapBuffers(preview)
        }
        countFrame()
    }

    /**
     * The SurfaceTexture transform makes camera content upright for the device's natural
     * orientation (the camera service applies the sensor orientation for preview-type buffers).
     * Rotate further by the display rotation so content is upright as the phone is held, and use
     * the resulting aspect ratio for aspect-fill (upstream: video orientation follows the device).
     */
    private val loggedTransform = FloatArray(17)

    private fun updateDrawMatrix() {
        // Logged when it changes: camera transforms and rotation handling differ between phones.
        val changed = loggedTransform[16] != displayRotationDegrees.toFloat() ||
            !textureMatrix.indices.all { loggedTransform[it] == textureMatrix[it] }
        if (changed) {
            textureMatrix.copyInto(loggedTransform)
            loggedTransform[16] = displayRotationDegrees.toFloat()
            android.util.Log.i(TAG, "Transform ${textureMatrix.joinToString(",") { "%.0f".format(it) }} display $displayRotationDegrees")
        }
        val transformRotates = Math.abs(textureMatrix[0]) < 0.5f
        val totalQuarterTurns = ((if (transformRotates) 1 else 0) + displayRotationDegrees / 90) % 2
        sourceAspect = if (totalQuarterTurns == 1) 1f / cameraAspect else cameraAspect
        val rotation = FloatArray(16)
        android.opengl.Matrix.setIdentityM(rotation, 0)
        android.opengl.Matrix.translateM(rotation, 0, 0.5f, 0.5f, 0f)
        // The camera's transform already turns the frame upright for the device's natural
        // orientation. Display rotation is the opposite of the device's physical turn (ROTATION_90
        // = turned 90° counter-clockwise), so the picture is turned back clockwise.
        android.opengl.Matrix.rotateM(rotation, 0, -displayRotationDegrees.toFloat(), 0f, 0f, 1f)
        android.opengl.Matrix.translateM(rotation, 0, -0.5f, -0.5f, 0f)
        android.opengl.Matrix.multiplyMM(drawMatrix, 0, textureMatrix, 0, rotation, 0)
    }

    private fun countFrame() {
        val now = System.nanoTime()
        if (now - fpsWindowStart >= 1_000_000_000) {
            outputFps = framesThisSecond
            android.util.Log.d(TAG, "Output fps $framesThisSecond")
            framesThisSecond = 0
            fpsWindowStart = now
        }
        framesThisSecond++
    }

    fun stop() {
        val done = java.util.concurrent.CountDownLatch(1)
        glHandler.post {
            running = false
            // Wait (briefly) for the camera to close, so a new pipeline can open it straight away.
            val closed = java.util.concurrent.CountDownLatch(1)
            cameraSource?.release { closed.countDown() } ?: closed.countDown()
            cameraSource = null
            closed.await(2, java.util.concurrent.TimeUnit.SECONDS)
            val egl = egl
            if (egl != null) {
                egl.makeNothingCurrent()
                previewSurface?.let { egl.releaseSurface(it) }
                for ((encoder, surface) in encoders) {
                    egl.releaseSurface(surface)
                    encoder.release()
                }
                renderer?.release()
                textureRenderer?.release()
                oesRectRenderer?.release()
                surfaceOverlays.values.forEach { releaseSurfaceOverlay(it) }
                surfaceOverlays.clear()
                effectRenderer?.release()
                luts.forEach { GLES20.glDeleteTextures(1, intArrayOf(it.textureId), 0) }
                luts = emptyList()
                canvas?.release()
                scratch?.release()
                snapshotTarget?.release()
                snapshotTarget = null
                overlays.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it.textureId), 0) }
                overlays.clear()
                surfaceTexture?.release()
                egl.release()
            }
            encoders.clear()
            this.egl = null
            previewSurface = null
            done.countDown()
        }
        done.await(3, java.util.concurrent.TimeUnit.SECONDS)
        glThread.quitSafely()
        snapshotExecutor.shutdown()
    }
}
