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
import io.github.mesmerize08.nexusirl.media.pipeline.EncoderCapabilities
import io.github.mesmerize08.nexusirl.settings.SettingsArchive
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.StreamUrlSecrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Process-wide entry point the UI talks to (upstream: the Model's stream controls). */
object StreamController {
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

    /** Display rotation (0/90/180/270) so camera content stays upright as the phone is held. */
    fun setDisplayRotation(degrees: Int) {
        displayRotationDegrees = degrees
        session?.setDisplayRotation(degrees)
    }

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        session?.setPreviewSurface(surface)
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
        val newSession = StreamSession(context, config) { mutableStatus.value = it }
        session = newSession
        newSession.setDisplayRotation(displayRotationDegrees)
        newSession.start()
        previewSurface?.let { newSession.setPreviewSurface(it) }
    }

    internal fun stopSession() {
        session?.stop()
        session = null
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
        StreamController.stopSession()
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
