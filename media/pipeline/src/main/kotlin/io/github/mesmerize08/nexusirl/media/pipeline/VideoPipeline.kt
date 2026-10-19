// Video path from camera to encoder, modelled on upstream Moblin/Media/HaishinKit/Media/Video/
// VideoUnit.swift @97e26d2 for built-in cameras: render on each camera frame, a gap filler that
// re-emits the last frame (PTS advanced) after 50 ms without input, black frames when no source
// is available, and a monotonic PTS guard.
package io.github.mesmerize08.nexusirl.media.pipeline

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import io.github.mesmerize08.nexusirl.media.pipeline.gl.EglCore
import io.github.mesmerize08.nexusirl.media.pipeline.gl.OesRenderer

class VideoPipeline(
    private val context: Context,
    private val settings: VideoEncoderSettings,
    private val sink: MediaSink,
    private val onError: (String) -> Unit = {},
) {
    private val glThread = HandlerThread("video-gl").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private var egl: EglCore? = null
    private var renderer: OesRenderer? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var encoderSurface: EGLSurface? = null
    private var previewSurface: EGLSurface? = null
    private var camera: CameraSource? = null
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

    var encoder: VideoEncoder? = null
        private set

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

    fun start() {
        glHandler.post {
            val encoder = VideoEncoder(settings, sink)
            this.encoder = encoder
            val egl = EglCore()
            this.egl = egl
            encoderSurface = egl.createWindowSurface(encoder.inputSurface)
            egl.makeCurrent(encoderSurface!!)
            val renderer = OesRenderer()
            this.renderer = renderer
            val texture = SurfaceTexture(renderer.textureId)
            surfaceTexture = texture
            texture.setOnFrameAvailableListener({ handleFrameAvailable() }, glHandler)
            running = true
            startCamera(texture)
            glHandler.postDelayed(::handleFrameTimer, frameIntervalNanos / 1_000_000)
        }
    }

    private fun startCamera(texture: SurfaceTexture) {
        val camera = CameraSource(context)
        this.camera = camera
        val cameraId = camera.backCameraId()
        if (cameraId == null) {
            onError("No camera")
            return
        }
        val size = camera.chooseSize(cameraId, settings.width, settings.height)
        cameraAspect = size.width.toFloat() / size.height
        android.util.Log.i(TAG, "Camera $cameraId size ${size.width}x${size.height} orientation ${camera.sensorOrientation(cameraId)} for ${settings.width}x${settings.height}@${settings.fps}")
        camera.open(cameraId, texture, size, settings.fps, object : CameraSource.Listener {
            override fun onError(message: String) = this@VideoPipeline.onError(message)
        })
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
        if (camera?.timestampsAreRealtime != true) {
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
        val encoderSurface = encoderSurface ?: return
        if (presentationNanos <= lastPresentationNanos) {
            return
        }
        lastPresentationNanos = presentationNanos
        updateDrawMatrix()
        egl.makeCurrent(encoderSurface)
        renderer.draw(drawMatrix, settings.width, settings.height, sourceAspect)
        egl.setPresentationTime(encoderSurface, presentationNanos)
        egl.swapBuffers(encoderSurface)
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
        val encoderSurface = encoderSurface ?: return
        if (presentationNanos <= lastPresentationNanos) {
            return
        }
        lastPresentationNanos = presentationNanos
        egl.makeCurrent(encoderSurface)
        renderer.drawBlack(settings.width, settings.height)
        egl.setPresentationTime(encoderSurface, presentationNanos)
        egl.swapBuffers(encoderSurface)
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
            camera?.release()
            camera = null
            val egl = egl
            if (egl != null) {
                egl.makeNothingCurrent()
                previewSurface?.let { egl.releaseSurface(it) }
                encoderSurface?.let { egl.releaseSurface(it) }
                renderer?.release()
                surfaceTexture?.release()
                encoder?.release()
                egl.release()
            }
            this.egl = null
            previewSurface = null
            encoderSurface = null
            encoder = null
            done.countDown()
        }
        done.await(3, java.util.concurrent.TimeUnit.SECONDS)
        glThread.quitSafely()
    }
}
