// Port of Moblin/View/Settings/Location/LocationSettingsView.swift and PrivacyRegionsSettingsView.swift
// @97e26d2 (v1 subset). Privacy regions are added around the current position instead of drawn on a
// map, and coordinates are not shown on screen.
package io.github.mesmerize08.nexusirl.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.effects.LengthUnit
import io.github.mesmerize08.nexusirl.effects.LocationFormat
import io.github.mesmerize08.nexusirl.location.LocationController
import io.github.mesmerize08.nexusirl.settings.SettingsLocation
import io.github.mesmerize08.nexusirl.settings.SettingsPrivacyRegion
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import kotlin.math.roundToInt

private val PRIVACY_REGION_SIZES = listOf(100.0, 250.0, 500.0, 1000.0, 2000.0, 5000.0)

/** Location on/off, the running totals and privacy regions (upstream location settings). */
@Composable
fun LocationScreen(store: SettingsStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val database by store.database.collectAsState()
    val active by LocationController.active.collectAsState()
    val location = database.location
    var regionSize by remember { mutableDoubleStateOf(500.0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LocationController.start(context)
    }
    fun update(transform: (SettingsLocation) -> SettingsLocation) = store.update { it.copy(location = transform(it.location)) }
    Page(title = stringResource(R.string.location), onBack = onBack) {
        Text(stringResource(R.string.location_help), style = MaterialTheme.typography.bodySmall)
        SwitchRow(stringResource(R.string.enabled), location.enabled) { on ->
            update { it.copy(enabled = on) }
            if (on && !LocationController.hasPermission(context)) {
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
        if (location.enabled && !active) {
            Text(stringResource(R.string.location_not_permitted), color = MaterialTheme.colorScheme.error)
        }
        HorizontalDivider()
        Text(
            stringResource(
                R.string.location_totals,
                LocationFormat.distance(location.distance, LengthUnit.SYSTEM),
                LocationFormat.altitude(location.altitudeAscent, LengthUnit.SYSTEM),
                LocationFormat.altitude(location.altitudeDescent, LengthUnit.SYSTEM),
            ),
        )
        SwitchRow(stringResource(R.string.reset_when_going_live), location.resetWhenGoingLive) { on ->
            update { it.copy(resetWhenGoingLive = on) }
        }
        OutlinedButton(onClick = { LocationController.reset() }) { Text(stringResource(R.string.reset)) }
        HorizontalDivider()
        Text(stringResource(R.string.privacy_regions), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.privacy_regions_help), style = MaterialTheme.typography.bodySmall)
        location.privacyRegions.forEachIndexed { index, region ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // The size only: the coordinates of, say, a home stay off the screen.
                val meters = (region.latitudeDelta * 40_075_000 / 360).roundToInt()
                Text(stringResource(R.string.privacy_region_name, index + 1, meters), modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        update { current ->
                            current.copy(
                                privacyRegions = current.privacyRegions.filterNot { it.id == region.id },
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
        ChoiceField(stringResource(R.string.privacy_region_size), regionSize, PRIVACY_REGION_SIZES, { "${it.roundToInt()} m" }, {
            regionSize = it
        })
        OutlinedButton(onClick = {
            val here = LocationController.currentLocation()
            if (here == null) {
                Toast.makeText(context, R.string.location_unknown, Toast.LENGTH_LONG).show()
            } else {
                update {
                    it.copy(
                        privacyRegions = it.privacyRegions + SettingsPrivacyRegion.around(here.latitude, here.longitude, regionSize),
                    )
                }
            }
        }) { Text(stringResource(R.string.add_privacy_region_here)) }
    }
}
