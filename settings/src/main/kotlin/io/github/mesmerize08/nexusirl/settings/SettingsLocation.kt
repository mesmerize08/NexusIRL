// Port of Moblin/Various/Settings/SettingsLocation.swift and SettingsPrivacyRegion.contains
// (Various/Utils/LocationUtils.swift) @97e26d2 (v1 subset; accuracy and distance filter stay in
// `extra`).
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SDouble
import io.github.mesmerize08.nexusirl.core.codable.SUuid
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreserving
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreservingSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlin.math.cos

/**
 * Upstream `SettingsPrivacyRegion`: a latitude/longitude box in degrees. While the device is inside
 * one, the map shows no position.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsPrivacyRegion.Serializer::class)
data class SettingsPrivacyRegion(
    val id: SUuid = UUID.randomUUID(),
    val latitude: SDouble = 0.0,
    val longitude: SDouble = 0.0,
    val latitudeDelta: SDouble = 30.0,
    val longitudeDelta: SDouble = 30.0,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsPrivacyRegion>(generatedSerializer())

    /** Upstream `contains(coordinate:)`. */
    fun contains(latitude: Double, longitude: Double): Boolean =
        cos(Math.toRadians(this.latitude - latitude)) > cos(Math.toRadians(latitudeDelta / 2)) &&
            cos(Math.toRadians(this.longitude - longitude)) > cos(Math.toRadians(longitudeDelta / 2))

    companion object {
        /** Upstream `toLatitudeDeltaDegrees` / `toLongitudeDeltaDegrees`: a [meters] wide box around a point. */
        fun around(latitude: Double, longitude: Double, meters: Double): SettingsPrivacyRegion = SettingsPrivacyRegion(
            latitude = latitude,
            longitude = longitude,
            latitudeDelta = 360 * meters / 40_075_000,
            longitudeDelta = 360 * meters / (40_075_000 * cos(Math.toRadians(latitude))),
        )
    }
}

/** Upstream `SettingsLocation`: location on/off, privacy regions and the running totals in meters. */
@KeepGeneratedSerializer
@Serializable(with = SettingsLocation.Serializer::class)
data class SettingsLocation(
    val enabled: Boolean = false,
    val privacyRegions: List<SettingsPrivacyRegion> = emptyList(),
    val distance: SDouble = 0.0,
    val splitDistance: SDouble = 0.0,
    val altitudeAscent: SDouble = 0.0,
    val altitudeDescent: SDouble = 0.0,
    val splitAltitudeAscent: SDouble = 0.0,
    val splitAltitudeDescent: SDouble = 0.0,
    val resetWhenGoingLive: Boolean = false,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsLocation>(generatedSerializer())

    fun isInPrivacyRegion(latitude: Double, longitude: Double): Boolean = privacyRegions.any { it.contains(latitude, longitude) }
}
