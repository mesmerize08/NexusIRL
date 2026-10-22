package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.math.abs

/** Camera timestamps to the host clock (CLOCK_MONOTONIC, `System.nanoTime()`). */
object CameraClock {
    /**
     * [sensorNanos] in the host clock. Boot-time (elapsedRealtime) stamps are shifted by the time
     * spent in deep sleep since boot. The sensor may say REALTIME ([sensorSaysRealtime]) while the
     * camera service already delivers MONOTONIC stamps to the SurfaceTexture (seen on a Galaxy S26
     * Ultra, Android 17, where trusting the metadata put every frame hours in the past and the
     * preview stayed black), so the stamp's own clock is the one it is closest to.
     */
    fun toHostClock(sensorNanos: Long, sensorSaysRealtime: Boolean, monotonicNow: Long, realtimeNow: Long): Long {
        if (!sensorSaysRealtime || abs(sensorNanos - monotonicNow) <= abs(sensorNanos - realtimeNow)) {
            return sensorNanos
        }
        return sensorNanos - (realtimeNow - monotonicNow)
    }
}
