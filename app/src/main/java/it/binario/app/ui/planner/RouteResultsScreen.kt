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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import it.binario.app.R
import it.binario.app.RefreshState
import it.binario.app.TrainViewModel
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailTime
import it.binario.app.data.TrainService
import it.binario.app.data.repository.JourneySearchStatus
import it.binario.app.journeys.JourneyEmptyReason
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.journeys.JourneyResultsFilter
import it.binario.app.journeys.JourneyResultsPresentation
import it.binario.app.journeys.JourneyResultsPresenter
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
import it.binario.app.ui.common.isCachedCopy
import it.binario.app.ui.common.localizedDisplayName
import it.binario.app.ui.common.localizedLabel
import it.binario.app.ui.common.localizedQualityLabel
import it.binario.app.ui.common.ltrUnit
import it.binario.app.ui.common.rememberCo2
import it.binario.app.ui.journeys.JourneyResultCard
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
 * Più veloce, over everything shown. The notices of the journeys search (in progress, scheduled times only, coverage,
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
    selectedJourneyId: String? = null
) {
    val roundTrip = viewModel.hasReturnJourney
    val primaryDirection = viewModel.direction
    val locale = LocalConfiguration.current.locales[0]
    val query = viewModel.journeyQuery
    val route = viewModel.activeRoute
    var filterName by rememberSaveable { mutableStateOf(JourneyResultsFilter.ALL.name) }
    val filter = JourneyResultsFilter.valueOf(filterName)
    val journeysMatch = JourneyRequest.of(viewModel)?.isAnsweredBy(journeys.query) == true
    val journeyResult = journeys.result.takeIf { journeysMatch }
    // Always the unfiltered options: the chips below filter the merged list.
    val presentation = remember(journeyResult) { journeyResult?.let { JourneyResultsPresenter.present(it) } }
    val journeyRows = presentation?.rows.orEmpty()
    val allServices = viewModel.currentServices()
    val allReturnServices = viewModel.currentReturnServices()
    val entries = remember(allServices, journeyRows, query.serviceDate) { ResultsMerge.entries(allServices, journeyRows, query.serviceDate) }
    val returnEntries = remember(allReturnServices, query.serviceDate) { ResultsMerge.entries(allReturnServices, emptyList(), query.serviceDate) }
    val shown = ResultsMerge.apply(entries, filter)
    val returnShown = ResultsMerge.apply(returnEntries, filter)
    val counts = ResultsMerge.counts(entries + returnEntries)
    val journeysShown = entries.any { it is ResultEntry.Journey }
    val isDemonstration = viewModel.isDemonstrationData()
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
    val context = LocalContext.current
    val providerLabel = viewModel.currentProviderLabel() ?: stringResource(R.string.provider_unavailable)
    val journeysFetchedAt = journeyResult?.generatedAt?.toEpochMilli() ?: 0L
    val outboundRows = timetableRows(shown, isDemonstration, providerLabel, viewModel.lastRefreshEpochMillis(), journeysFetchedAt)
    val returnRows = if (roundTrip) timetableRows(returnShown, isDemonstration, providerLabel, viewModel.lastRefreshEpochMillis(), journeysFetchedAt) else emptyList()
    val originName = route.origin(primaryDirection).name
    val destinationName = route.destination(primaryDirection).name
    val defaultTimetableName = stringResource(R.string.share_route, originName, destinationName)
    val shareStrings = timetableShareStrings()
    val searchLabel = searchDateLabel(query.serviceDate, query.departureTime)
    val shareChooser = stringResource(R.string.share_timetable_chooser)
    var savedTimetableId by rememberSaveable(route.id, primaryDirection, query) { mutableStateOf<String?>(null) }
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
                ResultFilterChips(selected = filter, counts = counts, onSelect = { filterName = it.name })
                Text(
                    listOfNotNull(resultSummary, viewModel.refreshState.localizedLabel(), viewModel.refreshMessage?.localized()).joinToString(" · "),
                    modifier = Modifier.padding(horizontal = ScreenMargin, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DataStatusLine(
                    freshness = viewModel.snapshot?.freshness,
                    provider = viewModel.currentProviderLabel() ?: stringResource(R.string.provider_unavailable),
                    confidence = viewModel.localizedQualityLabel(),
                    refreshedAt = viewModel.lastRefreshEpochMillis(),
                    isDemonstration = isDemonstration,
                    isCachedCopy = viewModel.isCachedCopy(),
                    message = null
                )
                if (viewModel.hasActiveRoute && (outboundRows.isNotEmpty() || returnRows.isNotEmpty())) {
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
                                    rows = outboundRows,
                                    returnRows = returnRows
                                )
                            )
                            savedTimetableId = id
                        },
                        onShare = {
                            startTextShare(
                                context,
                                TimetableShareFormatter.list(originName, destinationName, searchLabel, outboundRows, returnRows, shareStrings),
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
        resultItems(
            keyPrefix = "out",
            entries = shown,
            anyEntries = entries.isNotEmpty(),
            state = viewModel.refreshState,
            hasSnapshot = viewModel.snapshot != null,
            states = outboundStates,
            isDemonstration = isDemonstration,
            selectedServiceId = selectedServiceId,
            selectedJourneyId = selectedJourneyId,
            openDetails = openDetails,
            openJourney = openJourney,
            stationIndex = stationIndex
        )
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
                selectedServiceId = selectedServiceId,
                selectedJourneyId = null,
                openDetails = openDetails,
                openJourney = openJourney,
                stationIndex = stationIndex
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
    selectedServiceId: String?,
    selectedJourneyId: String?,
    openDetails: (TrainService) -> Unit,
    openJourney: (JourneyOptionRow) -> Unit,
    stationIndex: OpenMapStationIndex
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
                    co2 = rememberCo2(entry.service, stationIndex)
                )
                is ResultEntry.Journey -> JourneyResultCard(
                    row = entry.row,
                    onOpen = { openJourney(entry.row) },
                    modifier = itemModifier.testTag("journey-result-card"),
                    selected = entry.row.id == selectedJourneyId,
                    co2 = rememberCo2(entry.row, stationIndex)
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

/** The four chips of the render; each announces whether it is selected and how many options it shows. */
@Composable
private fun ResultFilterChips(selected: JourneyResultsFilter, counts: Map<JourneyResultsFilter, Int>, onSelect: (JourneyResultsFilter) -> Unit) {
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
        items(JourneyResultsFilter.entries) { filter ->
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
