package it.binario.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.data.RailTime
import it.binario.app.data.Station
import it.binario.app.data.bikesharing.BikeSharingNearby
import it.binario.app.data.bikesharing.BikeSharingResult
import it.binario.app.data.bikesharing.SharedVehicleKind
import it.binario.app.data.core.GeoPoint
import it.binario.app.data.repository.BinarioDataGraph
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * "Bici in condivisione vicino a <stazione>": docking stations and free-floating bikes or scooters within 300 m of
 * the station's own coordinates (the catalogue's, or a city stop's published position; never the device position),
 * from the GBFS feeds of the systems serving that place. Nothing is requested until the user taps the button; a
 * station that no system of this build serves shows nothing.
 */
@Composable
internal fun BikeSharingNearbySection(station: Station, modifier: Modifier = Modifier) {
    val latitude = station.latitude ?: return
    val longitude = station.longitude ?: return
    val point = remember(latitude, longitude) {
        runCatching { GeoPoint(latitude, longitude) }.getOrNull()
    } ?: return
    val service = remember { BinarioDataGraph.bikeSharingService() }
    if (!service.serves(point)) return
    var result by remember(station.id) { mutableStateOf<BikeSharingResult?>(null) }
    var loading by remember(station.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() {
        if (loading) return
        loading = true
        scope.launch {
            result = service.nearby(point)
            loading = false
        }
    }
    SourceSectionCard(title = stringResource(R.string.bike_sharing_title, station.name), modifier = modifier.testTag("bike-sharing")) {
        when (val current = result) {
            null -> if (loading) {
                SourceSectionNote(stringResource(R.string.bike_sharing_loading))
            } else {
                OutlinedButton(onClick = ::load, modifier = Modifier.testTag("bike-sharing-show")) {
                    Text(stringResource(R.string.bike_sharing_show))
                }
            }
            BikeSharingResult.NotCovered -> SourceSectionNote(stringResource(R.string.bike_sharing_none))
            BikeSharingResult.Unavailable -> {
                SourceSectionNote(stringResource(R.string.bike_sharing_unavailable))
                OutlinedButton(onClick = { result = null; load() }) { Text(stringResource(R.string.bike_sharing_show)) }
            }
            is BikeSharingResult.Loaded -> {
                if (current.systems.all { it.docks.isEmpty() && it.vehicles.isEmpty() }) {
                    SourceSectionNote(stringResource(R.string.bike_sharing_none))
                }
                current.systems.forEach { BikeSharingSystemBlock(it) }
            }
        }
    }
}

@Composable
private fun BikeSharingSystemBlock(nearby: BikeSharingNearby) {
    val uriHandler = LocalUriHandler.current
    val distance = @Composable { meters: Int -> stringResource(R.string.distance_meters, meters) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (nearby.docks.isNotEmpty() || nearby.vehicles.isNotEmpty()) {
            Text(nearby.system.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        nearby.docks.forEach { dock ->
            val parts = listOfNotNull(
                distance(dock.distanceMeters),
                stringResource(R.string.bike_sharing_dock_bikes, dock.bikesAvailable),
                dock.ebikesAvailable?.takeIf { it > 0 }?.let { stringResource(R.string.bike_sharing_dock_ebikes, it) },
                dock.docksAvailable?.takeIf { it > 0 }?.let { stringResource(R.string.bike_sharing_dock_free, it) }
            )
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(dock.name, style = MaterialTheme.typography.bodyMedium)
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        nearby.vehicles.forEach { vehicle ->
            val parts = listOfNotNull(
                stringResource(vehicle.kind.label()),
                distance(vehicle.distanceMeters),
                vehicle.batteryPercent?.let { stringResource(R.string.bike_sharing_battery, it) }
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
        if (nearby.docks.isNotEmpty() || nearby.vehicles.isNotEmpty()) {
            SourceSectionNote(
                stringResource(R.string.bike_sharing_source, nearby.system.attribution,
                    nearby.observedAt.atZone(RailTime.defaultZone).format(TIME))
            )
            TextButton(onClick = { openExternalLink(uriHandler, nearby.system.websiteUrl) }) {
                Text(stringResource(R.string.bike_sharing_open, nearby.system.name), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@StringRes
private fun SharedVehicleKind.label(): Int = when (this) {
    SharedVehicleKind.BIKE -> R.string.bike_sharing_type_bike
    SharedVehicleKind.EBIKE -> R.string.bike_sharing_type_ebike
    SharedVehicleKind.SCOOTER -> R.string.bike_sharing_type_scooter
    SharedVehicleKind.OTHER -> R.string.bike_sharing_type_other
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
