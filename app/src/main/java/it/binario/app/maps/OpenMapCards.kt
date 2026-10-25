package it.binario.app.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.NearbyStation
import it.binario.app.R
import it.binario.app.data.Station
import it.binario.app.data.TrainService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val MAP_HEIGHT = 240.dp
private const val STATION_NEIGHBOURS = 6

/** Shares the station catalogue between the map cards; rebuilt only when the catalogue changes. */
@Composable
internal fun rememberOpenMapStationIndex(stations: List<Station>, exactLookup: (String) -> Station?): OpenMapStationIndex =
    remember(stations) { OpenMapStationIndex(stations, exactLookup) }

/**
 * "Mappa del percorso" in the train detail. Collapsed by default: no MapView, no network request and no
 * coordinate matching happen until the user opens it.
 */
@Composable
internal fun RouteMapCard(service: TrainService, stationIndex: OpenMapStationIndex, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(service.id) { mutableStateOf(false) }
    OpenMapCardFrame(
        title = stringResource(R.string.map_route_title),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier
    ) {
        if (expanded) RouteMapBody(service, stationIndex)
    }
}

@Composable
private fun ColumnScope.RouteMapBody(service: TrainService, stationIndex: OpenMapStationIndex) {
    val model by produceState<RouteMapModel?>(null, service.stops, service.operationalStatusVerified, stationIndex) {
        value = withContext(Dispatchers.Default) {
            RouteMapPlanner.build(service.stops, service.operationalStatusVerified) { name ->
                stationIndex.locate(name)?.mapPoint()
            }
        }
    }
    val current = model
    if (current == null) {
        OpenMapNote(stringResource(R.string.map_loading))
        return
    }
    val mapped = current.mappedStops
    if (mapped.isEmpty()) {
        OpenMapNote(stringResource(R.string.map_route_no_coordinates))
        return
    }
    var selectedStopIndex by rememberSaveable(service.id) { mutableStateOf<Int?>(null) }
    val palette = openMapPalette(MaterialTheme.colorScheme)
    val lineColor = MaterialTheme.colorScheme.primary.toArgb()
    val content = remember(current, selectedStopIndex, palette, lineColor) {
        OpenMapContent(
            markers = mapped.map { stop ->
                OpenMapMarker(stop.point!!, OpenMapMarkerStyles.routeStop(stop.primaryRole, stop.stopIndex == selectedStopIndex, palette))
            },
            line = current.line,
            lineColor = lineColor,
            camera = current.camera
        )
    }
    OpenMap(
        content = content,
        contentDescription = stringResource(R.string.map_route_content_description, service.origin, service.destination),
        onMarkerTap = { markerIndex -> selectedStopIndex = mapped.getOrNull(markerIndex)?.stopIndex },
        modifier = Modifier.fillMaxWidth().height(MAP_HEIGHT)
    )
    OpenMapAttribution()
    current.stops.firstOrNull { it.stopIndex == selectedStopIndex }?.let { stop -> SelectedStopPanel(stop) }
        ?: OpenMapNote(stringResource(R.string.map_route_tap_hint))
    RouteLegend(current, palette)
    OpenMapNote(stringResource(R.string.map_route_note))
    OpenMapNote(stringResource(R.string.map_route_coverage, mapped.size, current.stops.size))
    // Text alternative: every stop with its times and role, mapped or not.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        current.stops.forEach { stop -> Text(routeStopLine(stop), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SelectedStopPanel(stop: RouteMapStop) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stop.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(stopTimes(stop), style = MaterialTheme.typography.bodySmall)
            val roles = stopRoles(stop)
            if (roles.isNotEmpty()) Text(roles, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RouteLegend(model: RouteMapModel, palette: OpenMapPalette) {
    val present = model.mappedStops.mapNotNull { it.primaryRole }.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (RouteStopRole.ORIGIN in present || RouteStopRole.DESTINATION in present) {
            LegendEntry(OpenMapMarkerStyles.routeStop(RouteStopRole.ORIGIN, false, palette), stringResource(R.string.map_role_origin) + " · " + stringResource(R.string.map_role_destination))
        }
        listOf(
            RouteStopRole.LAST_CONFIRMED to R.string.map_role_last_confirmed,
            RouteStopRole.NEXT to R.string.map_role_next,
            RouteStopRole.SKIPPED to R.string.map_role_skipped
        ).forEach { (role, label) ->
            if (role in present) LegendEntry(OpenMapMarkerStyles.routeStop(role, false, palette), stringResource(label))
        }
    }
}

/** "Mappa della stazione": the selected station and its nearest catalogue neighbours. */
@Composable
internal fun StationMapCard(
    station: Station,
    stationIndex: OpenMapStationIndex,
    onOpenStation: (Station) -> Unit,
    modifier: Modifier = Modifier
) {
    val located = remember(station, stationIndex) { stationIndex.coordinatesOf(station) } ?: return
    val centre = located.mapPoint() ?: return
    var expanded by rememberSaveable(located.id) { mutableStateOf(false) }
    OpenMapCardFrame(
        title = stringResource(R.string.map_station_title),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier
    ) {
        if (!expanded) return@OpenMapCardFrame
        val neighbours by produceState<List<NearbyStation>?>(null, located.id, stationIndex) {
            value = withContext(Dispatchers.Default) { stationIndex.neighbours(located, STATION_NEIGHBOURS) }
        }
        val list = neighbours
        if (list == null) {
            OpenMapNote(stringResource(R.string.map_loading))
            return@OpenMapCardFrame
        }
        val palette = openMapPalette(MaterialTheme.colorScheme)
        val content = remember(centre, list, palette) {
            val points = list.mapNotNull { it.station.mapPoint() }
            OpenMapContent(
                markers = listOf(OpenMapMarker(centre, OpenMapMarkerStyles.focusStation(palette), tappable = false)) +
                    list.mapNotNull { nearby -> nearby.station.mapPoint()?.let { OpenMapMarker(it, OpenMapMarkerStyles.station(palette)) } },
                camera = OpenMapCameraPlanner.plan(listOf(centre) + points)
            )
        }
        val tappable = remember(list) { list.filter { it.station.mapPoint() != null } }
        OpenMap(
            content = content,
            contentDescription = stringResource(R.string.map_station_content_description, located.name),
            onMarkerTap = { markerIndex -> tappable.getOrNull(markerIndex - 1)?.let { onOpenStation(it.station) } },
            modifier = Modifier.fillMaxWidth().height(MAP_HEIGHT)
        )
        OpenMapAttribution()
        OpenMapNote(stringResource(R.string.map_station_tap_hint))
        LegendEntry(OpenMapMarkerStyles.focusStation(palette), located.name)
        if (list.isNotEmpty()) {
            LegendEntry(OpenMapMarkerStyles.station(palette), stringResource(R.string.station_board_nearby))
            StationList(list, onOpenStation)
        }
    }
}

/**
 * Map of the stations already listed by distance. Opens only after an explicit tap on a one-line notice,
 * because the tiles it downloads reveal the area around the user's approximate position.
 */
@Composable
internal fun NearbyStationsMapCard(
    nearby: List<NearbyStation>,
    userPosition: GeoPoint?,
    onOpenStation: (Station) -> Unit,
    modifier: Modifier = Modifier
) {
    val mappable = remember(nearby) { nearby.filter { it.station.mapPoint() != null } }
    if (mappable.isEmpty()) return
    var loaded by rememberSaveable { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.map_nearby_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (!loaded) {
                OpenMapNote(stringResource(R.string.map_nearby_notice))
                OutlinedButton(onClick = { loaded = true }) { Text(stringResource(R.string.map_nearby_load)) }
                return@Column
            }
            val palette = openMapPalette(MaterialTheme.colorScheme)
            val user = userPosition?.takeIf { it.isValid }
            val content = remember(mappable, user, palette) {
                val points = mappable.mapNotNull { it.station.mapPoint() }
                OpenMapContent(
                    markers = points.map { OpenMapMarker(it, OpenMapMarkerStyles.station(palette)) } +
                        listOfNotNull(user?.let { OpenMapMarker(it, OpenMapMarkerStyles.approximatePosition(palette), tappable = false) }),
                    camera = OpenMapCameraPlanner.plan(points + listOfNotNull(user))
                )
            }
            OpenMap(
                content = content,
                contentDescription = stringResource(R.string.map_nearby_content_description),
                onMarkerTap = { markerIndex -> mappable.getOrNull(markerIndex)?.let { onOpenStation(it.station) } },
                modifier = Modifier.fillMaxWidth().height(MAP_HEIGHT)
            )
            OpenMapAttribution()
            OpenMapNote(stringResource(R.string.map_nearby_tap_hint))
            LegendEntry(OpenMapMarkerStyles.station(palette), stringResource(R.string.station_board_nearby))
            if (user != null) LegendEntry(OpenMapMarkerStyles.approximatePosition(palette), stringResource(R.string.map_legend_user_position))
        }
    }
}

/** Privacy and credit lines for the settings screen, next to the OpenStreetMap catalogue credit. */
@Composable
internal fun OpenMapsPrivacyNotes() {
    Text(stringResource(R.string.settings_privacy_maps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.settings_credits_maps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StationList(stations: List<NearbyStation>, onOpenStation: (Station) -> Unit) {
    Column {
        stations.forEach { nearby ->
            val distance = stringResource(R.string.distance_kilometers, nearby.distanceKm)
            val description = stringResource(R.string.map_open_board_accessibility, nearby.station.name) + " · " + distance
            TextButton(
                onClick = { onOpenStation(nearby.station) },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }
            ) {
                Text(
                    nearby.station.name + " · " + distance,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun OpenMapCardFrame(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onToggle) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(if (expanded) R.string.map_action_hide else R.string.map_action_show))
                }
            }
            content()
        }
    }
}

@Composable
private fun OpenMapNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OpenMapAttribution() {
    Text(stringResource(R.string.map_attribution), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LegendEntry(style: OpenMapMarkerStyle, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(14.dp)
                .background(cssColor(style.color).copy(alpha = style.opacity.coerceIn(0.3f, 1f)), CircleShape)
                .border(2.dp, cssColor(style.strokeColor), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun stopTimes(stop: RouteMapStop): String = stop.expected
    ?.let { stringResource(R.string.map_stop_scheduled_expected, stop.scheduled, it) }
    ?: stringResource(R.string.map_stop_scheduled, stop.scheduled)

@Composable
private fun stopRoles(stop: RouteMapStop): String = buildList {
    if (RouteStopRole.ORIGIN in stop.roles) add(stringResource(R.string.map_role_origin))
    if (RouteStopRole.DESTINATION in stop.roles) add(stringResource(R.string.map_role_destination))
    if (RouteStopRole.LAST_CONFIRMED in stop.roles) add(stringResource(R.string.map_role_last_confirmed))
    if (RouteStopRole.NEXT in stop.roles) add(stringResource(R.string.map_role_next))
    if (RouteStopRole.SKIPPED in stop.roles) add(stringResource(R.string.map_role_skipped))
}.joinToString(" · ")

@Composable
private fun routeStopLine(stop: RouteMapStop): String = listOfNotNull(
    stop.name,
    stopTimes(stop),
    stopRoles(stop).takeIf { it.isNotEmpty() },
    if (stop.point == null) stringResource(R.string.map_stop_not_on_map) else null
).joinToString(" · ")

private fun openMapPalette(colors: ColorScheme): OpenMapPalette = OpenMapPalette(
    primary = OpenMapMarkerStyles.cssHex(colors.primary.toArgb()),
    tertiary = OpenMapMarkerStyles.cssHex(colors.tertiary.toArgb()),
    error = OpenMapMarkerStyles.cssHex(colors.error.toArgb()),
    surface = OpenMapMarkerStyles.cssHex(colors.surface.toArgb()),
    secondary = OpenMapMarkerStyles.cssHex(colors.secondary.toArgb())
)

private fun cssColor(hex: String): Color = Color(0xFF000000 or hex.removePrefix("#").toLong(16))
