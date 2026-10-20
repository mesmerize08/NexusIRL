package io.github.mesmerize08.nexusirl.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.github.mesmerize08.nexusirl.MainActivity
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.location.LocationController

/**
 * Keeps capture alive in the background while live or recording (upstream: UIBackgroundModes
 * audio/voip and the Live Activity). Foreground service type camera|microphone. Started and
 * stopped by [StreamController]; it owns no media itself.
 */
class StreamingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or locationType(),
            )
        } else {
            // Camera and microphone service types exist from Android 11; Android 10 has no type check.
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nexusirl:capture")
                .apply { acquire(12 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
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

    /** Location keeps updating in the background only when it is on and permitted. */
    private fun locationType(): Int =
        if (LocationController.active.value && LocationController.hasPermission(this)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
}
