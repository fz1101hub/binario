package it.binario.app.ui.planner

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import it.binario.app.DictationReviewStatus
import it.binario.app.DictationTarget
import it.binario.app.JourneyChangeFilter
import it.binario.app.PlannerDateTime
import it.binario.app.PlannerVoiceParser
import it.binario.app.R
import it.binario.app.TrainNumberSearchForm
import it.binario.app.TrainViewModel
import it.binario.app.data.Direction
import it.binario.app.data.RailTime
import it.binario.app.data.Station
import it.binario.app.feedback
import it.binario.app.resolve
import it.binario.app.ui.common.StationField
import it.binario.app.ui.common.localizedAccessibleLabel
import it.binario.app.ui.common.localizedDisplayName
import it.binario.app.ui.common.localizedLabel
import it.binario.app.ui.common.localizedName
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

// Pianifica: journey, train-number and station search.

internal enum class SearchMode { ROUTE, TRAIN, STATION }

@Composable
internal fun SearchMode.localizedLabel(): String = stringResource(
    when (this) {
        SearchMode.ROUTE -> R.string.search_mode_route
        SearchMode.TRAIN -> R.string.search_mode_train
        SearchMode.STATION -> R.string.search_mode_station
    }
)

@Composable
internal fun PlannerScreen(
    modifier: Modifier,
    viewModel: TrainViewModel,
    initialMode: SearchMode,
    modeRequestId: Int,
    showResults: () -> Unit,
    showTrainResults: () -> Unit,
    openMore: () -> Unit,
    openStations: (Station?) -> Unit
) {
    var originText by rememberSaveable { mutableStateOf(viewModel.plannerOriginText()) }
    var destinationText by rememberSaveable { mutableStateOf(viewModel.plannerDestinationText()) }
    var returnJourney by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var stationText by rememberSaveable { mutableStateOf("") }
    var travelDate by rememberSaveable { mutableStateOf(LocalDate.now(RailTime.defaultZone).toString()) }
    var travelTime by rememberSaveable { mutableStateOf(LocalTime.now(RailTime.defaultZone).withSecond(0).withNano(0).toString()) }
    var returnDate by rememberSaveable { mutableStateOf(LocalDate.now(RailTime.defaultZone).plusDays(1).toString()) }
    var returnTime by rememberSaveable { mutableStateOf(LocalTime.now(RailTime.defaultZone).withSecond(0).withNano(0).toString()) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showReturnDatePicker by rememberSaveable { mutableStateOf(false) }
    var showReturnTimePicker by rememberSaveable { mutableStateOf(false) }
    var speechMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var voiceTargetName by rememberSaveable { mutableStateOf(SearchMode.ROUTE.name) }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val resources = LocalResources.current
    val dateUnselected = stringResource(R.string.planner_date_unselected)
    val timeUnselected = stringResource(R.string.planner_time_unselected)
    LaunchedEffect(modeRequestId) { mode = initialMode }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val voiceTarget = SearchMode.valueOf(voiceTargetName)
        val spoken = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else null
        if (spoken.isNullOrBlank()) {
            speechMessage = resources.getString(R.string.voice_no_text)
        } else {
            val dictationTarget = when (voiceTarget) {
                SearchMode.ROUTE -> DictationTarget.ROUTE
                SearchMode.TRAIN -> DictationTarget.TRAIN
                SearchMode.STATION -> DictationTarget.STATION
            }
            val review = PlannerVoiceParser.review(spoken, viewModel.stations, dictationTarget)
            val dictated = review.input
            if (review.status == DictationReviewStatus.CONFIRMED) when (voiceTarget) {
                SearchMode.ROUTE -> {
                    dictated.origin?.let { originText = it.name }
                    dictated.destination?.let { destinationText = it.name }
                    dictated.date?.let { travelDate = it.toString() }
                    dictated.time?.let { travelTime = it.toString() }
                    dictated.returnJourney?.let { requestedReturn ->
                        returnJourney = requestedReturn
                        if (requestedReturn) {
                            val outboundDate = PlannerDateTime.dateOrToday(travelDate)
                            val outboundTime = PlannerDateTime.timeOrNow(travelTime)
                            val currentReturnDate = PlannerDateTime.dateOrToday(returnDate)
                            val currentReturnTime = PlannerDateTime.timeOrNow(returnTime)
                            if (!PlannerDateTime.isReturnAfterDeparture(outboundDate, outboundTime, currentReturnDate, currentReturnTime)) {
                                returnDate = outboundDate.plusDays(1).toString()
                                returnTime = outboundTime.toString()
                            }
                        }
                    }
                }
                SearchMode.TRAIN -> {
                    dictated.trainNumber?.let(viewModel::updateTrainNumberQuery)
                }
                SearchMode.STATION -> {
                    (dictated.station ?: dictated.origin)?.let { stationText = it.name }
                }
            }
            speechMessage = review.feedback(dictationTarget).resolve(resources)
        }
    }
    fun startVoiceInput(target: SearchMode) {
        voiceTargetName = target.name
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, when (target) {
                SearchMode.ROUTE -> resources.getString(R.string.voice_prompt_route)
                SearchMode.TRAIN -> resources.getString(R.string.voice_prompt_train)
                SearchMode.STATION -> resources.getString(R.string.voice_prompt_station)
            })
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            speechMessage = resources.getString(R.string.voice_unavailable)
        } else voiceLauncher.launch(intent)
    }
    if (showDatePicker) {
        val selected = PlannerDateTime.dateOrToday(travelDate)
        LaunchedEffect(selected) {
            DatePickerDialog(context, { _, year, month, day ->
                travelDate = LocalDate.of(year, month + 1, day).toString()
                showDatePicker = false
            }, selected.year, selected.monthValue - 1, selected.dayOfMonth).apply {
                setOnDismissListener { showDatePicker = false }
                show()
            }
        }
    }
    if (showTimePicker) {
        val selected = PlannerDateTime.timeOrNow(travelTime)
        LaunchedEffect(selected) {
            TimePickerDialog(context, { _, hour, minute ->
                travelTime = LocalTime.of(hour, minute).toString()
                showTimePicker = false
            }, selected.hour, selected.minute, true).apply {
                setOnDismissListener { showTimePicker = false }
                show()
            }
        }
    }
    if (showReturnDatePicker) {
        val selected = PlannerDateTime.dateOrToday(returnDate)
        LaunchedEffect(selected) {
            DatePickerDialog(context, { _, year, month, day ->
                returnDate = LocalDate.of(year, month + 1, day).toString()
                showReturnDatePicker = false
            }, selected.year, selected.monthValue - 1, selected.dayOfMonth).apply {
                setOnDismissListener { showReturnDatePicker = false }
                show()
            }
        }
    }
    if (showReturnTimePicker) {
        val selected = PlannerDateTime.timeOrNow(returnTime)
        LaunchedEffect(selected) {
            TimePickerDialog(context, { _, hour, minute ->
                returnTime = LocalTime.of(hour, minute).toString()
                showReturnTimePicker = false
            }, selected.hour, selected.minute, true).apply {
                setOnDismissListener { showReturnTimePicker = false }
                show()
            }
        }
    }
    val origin = viewModel.stationFromName(originText)
    val destination = viewModel.stationFromName(destinationText)
    val selectedDepartureDate = runCatching { LocalDate.parse(travelDate) }.getOrNull()
    val selectedDepartureTime = runCatching { LocalTime.parse(travelTime) }.getOrNull()
    val selectedReturnDate = runCatching { LocalDate.parse(returnDate) }.getOrNull()
    val selectedReturnTime = runCatching { LocalTime.parse(returnTime) }.getOrNull()
    val roundTripChronologyValid = !returnJourney || (
        selectedDepartureDate != null &&
            selectedDepartureTime != null &&
            selectedReturnDate != null &&
            selectedReturnTime != null &&
            PlannerDateTime.isReturnAfterDeparture(
                selectedDepartureDate,
                selectedDepartureTime,
                selectedReturnDate,
                selectedReturnTime
            )
        )
    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            PlannerHeader(isDemonstration = viewModel.isDemonstrationData())
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SearchMode.entries) { item -> FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item.localizedLabel()) }) }
                }
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (mode) {
                        SearchMode.ROUTE -> {
                        VoiceInputButton(onClick = { startVoiceInput(SearchMode.ROUTE) }, label = stringResource(R.string.planner_voice_route))
                        speechMessage?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(stringResource(R.string.planner_departure_station), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        StationField(
                            label = stringResource(R.string.planner_origin),
                            value = originText,
                            onValueChange = { originText = it; viewModel.updateStationSuggestions("planner-origin", it) },
                            matches = viewModel.stationSuggestions("planner-origin"),
                            onSelect = { originText = it.name; viewModel.clearStationSuggestions("planner-origin") }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.planner_arrival_station), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { val old = originText; originText = destinationText; destinationText = old }) {
                                Icon(Icons.Default.SwapHoriz, contentDescription = stringResource(R.string.action_swap_origin_destination))
                            }
                        }
                        StationField(
                            label = stringResource(R.string.planner_destination),
                            value = destinationText,
                            onValueChange = { destinationText = it; viewModel.updateStationSuggestions("planner-destination", it) },
                            matches = viewModel.stationSuggestions("planner-destination"),
                            onSelect = { destinationText = it.name; viewModel.clearStationSuggestions("planner-destination") }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FilterChip(selected = !returnJourney, onClick = { returnJourney = false }, label = { Text(stringResource(R.string.planner_one_way)) })
                            FilterChip(
                                selected = returnJourney,
                                onClick = {
                                    returnJourney = true
                                    val outboundDate = PlannerDateTime.dateOrToday(travelDate)
                                    val outboundTime = PlannerDateTime.timeOrNow(travelTime)
                                    val currentReturnDate = PlannerDateTime.dateOrToday(returnDate)
                                    val currentReturnTime = PlannerDateTime.timeOrNow(returnTime)
                                    if (!PlannerDateTime.isReturnAfterDeparture(outboundDate, outboundTime, currentReturnDate, currentReturnTime)) {
                                        returnDate = outboundDate.plusDays(1).toString()
                                        returnTime = outboundTime.toString()
                                    }
                                },
                                label = { Text(stringResource(R.string.planner_round_trip)) }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            val outboundDateDescription = stringResource(
                                R.string.accessibility_select_outbound_date,
                                PlannerDateTime.dateLabel(travelDate, locale, dateUnselected)
                            )
                            val outboundTimeDescription = stringResource(
                                R.string.accessibility_select_outbound_time,
                                PlannerDateTime.timeLabel(travelTime, locale, timeUnselected)
                            )
                            OutlinedButton(
                                onClick = { showDatePicker = true },
                                modifier = Modifier.weight(1f).semantics { contentDescription = outboundDateDescription }
                            ) {
                                Column(horizontalAlignment = Alignment.Start) { Text(stringResource(R.string.planner_outbound_date), style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.dateLabel(travelDate, locale, dateUnselected), fontWeight = FontWeight.Bold) }
                            }
                            OutlinedButton(
                                onClick = { showTimePicker = true },
                                modifier = Modifier.weight(1f).semantics { contentDescription = outboundTimeDescription }
                            ) {
                                Column(horizontalAlignment = Alignment.Start) { Text(stringResource(R.string.planner_outbound_time), style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.timeLabel(travelTime, locale, timeUnselected), fontWeight = FontWeight.Bold) }
                            }
                        }
                        if (returnJourney) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                val returnDateDescription = stringResource(
                                    R.string.accessibility_select_return_date,
                                    PlannerDateTime.dateLabel(returnDate, locale, dateUnselected)
                                )
                                val returnTimeDescription = stringResource(
                                    R.string.accessibility_select_return_time,
                                    PlannerDateTime.timeLabel(returnTime, locale, timeUnselected)
                                )
                                OutlinedButton(
                                    onClick = { showReturnDatePicker = true },
                                    modifier = Modifier.weight(1f).semantics { contentDescription = returnDateDescription }
                                ) {
                                    Column(horizontalAlignment = Alignment.Start) { Text(stringResource(R.string.planner_return_date), style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.dateLabel(returnDate, locale, dateUnselected), fontWeight = FontWeight.Bold) }
                                }
                                OutlinedButton(
                                    onClick = { showReturnTimePicker = true },
                                    modifier = Modifier.weight(1f).semantics { contentDescription = returnTimeDescription }
                                ) {
                                    Column(horizontalAlignment = Alignment.Start) { Text(stringResource(R.string.planner_return_time), style = MaterialTheme.typography.labelSmall); Text(PlannerDateTime.timeLabel(returnTime, locale, timeUnselected), fontWeight = FontWeight.Bold) }
                                }
                            }
                            if (!roundTripChronologyValid) {
                                Text(
                                    stringResource(R.string.planner_return_after_outbound),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        FilterChip(
                            selected = showFilters,
                            onClick = { showFilters = !showFilters },
                            label = { Text(stringResource(if (showFilters) R.string.planner_filters_open else R.string.planner_filters_all_trains)) },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        if (showFilters) {
                            JourneyChangeFilterBar(viewModel.journeyChangeFilter, viewModel::selectJourneyChangeFilter)
                            OperatorFilterBar(viewModel.operatorFilter, viewModel.operatorFilterOptions(), viewModel::selectOperatorFilter)
                            Text(stringResource(R.string.planner_filter_disclosure), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = {
                                val searched = if (
                                    origin != null &&
                                    destination != null &&
                                    origin != destination &&
                                    selectedDepartureDate != null &&
                                    selectedDepartureTime != null
                                ) {
                                    if (
                                        returnJourney &&
                                        selectedReturnDate != null &&
                                        selectedReturnTime != null &&
                                        roundTripChronologyValid
                                    ) {
                                        viewModel.searchRoundTrip(
                                            origin,
                                            destination,
                                            selectedDepartureDate,
                                            selectedDepartureTime,
                                            selectedReturnDate,
                                            selectedReturnTime
                                        )
                                    } else if (!returnJourney) {
                                        viewModel.searchRoute(origin, destination, selectedDepartureDate, selectedDepartureTime)
                                    } else false
                                } else false
                                if (searched) showResults()
                            },
                            enabled = origin != null &&
                                destination != null &&
                                origin != destination &&
                                selectedDepartureDate != null &&
                                selectedDepartureTime != null &&
                                roundTripChronologyValid,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (returnJourney) R.string.planner_search_round_trip else R.string.planner_search_solutions))
                        }
                        }
                        SearchMode.TRAIN -> {
                            VoiceInputButton(onClick = { startVoiceInput(SearchMode.TRAIN) }, label = stringResource(R.string.planner_voice_train))
                            speechMessage?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            TrainNumberSearchForm(
                                query = viewModel.trainNumberQuery,
                                onQueryChange = viewModel::updateTrainNumberQuery,
                                onSearch = { viewModel.searchTrainNumber(); showTrainResults() }
                            )
                        }
                        SearchMode.STATION -> {
                            VoiceInputButton(onClick = { startVoiceInput(SearchMode.STATION) }, label = stringResource(R.string.planner_voice_station))
                            speechMessage?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(stringResource(R.string.planner_station_board_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            StationField(
                                label = stringResource(R.string.station_label),
                                value = stationText,
                                onValueChange = { stationText = it; viewModel.updateStationSuggestions("planner-station", it) },
                                matches = viewModel.stationSuggestions("planner-station"),
                                onSelect = { stationText = it.name; viewModel.clearStationSuggestions("planner-station") }
                            )
                            Button(
                                onClick = { openStations(viewModel.stationFromName(stationText)) },
                                enabled = viewModel.stationFromName(stationText) != null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Route, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.planner_open_station_board))
                            }
                        }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.planner_recent_and_favourite), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = openMore) { Text(stringResource(R.string.action_manage)) }
            }
        }
        items(viewModel.recentRoutes.take(4), key = { "recent-${it.origin.id}-${it.destination.id}" }) { recent ->
            Row(
                Modifier.fillMaxWidth().clickable { if (viewModel.useRecentRoute(recent)) { originText = recent.origin.name; destinationText = recent.destination.name } }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(recent.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.planner_recent_search), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.accessibility_repeat_search, recent.label))
            }
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        items(viewModel.savedRoutes.filter { it.isPinned }.take(4), key = { it.id }) { route ->
            Row(
                Modifier.fillMaxWidth().clickable { viewModel.activateRoute(route); originText = route.outwardOrigin.name; destinationText = route.outwardDestination.name }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (route.isPinned) Icons.Default.Star else Icons.Default.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(route.localizedName(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(route.localizedDisplayName(Direction.OUTBOUND), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.accessibility_use_route, route.localizedName()))
            }
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Planner-first home: the green app bar continues into the headline, so the question is the first thing read. */
@Composable
private fun PlannerHeader(isDemonstration: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.planner_headline),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(stringResource(R.string.planner_subtitle), style = MaterialTheme.typography.bodyMedium)
            if (isDemonstration) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.data_status_demo), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun OperatorFilterBar(
    selected: String?,
    operators: List<String>,
    onSelected: (String?) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)
    ) {
        item { FilterChip(selected = selected == null, onClick = { onSelected(null) }, label = { Text(stringResource(R.string.filter_all)) }) }
        items(operators, key = { it.lowercase(Locale.ROOT) }) { operator ->
            FilterChip(
                selected = selected?.equals(operator, ignoreCase = true) == true,
                onClick = { onSelected(operator) },
                label = { Text(operator) }
            )
        }
    }
}

@Composable
private fun JourneyChangeFilterBar(selected: JourneyChangeFilter, onSelected: (JourneyChangeFilter) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.journey_changes_uppercase), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(JourneyChangeFilter.entries) { filter ->
                val accessibilityLabel = filter.localizedAccessibleLabel()
                FilterChip(
                    selected = selected == filter,
                    onClick = { onSelected(filter) },
                    label = { Text(filter.localizedLabel()) },
                    modifier = Modifier.semantics { contentDescription = accessibilityLabel }
                )
            }
        }
    }
}

@Composable
private fun VoiceInputButton(onClick: () -> Unit, label: String) {
    val accessibilityLabel = stringResource(R.string.accessibility_system_voice_input, label)
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = accessibilityLabel }
    ) {
        Icon(Icons.Default.Mic, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
