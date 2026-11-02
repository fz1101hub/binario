package it.binario.app.ui.planner

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import it.binario.app.ui.common.BinarioCard
import it.binario.app.ui.common.HeaderOverlapBox
import it.binario.app.ui.common.Pill
import it.binario.app.ui.common.ScreenHeader
import it.binario.app.ui.common.ScreenMargin
import it.binario.app.ui.common.SectionTitle
import it.binario.app.ui.common.SegmentedControl
import it.binario.app.ui.common.StationField
import it.binario.app.ui.common.localizedAccessibleLabel
import it.binario.app.ui.common.localizedDisplayName
import it.binario.app.ui.common.localizedLabel
import it.binario.app.ui.common.localizedName
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
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
    // Until the traveller picks a date or a time the search means "today" and "now" at the moment of searching.
    var dateChosen by rememberSaveable { mutableStateOf(false) }
    var timeChosen by rememberSaveable { mutableStateOf(false) }
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
                    dictated.date?.let { travelDate = it.toString(); dateChosen = true }
                    dictated.time?.let { travelTime = it.toString(); timeChosen = true }
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
                dateChosen = true
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
                timeChosen = true
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
    val search: () -> Unit = {
        // "Adesso" and "Oggi" mean the moment of the search, not the moment the screen was opened.
        val departureDate = if (dateChosen) selectedDepartureDate else LocalDate.now(RailTime.defaultZone)
        val departureTime = if (timeChosen) selectedDepartureTime else LocalTime.now(RailTime.defaultZone).withSecond(0).withNano(0)
        val searched = if (
            origin != null &&
            destination != null &&
            origin != destination &&
            departureDate != null &&
            departureTime != null
        ) {
            if (
                returnJourney &&
                selectedReturnDate != null &&
                selectedReturnTime != null &&
                PlannerDateTime.isReturnAfterDeparture(departureDate, departureTime, selectedReturnDate, selectedReturnTime)
            ) {
                viewModel.searchRoundTrip(origin, destination, departureDate, departureTime, selectedReturnDate, selectedReturnTime)
            } else if (!returnJourney) {
                viewModel.searchRoute(origin, destination, departureDate, departureTime)
            } else false
        } else false
        if (searched) showResults()
    }
    val today = LocalDate.now(RailTime.defaultZone)
    val shownDepartureDate = if (dateChosen) selectedDepartureDate else today
    val outboundDateLabel = shortDateLabel(shownDepartureDate, today, locale, dateUnselected)
    val outboundTimeLabel = if (timeChosen) PlannerDateTime.timeLabel(travelTime, locale, timeUnselected) else stringResource(R.string.planner_time_now)
    LazyColumn(modifier = modifier.fillMaxSize().testTag("planner-screen"), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            ScreenHeader(
                title = stringResource(R.string.planner_headline),
                subtitle = stringResource(R.string.planner_subtitle),
                bottomPadding = 12.dp
            ) {
                if (viewModel.isDemonstrationData()) {
                    Pill(
                        stringResource(R.string.data_status_demo),
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                        icon = Icons.Default.Info
                    )
                }
            }
        }
        item {
            HeaderOverlapBox {
                BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SegmentedControl(
                            options = SearchMode.entries,
                            selected = mode,
                            onSelect = { mode = it },
                            labels = SearchMode.entries.map { it.localizedLabel() },
                            tagOf = { "planner-mode-${it.name.lowercase(Locale.ROOT)}" }
                        )
                        when (mode) {
                            SearchMode.ROUTE -> {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        StationField(
                                            label = stringResource(R.string.planner_origin),
                                            value = originText,
                                            onValueChange = { originText = it; viewModel.updateStationSuggestions("planner-origin", it) },
                                            matches = viewModel.stationSuggestions("planner-origin"),
                                            onSelect = { originText = it.name; viewModel.clearStationSuggestions("planner-origin") }
                                        )
                                        StationField(
                                            label = stringResource(R.string.planner_destination),
                                            value = destinationText,
                                            onValueChange = { destinationText = it; viewModel.updateStationSuggestions("planner-destination", it) },
                                            matches = viewModel.stationSuggestions("planner-destination"),
                                            onSelect = { destinationText = it.name; viewModel.clearStationSuggestions("planner-destination") }
                                        )
                                    }
                                    FilledTonalIconButton(
                                        onClick = { val old = originText; originText = destinationText; destinationText = old },
                                        modifier = Modifier.size(48.dp).testTag("planner-swap")
                                    ) {
                                        Icon(Icons.Default.SwapVert, contentDescription = stringResource(R.string.action_swap_origin_destination))
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    PlannerChoiceButton(
                                        icon = Icons.Default.CalendarMonth,
                                        text = outboundDateLabel,
                                        description = stringResource(R.string.accessibility_select_outbound_date, PlannerDateTime.dateLabel(shownDepartureDate.toString(), locale, dateUnselected)),
                                        onClick = { showDatePicker = true },
                                        modifier = Modifier.weight(1f).testTag("planner-date")
                                    )
                                    PlannerChoiceButton(
                                        icon = Icons.Default.Schedule,
                                        text = outboundTimeLabel,
                                        description = stringResource(R.string.accessibility_select_outbound_time, outboundTimeLabel),
                                        onClick = { showTimePicker = true },
                                        modifier = Modifier.weight(1f).testTag("planner-time")
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    val tripLabel = stringResource(if (returnJourney) R.string.planner_round_trip else R.string.planner_one_way)
                                    PlannerChoiceButton(
                                        icon = if (returnJourney) Icons.Default.ToggleOn else Icons.Default.ToggleOff,
                                        text = tripLabel,
                                        description = stringResource(R.string.accessibility_trip_kind, tripLabel),
                                        onClick = {
                                            returnJourney = !returnJourney
                                            if (returnJourney) {
                                                val outboundDate = PlannerDateTime.dateOrToday(travelDate)
                                                val outboundTime = PlannerDateTime.timeOrNow(travelTime)
                                                val currentReturnDate = PlannerDateTime.dateOrToday(returnDate)
                                                val currentReturnTime = PlannerDateTime.timeOrNow(returnTime)
                                                if (!PlannerDateTime.isReturnAfterDeparture(outboundDate, outboundTime, currentReturnDate, currentReturnTime)) {
                                                    returnDate = outboundDate.plusDays(1).toString()
                                                    returnTime = outboundTime.toString()
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f).testTag("planner-trip-kind")
                                    )
                                    PlannerChoiceButton(
                                        icon = Icons.Default.FilterList,
                                        text = stringResource(R.string.planner_filters),
                                        description = stringResource(if (showFilters) R.string.planner_filters_open else R.string.planner_filters_all_trains),
                                        onClick = { showFilters = !showFilters },
                                        trailingIcon = if (showFilters) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        modifier = Modifier.weight(1f).testTag("planner-filters")
                                    )
                                }
                                if (returnJourney) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        val returnDateLabel = shortDateLabel(selectedReturnDate, today, locale, dateUnselected)
                                        val returnTimeLabel = PlannerDateTime.timeLabel(returnTime, locale, timeUnselected)
                                        PlannerChoiceButton(
                                            icon = Icons.Default.CalendarMonth,
                                            text = stringResource(R.string.planner_return_short, returnDateLabel),
                                            description = stringResource(R.string.accessibility_select_return_date, PlannerDateTime.dateLabel(returnDate, locale, dateUnselected)),
                                            onClick = { showReturnDatePicker = true },
                                            modifier = Modifier.weight(1f)
                                        )
                                        PlannerChoiceButton(
                                            icon = Icons.Default.Schedule,
                                            text = returnTimeLabel,
                                            description = stringResource(R.string.accessibility_select_return_time, returnTimeLabel),
                                            onClick = { showReturnTimePicker = true },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (!roundTripChronologyValid) {
                                        Text(
                                            stringResource(R.string.planner_return_after_outbound),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                if (showFilters) {
                                    JourneyChangeFilterBar(viewModel.journeyChangeFilter, viewModel::selectJourneyChangeFilter)
                                    OperatorFilterBar(viewModel.operatorFilter, viewModel.operatorFilterOptions(), viewModel::selectOperatorFilter)
                                    Text(stringResource(R.string.planner_filter_disclosure), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Button(
                                    onClick = search,
                                    enabled = origin != null &&
                                        destination != null &&
                                        origin != destination &&
                                        selectedDepartureDate != null &&
                                        selectedDepartureTime != null &&
                                        roundTripChronologyValid,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("planner-search"),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(stringResource(if (returnJourney) R.string.planner_search_round_trip else R.string.planner_search_solutions), fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                                }
                                VoiceInputButton(onClick = { startVoiceInput(SearchMode.ROUTE) }, label = stringResource(R.string.planner_voice_route))
                                speechMessage?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            SearchMode.TRAIN -> {
                                TrainNumberSearchForm(
                                    query = viewModel.trainNumberQuery,
                                    onQueryChange = viewModel::updateTrainNumberQuery,
                                    onSearch = { viewModel.searchTrainNumber(); showTrainResults() }
                                )
                                VoiceInputButton(onClick = { startVoiceInput(SearchMode.TRAIN) }, label = stringResource(R.string.planner_voice_train))
                                speechMessage?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            SearchMode.STATION -> {
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
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(stringResource(R.string.planner_open_station_board), fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                                }
                                VoiceInputButton(onClick = { startVoiceInput(SearchMode.STATION) }, label = stringResource(R.string.planner_voice_station))
                                speechMessage?.let { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.planner_recent_and_favourite), Modifier.weight(1f))
                TextButton(onClick = openMore) { Text(stringResource(R.string.action_manage)) }
            }
        }
        items(viewModel.recentRoutes.take(4), key = { "recent-${it.origin.id}-${it.destination.id}" }) { recent ->
            PlannerRouteRow(
                title = recent.label,
                detail = stringResource(R.string.planner_recent_search),
                icon = Icons.Default.History,
                actionDescription = stringResource(R.string.accessibility_repeat_search, recent.label),
                onClick = { if (viewModel.useRecentRoute(recent)) { originText = recent.origin.name; destinationText = recent.destination.name } }
            )
        }
        items(viewModel.savedRoutes.filter { it.isPinned }.take(4), key = { it.id }) { route ->
            PlannerRouteRow(
                title = route.localizedDisplayName(Direction.OUTBOUND),
                detail = route.localizedName(),
                icon = Icons.Default.Star,
                actionDescription = stringResource(R.string.accessibility_use_route, route.localizedName()),
                onClick = { viewModel.activateRoute(route); originText = route.outwardOrigin.name; destinationText = route.outwardDestination.name }
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** A light box of the planner card: icon and value, the whole box is the 56 dp touch target. */
@Composable
private fun PlannerChoiceButton(
    icon: ImageVector,
    text: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector? = null
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp).semantics { contentDescription = description },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            trailingIcon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
        }
    }
}

/** A recent search or pinned route under the planner card, as a white row of the render. */
@Composable
private fun PlannerRouteRow(title: String, detail: String, icon: ImageVector, actionDescription: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenMargin, vertical = 4.dp)
            .semantics { contentDescription = actionDescription },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 1.dp
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Oggi 21 nov", or "ven 22 nov" for another day. */
@Composable
private fun shortDateLabel(date: LocalDate?, today: LocalDate, locale: Locale, fallback: String): String = when (date) {
    null -> fallback
    today -> stringResource(R.string.planner_date_today, date.format(DateTimeFormatter.ofPattern("d MMM", locale)))
    else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
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
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = accessibilityLabel }
    ) {
        Icon(Icons.Default.Mic, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
