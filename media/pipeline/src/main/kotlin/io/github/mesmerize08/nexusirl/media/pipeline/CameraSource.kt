package io.github.mesmerize08.nexusirl.media.pipeline

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.roundToInt

/** One selectable camera (upstream `SettingsCameraId` / camera picker entries). */
data class CameraInfo(
    val id: String,
    val facing: Int,
    val sensorOrientation: Int,
    val minZoom: Float,
    val maxZoom: Float,
    val hasTorch: Boolean,
    val supportsFocusPoint: Boolean,
    /** Manual ISO and shutter (MANUAL_SENSOR capability). */
    val supportsManualExposure: Boolean = false,
    val minIso: Int = 0,
    val maxIso: Int = 0,
    val minExposureNs: Long = 0,
    val maxExposureNs: Long = 0,
    /** Manual focus: the minimum focus distance in diopters (0 means fixed focus). */
    val minFocusDistance: Float = 0f,
    /** Manual white balance (MANUAL_POST_PROCESSING capability). */
    val supportsManualWhiteBalance: Boolean = false,
    val exposureCompensationMin: Int = 0,
    val exposureCompensationMax: Int = 0,
    /** EV per exposure compensation step. */
    val exposureCompensationStep: Float = 0f,
    val supportsStabilization: Boolean = false,
) {
    val supportsManualFocus: Boolean
        get() = minFocusDistance > 0f

    val isFront: Boolean
        get() = facing == CameraMetadata.LENS_FACING_FRONT

    val label: String
        get() = when (facing) {
            CameraMetadata.LENS_FACING_BACK -> "Back"
            CameraMetadata.LENS_FACING_FRONT -> "Front"
            else -> "External"
        } + " ($id)"
}

/**
 * Camera2 capture into a [SurfaceTexture] owned by the GL pipeline (upstream
 * `VideoCaptureSession` on AVCaptureSession), with the camera controls of upstream
 * `ModelCamera.swift`: zoom, torch, tap-to-focus and back to continuous auto focus.
 *
 * All camera calls run on the camera thread.
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
    private var request: CaptureRequest.Builder? = null
    private var characteristics: CameraCharacteristics? = null

    /** Current camera, or null before [open]. */
    @Volatile
    var current: CameraInfo? = null
        private set

    @Volatile
    var zoom = 1f
        private set

    @Volatile
    var torch = false
        private set

    /** Latest values the camera used (auto or manual), from capture results. */
    @Volatile
    var currentExposureNs = 0L
        private set

    @Volatile
    var currentIso = 0
        private set

    @Volatile
    var currentFocusDistance = 0f
        private set

    @Volatile
    private var currentColorTransform: ColorSpaceTransform? = null
    private var manualExposure = false
    private var frameDurationNs = 33_333_333L

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { currentExposureNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { currentIso = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { currentFocusDistance = it }
            result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { currentColorTransform = it }
        }
    }

    /**
     * True when sensor timestamps are on the boot-time clock (REALTIME source) rather than
     * CLOCK_MONOTONIC; they must then be shifted into the host clock domain.
     */
    var timestampsAreRealtime = false
        private set

    /** Cameras, back ones first (upstream lists back, front, then external cameras). */
    fun cameras(): List<CameraInfo> = manager.cameraIdList.mapNotNull { id ->
        try {
            describe(id)
        } catch (e: Exception) {
            null
        }
    }.sortedBy {
        when (it.facing) {
            CameraMetadata.LENS_FACING_BACK -> 0
            CameraMetadata.LENS_FACING_FRONT -> 1
            else -> 2
        }
    }

    fun backCameraId(): String? = cameras().firstOrNull()?.id

    private fun describe(id: String): CameraInfo {
        val c = manager.getCameraCharacteristics(id)
        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else {
            null
        } ?: Range(1f, c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
        val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val compensation = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        return CameraInfo(
            id = id,
            facing = c.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_EXTERNAL,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            minZoom = zoomRange.lower,
            maxZoom = zoomRange.upper,
            hasTorch = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            supportsFocusPoint = (c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0,
            supportsManualExposure = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities,
            minIso = isoRange?.lower ?: 0,
            maxIso = isoRange?.upper ?: 0,
            minExposureNs = exposureRange?.lower ?: 0,
            maxExposureNs = exposureRange?.upper ?: 0,
            minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            supportsManualWhiteBalance = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities,
            exposureCompensationMin = compensation?.lower ?: 0,
            exposureCompensationMax = compensation?.upper ?: 0,
            exposureCompensationStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
            supportsStabilization = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true ||
                c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
        )
    }

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
        this.characteristics = characteristics
        current = describe(cameraId)
        zoom = 1f.coerceIn(current!!.minZoom, current!!.maxZoom)
        torch = false
        manualExposure = false
        frameDurationNs = 1_000_000_000L / fps
        timestampsAreRealtime = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val fpsRange = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper >= fps }
            ?.minByOrNull { abs(it.upper - fps) * 100 + abs(it.lower - fps) }
            ?: Range(fps, fps)
        surfaceTexture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(surfaceTexture)
        this.surface = surface
        // No permission yet (first start, before the user answers), another app holds the camera, or
        // a device policy disables it: report it instead of crashing; the caller may retry later.
        try {
            manager.openCamera(
                cameraId,
                executor,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                        request = builder
                        applyZoom(builder)
                        applyStabilization(builder, stabilization)
                        val configuration = SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            listOf(OutputConfiguration(surface)),
                            executor,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(captureSession: CameraCaptureSession) {
                                    session = captureSession
                                    try {
                                        captureSession.setRepeatingRequest(builder.build(), captureCallback, handler)
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
                },
            )
        } catch (e: SecurityException) {
            listener.onError("No camera permission")
        } catch (e: CameraAccessException) {
            listener.onError("Camera unavailable (${e.reason})")
        }
    }

    /** Sets the zoom factor, clamped to the camera's range (upstream `setCameraZoomX`). */
    fun setZoom(factor: Float) {
        handler.post {
            val info = current ?: return@post
            zoom = factor.coerceIn(info.minZoom, info.maxZoom)
            val builder = request ?: return@post
            applyZoom(builder)
            updateRepeating()
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            val active = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val width = (active.width() / zoom).roundToInt()
            val height = (active.height() / zoom).roundToInt()
            val left = active.left + (active.width() - width) / 2
            val top = active.top + (active.height() - height) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + width, top + height))
        }
    }

    /** Torch on or off (upstream `toggleTorch`). No-op on cameras without a flash. */
    fun setTorch(on: Boolean) {
        handler.post {
            if (current?.hasTorch != true) {
                return@post
            }
            torch = on
            val builder = request ?: return@post
            if (!manualExposure) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            }
            builder.set(CaptureRequest.FLASH_MODE, if (on) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
            updateRepeating()
        }
    }

    /**
     * Focuses (and meters exposure) at a point in normalized sensor coordinates, 0..1 on both axes
     * (upstream `setFocusPointOfInterest`). Focus stays there until [setAutoFocus].
     */
    fun focusAt(sensorX: Float, sensorY: Float) {
        handler.post {
            val builder = request ?: return@post
            val session = session ?: return@post
            val active = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
            val size = (minOf(active.width(), active.height()) * 0.1f).roundToInt()
            val x = (active.left + sensorX.coerceIn(0f, 1f) * active.width()).roundToInt()
            val y = (active.top + sensorY.coerceIn(0f, 1f) * active.height()).roundToInt()
            val region = MeteringRectangle(
                Rect(
                    (x - size).coerceAtLeast(active.left),
                    (y - size).coerceAtLeast(active.top),
                    (x + size).coerceAtMost(active.right),
                    (y + size).coerceAtMost(active.bottom),
                ),
                MeteringRectangle.METERING_WEIGHT_MAX - 1,
            )
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            try {
                // One trigger request, then keep the regions in the repeating request.
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                session.capture(builder.build(), null, handler)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                session.setRepeatingRequest(builder.build(), captureCallback, handler)
            } catch (e: Exception) {
                // Session closed concurrently.
            }
        }
    }

    /** Back to continuous auto focus and whole-frame metering (upstream long press). */
    fun setAutoFocus() {
        handler.post {
            val builder = request ?: return@post
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            updateRepeating()
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        }
    }

    /** Manual focus at a lens position, 0 closest to 1 farthest (upstream `setManualFocus`). */
    fun setManualFocus(position: Float) {
        handler.post {
            val info = current ?: return@post
            val builder = request ?: return@post
            if (!info.supportsManualFocus) {
                return@post
            }
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, CameraFactors.focusDistanceFromPosition(position, info.minFocusDistance))
            updateRepeating()
        }
    }

    /**
     * Manual exposure (upstream `setManualExposureAndIso`): a null value keeps what the camera is
     * using now. The frame duration stays at the frame rate.
     */
    fun setManualExposure(exposureNs: Long?, iso: Int?) {
        handler.post {
            val info = current ?: return@post
            val builder = request ?: return@post
            if (!info.supportsManualExposure) {
                return@post
            }
            manualExposure = true
            val exposure = (exposureNs ?: currentExposureNs.takeIf { it > 0 } ?: frameDurationNs / 2)
                .coerceIn(info.minExposureNs, minOf(info.maxExposureNs, frameDurationNs))
            val sensitivity = (iso ?: currentIso.takeIf { it > 0 } ?: info.minIso).coerceIn(info.minIso, info.maxIso)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
            updateRepeating()
        }
    }

    /** Back to continuous auto exposure (upstream `setAutoExposureAndIso`). */
    fun setAutoExposure() {
        handler.post {
            val builder = request ?: return@post
            manualExposure = false
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            updateRepeating()
        }
    }

    /** Exposure bias in EV (upstream `setExposureBias`); ignored outside the camera's range. */
    fun setExposureBias(bias: Float) {
        handler.post {
            val info = current ?: return@post
            val builder = request ?: return@post
            val index = CameraFactors.compensationIndex(
                bias,
                info.exposureCompensationStep,
                info.exposureCompensationMin,
                info.exposureCompensationMax,
            ) ?: return@post
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index)
            updateRepeating()
        }
    }

    /**
     * Manual white balance at a colour temperature (upstream `setManualWhiteBalance`). Keeps the
     * sensor's own colour matrix from the latest frame and only replaces the channel gains.
     */
    fun setManualWhiteBalance(kelvin: Float) {
        handler.post {
            val info = current ?: return@post
            val builder = request ?: return@post
            val transform = currentColorTransform
            if (!info.supportsManualWhiteBalance || transform == null) {
                return@post
            }
            val (red, green, blue) = CameraFactors.whiteBalanceGains(kelvin)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(red, green, green, blue))
            updateRepeating()
        }
    }

    /** Back to continuous auto white balance (upstream `setAutoWhiteBalance`). */
    fun setAutoWhiteBalance() {
        handler.post {
            val builder = request ?: return@post
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)
            updateRepeating()
        }
    }

    /** Stabilization to apply when the camera opens and after [setStabilization]. */
    @Volatile
    var stabilization = false
        private set

    /** Video stabilization (upstream `videoStabilizationMode`): electronic and, where present, optical. */
    fun setStabilization(on: Boolean) {
        stabilization = on
        handler.post {
            val builder = request ?: return@post
            applyStabilization(builder, on)
            updateRepeating()
        }
    }

    private fun applyStabilization(builder: CaptureRequest.Builder, on: Boolean) {
        val characteristics = characteristics ?: return
        if (characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        ) {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (on) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
        }
        if (characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        ) {
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (on) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            )
        }
    }

    private fun updateRepeating() {
        val builder = request ?: return
        try {
            session?.setRepeatingRequest(builder.build(), captureCallback, handler)
        } catch (e: Exception) {
            // Session closed concurrently.
        }
    }

    fun close() {
        handler.post {
            session?.close()
            session = null
            device?.close()
            device = null
            surface?.release()
            surface = null
            request = null
        }
    }

    fun release() {
        close()
        thread.quitSafely()
    }
}
