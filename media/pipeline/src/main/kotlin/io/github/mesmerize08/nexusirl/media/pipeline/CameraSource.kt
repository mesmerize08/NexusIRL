package io.github.mesmerize08.nexusirl.media.pipeline

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Camera2 capture into a [SurfaceTexture] owned by the GL pipeline (upstream
 * `VideoCaptureSession` on AVCaptureSession). Manual controls (focus/ISO/WB/zoom) arrive in Phase 3.
 */
class CameraSource(private val context: Context) {
    interface Listener {
        fun onError(message: String)
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null

    /**
     * True when sensor timestamps are on the boot-time clock (REALTIME source) rather than
     * CLOCK_MONOTONIC; they must then be shifted into the host clock domain.
     */
    var timestampsAreRealtime = false
        private set

    fun backCameraId(): String? = manager.cameraIdList.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
    } ?: manager.cameraIdList.firstOrNull()

    fun sensorOrientation(cameraId: String): Int =
        manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    /** All SurfaceTexture output sizes, for diagnostics. */
    fun outputSizes(cameraId: String): List<Size> =
        manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(SurfaceTexture::class.java).toList()

    /** Picks the supported output size closest to [width]x[height] (same aspect preferred). */
    fun chooseSize(cameraId: String, width: Int, height: Int): Size {
        val map = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
        val aspect = width.toFloat() / height
        return sizes.minByOrNull { size ->
            abs(size.width.toFloat() / size.height - aspect) * 10_000 + abs(size.width * size.height - width * height) / 1000f
        } ?: Size(width, height)
    }

    @SuppressLint("MissingPermission")
    fun open(cameraId: String, surfaceTexture: SurfaceTexture, size: Size, fps: Int, listener: Listener) {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        timestampsAreRealtime = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val fpsRange = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper >= fps }
            ?.minByOrNull { abs(it.upper - fps) * 100 + abs(it.lower - fps) }
            ?: Range(fps, fps)
        surfaceTexture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(surfaceTexture)
        this.surface = surface
        manager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }.build()
                val configuration = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(surface)),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(captureSession: CameraCaptureSession) {
                            session = captureSession
                            try {
                                captureSession.setRepeatingRequest(request, null, handler)
                            } catch (e: Exception) {
                                listener.onError("Camera request failed: ${e.message}")
                            }
                        }

                        override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                            listener.onError("Camera session configuration failed")
                        }
                    },
                )
                camera.createCaptureSession(configuration)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                device = null
                listener.onError("Camera disconnected")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                device = null
                listener.onError("Camera error $error")
            }
        })
    }

    fun close() {
        handler.post {
            session?.close()
            session = null
            device?.close()
            device = null
            surface?.release()
            surface = null
        }
    }

    fun release() {
        close()
        thread.quitSafely()
    }
}
