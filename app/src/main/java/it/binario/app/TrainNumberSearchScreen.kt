package it.binario.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.data.RailTime
import it.binario.app.data.repository.TrainNumberMatch
import it.binario.app.data.repository.TrainNumberQuery
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun TrainNumberSearchForm(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("train-number-search"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.planner_train_filter_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.planner_train_filter_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = query, onValueChange = onQueryChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.planner_train_number)) }, placeholder = { Text(stringResource(R.string.planner_train_number_example)) })
        Button(onClick = onSearch, enabled = TrainNumberQuery(query, LocalDate.now(RailTime.defaultZone)).valid,
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.planner_filter_train)) }
    }
}

@Composable
internal fun TrainNumberResultsScreen(modifier: Modifier, viewModel: TrainViewModel, onOpen: (TrainNumberMatch) -> Unit) {
    LazyColumn(modifier.fillMaxSize().testTag("train-number-results")) {
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.train_number_results_heading, viewModel.trainNumberQuery), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.train_number_results_subtitle), style = MaterialTheme.typography.bodyMedium)
                if (viewModel.trainNumberSearchState == RefreshState.LOADING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.train_number_loading), style = MaterialTheme.typography.bodySmall)
                }
                if (viewModel.trainNumberUnavailableSources.isNotEmpty()) {
                    Text(stringResource(R.string.train_number_sources_partial), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (viewModel.trainNumberMatches.isEmpty() && viewModel.trainNumberSearchState != RefreshState.LOADING) {
                    Text(stringResource(if (viewModel.trainNumberSearchState == RefreshState.ERROR) R.string.train_number_unavailable else R.string.train_number_not_found))
                }
            }
        }
        items(viewModel.trainNumberMatches, key = { it.id }) { match ->
            Column {
                Text("${match.service.origin} → ${match.service.destination}", modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.train_number_run_date,
                    (match.service.scheduledDepartureDateTime?.toLocalDate() ?: viewModel.trainNumberSearchDate).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))),
                    modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
                Text(match.snapshot.providerName, modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                DepartureListRow(match.service, onOpen = { onOpen(match) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
