package it.binario.app.ui.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import it.binario.app.BinarioTheme
import it.binario.app.R
import it.binario.app.RefreshState
import it.binario.app.TrainViewModel
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailTime
import it.binario.app.data.TrainService
import it.binario.app.localized
import it.binario.app.ui.common.DataStatusLine
import it.binario.app.ui.common.HeaderSheetTop
import it.binario.app.ui.common.JourneyTimes
import it.binario.app.ui.common.ResultCard
import it.binario.app.ui.common.ResultOrder
import it.binario.app.ui.common.ScreenHeader
import it.binario.app.ui.common.ScreenMargin
import it.binario.app.ui.common.SearchInfoState
import it.binario.app.ui.common.SectionTitle
import it.binario.app.ui.common.ServiceGroup
import it.binario.app.ui.common.TonalButton
import it.binario.app.ui.common.isCachedCopy
import it.binario.app.ui.common.localizedDisplayName
import it.binario.app.ui.common.localizedLabel
import it.binario.app.ui.common.localizedQualityLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pianifica: the solutions of a route or round-trip search.

/**
 * Results of a route search as the cards of the render. The chips filter by the train categories present in
 * the results and order by departure (the neutral default) or by the shortest scheduled journey: the route
 * search returns direct services, so no "fewer changes" filter is offered.
 *
 * @param selectedServiceId the solution shown beside the list on wide windows, tinted in the list.
 */
@Composable
internal fun RouteResultsScreen(
    modifier: Modifier,
    viewModel: TrainViewModel,
    openDetails: (TrainService) -> Unit,
    editSearch: () -> Unit,
    selectedServiceId: String? = null
) {
    val roundTrip = viewModel.hasReturnJourney
    val primaryDirection = viewModel.direction
    val locale = LocalConfiguration.current.locales[0]
    var groupName by rememberSaveable { mutableStateOf<String?>(null) }
    var orderName by rememberSaveable { mutableStateOf(ResultOrder.DEPARTURE.name) }
    val allServices = viewModel.currentServices()
    val allReturnServices = viewModel.currentReturnServices()
    val groups = ServiceGroup.chipsFor(allServices + allReturnServices)
    val group = groupName?.let { name -> ServiceGroup.entries.firstOrNull { it.name == name } }?.takeIf { it in groups }
    val order = ResultOrder.valueOf(orderName)
    val services = JourneyTimes.ordered(JourneyTimes.filtered(allServices, group), order)
    val returnServices = JourneyTimes.ordered(JourneyTimes.filtered(allReturnServices, group), order)
    // Solutions stay in neutral departure order unless the traveller asks for the fastest: no row is promoted.
    val isDemonstration = viewModel.isDemonstrationData()
    val resultSummary = if (roundTrip) {
        stringResource(
            R.string.planner_round_trip_results,
            pluralStringResource(R.plurals.planner_outbound_result_count, services.size, services.size),
            pluralStringResource(R.plurals.planner_return_result_count, returnServices.size, returnServices.size)
        )
    } else {
        pluralStringResource(R.plurals.search_results_count, services.size, services.size)
    }

    val outboundStates = ResultStates(
        loading = stringResource(R.string.search_outbound_loading_title) to stringResource(R.string.search_outbound_loading_message),
        unavailable = stringResource(R.string.search_outbound_unavailable_title) to
            (viewModel.refreshMessage?.localized() ?: stringResource(R.string.search_outbound_unavailable_message)),
        empty = stringResource(R.string.search_outbound_empty_title) to stringResource(R.string.search_outbound_empty_message)
    )
    val returnStates = ResultStates(
        loading = stringResource(R.string.search_return_loading_title) to stringResource(R.string.search_return_loading_message),
        unavailable = stringResource(R.string.search_return_unavailable_title) to
            (viewModel.returnRefreshMessage?.localized() ?: stringResource(R.string.search_return_unavailable_message)),
        empty = stringResource(R.string.search_return_empty_title) to stringResource(R.string.search_return_empty_message)
    )
    LazyColumn(modifier = modifier.fillMaxSize().testTag("route-results")) {
        item {
            ScreenHeader(
                title = viewModel.activeRoute.localizedDisplayName(primaryDirection),
                subtitle = journeyLabel(viewModel.journeyQuery, locale),
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
                ResultChips(
                    groups = groups,
                    selectedGroup = group,
                    onGroup = { groupName = it?.name },
                    order = order,
                    onOrder = { orderName = it.name }
                )
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
            }
        }
        if (roundTrip) {
            item { SectionTitle(stringResource(R.string.planner_outbound) + " · " + viewModel.activeRoute.localizedDisplayName(primaryDirection)) }
        }
        resultItems(
            keyPrefix = "out",
            services = services,
            state = viewModel.refreshState,
            hasSnapshot = viewModel.snapshot != null,
            states = outboundStates,
            isDemonstration = isDemonstration,
            selectedServiceId = selectedServiceId,
            openDetails = openDetails
        )
        if (roundTrip) {
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    SectionTitle(stringResource(R.string.planner_return) + " · " + viewModel.activeRoute.localizedDisplayName(Direction.RETURN))
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
                services = returnServices,
                state = viewModel.returnRefreshState,
                hasSnapshot = viewModel.returnSnapshot != null,
                states = returnStates,
                isDemonstration = isDemonstration,
                selectedServiceId = selectedServiceId,
                openDetails = openDetails
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Title and message of the loading, unavailable and empty states of one leg. */
private class ResultStates(val loading: Pair<String, String>, val unavailable: Pair<String, String>, val empty: Pair<String, String>)

private fun LazyListScope.resultItems(
    keyPrefix: String,
    services: List<TrainService>,
    state: RefreshState,
    hasSnapshot: Boolean,
    states: ResultStates,
    isDemonstration: Boolean,
    selectedServiceId: String?,
    openDetails: (TrainService) -> Unit
) {
    when {
        state == RefreshState.LOADING && !hasSnapshot -> item(key = "$keyPrefix-loading") { SearchInfoState(states.loading.first, states.loading.second) }
        state == RefreshState.ERROR && !hasSnapshot -> item(key = "$keyPrefix-error") { SearchInfoState(states.unavailable.first, states.unavailable.second) }
        services.isEmpty() -> item(key = "$keyPrefix-empty") { SearchInfoState(states.empty.first, states.empty.second) }
        else -> items(services, key = { "$keyPrefix-${it.id}" }) { service ->
            ResultCard(
                service = service,
                isDemonstration = isDemonstration,
                onOpen = { openDetails(service) },
                modifier = Modifier.padding(horizontal = ScreenMargin, vertical = 6.dp).testTag("result-card"),
                selected = service.id == selectedServiceId
            )
        }
    }
}

@Composable
private fun ResultChips(
    groups: List<ServiceGroup>,
    selectedGroup: ServiceGroup?,
    onGroup: (ServiceGroup?) -> Unit,
    order: ResultOrder,
    onOrder: (ResultOrder) -> Unit
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
        if (groups.isNotEmpty()) {
            item {
                FilterChip(selected = selectedGroup == null, onClick = { onGroup(null) }, label = { Text(stringResource(R.string.filter_all), fontWeight = FontWeight.Bold) }, colors = chipColors, border = null)
            }
            items(groups) { group ->
                FilterChip(selected = selectedGroup == group, onClick = { onGroup(group) }, label = { Text(group.localizedLabel()) }, colors = chipColors, border = null)
            }
        }
        item {
            FilterChip(selected = order == ResultOrder.FASTEST, onClick = { onOrder(ResultOrder.FASTEST) }, label = { Text(stringResource(R.string.results_order_fastest)) }, colors = chipColors, border = null)
        }
        item {
            FilterChip(selected = order == ResultOrder.DEPARTURE, onClick = { onOrder(ResultOrder.DEPARTURE) }, label = { Text(stringResource(R.string.results_order_departure)) }, colors = chipColors, border = null)
        }
    }
}

/** "Oggi, 21 nov · dalle 17:00" for the date and time the results answer. */
@Composable
internal fun journeyLabel(query: JourneyQuery, locale: Locale): String {
    val today = LocalDate.now(RailTime.defaultZone)
    val date = if (query.serviceDate == today) {
        stringResource(R.string.results_date_today, query.serviceDate.format(DateTimeFormatter.ofPattern("d MMM", locale)))
    } else {
        query.serviceDate.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
    }
    return stringResource(R.string.results_date_from_time, date, query.departureTime.format(DateTimeFormatter.ofPattern("HH:mm", locale)))
}
