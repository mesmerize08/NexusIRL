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
import io.github.mesmerize08.nexusirl.media.pipeline.EncoderCapabilities
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEncoderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Process-wide entry point the UI talks to (upstream: the Model's stream controls). */
object StreamController {
    private val mutableStatus = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = mutableStatus

    @Volatile
    internal var session: StreamSession? = null
    private var previewSurface: Surface? = null

    fun goLive(context: Context, url: String, settings: VideoEncoderSettings) {
        if (session != null) {
            return
        }
        pendingUrl = url
        pendingSettings = settings
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

    internal var pendingUrl: String? = null
    internal var pendingSettings: VideoEncoderSettings? = null

    internal fun start(context: Context) {
        val url = pendingUrl ?: return
        val requested = pendingSettings ?: return
        pendingUrl = null
        // No HEVC encoder (common on low-end SoCs): fall back to H.264 (docs/PORT_PLAN.md §6).
        val settings = if (requested.hevc && !EncoderCapabilities.supportsHevc()) requested.copy(hevc = false) else requested
        val newSession = StreamSession(context, url, settings) { mutableStatus.value = it }
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
