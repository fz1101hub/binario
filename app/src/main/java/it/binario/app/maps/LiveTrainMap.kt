package it.binario.app.maps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import it.binario.app.R
import it.binario.app.TrainViewModel
import it.binario.app.data.RailTime
import it.binario.app.data.Station
import it.binario.app.data.TrainService
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.StationId
import it.binario.app.data.journey.ScheduledRailRun
import it.binario.app.data.journey.ScheduledRunRequest
import it.binario.app.data.journey.ScheduledRunScope
import it.binario.app.data.provider.JourneyEndpoint
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.data.repository.withoutExpiredObservations
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What a map of trains in motion is about: the active route of the board, or the area around a station. */
internal sealed interface LiveTrainMapScope {
    data class Route(val origin: Station, val destination: Station) : LiveTrainMapScope
    data class Area(val station: Station) : LiveTrainMapScope
}

/** The fixed parts of one map, from catalogue coordinates: null when a station of the scope has none. */
internal data class LiveTrainMapSetup(
    /** Distances in the list are measured from here (the board's station). */
    val focus: GeoPoint,
    /** The stations drawn for orientation (route ends, or the station). */
    val places: List<GeoPoint>,
    val camera: OpenMapCamera,
    /** The area assumed visible until the map reports its own. */
    val initialArea: GeoBounds
) {
    companion object {
        fun of(scope: LiveTrainMapScope, index: OpenMapStationIndex): LiveTrainMapSetup? = when (scope) {
            is LiveTrainMapScope.Route -> {
                val origin = index.coordinatesOf(scope.origin)?.mapPoint()
                val destination = index.coordinatesOf(scope.destination)?.mapPoint()
                val camera = if (origin != null && destination != null) OpenMapCameraPlanner.plan(listOf(origin, destination)) else null
                if (origin == null || destination == null || camera == null) null
                else LiveTrainMapSetup(origin, listOf(origin, destination), camera, LiveTrainAreas.routeArea(origin, destination))
            }
            is LiveTrainMapScope.Area -> index.coordinatesOf(scope.station)?.mapPoint()?.let { centre ->
                val area = LiveTrainAreas.around(centre)
                LiveTrainMapSetup(centre, listOf(centre), OpenMapCamera.Fit(area), area)
            }
        }
    }
}

/**
 * "Treni in movimento" under the route board: the trains of the active route (in the board's direction) on a map,
 * placed by their timetable and by the delay the board already shows. A tap opens the board's own detail of a listed
 * train, otherwise the train-number search for it.
 */
@Composable
internal fun RouteLiveTrainsEntry(
    viewModel: TrainViewModel,
    origin: Station,
    destination: Station,
    onOpenService: (TrainService) -> Unit,
    onOpenTrainNumber: (number: String, origin: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val stationIndex = rememberOpenMapStationIndex(viewModel.stations, viewModel::stationFromName)
    val scope = remember(origin, destination) { LiveTrainMapScope.Route(origin, destination) }
    if (remember(scope, stationIndex) { LiveTrainMapSetup.of(scope, stationIndex) } == null) return
    var open by rememberSaveable(origin.id, destination.id) { mutableStateOf(false) }
    LiveTrainMapButton(onClick = { open = true }, modifier = modifier)
    if (!open) return
    val services = viewModel.homeServices()
    val trusted = !viewModel.isDemonstrationData()
    val observations = remember(services, trusted) {
        LiveTrainObservations.fromServices(services, trusted, LocalDate.now(RailTime.defaultZone))
    }
    LiveTrainMapDialog(
        scope = scope,
        stationIndex = stationIndex,
        observations = observations,
        onOpenTrain = { train ->
            open = false
            val service = train.observation?.let { LiveTrainObservations.rowOf(it, observations) }?.let(services::getOrNull)
            if (service != null) onOpenService(service) else openByNumber(train, onOpenTrainNumber)
        },
        onDismiss = { open = false }
    )
}

/**
 * "Treni in movimento" in the station board: the trains around [station]. A train of the loaded board opens its run
 * details; any other one the train-number search.
 */
@Composable
internal fun StationLiveTrainsEntry(
    viewModel: TrainViewModel,
    station: Station,
    onOpenTrainNumber: (number: String, origin: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val stationIndex = rememberOpenMapStationIndex(viewModel.stations, viewModel::stationFromName)
    val scope = remember(station) { LiveTrainMapScope.Area(station) }
    if (remember(scope, stationIndex) { LiveTrainMapSetup.of(scope, stationIndex) } == null) return
    var open by rememberSaveable(station.id) { mutableStateOf(false) }
    LiveTrainMapButton(onClick = { open = true }, modifier = modifier)
    if (!open) return
    val board = viewModel.stationBoard?.takeIf { it.station.id == station.id }
    val entries = remember(board) { board?.entries.orEmpty().map { it.withoutExpiredObservations(Instant.now()) } }
    val observations = remember(entries) { LiveTrainObservations.fromBoard(entries) }
    LiveTrainMapDialog(
        scope = scope,
        stationIndex = stationIndex,
        observations = observations,
        onOpenTrain = { train ->
            open = false
            val entry = train.observation?.let { LiveTrainObservations.rowOf(it, observations) }?.let(entries::getOrNull)
                ?.takeIf { it.serviceKind != ServiceKind.URBAN }
            if (entry != null) viewModel.openStationBoardRun(entry) else openByNumber(train, onOpenTrainNumber)
        },
        onDismiss = { open = false }
    )
}

private fun openByNumber(train: LiveTrain, onOpenTrainNumber: (String, String) -> Unit) {
    val number = train.run.publicNumber ?: return
    onOpenTrainNumber(number, train.run.originName)
}

@Composable
private fun LiveTrainMapButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Icon(Icons.Default.Map, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.live_map_title))
    }
}

/**
 * The map of trains in motion, full screen: the map, its legend and the same trains as a list sorted by distance
 * (the alternative TalkBack reads). Positions are computed off the main thread every 30 seconds while the app is in
 * the foreground; the timetable is read from the offline packs once per quarter of an hour, and no request is made
 * for the trains themselves.
 */
@Composable
internal fun LiveTrainMapDialog(
    scope: LiveTrainMapScope,
    stationIndex: OpenMapStationIndex,
    observations: List<LiveRunObservation>,
    onOpenTrain: (LiveTrain) -> Unit,
    onDismiss: () -> Unit
) {
    val setup = remember(scope, stationIndex) { LiveTrainMapSetup.of(scope, stationIndex) }
    if (setup == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val subtitle = when (scope) {
        is LiveTrainMapScope.Route -> stringResource(R.string.planned_journey_route, scope.origin.name, scope.destination.name)
        is LiveTrainMapScope.Area -> stringResource(R.string.live_map_area_subtitle, scope.station.name)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.live_map_title),
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                    }
                }
                LiveTrainMapBody(scope, setup, subtitle, observations, onOpenTrain)
            }
        }
    }
}

@Composable
private fun ColumnScope.LiveTrainMapBody(
    scope: LiveTrainMapScope,
    setup: LiveTrainMapSetup,
    subtitle: String,
    observations: List<LiveRunObservation>,
    onOpenTrain: (LiveTrain) -> Unit
) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableStateOf(railNow()) }
    // Only while the app is in the foreground: the loop stops with the activity and restarts when it is visible again.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = railNow()
                delay(LiveTrainRefresh.POSITIONS_MILLIS)
            }
        }
    }
    var visible by remember(setup) { mutableStateOf(setup.initialArea) }
    val areaScope = scope is LiveTrainMapScope.Area
    val tooWide = areaScope && LiveTrainAreas.tooWide(visible)
    var readArea by remember(setup) { mutableStateOf(LiveTrainAreas.readArea(setup.initialArea)) }
    LaunchedEffect(visible, tooWide) {
        if (areaScope && !tooWide && !LiveTrainAreas.covers(readArea, visible)) readArea = LiveTrainAreas.readArea(visible)
    }
    val slot = LiveTrainRefresh.slotStart(now)
    val runs by produceState<List<ScheduledRailRun>?>(null, scope, slot, if (areaScope) readArea else null, tooWide) {
        if (tooWide) return@produceState
        value = try {
            BinarioDataGraph.offlineJourneyPlanner(context).scheduledRailRuns(runRequest(scope, slot, readArea))
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            emptyList()
        }
    }
    val selection by produceState<LiveTrainSelection?>(null, runs, now, visible, observations, tooWide) {
        val loaded = runs ?: return@produceState
        value = if (tooWide) LiveTrainSelection(emptyList(), 0) else withContext(Dispatchers.Default) {
            LiveTrainSelector.select(loaded, observations, now, visible, setup.focus)
        }
    }
    val colors = MaterialTheme.colorScheme
    val palette = openMapPalette(colors)
    val trains = selection?.trains.orEmpty()
    val labelColor = colors.onSurface.toArgb()
    val haloColor = colors.surface.toArgb()
    val content = remember(setup, trains, palette, labelColor, haloColor) {
        OpenMapContent(
            markers = setup.places.map { OpenMapMarker(it, OpenMapMarkerStyles.focusStation(palette), tappable = false) } +
                trains.map { OpenMapMarker(it.point, OpenMapMarkerStyles.liveTrain(it.estimate, palette)) },
            camera = setup.camera,
            labels = trains.map { OpenMapLabel(it.point, LiveTrainLabels.marker(it)) },
            labelColor = labelColor,
            labelHaloColor = haloColor
        )
    }
    val currentTrains by rememberUpdatedState(trains)
    OpenMap(
        content = content,
        contentDescription = stringResource(R.string.live_map_content_description, subtitle),
        onMarkerTap = { markerIndex -> currentTrains.getOrNull(markerIndex - setup.places.size)?.let(onOpenTrain) },
        modifier = Modifier.fillMaxWidth().fillMaxHeight(MAP_HEIGHT_FRACTION).padding(horizontal = 8.dp),
        onVisibleAreaChanged = { visible = it }
    )
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "attribution") { OpenMapAttribution() }
        item(key = "legend") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LegendEntry(OpenMapMarkerStyles.liveTrain(LiveTrainEstimate.LIVE_DELAY, palette), stringResource(R.string.live_map_legend_live))
                LegendEntry(OpenMapMarkerStyles.liveTrain(LiveTrainEstimate.TIMETABLE, palette), stringResource(R.string.live_map_legend_timetable))
                LegendEntry(OpenMapMarkerStyles.focusStation(palette), subtitle)
            }
        }
        item(key = "note") { OpenMapNote(stringResource(R.string.live_map_note)) }
        item(key = "status") {
            val current = selection
            val status = when {
                tooWide -> stringResource(R.string.live_map_zoom_in)
                runs == null || current == null -> stringResource(R.string.live_map_loading)
                current.trains.isEmpty() -> stringResource(R.string.live_map_none)
                current.capped -> pluralStringResource(R.plurals.live_map_count, current.inBounds, current.inBounds) + " " +
                    pluralStringResource(R.plurals.live_map_capped, current.trains.size, current.trains.size)
                else -> pluralStringResource(R.plurals.live_map_count, current.trains.size, current.trains.size)
            }
            // A heading, so that TalkBack can jump from the map to the list of the same trains.
            Text(
                status,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
        items(trains, key = { it.run.id }) { train ->
            LiveTrainRow(train, palette, onOpen = { onOpenTrain(train) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun LiveTrainRow(train: LiveTrain, palette: OpenMapPalette, onOpen: () -> Unit) {
    val title = stringResource(R.string.live_map_row_title, LiveTrainLabels.title(train.run), train.destination)
    val place = train.stopName?.let { stringResource(R.string.live_map_row_at, it) }
        ?: train.between?.let { (from, to) -> stringResource(R.string.live_map_row_between, from, to) }
    val estimate = when (val delay = train.delayMinutes) {
        null -> stringResource(R.string.live_map_row_timetable)
        else -> stringResource(
            R.string.live_map_row_live,
            if (delay > 0) pluralStringResource(R.plurals.operational_delay_minutes, delay, delay) else stringResource(R.string.operational_on_time)
        )
    }
    val distance = stringResource(R.string.distance_kilometers, train.distanceKm)
    val description = stringResource(
        R.string.accessibility_open_service,
        listOfNotNull(title, place).joinToString(". "),
        listOf(estimate, distance).joinToString(". ")
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { onOpen(); true }
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OpenMapMarkerDot(OpenMapMarkerStyles.liveTrain(train.estimate, palette))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            place?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "$estimate · $distance",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun runRequest(scope: LiveTrainMapScope, at: LocalDateTime, area: GeoBounds): ScheduledRunRequest = ScheduledRunRequest(
    at = at,
    scope = when (scope) {
        is LiveTrainMapScope.Route -> ScheduledRunScope.Route(
            StationId(scope.origin.id), scope.origin.endpoint(), StationId(scope.destination.id), scope.destination.endpoint()
        )
        is LiveTrainMapScope.Area -> ScheduledRunScope.Area(area.north, area.south, area.east, area.west)
    }
)

private fun Station.endpoint(): JourneyEndpoint? =
    name.takeIf(String::isNotBlank)?.let { JourneyEndpoint(name = it, aliases = aliases, railwayCode = railwayCode) }

private fun railNow(): LocalDateTime = LocalDateTime.now(RailTime.defaultZone)

/** Share of the dialog's height the map takes; the legend and the list scroll below it. */
private const val MAP_HEIGHT_FRACTION = 0.5f
