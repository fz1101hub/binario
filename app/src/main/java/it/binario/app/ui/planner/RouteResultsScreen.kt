package it.binario.app.ui.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import it.binario.app.R
import it.binario.app.RefreshState
import it.binario.app.TrainViewModel
import it.binario.app.data.Direction
import it.binario.app.data.TrainService
import it.binario.app.localized
import it.binario.app.ui.common.BoardColumnHeader
import it.binario.app.ui.common.DepartureListRow
import it.binario.app.ui.common.SearchInfoState
import it.binario.app.ui.common.localizedDisplayName
import it.binario.app.ui.common.localizedLabel

// Pianifica: the solutions of a route or round-trip search.

@Composable
internal fun RouteResultsScreen(modifier: Modifier, viewModel: TrainViewModel, openDetails: (TrainService) -> Unit) {
    val services = viewModel.currentServices()
    val returnServices = viewModel.currentReturnServices()
    val roundTrip = viewModel.hasReturnJourney
    val primaryDirection = viewModel.direction
    val locale = LocalConfiguration.current.locales[0]
    val resultSummary = if (roundTrip) {
        stringResource(
            R.string.planner_round_trip_results,
            pluralStringResource(R.plurals.planner_outbound_result_count, services.size, services.size),
            pluralStringResource(R.plurals.planner_return_result_count, returnServices.size, returnServices.size)
        )
    } else {
        pluralStringResource(R.plurals.search_results_count, services.size, services.size)
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(viewModel.activeRoute.localizedDisplayName(primaryDirection), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    resultSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (roundTrip) stringResource(R.string.planner_outbound) else primaryDirection.localizedLabel().uppercase(locale), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(viewModel.activeRoute.localizedDisplayName(primaryDirection), style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(viewModel.refreshState.localizedLabel(), viewModel.refreshMessage?.localized()).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            BoardColumnHeader()
        }
        when {
            viewModel.refreshState == RefreshState.LOADING && viewModel.snapshot == null ->
                item { SearchInfoState(stringResource(R.string.search_outbound_loading_title), stringResource(R.string.search_outbound_loading_message)) }
            viewModel.refreshState == RefreshState.ERROR && viewModel.snapshot == null ->
                item { SearchInfoState(stringResource(R.string.search_outbound_unavailable_title), viewModel.refreshMessage?.localized() ?: stringResource(R.string.search_outbound_unavailable_message)) }
            services.isEmpty() ->
                item { SearchInfoState(stringResource(R.string.search_outbound_empty_title), stringResource(R.string.search_outbound_empty_message)) }
            else -> items(services, key = { "out-${it.id}" }) { service ->
                DepartureListRow(service, isNext = service == services.first(), onOpen = { openDetails(service) })
            }
        }

        if (roundTrip) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.planner_return), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(viewModel.activeRoute.localizedDisplayName(Direction.RETURN), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(viewModel.returnRefreshState.localizedLabel(), viewModel.returnRefreshMessage?.localized()).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BoardColumnHeader()
            }
            when {
                viewModel.returnRefreshState == RefreshState.LOADING && viewModel.returnSnapshot == null ->
                    item { SearchInfoState(stringResource(R.string.search_return_loading_title), stringResource(R.string.search_return_loading_message)) }
                viewModel.returnRefreshState == RefreshState.ERROR && viewModel.returnSnapshot == null ->
                    item { SearchInfoState(stringResource(R.string.search_return_unavailable_title), viewModel.returnRefreshMessage?.localized() ?: stringResource(R.string.search_return_unavailable_message)) }
                returnServices.isEmpty() ->
                    item { SearchInfoState(stringResource(R.string.search_return_empty_title), stringResource(R.string.search_return_empty_message)) }
                else -> items(returnServices, key = { "ret-${it.id}" }) { service ->
                    DepartureListRow(service, isNext = service == returnServices.first(), onOpen = { openDetails(service) })
                }
            }
        }
    }
}
