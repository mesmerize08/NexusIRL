package io.github.mesmerize08.nexusirl.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.github.mesmerize08.nexusirl.MainActivity
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.location.LocationController
import io.github.mesmerize08.nexusirl.moblink.MoblinkController

/**
 * Keeps capture alive in the background while live or recording (upstream: UIBackgroundModes
 * audio/voip and the Live Activity), and the Moblink relay while it runs. Declares only the
 * foreground service types in use. Started and stopped by [StreamController]; it owns nothing
 * itself.
 */
class StreamingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        StreamController.pendingServiceStarts = (StreamController.pendingServiceStarts - 1).coerceAtLeast(0)
        if (!StreamController.isCapturing && !MoblinkController.isRunning) {
            // Capture ended before the service got here. startForeground must still be called;
            // without a type it would claim the manifest's camera type, which can throw in the
            // background, so use the type that needs nothing extra, then end.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Only the types in use: capture, and/or the Moblink relay (a network device role).
            var types = 0
            if (StreamController.isCapturing) {
                types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or locationType()
            }
            if (MoblinkController.isRunning) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, buildNotification(), types)
        } else {
            // Camera and microphone service types exist from Android 11; Android 10 has no type check.
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        if (wakeLock == null) {
            // No timeout: the service only runs while live, recording or relaying, and ends with it.
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nexusirl:capture")
                .apply {
                    setReferenceCounted(false)
                    @Suppress("WakelockTimeout")
                    acquire()
                }
        }
        if (wifiLock == null) {
            // Wi-Fi power saving delays and drops packets once the screen is off, which SRT(LA)
            // and Moblink tunnels feel immediately. Low-latency mode applies while the app is in
            // the foreground; high-performance mode keeps the radio awake in the background.
            val wifi = applicationContext.getSystemService(WifiManager::class.java)

            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi?.createWifiLock(mode, "nexusirl:stream")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
        wifiLock?.release()
        wifiLock = null
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
            .setContentText(
                getString(if (StreamController.isCapturing) R.string.notification_streaming else R.string.notification_moblink_relay),
            )
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
