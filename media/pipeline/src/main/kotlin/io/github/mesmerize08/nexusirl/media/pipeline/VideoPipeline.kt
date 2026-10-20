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
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import io.github.mesmerize08.nexusirl.media.pipeline.gl.EglCore
import io.github.mesmerize08.nexusirl.media.pipeline.gl.OesRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VideoPipeline(
    private val context: Context,
    /** Canvas size and frame rate. An attached encoder must use the same size and rate. */
    val settings: VideoEncoderSettings,
    private val onError: (String) -> Unit = {},
) {
    private val glThread = HandlerThread("video-gl").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private var egl: EglCore? = null
    private var renderer: OesRenderer? = null
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
    private var lastFrameWallNanos = 0L
    private var running = false

    /** Frames rendered per second (output side), for the status line. */
    @Volatile
    var outputFps = 0
        private set
    private var framesThisSecond = 0
    private var fpsWindowStart = 0L

    private val frameIntervalNanos = 1_000_000_000L / settings.fps

    private companion object {
        const val TAG = "VideoPipeline"
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
            val texture = SurfaceTexture(renderer.textureId)
            surfaceTexture = texture
            texture.setOnFrameAvailableListener({ handleFrameAvailable() }, glHandler)
            running = true
            startCamera(texture, cameraId)
            glHandler.postDelayed(::handleFrameTimer, frameIntervalNanos / 1_000_000)
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
            val encoder = VideoEncoder(encoderSettings, sink)
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
     * Renders the current frame at canvas size into a bitmap (upstream `takeSnapshot` with age 0).
     * [onDone] runs on the GL thread with null when there is no frame yet.
     */
    fun takeSnapshot(onDone: (Bitmap?) -> Unit) {
        glHandler.post {
            val egl = egl
            val renderer = renderer
            if (egl == null || renderer == null || !hasFrame) {
                onDone(null)
                return@post
            }
            val width = settings.width
            val height = settings.height
            val surface = egl.createOffscreenSurface(width, height)
            try {
                egl.makeCurrent(surface)
                updateDrawMatrix()
                renderer.draw(drawMatrix, width, height, sourceAspect)
                val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                val upsideDown = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                upsideDown.copyPixelsFromBuffer(pixels)
                // GL rows start at the bottom.
                val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
                val bitmap = Bitmap.createBitmap(upsideDown, 0, 0, width, height, flip, false)
                upsideDown.recycle()
                onDone(bitmap)
            } catch (e: Exception) {
                onDone(null)
            } finally {
                egl.makeNothingCurrent()
                egl.releaseSurface(surface)
            }
        }
    }

    /** Switches to another camera (upstream `attachCamera`), keeping the pipeline running. */
    fun switchCamera(cameraId: String) {
        glHandler.post {
            val texture = surfaceTexture ?: return@post
            if (cameraSource?.current?.id == cameraId) {
                return@post
            }
            cameraSource?.release()
            cameraSource = null
            startCamera(texture, cameraId)
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

    private fun startCamera(texture: SurfaceTexture, requestedId: String?) {
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
        glHandler.post {
            val egl = egl ?: return@post
            previewSurface?.let {
                egl.makeNothingCurrent()
                egl.releaseSurface(it)
            }
            previewSurface = surface?.let { egl.createWindowSurface(it) }
        }
    }

    private fun toHostClock(sensorNanos: Long): Long {
        if (cameraSource?.timestampsAreRealtime != true) {
            return sensorNanos
        }
        // Shift boot-time (elapsedRealtime) stamps into the CLOCK_MONOTONIC domain.
        return sensorNanos - (SystemClock.elapsedRealtimeNanos() - System.nanoTime())
    }

    private fun handleFrameAvailable() {
        val texture = surfaceTexture ?: return
        if (!running) {
            return
        }
        texture.updateTexImage()
        texture.getTransformMatrix(textureMatrix)
        val presentationNanos = toHostClock(texture.timestamp)
        // Upstream drops frames whose PTS is not after the previous one.
        if (presentationNanos <= lastPresentationNanos) {
            return
        }
        if (!hasFrame) {
            android.util.Log.i(TAG, "First frame, transform ${textureMatrix.joinToString(",") { "%.2f".format(it) }}")
        }
        hasFrame = true
        lastFrameWallNanos = System.nanoTime()
        render(presentationNanos)
    }

    /** Gap filler and black frames, checked once per frame interval. */
    private fun handleFrameTimer() {
        if (!running) {
            return
        }
        val now = System.nanoTime()
        if (!hasFrame) {
            renderBlack(now)
        } else if (now - lastFrameWallNanos > 50_000_000) {
            // Re-emit the last frame with its PTS advanced by the time since it arrived.
            render(lastPresentationNanos + (now - lastFrameWallNanos).coerceAtLeast(frameIntervalNanos))
            lastFrameWallNanos = now
        }
        glHandler.postDelayed(::handleFrameTimer, frameIntervalNanos / 1_000_000)
    }

    private fun render(presentationNanos: Long) {
        val egl = egl ?: return
        val renderer = renderer ?: return
        if (presentationNanos <= lastPresentationNanos) {
            return
        }
        lastPresentationNanos = presentationNanos
        updateDrawMatrix()
        for ((_, encoderSurface) in encoders) {
            egl.makeCurrent(encoderSurface)
            renderer.draw(drawMatrix, settings.width, settings.height, sourceAspect)
            egl.setPresentationTime(encoderSurface, presentationNanos)
            egl.swapBuffers(encoderSurface)
        }
        previewSurface?.let { preview ->
            egl.makeCurrent(preview)
            val (width, height) = egl.surfaceSize(preview)
            renderer.draw(drawMatrix, width, height, sourceAspect)
            egl.swapBuffers(preview)
        }
        countFrame()
    }

    private fun renderBlack(presentationNanos: Long) {
        val egl = egl ?: return
        val renderer = renderer ?: return
        if (encoders.isEmpty() || presentationNanos <= lastPresentationNanos) {
            return
        }
        lastPresentationNanos = presentationNanos
        for ((_, encoderSurface) in encoders) {
            egl.makeCurrent(encoderSurface)
            renderer.drawBlack(settings.width, settings.height)
            egl.setPresentationTime(encoderSurface, presentationNanos)
            egl.swapBuffers(encoderSurface)
        }
        countFrame()
    }

    /**
     * The SurfaceTexture transform makes camera content upright for the device's natural
     * orientation (the camera service applies the sensor orientation for preview-type buffers).
     * Rotate further by the display rotation so content is upright as the phone is held, and use
     * the resulting aspect ratio for aspect-fill (upstream: video orientation follows the device).
     */
    private fun updateDrawMatrix() {
        val transformRotates = Math.abs(textureMatrix[0]) < 0.5f
        val totalQuarterTurns = ((if (transformRotates) 1 else 0) + displayRotationDegrees / 90) % 2
        sourceAspect = if (totalQuarterTurns == 1) 1f / cameraAspect else cameraAspect
        val rotation = FloatArray(16)
        android.opengl.Matrix.setIdentityM(rotation, 0)
        android.opengl.Matrix.translateM(rotation, 0, 0.5f, 0.5f, 0f)
        android.opengl.Matrix.rotateM(rotation, 0, displayRotationDegrees.toFloat(), 0f, 0f, 1f)
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
            cameraSource?.release()
            cameraSource = null
            val egl = egl
            if (egl != null) {
                egl.makeNothingCurrent()
                previewSurface?.let { egl.releaseSurface(it) }
                for ((encoder, surface) in encoders) {
                    egl.releaseSurface(surface)
                    encoder.release()
                }
                renderer?.release()
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
    }
}
