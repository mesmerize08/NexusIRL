// Port of `createSnapshotter` in Moblin/VideoEffects/MapEffect.swift and `translateMeters` in
// Moblin/Various/Utils/LocationUtils.swift @97e26d2. MapKit's camera distance is turned into a
// MapLibre zoom level showing the same ground width.
package io.github.mesmerize08.nexusirl.effects

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** Where a map widget looks and where the position dot goes. */
data class MapCamera(
    val latitude: Double,
    val longitude: Double,
    /** MapLibre zoom for a [sidePixels] wide square image. */
    val zoom: Double,
    /** Degrees clockwise from north; 0 with north up. */
    val bearing: Double,
    /** How far the dot sits below the centre, as a fraction of half the side (upstream `dotOffsetRatio`). */
    val dotOffsetRatio: Double,
) {
    companion object {
        /** MapKit's field of view: half the visible side is tan(15°) × camera distance. */
        private val HALF_SIDE_PER_DISTANCE = tan(PI / 12)
        private const val EARTH_CIRCUMFERENCE = 40_075_016.686

        /**
         * Upstream: the camera follows the course unless [northUp]; above 4 m/s it zooms out by
         * 150 m of camera distance per m/s and (course up) moves ahead so the dot sits lower,
         * up to half of half the side at 20 m/s.
         */
        fun compute(
            latitude: Double,
            longitude: Double,
            speed: Double,
            course: Double,
            northUp: Boolean,
            distance: Double,
            sidePixels: Int,
        ): MapCamera {
            var cameraDistance = distance
            var centerLatitude = latitude
            var centerLongitude = longitude
            var dotOffsetRatio = 0.0
            if (speed > 4) {
                cameraDistance += 150 * (speed - 4)
                if (!northUp) {
                    val halfMapSideLength = HALF_SIDE_PER_DISTANCE * cameraDistance
                    val maxDotOffsetFromCenter = halfMapSideLength / 2
                    val maxDotSpeed = 20.0
                    val k = maxDotOffsetFromCenter / (maxDotSpeed - 4)
                    val dotOffsetInMeters = min(k * (speed - 4), maxDotOffsetFromCenter)
                    val radians = Math.toRadians(max(course, 0.0))
                    centerLatitude = latitude + 360 * (cos(radians) * dotOffsetInMeters) / 40_075_000
                    centerLongitude = longitude + 360 * (sin(radians) * dotOffsetInMeters) / (40_075_000 * cos(Math.toRadians(latitude)))
                    dotOffsetRatio = dotOffsetInMeters / halfMapSideLength
                }
            }
            // MapLibre (512 px tiles): metres per pixel = circumference × cos(lat) / (512 × 2^zoom).
            val groundWidth = 2 * HALF_SIDE_PER_DISTANCE * cameraDistance
            val metersPerPixel = groundWidth / sidePixels.coerceAtLeast(1)
            val zoom = ln(EARTH_CIRCUMFERENCE * cos(Math.toRadians(centerLatitude)) / (512 * metersPerPixel)) / ln(2.0)
            return MapCamera(
                latitude = centerLatitude,
                longitude = centerLongitude,
                zoom = zoom.coerceIn(0.0, 22.0),
                bearing = if (northUp || course < 0) 0.0 else course,
                dotOffsetRatio = dotOffsetRatio,
            )
        }
    }
}
