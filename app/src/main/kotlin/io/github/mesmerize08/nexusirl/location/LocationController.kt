// Port of Moblin/Various/Model/ModelLocation.swift and Various/Location.swift (location manager)
// @97e26d2 (v1 subset: no RealtimeIRL, slope or split resets yet).
package io.github.mesmerize08.nexusirl.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.mesmerize08.nexusirl.settings.SettingsLocation
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.stream.StreamController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** The values text widgets show (metres, metres per second). */
data class LocationValues(val speed: Double?, val averageSpeed: Double?, val altitude: Double?, val totals: SettingsLocation)

/**
 * Follows the device location while location is enabled in settings (upstream
 * `database.location.enabled`) and the permission is granted, keeping upstream's running totals:
 * distance (only moves larger than the reported accuracy), altitude ascent/descent (changes of at
 * least max(vertical accuracy, 3 m)) and average speed. Totals are kept in memory and saved to
 * settings every 30 s and when location stops.
 *
 * Call from the main thread.
 */
object LocationController {
    private const val UPDATE_MILLIS = 1000L
    private const val SAVE_MILLIS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private var store: SettingsStore? = null
    private var manager: LocationManager? = null

    @Volatile
    private var running = false
    private var scope: CoroutineScope? = null

    @Volatile
    private var latest: Location? = null
    private var distanceReference: Location? = null
    private var altitudeReference: Double? = null

    @Volatile
    private var totals = SettingsLocation()
    private var totalsChanged = false
    private var lastSaveMillis = 0L
    private var averageSpeedStartMillis = SystemClock.elapsedRealtime()
    private var averageSpeedStartDistance = 0.0

    @Volatile
    private var averageSpeed = 0.0

    private val mutableActive = MutableStateFlow(false)

    /** Whether location updates are running. */
    val active: StateFlow<Boolean> = mutableActive

    private val listener = LocationListener { location -> latest = location }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Starts following the location setting (idempotent). Call again after the permission is granted. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        val settings = StreamController.settings(appContext)
        store = settings
        if (scope == null) {
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = newScope
            newScope.launch {
                settings.database.map { it.location.enabled }.distinctUntilChanged().collect { reload(appContext) }
            }
        } else {
            reload(appContext)
        }
    }

    @SuppressLint("MissingPermission")
    private fun reload(context: Context) {
        val settings = store ?: return
        val wanted = settings.current.location.enabled && hasPermission(context)
        if (wanted == running) {
            return
        }
        if (!wanted) {
            stopUpdates()
            return
        }
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return
        manager = locationManager
        totals = settings.current.location
        totalsChanged = false
        distanceReference = null
        altitudeReference = null
        resetAverageSpeed()
        // The fused provider (AOSP, no Play services) where present, else GPS plus network.
        val providers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            locationManager.hasProvider(LocationManager.FUSED_PROVIDER)
        ) {
            listOf(LocationManager.FUSED_PROVIDER)
        } else {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { locationManager.allProviders.contains(it) }
        }
        for (provider in providers) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Upstream's default accuracy is "best".
                    val request = LocationRequest.Builder(UPDATE_MILLIS).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build()
                    locationManager.requestLocationUpdates(provider, request, context.mainExecutor, listener)
                } else {
                    locationManager.requestLocationUpdates(provider, UPDATE_MILLIS, 0f, listener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                // Provider disabled or unavailable; the others may still work.
            }
        }
        running = true
        mutableActive.value = true
        lastSaveMillis = SystemClock.elapsedRealtime()
        handler.postDelayed(::tick, UPDATE_MILLIS)
    }

    private fun stopUpdates() {
        manager?.removeUpdates(listener)
        manager = null
        handler.removeCallbacksAndMessages(null)
        running = false
        mutableActive.value = false
        latest = null
        save()
    }

    /** Upstream's once-a-second `updateDistance`, `updateAltitude` and `updateAverageSpeed`. */
    private fun tick() {
        if (!running) {
            return
        }
        val location = latest
        if (location != null) {
            updateDistance(location)
            updateAltitude(location)
        }
        val elapsedSeconds = (SystemClock.elapsedRealtime() - averageSpeedStartMillis) / 1000.0
        if (elapsedSeconds > 0) {
            averageSpeed = (totals.distance - averageSpeedStartDistance) / elapsedSeconds
        }
        if (totalsChanged && SystemClock.elapsedRealtime() - lastSaveMillis >= SAVE_MILLIS) {
            save()
        }
        handler.postDelayed(::tick, UPDATE_MILLIS)
    }

    private fun updateDistance(location: Location) {
        val reference = distanceReference
        if (reference == null) {
            distanceReference = location
            return
        }
        val distance = location.distanceTo(reference).toDouble()
        if (distance > reference.accuracy) {
            totals = totals.copy(distance = totals.distance + distance, splitDistance = totals.splitDistance + distance)
            totalsChanged = true
            distanceReference = location
        }
    }

    private fun updateAltitude(location: Location) {
        if (!location.hasAltitude() || !location.hasVerticalAccuracy() || location.verticalAccuracyMeters <= 0) {
            return
        }
        val reference = altitudeReference
        if (reference == null) {
            altitudeReference = location.altitude
            return
        }
        val delta = location.altitude - reference
        if (abs(delta) < max(location.verticalAccuracyMeters.toDouble(), 3.0)) {
            return
        }
        totals = if (delta > 0) {
            totals.copy(altitudeAscent = totals.altitudeAscent + delta, splitAltitudeAscent = totals.splitAltitudeAscent + delta)
        } else {
            totals.copy(altitudeDescent = totals.altitudeDescent - delta, splitAltitudeDescent = totals.splitAltitudeDescent - delta)
        }
        totalsChanged = true
        altitudeReference = location.altitude
    }

    private fun resetAverageSpeed() {
        averageSpeed = 0.0
        averageSpeedStartMillis = SystemClock.elapsedRealtime()
        averageSpeedStartDistance = totals.distance
    }

    private fun save() {
        val settings = store ?: return
        lastSaveMillis = SystemClock.elapsedRealtime()
        if (!totalsChanged) {
            return
        }
        totalsChanged = false
        val saved = totals
        settings.update {
            it.copy(
                location = it.location.copy(
                    distance = saved.distance,
                    splitDistance = saved.splitDistance,
                    altitudeAscent = saved.altitudeAscent,
                    altitudeDescent = saved.altitudeDescent,
                    splitAltitudeAscent = saved.splitAltitudeAscent,
                    splitAltitudeDescent = saved.splitAltitudeDescent,
                ),
            )
        }
    }

    /** Upstream `resetLocationData`: all totals and the average speed start over. */
    fun reset() {
        totals = totals.copy(
            distance = 0.0,
            splitDistance = 0.0,
            altitudeAscent = 0.0,
            altitudeDescent = 0.0,
            splitAltitudeAscent = 0.0,
            splitAltitudeDescent = 0.0,
        )
        distanceReference = null
        altitudeReference = null
        totalsChanged = true
        resetAverageSpeed()
        save()
    }

    /** Upstream: reset when going live if `resetWhenGoingLive` is set. */
    fun onGoLive() {
        if (store?.current?.location?.resetWhenGoingLive == true) {
            reset()
        }
    }

    /** Text widget values; null while location is off. Any thread. */
    fun values(): LocationValues? {
        if (!running) {
            return null
        }
        val location = latest
        return LocationValues(
            speed = location?.takeIf { it.hasSpeed() }?.speed?.toDouble(),
            averageSpeed = averageSpeed,
            altitude = location?.takeIf { it.hasAltitude() }?.altitude,
            totals = totals,
        )
    }

    /** The latest position, ignoring privacy regions (for adding one). */
    fun currentLocation(): Location? = latest

    /**
     * The position for map widgets: none while location is off, unknown, or inside a privacy
     * region (upstream shows an empty location there). Any thread.
     */
    fun mapLocation(): Location? {
        val location = latest ?: return null
        val regions = store?.current?.location ?: return null
        return if (regions.isInPrivacyRegion(location.latitude, location.longitude)) null else location
    }
}
