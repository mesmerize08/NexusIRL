package io.github.mesmerize08.nexusirl.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.view.Surface
import io.github.mesmerize08.nexusirl.MainActivity
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.core.secrets.KeystoreSecretStore
import io.github.mesmerize08.nexusirl.core.secrets.MissingSecretException
import io.github.mesmerize08.nexusirl.media.pipeline.CameraInfo
import io.github.mesmerize08.nexusirl.media.pipeline.CameraSource
import io.github.mesmerize08.nexusirl.media.pipeline.EncoderCapabilities
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEncoderSettings
import io.github.mesmerize08.nexusirl.media.pipeline.VideoPipeline
import io.github.mesmerize08.nexusirl.settings.SettingsArchive
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.StreamUrlSecrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** What the camera controls show (upstream: the zoom, torch and camera picker state). */
data class CameraUiState(
    val cameras: List<CameraInfo> = emptyList(),
    val current: CameraInfo? = null,
    val zoom: Float = 1f,
    val torch: Boolean = false,
)

/**
 * Process-wide entry point the UI talks to (upstream: the Model's stream and camera controls).
 *
 * Owns the camera pipeline: it runs while the app is visible (preview) or while streaming, like
 * upstream where the camera is always attached. Going live attaches an encoder to it. Call from
 * the main thread.
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
        goLive(context, StreamConfig.from(stream, url))
        return null
    }

    fun goLive(context: Context, config: StreamConfig) {
        if (session != null) {
            return
        }
        pendingConfig = config
        context.startForegroundService(Intent(context, StreamingService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, StreamingService::class.java))
    }

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
        android.util.Log.d(TAG, "UI visible $visible, session ${session != null}")
        uiVisible = visible
        if (visible) {
            refreshPreview(context)
        } else if (session == null) {
            releasePipeline()
        }
    }

    /** Restarts the preview when the selected stream's canvas changed (not while live). */
    fun refreshPreview(context: Context) {
        if (session != null || !uiVisible) {
            return
        }
        ensurePipeline(context, previewSettings(context))
    }

    private fun previewSettings(context: Context): VideoEncoderSettings {
        val stream = settings(context).current.selectedStream
            ?: return VideoEncoderSettings(width = 1280, height = 720, fps = 30, bitrate = 0)
        return VideoEncoderSettings(width = stream.resolution.width, height = stream.resolution.height, fps = stream.fps, bitrate = 0)
    }

    private fun ensurePipeline(context: Context, canvas: VideoEncoderSettings): VideoPipeline {
        pipeline?.let { current ->
            val s = current.settings
            if (s.width == canvas.width && s.height == canvas.height && s.fps == canvas.fps) {
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

    private var pendingConfig: StreamConfig? = null

    internal fun start(context: Context) {
        val requested = pendingConfig ?: return
        pendingConfig = null
        // No HEVC encoder (common on low-end SoCs): fall back to H.264 (docs/PORT_PLAN.md §6).
        val config = if (requested.video.hevc && !EncoderCapabilities.supportsHevc()) {
            requested.copy(video = requested.video.copy(hevc = false))
        } else {
            requested
        }
        val video = ensurePipeline(context, config.video)
        val newSession = StreamSession(context, config, video) { mutableStatus.value = it }
        session = newSession
        newSession.start()
    }

    internal fun stopSession(context: Context) {
        session?.stop()
        session = null
        if (uiVisible) {
            // The stream may have used a different canvas than the selected stream's preview.
            refreshPreview(context)
        } else {
            releasePipeline()
        }
    }
}

/**
 * Keeps capture and streaming alive in the background (upstream: UIBackgroundModes audio/voip and
 * the Live Activity). Foreground service type camera|microphone.
 */
class StreamingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nexusirl:streaming")
            .apply { acquire(12 * 60 * 60 * 1000L) }
        StreamController.start(applicationContext)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        StreamController.stopSession(applicationContext)
        wakeLock?.release()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_streaming))
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "streaming"
        const val NOTIFICATION_ID = 1
    }
}
