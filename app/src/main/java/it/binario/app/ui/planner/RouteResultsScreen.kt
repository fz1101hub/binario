package it.binario.app.ui.planner

import it.binario.app.ui.common.LocalizedDates
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.BinarioTheme
import it.binario.app.JourneyChangeFilter
import it.binario.app.R
import it.binario.app.RefreshState
import it.binario.app.TrainViewModel
import it.binario.app.TravelPreferencesRepository
import it.binario.app.data.provider.JourneyPreferences
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailTime
import it.binario.app.data.TrainService
import it.binario.app.data.repository.JourneySearchStatus
import it.binario.app.journeys.JourneyEmptyReason
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.journeys.JourneyResultsFilter
import it.binario.app.journeys.JourneyResultsPresentation
import it.binario.app.journeys.JourneySearchUiState
import it.binario.app.localized
import it.binario.app.maps.OpenMapStationIndex
import it.binario.app.maps.rememberOpenMapStationIndex
import it.binario.app.timetables.SavedTimetable
import it.binario.app.timetables.SavedTimetableStore
import it.binario.app.timetables.TimetableShareFormatter
import it.binario.app.ui.common.DataStatusLine
import it.binario.app.ui.common.HeaderSheetTop
import it.binario.app.ui.common.InfoNoticeCard
import it.binario.app.ui.common.ResultCard
import it.binario.app.ui.common.ScreenHeader
import it.binario.app.ui.common.ScreenMargin
import it.binario.app.ui.common.SearchInfoState
import it.binario.app.ui.common.SectionTitle
import it.binario.app.ui.common.StationShortNames
import it.binario.app.ui.common.TonalButton
import it.binario.app.ui.common.DataState
import it.binario.app.ui.common.dataState
import it.binario.app.ui.common.localizedProviderLabel
import it.binario.app.ui.common.localizedDisplayName
import it.binario.app.ui.common.localizedAccessibleLabel
import it.binario.app.ui.common.localizedLabel
import it.binario.app.ui.common.localizedQualityLabel
import it.binario.app.ui.common.ltrUnit
import it.binario.app.ui.common.rememberCo2
import it.binario.app.ui.journeys.JourneyResultCard
import it.binario.app.ui.journeys.journeyLegLabel
import it.binario.app.ui.common.NetworkReliabilityLines
import it.binario.app.ui.common.NetworkReliabilityText
import it.binario.app.ui.common.ReliabilityAggregateLines
import it.binario.app.ui.common.RoutePunctualityLines
import it.binario.app.ui.common.networkReliabilityAttribution
import it.binario.app.ui.common.rememberNetworkReliability
import it.binario.app.ui.common.rememberReliabilityAggregateStrings
import it.binario.app.ui.common.rememberNetworkReliabilityStrings
import it.binario.app.ui.common.UsualPlatformLines
import it.binario.app.ui.common.rememberUsualPlatformStrings
import it.binario.app.data.reliability.UsualPlatform
import it.binario.app.data.reliability.NetworkReliabilityStations
import it.binario.app.data.reliability.NetworkReliability
import it.binario.app.data.reliability.NetworkReliabilityAggregates
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// Pianifica: the solutions of a route or round-trip search.

/**
 * Results of a route search as the cards of the render: one list by departure that holds the direct trains of the
 * route search (live or catalogue, unchanged) and the options of the separate journeys-with-changes search, each
 * with its badge ("Diretto", "1 cambio", "2 cambi") and source. A journey without changes that is the same train as
 * a direct-train result is shown once, as the direct train. The chips are the render's: Tutti, Diretti, Meno cambi,
 * Più veloce, over everything shown, and «Più affidabili» with the embedded network statistics, which also add a
 * reliability line to the cards they measure. The notices of the journeys search (in progress, scheduled times only, coverage,
 * why it found nothing) follow the outbound list, so the top of the screen stays the render's.
 *
 * @param journeys state of the journeys-with-changes search; its options appear only when they answer this route,
 *   direction, date and time ([JourneyRequest.isAnsweredBy]).
 * @param selectedServiceId / selectedJourneyId the option shown beside the list on wide windows, tinted in the list.
 */
@Composable
internal fun RouteResultsScreen(
    modifier: Modifier,
    viewModel: TrainViewModel,
    journeys: JourneySearchUiState,
    openDetails: (TrainService) -> Unit,
    openJourney: (JourneyOptionRow) -> Unit,
    retryJourneys: () -> Unit,
    editSearch: () -> Unit,
    selectedServiceId: String? = null,
    selectedJourneyId: String? = null,
    /** "Filtri": Impostazioni › Preferenze di viaggio. */
    openTravelPreferences: () -> Unit = {},
    /** The first direct train as listed (after merge, chips and preferences), previewed beside the list from 840 dp. */
    onFirstShownService: (TrainService?) -> Unit = {},
    /** «Più tardi» / «Più presto» of the journeys with changes ([it.binario.app.journeys.JourneySearchViewModel.loadLater]). */
    loadLaterJourneys: () -> Unit = {},
    loadEarlierJourneys: () -> Unit = {}
) {
    val roundTrip = viewModel.hasReturnJourney
    val primaryDirection = viewModel.direction
    val locale = LocalConfiguration.current.locales[0]
    val query = viewModel.journeyQuery
    val route = viewModel.activeRoute
    val context = LocalContext.current
    val travelPreferences by TravelPreferencesRepository.state(context).collectAsState()
    // The embedded network statistics ("Più affidabili", the reliability lines); never on demonstration data.
    val reliabilityStats = rememberNetworkReliability().takeUnless { viewModel.isDemonstrationData() }
    // "Preferisci" chooses the chip the results open on; a chip tapped afterwards stays until it changes.
    var filterName by rememberSaveable(travelPreferences.ranking) { mutableStateOf(JourneyResultsFilter.forRanking(travelPreferences.ranking).name) }
    val filter = JourneyResultsFilter.valueOf(filterName).let {
        if (it == JourneyResultsFilter.MOST_RELIABLE && reliabilityStats == null) JourneyResultsFilter.ALL else it
    }
    val availableFilters = JourneyResultsFilter.entries.filter { it != JourneyResultsFilter.MOST_RELIABLE || reliabilityStats != null }
    val journeysMatch = JourneyRequest.of(viewModel)?.isAnsweredBy(journeys.query) == true
    val journeyResult = journeys.result.takeIf { journeysMatch }
    // "Ultimo di oggi" and "primo domani" only for a search of today.
    val today = LocalDate.now(RailTime.defaultZone)
    val markedDay = query.serviceDate.takeIf { it == today }
    // Always the unfiltered options: the chips below filter the merged list. The live check of the changes, when it
    // answers, marks the cards too ("Coincidenza a rischio"), not only the detail.
    val liveLegs = journeys.liveLegs
    val presentation = remember(journeyResult, markedDay, liveLegs) { journeyResult?.let { ResultsMerge.journeys(it, markedDay, liveLegs) } }
    val journeyRows = presentation?.rows.orEmpty()
    // The changes filter of the planner applies to the journeys with changes as to the direct trains.
    val changeFilter = viewModel.journeyChangeFilter
    val filteredJourneyRows = remember(journeyRows, changeFilter) { journeyRows.filter { changeFilter.accepts(it.changes) } }
    // The direct trains of the search, then those «Più presto» / «Più tardi» loaded around it (same filters).
    val allServices = viewModel.currentServices() + viewModel.pagedServices()
    val allReturnServices = viewModel.currentReturnServices()
    val entries = remember(allServices, filteredJourneyRows, query.serviceDate, travelPreferences) {
        ResultsMerge.entries(allServices, filteredJourneyRows, query.serviceDate, travelPreferences)
    }
    val returnEntries = remember(allReturnServices, query.serviceDate, travelPreferences) {
        ResultsMerge.entries(allReturnServices, emptyList(), query.serviceDate, travelPreferences)
    }
    val hiddenTrains = allServices.size + allReturnServices.size -
        ResultsMerge.shownServices(allServices, travelPreferences).size - ResultsMerge.shownServices(allReturnServices, travelPreferences).size
    val firstNextDay = markedDay?.let { ResultsMerge.firstDepartureNextDay(entries, journeyRows, it) }
    // «Nessun treno fino alle HH:mm»: the planner's window was empty and the next train leaves later the same day.
    val noTrainsUntil = remember(entries, journeyRows, query, firstNextDay) {
        ResultsMerge.noTrainsUntil(entries, journeyRows, query.requestedAt, firstNextDay)
    }
    // Each row measured at the stop the user leaves it: the destination of the search, or each change.
    val reliabilityStations = remember(viewModel.stations, route) {
        NetworkReliabilityStations(viewModel.stations + listOf(route.outwardOrigin, route.outwardDestination))
    }
    val reliability = remember(entries, reliabilityStats, reliabilityStations, route, primaryDirection) {
        reliabilityStats?.let { ResultsReliability.assess(entries, it, route.origin(primaryDirection), route.destination(primaryDirection), reliabilityStations) }.orEmpty()
    }
    val returnReliability = remember(returnEntries, reliabilityStats, reliabilityStations, route) {
        reliabilityStats?.let { ResultsReliability.assess(returnEntries, it, route.origin(Direction.RETURN), route.destination(Direction.RETURN), reliabilityStations) }.orEmpty()
    }
    // «Di solito al binario 12 (8 volte su 10)» where each row is boarded, while its own platform is not known.
    val usualPlatforms = remember(entries, reliabilityStats, reliabilityStations, route, primaryDirection) {
        reliabilityStats?.let { ResultsReliability.usualPlatforms(entries, it, route.origin(primaryDirection), route.destination(primaryDirection), reliabilityStations) }.orEmpty()
    }
    val returnUsualPlatforms = remember(returnEntries, reliabilityStats, reliabilityStations, route) {
        reliabilityStats?.let { ResultsReliability.usualPlatforms(returnEntries, it, route.origin(Direction.RETURN), route.destination(Direction.RETURN), reliabilityStations) }.orEmpty()
    }
    // «Questa tratta: …»: every observed train from the origin to the destination, with the departure hours.
    val routePunctuality = remember(reliabilityStats, route, primaryDirection, viewModel.hasActiveRoute) {
        reliabilityStats?.takeIf { viewModel.hasActiveRoute }?.let {
            NetworkReliabilityAggregates.route(
                it,
                NetworkReliability.stationCode(route.origin(primaryDirection)),
                NetworkReliability.stationCode(route.destination(primaryDirection))
            )
        }
    }
    val aggregateStrings = rememberReliabilityAggregateStrings()
    val platformStrings = rememberUsualPlatformStrings()
    // The weekday of the day searched (today for a search from now), when the route runs differently on it.
    val routePunctualityText = remember(routePunctuality, aggregateStrings, platformStrings, query.serviceDate) {
        routePunctuality?.let { punctuality ->
            val weekday = punctuality.weekdayRemark(query.serviceDate.dayOfWeek)?.let { UsualPlatformLines.weekday(it, platformStrings) }
            ReliabilityAggregateLines.route(punctuality, aggregateStrings, listOfNotNull(weekday))
        }
    }
    val shown = ResultsMerge.apply(entries, filter, travelPreferences, reliability)
    val returnShown = ResultsMerge.apply(returnEntries, filter, travelPreferences, returnReliability)
    val counts = ResultsMerge.counts(entries + returnEntries)
    val firstShownTrain = shown.firstNotNullOfOrNull { (it as? ResultEntry.Train)?.service }
    LaunchedEffect(firstShownTrain?.id) { onFirstShownService(firstShownTrain) }
    val journeysShown = entries.any { it is ResultEntry.Journey }
    // «Più presto» / «Più tardi»: each source pages from its own first and last departure, the chips keep their order.
    val servicesCanPage = allServices.isNotEmpty()
    val journeysCanPage = journeysMatch && !journeys.loading && journeyResult?.options?.isNotEmpty() == true
    val pagingAvailable = viewModel.hasActiveRoute && entries.isNotEmpty() && (servicesCanPage || journeysCanPage)
    val serviceEarlier = viewModel.earlierServicesPage()
    val serviceLater = viewModel.laterServicesPage()
    val earlierLoading = serviceEarlier.loading || (journeysCanPage && journeys.earlier.loading)
    val laterLoading = serviceLater.loading || (journeysCanPage && journeys.later.loading)
    // "Nothing new" once every source that can page has looked and found nothing.
    val earlierNothingNew = !earlierLoading && (serviceEarlier.nothingNew || journeys.earlier.nothingNew) &&
        (!servicesCanPage || serviceEarlier.nothingNew) && (!journeysCanPage || journeys.earlier.nothingNew)
    val laterNothingNew = !laterLoading && (serviceLater.nothingNew || journeys.later.nothingNew) &&
        (!servicesCanPage || serviceLater.nothingNew) && (!journeysCanPage || journeys.later.nothingNew)
    val isDemonstration = viewModel.isDemonstrationData()
    // Live, scheduled timetable or saved copy, for the status card and the chip of every card.
    val listState = viewModel.dataState()
    val returnListState = viewModel.returnSnapshot?.let { DataState.of(it.freshness, it.confidence, it.services, isDemonstration) } ?: listState
    val resultSummary = if (roundTrip) {
        stringResource(
            R.string.planner_round_trip_results,
            pluralStringResource(R.plurals.planner_outbound_result_count, shown.size, shown.size),
            pluralStringResource(R.plurals.planner_return_result_count, returnShown.size, returnShown.size)
        )
    } else {
        pluralStringResource(R.plurals.search_results_count, shown.size, shown.size)
    }
    // "Roma → Torino" as in the render; screen readers and the cards keep the full station names.
    val headline = if (viewModel.hasActiveRoute) StationShortNames.route(route.origin(primaryDirection), route.destination(primaryDirection)) else null
    // "Salva orario" and "Condividi": the list on screen as plain rows (labels in the app language, demonstration
    // data as timetable only), saved on this device or shared as text.
    val providerLabel = viewModel.localizedProviderLabel()
    val journeysFetchedAt = journeyResult?.generatedAt?.toEpochMilli() ?: 0L
    // The plain rows are built only when "Salva orario" or "Condividi" is tapped, from the list shown at that moment.
    val resources = LocalResources.current
    val refreshedAt = viewModel.lastRefreshEpochMillis()
    fun outboundRows() = timetableRows(shown, isDemonstration, providerLabel, refreshedAt, journeysFetchedAt, resources)
    fun returnRows() = if (roundTrip) timetableRows(returnShown, isDemonstration, providerLabel, refreshedAt, journeysFetchedAt, resources) else emptyList()
    val originName = route.origin(primaryDirection).name
    val destinationName = route.destination(primaryDirection).name
    val defaultTimetableName = stringResource(R.string.share_route, originName, destinationName)
    val shareStrings = timetableShareStrings()
    val searchLabel = searchDateLabel(query.serviceDate, query.departureTime)
    val shareChooser = stringResource(R.string.share_timetable_chooser)
    // A search from now keeps its saved copy while the clock moves on.
    var savedTimetableId by rememberSaveable(route.id, primaryDirection, if (viewModel.journeyFollowsCurrentTime) null else query) { mutableStateOf<String?>(null) }
    val stationIndex = rememberOpenMapStationIndex(viewModel.stations, viewModel::stationFromName)

    val outboundStates = ResultStates(
        loading = stringResource(R.string.search_outbound_loading_title) to stringResource(R.string.search_outbound_loading_message),
        unavailable = stringResource(R.string.search_outbound_unavailable_title) to
            (viewModel.refreshMessage?.localized() ?: stringResource(R.string.search_outbound_unavailable_message)),
        empty = stringResource(R.string.search_outbound_empty_title) to stringResource(R.string.search_outbound_empty_message),
        noDirect = stringResource(R.string.journey_empty_no_direct)
    )
    val returnStates = ResultStates(
        loading = stringResource(R.string.search_return_loading_title) to stringResource(R.string.search_return_loading_message),
        unavailable = stringResource(R.string.search_return_unavailable_title) to
            (viewModel.returnRefreshMessage?.localized() ?: stringResource(R.string.search_return_unavailable_message)),
        empty = stringResource(R.string.search_return_empty_title) to stringResource(R.string.search_return_empty_message),
        noDirect = stringResource(R.string.journey_empty_no_direct)
    )
    LazyColumn(modifier = modifier.fillMaxSize().testTag("route-results")) {
        item {
            ScreenHeader(
                title = ltrUnit(headline?.short ?: route.localizedDisplayName(primaryDirection)),
                titleDescription = headline?.full,
                subtitle = journeyLabel(query, locale),
                trailing = {
                    TonalButton(
                        text = stringResource(R.string.action_modify_search),
                        onClick = editSearch,
                        leadingIcon = Icons.Default.Edit,
                        onHeader = true,
                        modifier = Modifier.testTag("results-modify")
                    )
                }
            )
        }
        item {
            HeaderSheetTop {
                ResultFilterChips(filters = availableFilters, selected = filter, counts = counts, onSelect = { filterName = it.name })
                if (changeFilter != JourneyChangeFilter.ANY) {
                    ActiveChangeFilterChip(changeFilter, onClear = { viewModel.selectJourneyChangeFilter(JourneyChangeFilter.ANY) })
                }
                Text(
                    listOfNotNull(resultSummary, viewModel.refreshState.localizedLabel(), viewModel.refreshMessage?.localized()).joinToString(" · "),
                    modifier = Modifier.padding(horizontal = ScreenMargin, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DataStatusLine(
                    state = listState,
                    freshness = viewModel.snapshot?.freshness,
                    provider = providerLabel,
                    confidence = viewModel.localizedQualityLabel(),
                    refreshedAt = viewModel.lastRefreshEpochMillis(),
                    message = null
                )
                TravelPreferencesLine(
                    preferences = travelPreferences,
                    hiddenTrains = hiddenTrains,
                    onOpen = openTravelPreferences
                )
                routePunctualityText?.let { text ->
                    RoutePunctualityLines(
                        text,
                        windowEnd = null,
                        modifier = Modifier.padding(horizontal = ScreenMargin, vertical = 4.dp).testTag("results-route-punctuality")
                    )
                }
                if (reliabilityStats != null && (reliability.isNotEmpty() || returnReliability.isNotEmpty() || routePunctualityText != null ||
                        usualPlatforms.isNotEmpty() || returnUsualPlatforms.isNotEmpty())
                ) {
                    Text(
                        networkReliabilityAttribution(reliabilityStats.windowEnd),
                        modifier = Modifier.padding(horizontal = ScreenMargin, vertical = 4.dp).testTag("results-reliability-note"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (viewModel.hasActiveRoute && (shown.isNotEmpty() || (roundTrip && returnShown.isNotEmpty()))) {
                    ResultsActions(
                        saved = savedTimetableId != null,
                        onSave = {
                            val id = savedTimetableId ?: UUID.randomUUID().toString()
                            SavedTimetableStore.save(
                                context,
                                SavedTimetable(
                                    id = id,
                                    name = defaultTimetableName,
                                    origin = originName,
                                    destination = destinationName,
                                    serviceDate = query.serviceDate,
                                    fromTime = query.departureTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)),
                                    savedAtEpochMillis = System.currentTimeMillis(),
                                    rows = outboundRows(),
                                    returnRows = returnRows()
                                )
                            )
                            savedTimetableId = id
                        },
                        onShare = {
                            startTextShare(
                                context,
                                TimetableShareFormatter.list(originName, destinationName, searchLabel, outboundRows(), returnRows(), shareStrings),
                                shareChooser
                            )
                        }
                    )
                }
            }
        }
        if (roundTrip) {
            item { SectionTitle(stringResource(R.string.planner_outbound) + " · " + route.localizedDisplayName(primaryDirection)) }
        }
        firstNextDay?.let { first ->
            item(key = "no-more-today") {
                InfoNoticeCard(
                    text = stringResource(R.string.journey_no_more_today, first.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))),
                    modifier = Modifier.padding(top = 6.dp).testTag("results-no-more-today")
                )
            }
        }
        noTrainsUntil?.let { first ->
            item(key = "no-trains-until") {
                InfoNoticeCard(
                    text = stringResource(R.string.results_no_trains_until, first.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))),
                    modifier = Modifier.padding(top = 6.dp).testTag("results-no-trains-until")
                )
            }
        }
        if (pagingAvailable) {
            item(key = "out-earlier") {
                ResultsPageControl(
                    label = stringResource(R.string.results_earlier),
                    icon = Icons.Default.ExpandLess,
                    loading = earlierLoading,
                    nothingNew = earlierNothingNew.takeIf { it }?.let { stringResource(R.string.results_no_more_earlier) },
                    onClick = {
                        viewModel.loadEarlierServices()
                        if (journeysCanPage) loadEarlierJourneys()
                    },
                    modifier = Modifier.testTag("results-earlier")
                )
            }
        }
        resultItems(
            keyPrefix = "out",
            entries = shown,
            anyEntries = entries.isNotEmpty(),
            state = viewModel.refreshState,
            hasSnapshot = viewModel.snapshot != null,
            states = outboundStates,
            isDemonstration = isDemonstration,
            listState = listState,
            selectedServiceId = selectedServiceId,
            selectedJourneyId = selectedJourneyId,
            openDetails = openDetails,
            openJourney = openJourney,
            stationIndex = stationIndex,
            reliability = reliability,
            usualPlatforms = usualPlatforms
        )
        if (pagingAvailable) {
            item(key = "out-later") {
                ResultsPageControl(
                    label = stringResource(R.string.results_later),
                    icon = Icons.Default.ExpandMore,
                    loading = laterLoading,
                    nothingNew = laterNothingNew.takeIf { it }?.let { stringResource(R.string.results_no_more_later) },
                    onClick = {
                        viewModel.loadLaterServices()
                        if (journeysCanPage) loadLaterJourneys()
                    },
                    modifier = Modifier.testTag("results-later")
                )
            }
        }
        if (journeysMatch) {
            item(key = "journey-notices") {
                Column(Modifier.padding(top = 6.dp).testTag("journey-notices")) {
                    JourneyNotices(
                        loading = journeys.loading,
                        presentation = presentation?.takeIf { !journeys.loading },
                        optionsShown = journeysShown,
                        onRetry = retryJourneys
                    )
                }
            }
        }
        if (roundTrip) {
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    SectionTitle(stringResource(R.string.planner_return) + " · " + route.localizedDisplayName(Direction.RETURN))
                    Text(
                        listOfNotNull(viewModel.returnRefreshState.localizedLabel(), viewModel.returnRefreshMessage?.localized()).joinToString(" · "),
                        modifier = Modifier.padding(horizontal = ScreenMargin),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            resultItems(
                keyPrefix = "ret",
                entries = returnShown,
                anyEntries = returnEntries.isNotEmpty(),
                state = viewModel.returnRefreshState,
                hasSnapshot = viewModel.returnSnapshot != null,
                states = returnStates,
                isDemonstration = isDemonstration,
                listState = returnListState,
                selectedServiceId = selectedServiceId,
                selectedJourneyId = null,
                openDetails = openDetails,
                openJourney = openJourney,
                stationIndex = stationIndex,
                reliability = returnReliability,
                usualPlatforms = returnUsualPlatforms
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** "Salva orario" (again after a save it updates the same saved copy) and "Condividi" for the list on screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultsActions(saved: Boolean, onSave: () -> Unit, onShare: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ScreenMargin, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TonalButton(
                text = stringResource(R.string.results_save_timetable),
                onClick = onSave,
                leadingIcon = if (saved) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd,
                modifier = Modifier.testTag("results-save-timetable")
            )
            TonalButton(
                text = stringResource(R.string.action_share),
                onClick = onShare,
                leadingIcon = Icons.Default.Share,
                modifier = Modifier.testTag("results-share")
            )
        }
        if (saved) {
            Text(
                stringResource(R.string.results_timetable_saved),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Title and message of the loading, unavailable and empty states of one leg. */
private class ResultStates(
    val loading: Pair<String, String>,
    val unavailable: Pair<String, String>,
    val empty: Pair<String, String>,
    val noDirect: String
)

private fun LazyListScope.resultItems(
    keyPrefix: String,
    entries: List<ResultEntry>,
    anyEntries: Boolean,
    state: RefreshState,
    hasSnapshot: Boolean,
    states: ResultStates,
    isDemonstration: Boolean,
    listState: DataState,
    selectedServiceId: String?,
    selectedJourneyId: String?,
    openDetails: (TrainService) -> Unit,
    openJourney: (JourneyOptionRow) -> Unit,
    stationIndex: OpenMapStationIndex,
    reliability: Map<String, EntryReliability>,
    usualPlatforms: Map<String, UsualPlatform> = emptyMap()
) {
    when {
        state == RefreshState.LOADING && !hasSnapshot ->
            item(key = "$keyPrefix-loading") { SearchInfoState(states.loading.first, states.loading.second) }
        state == RefreshState.ERROR && !hasSnapshot ->
            item(key = "$keyPrefix-error") { SearchInfoState(states.unavailable.first, states.unavailable.second) }
    }
    when {
        entries.isNotEmpty() -> items(entries, key = { "$keyPrefix-${it.key}" }) { entry ->
            val itemModifier = Modifier.padding(horizontal = ScreenMargin, vertical = 6.dp)
            when (entry) {
                is ResultEntry.Train -> ResultCard(
                    service = entry.service,
                    isDemonstration = isDemonstration,
                    onOpen = { openDetails(entry.service) },
                    modifier = itemModifier.testTag("result-card"),
                    selected = entry.service.id == selectedServiceId,
                    co2 = rememberCo2(entry.service, stationIndex),
                    lastOfDay = entry.lastOfDay,
                    dataState = DataState.ofService(entry.service, listState),
                    reliability = rememberEntryReliabilityText(reliability[entry.key], null, usualPlatforms[entry.key])
                )
                is ResultEntry.Journey -> JourneyResultCard(
                    row = entry.row,
                    onOpen = { openJourney(entry.row) },
                    modifier = itemModifier.testTag("journey-result-card"),
                    selected = entry.row.id == selectedJourneyId,
                    co2 = rememberCo2(entry.row, stationIndex),
                    reliability = rememberEntryReliabilityText(reliability[entry.key], entry.row, usualPlatforms[entry.key])
                )
            }
        }
        // "Diretti" can hide every option that exists.
        anyEntries -> item(key = "$keyPrefix-no-direct") { SearchInfoState(states.noDirect, "") }
        hasSnapshot || state == RefreshState.SUCCESS -> item(key = "$keyPrefix-empty") { SearchInfoState(states.empty.first, states.empty.second) }
    }
}

/**
 * Notices of the journeys-with-changes search in the style of the other notice cards: the search in progress,
 * scheduled times only, the networks the offline timetable covers, incomplete data, and why it found nothing.
 */
@Composable
private fun JourneyNotices(
    loading: Boolean,
    presentation: JourneyResultsPresentation?,
    optionsShown: Boolean,
    onRetry: () -> Unit
) {
    if (loading) {
        InfoNoticeCard(text = stringResource(R.string.journey_searching), modifier = Modifier.testTag("journeys-loading")) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        return
    }
    presentation ?: return
    if (optionsShown && presentation.showScheduledOnlyNotice) {
        InfoNoticeCard(
            title = stringResource(R.string.journey_results_title),
            text = stringResource(R.string.journey_scheduled_only_notice),
            modifier = Modifier.testTag("journeys-scheduled-only")
        )
    }
    if (optionsShown && presentation.showCoverageNotice) {
        InfoNoticeCard(text = stringResource(R.string.journey_coverage_notice))
    }
    if (presentation.showIncompleteNotice) {
        InfoNoticeCard(text = stringResource(R.string.journey_incomplete_notice))
    }
    if (presentation.accessibilityDataUnavailable) {
        InfoNoticeCard(text = stringResource(R.string.journey_accessibility_unavailable), modifier = Modifier.testTag("journeys-accessibility-unavailable"))
    }
    if (presentation.status != JourneySearchStatus.FOUND) {
        val reason = presentation.emptyReason ?: JourneyEmptyReason.UNAVAILABLE
        InfoNoticeCard(
            title = stringResource(R.string.journey_results_title),
            text = stringResource(reason.messageRes()),
            actionLabel = if (reason == JourneyEmptyReason.UNAVAILABLE) stringResource(R.string.notices_retry) else null,
            onAction = onRetry,
            modifier = Modifier.testTag("journeys-empty")
        )
    }
}

internal fun JourneyEmptyReason.messageRes(): Int = when (this) {
    JourneyEmptyReason.NO_SOLUTION -> R.string.journey_empty_no_solution
    JourneyEmptyReason.NOT_COVERED -> R.string.journey_empty_not_covered
    JourneyEmptyReason.SCHEDULE_EXPIRED -> R.string.journey_empty_schedule_expired
    JourneyEmptyReason.UNAVAILABLE -> R.string.journey_empty_unavailable
    JourneyEmptyReason.NO_DIRECT -> R.string.journey_empty_no_direct
}

/**
 * The line of a result card from its network statistics ([EntryReliability]): the train at the destination, or the
 * weakest train of a journey (named by [row]'s leg label when it has several) and its weakest change; then the usual
 * platform where it is boarded ([usualPlatform]), while its own platform is not known.
 */
@Composable
private fun rememberEntryReliabilityText(assessment: EntryReliability?, row: JourneyOptionRow?, usualPlatform: UsualPlatform? = null): NetworkReliabilityText? {
    if (assessment == null && usualPlatform == null) return null
    val strings = rememberNetworkReliabilityStrings()
    val platformStrings = rememberUsualPlatformStrings()
    val resources = LocalResources.current
    return remember(assessment, row, usualPlatform, strings, platformStrings) {
        val lines = when (assessment) {
            null -> emptyList()
            is EntryReliability.Train -> listOf(NetworkReliabilityLines.train(assessment.stop, strings))
            is EntryReliability.Journey -> NetworkReliabilityLines.journey(
                assessment.journey,
                legLabel = { index -> row?.legs?.getOrNull(index)?.let { journeyLegLabel(it, resources) }.orEmpty() },
                strings = strings
            )
        } + listOfNotNull(usualPlatform?.let { UsualPlatformLines.usualPlatform(it, platformStrings) })
        NetworkReliabilityLines.text(lines, strings)
    }
}

/**
 * «Più presto» / «Più tardi» under or above the list: the button, a progress bar while the window loads, and
 * «Nessun'altra soluzione …» when it found nothing new (announced politely).
 */
@Composable
private fun ResultsPageControl(
    label: String,
    icon: ImageVector,
    loading: Boolean,
    nothingNew: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().padding(horizontal = ScreenMargin, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 22.dp))
        } else {
            TonalButton(text = label, onClick = onClick, leadingIcon = icon, modifier = Modifier.fillMaxWidth())
        }
        nothingNew?.let {
            Text(
                it,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The chips of the render, and «Più affidabili» when the network statistics are available ([filters]); each
 * announces whether it is selected and how many options it shows.
 */
@Composable
private fun ResultFilterChips(
    filters: List<JourneyResultsFilter>,
    selected: JourneyResultsFilter,
    counts: Map<JourneyResultsFilter, Int>,
    onSelect: (JourneyResultsFilter) -> Unit
) {
    val colors = BinarioTheme.colors
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = colors.segmentTrack,
        labelColor = colors.onSegmentTrack,
        selectedContainerColor = colors.segmentSelected,
        selectedLabelColor = colors.onSegmentSelected
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag("results-chips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = ScreenMargin)
    ) {
        items(filters) { filter ->
            val label = stringResource(filter.labelRes())
            val count = counts[filter] ?: 0
            val description = label + ", " + pluralStringResource(R.plurals.search_results_count, count, count)
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(label, fontWeight = if (filter == selected) FontWeight.Bold else FontWeight.Medium) },
                colors = chipColors,
                border = null,
                modifier = Modifier
                    .semantics { contentDescription = description }
                    .testTag("results-filter-${filter.name.lowercase(Locale.ROOT)}")
            )
        }
    }
}

private fun JourneyResultsFilter.labelRes(): Int = when (this) {
    JourneyResultsFilter.ALL -> R.string.journey_filter_all
    JourneyResultsFilter.DIRECT -> R.string.journey_filter_direct
    JourneyResultsFilter.FEWEST_CHANGES -> R.string.journey_filter_fewest_changes
    JourneyResultsFilter.FASTEST -> R.string.journey_filter_fastest
    JourneyResultsFilter.LEAST_WALKING -> R.string.journey_filter_least_walking
    JourneyResultsFilter.MOST_RELIABLE -> R.string.journey_filter_most_reliable
}

/** The changes filter chosen in the planner ("Diretto", "Max 1 cambio"), shown on the results; a tap removes it. */
@Composable
private fun ActiveChangeFilterChip(filter: JourneyChangeFilter, onClear: () -> Unit) {
    val description = stringResource(R.string.results_change_filter_remove, filter.localizedAccessibleLabel())
    InputChip(
        selected = true,
        onClick = onClear,
        label = { Text(filter.localizedLabel()) },
        trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier
            .padding(horizontal = ScreenMargin)
            .semantics { contentDescription = description }
            .testTag("results-change-filter")
    )
}

/**
 * "Preferenze di viaggio": opens Impostazioni › Preferenze di viaggio (a different thing from the planner's
 * filters); with "Solo percorsi accessibili" on it says so, and it counts the direct trains the preferences hide.
 */
@Composable
private fun TravelPreferencesLine(preferences: JourneyPreferences, hiddenTrains: Int, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ScreenMargin, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TonalButton(
            text = stringResource(R.string.settings_section_travel),
            onClick = onOpen,
            leadingIcon = Icons.Default.Tune,
            modifier = Modifier.testTag("results-travel-preferences")
        )
        if (preferences.wheelchairOnly) {
            Text(
                stringResource(R.string.travel_wheelchair_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (hiddenTrains > 0) {
            Text(
                pluralStringResource(R.plurals.results_hidden_by_preferences, hiddenTrains, hiddenTrains),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** "Oggi, 21 nov · dalle 17:00" for the date and time the results answer. */
@Composable
internal fun journeyLabel(query: JourneyQuery, locale: Locale): String {
    val today = LocalDate.now(RailTime.defaultZone)
    val date = if (query.serviceDate == today) {
        stringResource(R.string.results_date_today, query.serviceDate.format(LocalizedDates.dayMonth(locale)))
    } else {
        query.serviceDate.format(LocalizedDates.weekdayDayMonth(locale))
    }
    return stringResource(R.string.results_date_from_time, date, query.departureTime.format(DateTimeFormatter.ofPattern("HH:mm", locale)))
}
