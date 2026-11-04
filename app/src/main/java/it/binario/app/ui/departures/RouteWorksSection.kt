package it.binario.app.ui.departures

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import it.binario.app.R
import it.binario.app.RailNoticeItem
import it.binario.app.SourceSectionCard
import it.binario.app.SourceSectionNote
import it.binario.app.SourceSectionState
import it.binario.app.UNCONFIGURED_ROUTE_ID
import it.binario.app.data.RailTime
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.directRunStations
import it.binario.app.data.notices.NoticeDates
import it.binario.app.data.notices.RouteWorks
import it.binario.app.data.notices.RouteWorksMatcher
import it.binario.app.data.notices.RouteWorksNotice
import it.binario.app.data.notices.WorksRoute
import it.binario.app.data.provider.JourneyEndpoint
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.ui.common.LocalizedDates
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Lavori e interruzioni sulle tue tratte»: the network notices (RFI and, with the personal sources, Trenitalia) about
 * works or interruptions whose text names a station of [routes] (their trains' stops in the embedded timetables) or a
 * line with both ends on one of them, with the route, what the text names and the dates it gives. Read when shown;
 * the feed's own cache decides whether that reaches the network.
 *
 * @param showWhenNone false hides the section when no notice names the routes (the route board); true says so (Per te).
 */
@Composable
internal fun RouteWorksSection(
    routes: List<SavedRoute>,
    catalogue: List<Station>,
    @StringRes title: Int,
    showWhenNone: Boolean,
    modifier: Modifier = Modifier,
    viewModel: RouteWorksViewModel = viewModel()
) {
    if (!viewModel.hasSources) return
    val shown = routes.filter { it.id != UNCONFIGURED_ROUTE_ID }.take(RouteWorksViewModel.MAX_ROUTES)
    if (shown.isEmpty()) return
    val key = RouteWorksViewModel.keyOf(shown)
    LaunchedEffect(key, catalogue.size) { viewModel.load(key, shown, catalogue) }
    val works = (viewModel.stateFor(key) as? SourceSectionState.Ready)?.value ?: return
    if (works.notices.isEmpty() && !showWhenNone && !works.unavailable) return
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    SourceSectionCard(title = stringResource(title), modifier = modifier.testTag("route-works")) {
        if (works.notices.isEmpty() && !works.unavailable) SourceSectionNote(stringResource(R.string.route_works_none))
        val visible = if (expanded) works.notices else works.notices.take(COLLAPSED_NOTICES)
        visible.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            RouteWorksItem(item, showRoute = shown.size > 1, locale = locale)
        }
        val hidden = works.notices.size - visible.size
        if (hidden > 0) {
            TextButton(onClick = { expanded = true }) { Text(pluralStringResource(R.plurals.route_works_show_more, hidden, hidden)) }
        }
        if (works.notices.isNotEmpty()) SourceSectionNote(stringResource(R.string.route_works_note))
        if (works.stale) SourceSectionNote(stringResource(R.string.notices_stale))
        if (works.unavailable) SourceSectionNote(stringResource(R.string.notices_unavailable))
    }
}

@Composable
private fun RouteWorksItem(item: RouteWorksNotice, showRoute: Boolean, locale: Locale) {
    Column(Modifier.fillMaxWidth()) {
        item.reasons.forEach { reason ->
            val named = listOfNotNull(reason.line?.let { stringResource(R.string.route_works_line, it) }) + reason.stations
            val text = named.joinToString(", ")
            Text(
                if (showRoute) stringResource(R.string.route_works_route_names, reason.routeName, text) else stringResource(R.string.route_works_names, text),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        item.dates?.let { SourceSectionNote(datesLabel(it, locale)) }
        RailNoticeItem(item.notice, showLines = false)
    }
}

@Composable
private fun datesLabel(dates: NoticeDates, locale: Locale): String {
    val format = LocalizedDates.dayMonth(locale)
    return if (dates.first == dates.last) stringResource(R.string.route_works_date, dates.first.format(format))
    else stringResource(R.string.route_works_dates, dates.first.format(format), dates.last.format(format))
}

private const val COLLAPSED_NOTICES = 3

/**
 * State of the works sections of Per te and of the route board. The notices come from the network notice sources of
 * the notices area (their own cache, ten minutes for RFI); the stations of each route from the embedded timetables of
 * today, kept for the process. Nothing is stored on the device.
 */
class RouteWorksViewModel(application: Application) : AndroidViewModel(application) {
    private val notices = BinarioDataGraph.railNoticesService()

    val hasSources: Boolean get() = notices.hasNetworkAlertSources

    var state by mutableStateOf<SourceSectionState<RouteWorks>>(SourceSectionState.Idle)
        private set
    private var key: String? = null
    private var loadedAt = 0L
    private var job: Job? = null
    private var matcher: Pair<List<Station>, RouteWorksMatcher>? = null
    private val routeStations = HashMap<String, List<Station>>()

    /** The state for [key] only: another set of routes never shows this one's notices. */
    fun stateFor(key: String): SourceSectionState<RouteWorks> = if (key == this.key) state else SourceSectionState.Loading

    fun load(key: String, routes: List<SavedRoute>, catalogue: List<Station>) {
        if (key == this.key && state != SourceSectionState.Idle && System.currentTimeMillis() - loadedAt in 0 until RELOAD_AFTER_MILLIS) return
        val keepShown = key == this.key && state is SourceSectionState.Ready
        this.key = key
        loadedAt = System.currentTimeMillis()
        job?.cancel()
        if (!keepShown) state = SourceSectionState.Loading
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val read = notices.general(includeTrenord = false)
                val today = LocalDate.now(RailTime.defaultZone)
                val worksRoutes = routes.map { route -> WorksRoute(route.id, route.name, stationsOf(route, today)) }
                RouteWorks(
                    notices = matcherFor(catalogue).match(read.network, worksRoutes, today),
                    unavailable = read.unavailable.isNotEmpty(),
                    stale = read.stale
                )
            }
            if (this@RouteWorksViewModel.key == key) state = SourceSectionState.Ready(result)
        }
    }

    private fun matcherFor(catalogue: List<Station>): RouteWorksMatcher =
        matcher?.takeIf { it.first === catalogue }?.second ?: RouteWorksMatcher(catalogue).also { matcher = catalogue to it }

    /** Both ends and the stations the route's direct trains call at today; the ends alone when no train runs direct. */
    private suspend fun stationsOf(route: SavedRoute, today: LocalDate): List<Station> {
        val origin = route.outwardOrigin
        val destination = route.outwardDestination
        val cacheKey = "${origin.id}>${destination.id}|$today"
        synchronized(routeStations) { routeStations[cacheKey] }?.let { return it }
        val between = try {
            BinarioDataGraph.offlineJourneyPlanner(getApplication()).directRunStations(
                StationId(origin.id), origin.endpoint(), StationId(destination.id), destination.endpoint(), today
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            emptyList()
        }
        return (listOf(origin) + between + listOf(destination)).distinctBy(Station::id)
            .also { synchronized(routeStations) { routeStations[cacheKey] = it } }
    }

    private fun Station.endpoint(): JourneyEndpoint? =
        name.takeIf(String::isNotBlank)?.let { JourneyEndpoint(name = it, aliases = aliases, railwayCode = railwayCode) }

    companion object {
        /** Routes read at most: the pinned ones come first in the saved order. */
        const val MAX_ROUTES = 8
        private const val RELOAD_AFTER_MILLIS = 120_000L

        fun keyOf(routes: List<SavedRoute>): String =
            routes.joinToString("|") { "${it.id}:${it.outwardOrigin.id}>${it.outwardDestination.id}:${it.name}" }
    }
}
