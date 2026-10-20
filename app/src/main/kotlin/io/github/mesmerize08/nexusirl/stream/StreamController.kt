package io.github.mesmerize08.nexusirl.stream

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import io.github.mesmerize08.nexusirl.core.secrets.KeystoreSecretStore
import io.github.mesmerize08.nexusirl.core.secrets.MissingSecretException
import io.github.mesmerize08.nexusirl.media.pipeline.CameraInfo
import io.github.mesmerize08.nexusirl.media.pipeline.CameraSource
import io.github.mesmerize08.nexusirl.media.pipeline.EncoderCapabilities
import io.github.mesmerize08.nexusirl.media.pipeline.Mp4Recorder
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEncoder
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEncoderSettings
import io.github.mesmerize08.nexusirl.media.pipeline.VideoPipeline
import io.github.mesmerize08.nexusirl.settings.SettingsArchive
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.StreamCodec
import io.github.mesmerize08.nexusirl.settings.StreamUrlSecrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the camera controls show (upstream: the zoom, torch and camera picker state). */
data class CameraUiState(
    val cameras: List<CameraInfo> = emptyList(),
    val current: CameraInfo? = null,
    val zoom: Float = 1f,
    val torch: Boolean = false,
)

/** A recording in progress (upstream `currentRecording`). */
data class RecordingState(val fileName: String, val startedAtElapsedMillis: Long)

/**
 * Process-wide entry point the UI talks to (upstream: the Model's stream, recording and camera
 * controls).
 *
 * Owns the shared capture: the camera pipeline runs while the app is visible (preview), live or
 * recording, like upstream where the camera is always attached; the microphone runs while live or
 * recording. Going live and recording each attach their own video encoder to the pipeline and
 * share the audio. The foreground service keeps all of it alive in the background.
 *
 * Call from the main thread.
 */
object StreamController {
    private const val TAG = "StreamController"
    private val mutableStatus = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = mutableStatus

    @Volatile
    internal var session: StreamSession? = null
    private var previewSurface: Surface? = null

    @Volatile
    private var settingsStore: SettingsStore? = null

    /** The app's settings (created on first use). */
    fun settings(context: Context): SettingsStore = settingsStore ?: synchronized(this) {
        settingsStore ?: SettingsStore(
            File(context.applicationContext.filesDir, SettingsArchive.SETTINGS_JSON),
            KeystoreSecretStore.create(context.applicationContext),
        ).also { settingsStore = it }
    }

    private var sharedAudio: SharedAudio? = null

    private fun audio(context: Context): SharedAudio = sharedAudio ?: SharedAudio(context).also { sharedAudio = it }

    // Streaming

    /** Goes live with the selected stream. Returns an error message, or null when starting. */
    fun goLive(context: Context): String? {
        val store = settings(context)
        val stream = store.current.selectedStream ?: return "Add a stream first"
        val url = try {
            store.resolveUrl(stream)
        } catch (e: MissingSecretException) {
            return "The stream key is missing. Edit the stream and enter it again."
        }
        if (!StreamUrlSecrets.isValid(url)) {
            return "Edit the stream and enter an srt://, srtla://, rtmp:// or rtmps:// URL"
        }
        return goLive(context, StreamConfig.from(stream, url))
    }

    /** Goes live with [requested]. Returns an error message, or null when starting. */
    fun goLive(context: Context, requested: StreamConfig): String? {
        if (session != null) {
            return null
        }
        // No HEVC encoder (common on low-end SoCs): fall back to H.264 (docs/PORT_PLAN.md §6).
        val config = if (requested.video.hevc && !EncoderCapabilities.supportsHevc()) {
            requested.copy(video = requested.video.copy(hevc = false))
        } else {
            requested
        }
        if (activeRecording != null && !pipelineMatches(config.video)) {
            return "Stop recording first: the stream uses a different resolution or frame rate"
        }
        val video = ensurePipeline(context, config.video)
        val newSession = StreamSession(context.applicationContext, config, video, audio(context)) { mutableStatus.value = it }
        session = newSession
        newSession.start()
        updateService(context)
        return null
    }

    fun stop(context: Context) {
        session?.stop()
        session = null
        afterCaptureEnded(context)
    }

    // Recording (upstream ModelRecording.swift)

    private class ActiveRecording(
        val uri: Uri,
        val descriptor: ParcelFileDescriptor,
        val recorder: Mp4Recorder,
        val encoder: VideoEncoder,
    )

    private var activeRecording: ActiveRecording? = null
    private val mutableRecording = MutableStateFlow<RecordingState?>(null)
    val recording: StateFlow<RecordingState?> = mutableRecording

    /**
     * Starts recording the camera and microphone to an MP4 in `Movies/NexusIRL` (shared storage,
     * so it shows in the gallery and Files). Uses the running canvas; returns an error message, or
     * null when started.
     */
    fun startRecording(context: Context): String? {
        if (activeRecording != null) {
            return null
        }
        val stream = settings(context).current.selectedStream
        val canvas = pipeline?.settings ?: previewSettings(context)
        val video = ensurePipeline(context, canvas)
        val recording = stream?.recording
        val hevc = (recording?.videoCodec ?: StreamCodec.H265_HEVC) == StreamCodec.H265_HEVC && EncoderCapabilities.supportsHevc()
        val encoderSettings = canvas.copy(
            bitrate = recording?.videoBitrate?.takeIf { it > 0 } ?: autoRecordingBitrate(canvas, hevc),
            hevc = hevc,
            keyFrameIntervalSeconds = recording?.maxKeyFrameInterval?.takeIf { it > 0 } ?: 2,
        )
        val name = "NexusIRL_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NexusIRL")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return "Failed to start recording"
        val descriptor = try {
            resolver.openFileDescriptor(uri, "w")
        } catch (e: Exception) {
            null
        }
        if (descriptor == null) {
            resolver.delete(uri, null, null)
            return "Failed to start recording"
        }
        val recorder = Mp4Recorder(descriptor.fileDescriptor, canvas.width, canvas.height)
        val encoder = video.startEncoder(encoderSettings, recorder)
        if (encoder == null) {
            recorder.stop()
            descriptor.close()
            resolver.delete(uri, null, null)
            return "Failed to start recording"
        }
        audio(context).add(recorder)
        activeRecording = ActiveRecording(uri, descriptor, recorder, encoder)
        mutableRecording.value = RecordingState(name, SystemClock.elapsedRealtime())
        updateService(context)
        return null
    }

    /** Stops recording and publishes the file. Returns false when nothing playable was recorded. */
    fun stopRecording(context: Context): Boolean {
        val active = activeRecording ?: return false
        activeRecording = null
        mutableRecording.value = null
        // Encoder first (its release waits for the last frame), then audio, then finalise the file.
        pipeline?.stopEncoder(active.encoder)
        sharedAudio?.remove(active.recorder)
        val playable = active.recorder.stop()
        active.descriptor.close()
        val resolver = context.contentResolver
        if (playable) {
            resolver.update(active.uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } else {
            resolver.delete(active.uri, null, null)
        }
        afterCaptureEnded(context)
        return playable
    }

    /**
     * Upstream lets AVAssetWriter pick the bitrate when it is 0 ("Auto"). MediaCodec needs a
     * number: about 0.15 bits per pixel for H.264 (1080p30 is about 9 Mbit/s), 40% less for H.265.
     */
    fun autoRecordingBitrate(canvas: VideoEncoderSettings, hevc: Boolean): Long {
        val bitsPerPixel = if (hevc) 0.09 else 0.15
        return (canvas.width.toLong() * canvas.height * canvas.fps * bitsPerPixel).toLong().coerceIn(1_000_000, 100_000_000)
    }

    // Snapshots (upstream ModelSnapshot.swift)

    /**
     * Saves the current frame as a JPEG (quality 0.9, as upstream) to `Pictures/NexusIRL`.
     * [onDone] runs on the main thread with true when saved.
     */
    fun takeSnapshot(context: Context, onDone: (Boolean) -> Unit) {
        val video = pipeline
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (video == null) {
            onDone(false)
            return
        }
        val resolver = context.applicationContext.contentResolver
        video.takeSnapshot { bitmap ->
            val saved = bitmap != null && try {
                val name = "NexusIRL_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NexusIRL")
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                val output = uri?.let { resolver.openOutputStream(it) }
                output?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } == true
            } catch (e: Exception) {
                false
            } finally {
                bitmap?.recycle()
            }
            main.post { onDone(saved) }
        }
    }

    // Capture lifetime

    private fun afterCaptureEnded(context: Context) {
        updateService(context)
        if (session != null || activeRecording != null) {
            return
        }
        if (uiVisible) {
            // The stream may have used a different canvas than the selected stream's preview.
            refreshPreview(context)
        } else {
            releasePipeline()
        }
    }

    /** The foreground service runs while live or recording. */
    private fun updateService(context: Context) {
        val intent = Intent(context, StreamingService::class.java)
        if (session != null || activeRecording != null) {
            context.startForegroundService(intent)
        } else {
            context.stopService(intent)
        }
    }

    // Camera pipeline and controls

    @Volatile
    private var displayRotationDegrees = 0

    private var pipeline: VideoPipeline? = null
    private var uiVisible = false
    private var cameraId: String? = null
    private val mutableCamera = MutableStateFlow(CameraUiState())
    val camera: StateFlow<CameraUiState> = mutableCamera

    /** Display rotation (0/90/180/270) so camera content stays upright as the phone is held. */
    fun setDisplayRotation(degrees: Int) {
        displayRotationDegrees = degrees
        pipeline?.displayRotationDegrees = degrees
    }

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        pipeline?.setPreviewSurface(surface)
    }

    /** The app came to the foreground (true) or left it (false): start or stop the preview camera. */
    fun setUiVisible(context: Context, visible: Boolean) {
        android.util.Log.d(TAG, "UI visible $visible, live ${session != null}, recording ${activeRecording != null}")
        uiVisible = visible
        if (visible) {
            refreshPreview(context)
        } else if (session == null && activeRecording == null) {
            releasePipeline()
        }
    }

    /** Restarts the preview when the selected stream's canvas changed (not while live or recording). */
    fun refreshPreview(context: Context) {
        if (session != null || activeRecording != null || !uiVisible) {
            return
        }
        ensurePipeline(context, previewSettings(context))
    }

    private fun previewSettings(context: Context): VideoEncoderSettings {
        val stream = settings(context).current.selectedStream
            ?: return VideoEncoderSettings(width = 1280, height = 720, fps = 30, bitrate = 0)
        return VideoEncoderSettings(width = stream.resolution.width, height = stream.resolution.height, fps = stream.fps, bitrate = 0)
    }

    private fun pipelineMatches(canvas: VideoEncoderSettings): Boolean {
        val s = pipeline?.settings ?: return false
        return s.width == canvas.width && s.height == canvas.height && s.fps == canvas.fps
    }

    private fun ensurePipeline(context: Context, canvas: VideoEncoderSettings): VideoPipeline {
        pipeline?.let { current ->
            if (pipelineMatches(canvas)) {
                return current
            }
            releasePipeline()
        }
        android.util.Log.d(TAG, "Starting camera pipeline ${canvas.width}x${canvas.height}@${canvas.fps}")
        val created = VideoPipeline(context.applicationContext, canvas) { message ->
            mutableStatus.value = mutableStatus.value.copy(detail = "Camera: $message")
        }
        created.displayRotationDegrees = displayRotationDegrees
        created.start(cameraId)
        previewSurface?.let { created.setPreviewSurface(it) }
        pipeline = created
        if (mutableCamera.value.cameras.isEmpty()) {
            val probe = CameraSource(context.applicationContext)
            val cameras = try {
                probe.cameras()
            } finally {
                probe.release()
            }
            mutableCamera.value = CameraUiState(cameras = cameras)
        }
        val cameras = mutableCamera.value.cameras
        mutableCamera.value = mutableCamera.value.copy(
            current = cameras.firstOrNull { it.id == cameraId } ?: cameras.firstOrNull(),
            zoom = 1f,
            torch = false,
        )
        return created
    }

    private fun releasePipeline() {
        android.util.Log.d(TAG, "Releasing camera pipeline ${pipeline != null}")
        pipeline?.stop()
        pipeline = null
    }

    /** Switches camera (upstream camera picker); remembered while the app runs. */
    fun switchCamera(id: String) {
        cameraId = id
        pipeline?.switchCamera(id)
        val info = mutableCamera.value.cameras.firstOrNull { it.id == id }
        mutableCamera.value = mutableCamera.value.copy(current = info, zoom = 1f, torch = false)
    }

    fun setZoom(factor: Float) {
        val info = mutableCamera.value.current ?: return
        val zoom = factor.coerceIn(info.minZoom, info.maxZoom)
        pipeline?.setZoom(zoom)
        mutableCamera.value = mutableCamera.value.copy(zoom = zoom)
    }

    fun setTorch(on: Boolean) {
        if (mutableCamera.value.current?.hasTorch != true) {
            return
        }
        pipeline?.setTorch(on)
        mutableCamera.value = mutableCamera.value.copy(torch = on)
    }

    /** Tap-to-focus at a normalized point of the preview (upstream tap gesture). */
    fun focusAt(x: Float, y: Float, previewAspect: Float) {
        pipeline?.focusAtPreviewPoint(x, y, previewAspect)
    }

    /** Back to continuous auto focus (upstream long press). */
    fun setAutoFocus() {
        pipeline?.setAutoFocus()
    }
}
