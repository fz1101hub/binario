package it.binario.app.plannedjourney

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import it.binario.app.NearestStationPicker
import it.binario.app.data.Station
import it.binario.app.requestSingleLocationFix
import it.binario.app.R
import it.binario.app.insertIntent
import it.binario.app.UiMessage
import it.binario.app.localized
import it.binario.app.ui.common.LocalizedDates

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun PlannedJourneyAgendaScreen(
    modifier: Modifier = Modifier,
    viewModel: PlannedJourneyViewModel,
    stations: List<PlannedJourneyStation>,
    onExportJson: () -> Unit,
    onExportIcs: () -> Unit,
    onImportRequested: () -> Unit,
    /** Offered as chips under the origin of the editor (the app's «Stazioni preferite»). */
    favouriteStations: List<PlannedJourneyStation> = emptyList(),
    /** The catalogue with coordinates, for «Usa la posizione per la partenza»; empty hides the button. */
    locationCatalogue: List<Station> = emptyList()
) {
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    // «Cerca stazione, data o ora» and the month chips narrow the chosen filter; both stay on this screen only.
    var query by rememberSaveable { mutableStateOf("") }
    var monthText by rememberSaveable { mutableStateOf<String?>(null) }
    val filtered = viewModel.agendaEntries()
    val months = remember(filtered) { PlannedJourneyAgendaSearch.months(filtered) }
    val month = monthText?.let { runCatching { YearMonth.parse(it) }.getOrNull() }?.takeIf { it in months }
    val entries = PlannedJourneyAgendaSearch.filter(filtered, query, month)
    val locale = LocalConfiguration.current.locales[0]
    val filters = listOf(
        PlannedJourneyAgendaFilter.UPCOMING,
        PlannedJourneyAgendaFilter.REMINDERS,
        PlannedJourneyAgendaFilter.ARCHIVED,
        PlannedJourneyAgendaFilter.ALL
    )
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("plannedJourneyAgenda"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.planned_journey_description), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.planned_journey_local_only),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                filters.forEach { filter ->
                    FilterChip(
                        selected = viewModel.filter == filter,
                        onClick = { viewModel.selectFilter(filter) },
                        label = { Text(filter.label()) }
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(80) },
                label = { Text(stringResource(R.string.planned_journey_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("plannedJourneySearch")
            )
        }
        if (months.size > 1) {
            item {
                val monthFormat = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(selected = month == null, onClick = { monthText = null },
                        label = { Text(stringResource(R.string.planned_journey_all_months)) })
                    months.forEach { candidate ->
                        FilterChip(selected = month == candidate, onClick = { monthText = candidate.toString() },
                            label = { Text(monthFormat.format(candidate)) })
                    }
                }
            }
        }
        item { PlannedJourneyReminderControls(viewModel) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = viewModel::beginCreate,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.planned_journey_add))
                }
                OutlinedButton(
                    onClick = onImportRequested,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.planned_journey_import))
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { viewModel.beginTextImport() },
                modifier = Modifier.fillMaxWidth().testTag("plannedJourneyTextImport")
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.planned_journey_text_import))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onExportJson, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.planned_journey_export_json))
                }
                OutlinedButton(onClick = onExportIcs, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.planned_journey_export_ics))
                }
            }
        }
        viewModel.warning?.let { warning ->
            item { AgendaMessage(warning.localized(), isError = true) }
        }
        viewModel.feedback?.let { feedback ->
            item { AgendaMessage(feedback.localized(), isError = false) }
        }
        if (entries.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(
                        stringResource(R.string.planned_journey_empty),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        } else {
            items(entries, key = { "${it.journey.id}:${it.occurrence.occurrenceDate}" }) { entry ->
                AgendaEntryCard(
                    entry = entry,
                    onEdit = { viewModel.beginEdit(entry.journey) },
                    onReturn = { viewModel.beginReturn(entry.journey) },
                    onReplan = { viewModel.beginReplan(entry.journey) },
                    onComplete = { viewModel.complete(entry) },
                    onArchive = { viewModel.archive(entry.journey.id) },
                    onDelete = { pendingDelete = entry.journey.id },
                    onToggleChecklist = { item -> viewModel.toggleChecklist(entry.journey.id, item) }
                )
            }
        }
        if (viewModel.snapshot.journeys.isNotEmpty() || viewModel.readOnly || viewModel.templates.isNotEmpty()) {
            item {
                TextButton(onClick = { confirmClear = true }, modifier = Modifier.testTag("plannedJourneyClear")) {
                    Text(stringResource(R.string.planned_journey_clear), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.planned_journey_clear_title)) },
            text = { Text(stringResource(R.string.planned_journey_clear_body)) },
            confirmButton = { TextButton(onClick = { viewModel.clearAll(); confirmClear = false }) {
                Text(stringResource(R.string.planned_journey_clear_confirm))
            } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) {
                Text(stringResource(android.R.string.cancel))
            } }
        )
    }
    viewModel.editor?.let { editor ->
        PlannedJourneyEditorDialog(
            editor = editor,
            stations = stations,
            templates = viewModel.templates,
            onApplyTemplate = viewModel::applyTemplate,
            onDeleteTemplate = viewModel::deleteTemplate,
            onSaveTemplate = { name -> viewModel.saveTemplate(name, stations) },
            favouriteStations = favouriteStations,
            locationCatalogue = locationCatalogue,
            onChange = viewModel::changeEditor,
            onSave = { viewModel.saveEditor(stations) },
            onDismiss = viewModel::dismissEditor
        )
    }
    viewModel.textImport?.takeIf { it.visible }?.let { state ->
        TextImportDialog(state = state, viewModel = viewModel, stations = stations)
    }
    ImportPreviewDialog(
        state = viewModel.importState,
        onApply = viewModel::applyJsonImport,
        onDismiss = viewModel::dismissImportPreview
    )
    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.planned_journey_delete)) },
            text = { Text(stringResource(R.string.planned_journey_delete_confirmation)) },
            confirmButton = { TextButton(onClick = { viewModel.remove(id); pendingDelete = null }) {
                Text(stringResource(R.string.planned_journey_delete))
            } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) {
                Text(stringResource(android.R.string.cancel))
            } }
        )
    }
}

/** The companion offered at most 12 favourite stations; the app's own list is shown up to the same bound. */
private const val MAX_FAVOURITE_CHIPS = 12

@Composable
private fun AgendaMessage(text: String, isError: Boolean) {
    Text(
        text,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { contentDescription = text }
    )
}

/** «Aggiungi al calendario» for one occurrence: the calendar app receives a prefilled event, Binario writes nothing. */
@Composable
private fun PlannedJourneyCalendarButton(entry: PlannedJourneyAgendaEntry, title: String) {
    val context = LocalContext.current
    var noCalendarApp by remember(entry.journey.id, entry.occurrence.occurrenceDate) { mutableStateOf(false) }
    val text = PlannedJourneyIcsText(
        eventDescription = stringResource(R.string.planned_journey_ics_event_description),
        reminderDescription = stringResource(R.string.planned_journey_ics_reminder_description)
    )
    TextButton(onClick = {
        noCalendarApp = try {
            context.startActivity(entry.calendarEvent(text, title).insertIntent())
            false
        } catch (_: ActivityNotFoundException) {
            true
        }
    }) {
        Icon(Icons.Default.CalendarMonth, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.calendar_add))
    }
    if (noCalendarApp) AgendaMessage(stringResource(R.string.calendar_no_app), isError = true)
}

@Composable
private fun AgendaEntryCard(
    entry: PlannedJourneyAgendaEntry,
    onEdit: () -> Unit,
    onReturn: () -> Unit,
    onReplan: () -> Unit,
    onComplete: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onToggleChecklist: (PlannedJourneyChecklistItem) -> Unit
) {
    val first = entry.occurrence.itinerary.segments.first()
    val last = entry.occurrence.itinerary.segments.last()
    val zone = first.departure.timeZone
    val time = LocalizedDates.weekdayDayMonthTime(LocalConfiguration.current.locales[0]).withZone(zone).format(entry.occurrence.scheduledAt)
    val routeLabel = stringResource(
        R.string.planned_journey_route,
        first.origin.displayName,
        last.destination.displayName
    )
    val title = entry.journey.label.ifBlank { routeLabel }
    val reminder = entry.journey.reminder?.minutesBeforeDeparture?.minutes
    val editDescription = stringResource(R.string.planned_journey_edit)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        routeLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(time, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = editDescription }) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                }
            }
            reminder?.let { minutes ->
                AssistChip(
                    onClick = {},
                    label = { Text(pluralStringResource(R.plurals.planned_journey_reminder_value, minutes, minutes)) }
                )
            }
            entry.journey.checklist.forEach { item ->
                val checklistDescription = stringResource(R.string.planned_journey_checklist_item, item.label)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = item.completed,
                        onCheckedChange = { onToggleChecklist(item) },
                        modifier = Modifier.semantics { contentDescription = checklistDescription }
                    )
                    Text(item.label, modifier = Modifier.weight(1f))
                }
            }
            if (entry.journey.status != PlannedJourneyStatus.ARCHIVED) {
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (entry.journey.status == PlannedJourneyStatus.ACTIVE) {
                        TextButton(onClick = onComplete, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.planned_journey_complete))
                        }
                    }
                    TextButton(onClick = onArchive, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Archive, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.planned_journey_archive))
                    }
                }
            }
            if (entry.journey.status == PlannedJourneyStatus.ACTIVE) PlannedJourneyCalendarButton(entry, title)
            // Return and replan keep the legs only (reversed for the return): each leg's date and time are chosen anew.
            val returnDescription = stringResource(R.string.planned_journey_return_a11y, title)
            val replanDescription = stringResource(R.string.planned_journey_replan_a11y, title)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onReturn, modifier = Modifier.weight(1f).semantics { contentDescription = returnDescription }) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.planned_journey_return))
                }
                TextButton(onClick = onReplan, modifier = Modifier.weight(1f).semantics { contentDescription = replanDescription }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.planned_journey_replan))
                }
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.planned_journey_delete)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlannedJourneyEditorDialog(
    editor: PlannedJourneyEditorState,
    stations: List<PlannedJourneyStation>,
    templates: List<PlannedJourneyTemplate>,
    onApplyTemplate: (String) -> Unit,
    onDeleteTemplate: (String) -> Unit,
    onSaveTemplate: (String) -> Unit,
    favouriteStations: List<PlannedJourneyStation>,
    locationCatalogue: List<Station>,
    onChange: ((PlannedJourneyEditorState) -> PlannedJourneyEditorState) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    var suggestionsFor by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val search by produceState<PlannedJourneyStationSearch?>(null, stations) {
        value = withContext(Dispatchers.Default) { PlannedJourneyStationSearch(stations) }
    }
    // «Usa la posizione per la partenza»: one approximate fix on request, the nearest catalogue station, nothing stored.
    val context = LocalContext.current
    val resources = LocalResources.current
    var locationMessage by remember { mutableStateOf<String?>(null) }
    fun useOrigin(station: PlannedJourneyStation) {
        onChange { it.withLeg(0) { leg -> leg.copy(originQuery = station.displayName, originStationId = station.canonicalId) } }
        suggestionsFor = null
    }
    fun locateOrigin() {
        requestSingleLocationFix(context) { location ->
            val nearest = location?.let { NearestStationPicker.pick(locationCatalogue, it.latitude, it.longitude) }
            locationMessage = when {
                location == null -> resources.getString(R.string.location_unavailable)
                nearest == null -> resources.getString(R.string.planner_location_none)
                else -> {
                    useOrigin(PlannedJourneyStation(nearest.station.id, nearest.station.name, nearest.station.railwayCode))
                    resources.getString(R.string.planner_location_origin, nearest.station.name)
                }
            }
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locateOrigin() else locationMessage = resources.getString(R.string.location_permission_denied)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editor.isEditing) R.string.planned_journey_edit else R.string.planned_journey_add)) },
        text = {
            LazyColumn(modifier = Modifier.testTag("plannedJourneyEditor"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                editor.notice?.let { notice -> item { AgendaMessage(notice.localized(), isError = false) } }
                item { TemplateFields(templates, onApplyTemplate, onDeleteTemplate, onSaveTemplate, canSave = !editor.isMultiLeg) }
                item {
                    OutlinedTextField(
                        value = editor.label,
                        onValueChange = { value -> onChange { it.copy(label = value) } },
                        label = { Text(stringResource(R.string.planned_journey_label)) },
                        modifier = Modifier.fillMaxWidth().testTag("plannedJourneyLabel"),
                        singleLine = true
                    )
                }
                val legs = editor.legs
                legs.forEachIndexed { index, leg ->
                    // The first leg keeps the tags it always had; the next ones carry their number.
                    val number = index + 1
                    val suffix = if (index == 0) "" else number.toString()
                    val originKey = "origin$suffix"
                    val destinationKey = "destination$suffix"
                    fun changeLeg(transform: (PlannedJourneyEditorLeg) -> PlannedJourneyEditorLeg) = onChange { it.withLeg(index, transform) }
                    if (legs.size > 1) item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.planned_journey_leg_title, number, legs.size),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            val removeDescription = stringResource(R.string.planned_journey_remove_leg_a11y, number, legs.size)
                            TextButton(
                                onClick = { onChange { it.removeLeg(index) }; suggestionsFor = null },
                                modifier = Modifier.testTag("plannedJourneyRemoveLeg$number").semantics { contentDescription = removeDescription }
                            ) { Text(stringResource(R.string.planned_journey_remove_leg)) }
                        }
                    }
                    item {
                        StationField(
                            value = leg.originQuery,
                            label = stringResource(R.string.planned_journey_origin),
                            search = search,
                            showSuggestions = suggestionsFor == originKey,
                            tag = "plannedJourneyOrigin$suffix",
                            onFocus = { suggestionsFor = originKey },
                            onValueChange = { value -> changeLeg { it.copy(originQuery = value, originStationId = null) }; suggestionsFor = originKey },
                            onSuggestion = { station -> changeLeg { it.copy(originQuery = station.displayName, originStationId = station.canonicalId) }; suggestionsFor = null; focus.clearFocus() },
                            trailingIcon = if (index != 0 || locationCatalogue.isEmpty()) null else {
                                {
                                    IconButton(onClick = {
                                        locationMessage = null
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locateOrigin()
                                        else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                                    }, modifier = Modifier.testTag("plannedJourneyOriginLocation")) {
                                        Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.planner_use_location))
                                    }
                                }
                            }
                        )
                        if (index == 0) locationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (index == 0 && favouriteStations.isNotEmpty()) item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.station_board_favourites), style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                favouriteStations.distinctBy { it.canonicalId }.take(MAX_FAVOURITE_CHIPS).forEach { station ->
                                    AssistChip(onClick = { useOrigin(station) }, label = { Text(station.displayName) })
                                }
                            }
                        }
                    }
                    item {
                        StationField(
                            value = leg.destinationQuery,
                            label = stringResource(R.string.planned_journey_destination),
                            search = search,
                            showSuggestions = suggestionsFor == destinationKey,
                            tag = "plannedJourneyDestination$suffix",
                            onFocus = { suggestionsFor = destinationKey },
                            onValueChange = { value -> changeLeg { it.copy(destinationQuery = value, destinationStationId = null) }; suggestionsFor = destinationKey },
                            onSuggestion = { station -> changeLeg { it.copy(destinationQuery = station.displayName, destinationStationId = station.canonicalId) }; suggestionsFor = null; focus.clearFocus() }
                        )
                    }
                    item { PlannedJourneyDateTimeFields(leg, suffix, ::changeLeg) }
                }
                if (editor.canAddLeg) item {
                    OutlinedButton(
                        onClick = { onChange { it.addLeg() } },
                        modifier = Modifier.fillMaxWidth().testTag("plannedJourneyAddLeg")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.planned_journey_add_leg))
                    }
                }
                item {
                    Text(stringResource(R.string.planned_journey_reminder), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        plannedJourneyReminderChoices.forEach { option ->
                            FilterChip(
                                selected = editor.reminderMinutes == option,
                                onClick = { onChange { it.copy(reminderMinutes = option) } },
                                label = { Text(option?.minutes?.toString() ?: stringResource(R.string.planned_journey_none)) }
                            )
                        }
                    }
                }
                item { RecurrenceFields(editor, onChange) }
                item {
                    OutlinedTextField(
                        value = editor.checklistText,
                        onValueChange = { value -> onChange { it.copy(checklistText = value) } },
                        label = { Text(stringResource(R.string.planned_journey_checklist)) },
                        supportingText = { Text(stringResource(R.string.planned_journey_checklist_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
                item { ChecklistPresets(onApply = { preset, label -> onChange { it.copy(checklistText = PlannedJourneyChecklistPresets.apply(it.checklistText, preset, label)) } }) }
                editor.validationMessage?.let { message -> item { AgendaMessage(message.localized(), true) } }
            }
        },
        confirmButton = { TextButton(onClick = onSave, modifier = Modifier.testTag("plannedJourneySave")) { Text(stringResource(R.string.planned_journey_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.planned_journey_cancel)) } }
    )
}

/** «Modelli di viaggio»: apply or delete a saved template, or save the form under a name (never its date). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateFields(
    templates: List<PlannedJourneyTemplate>,
    onApply: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSave: (String) -> Unit,
    /** A template is one leg: a multi-leg journey offers only the saved templates. */
    canSave: Boolean
) {
    var name by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.planned_journey_templates_title), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.planned_journey_templates_description), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            templates.forEach { template ->
                val apply = stringResource(R.string.planned_journey_template_apply_a11y, template.name)
                val delete = stringResource(R.string.planned_journey_template_delete_a11y, template.name)
                AssistChip(
                    onClick = { onApply(template.id) },
                    label = { Text(template.name) },
                    modifier = Modifier.semantics { contentDescription = apply },
                    trailingIcon = {
                        IconButton(onClick = { onDelete(template.id) }, modifier = Modifier.semantics { contentDescription = delete }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                )
            }
        }
        if (canSave) Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(PlannedJourneyTemplates.MAX_NAME_LENGTH) },
                label = { Text(stringResource(R.string.planned_journey_template_name)) },
                placeholder = { Text(stringResource(R.string.planned_journey_template_placeholder)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            val saveDescription = stringResource(R.string.planned_journey_template_save_a11y)
            TextButton(
                onClick = { onSave(name) },
                enabled = name.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = saveDescription }
            ) { Text(stringResource(R.string.planned_journey_save)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecurrenceFields(
    editor: PlannedJourneyEditorState,
    onChange: ((PlannedJourneyEditorState) -> PlannedJourneyEditorState) -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    val startDay = runCatching { LocalDate.parse(editor.date).dayOfWeek }.getOrNull()
    val counts = (listOf(1, 2, 4, 8, PlannedJourneyPersistenceSchema.MAX_RECURRENCE_OCCURRENCES) + editor.repeatCount).distinct().sorted()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.planned_journey_repeat_title), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.planned_journey_repeat_description), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            counts.forEach { count ->
                FilterChip(
                    selected = editor.repeatCount == count,
                    onClick = { onChange { it.copy(repeatCount = count) } },
                    label = {
                        Text(if (count == 1) stringResource(R.string.planned_journey_none)
                        else pluralStringResource(R.plurals.planned_journey_repeat_occurrences, count, count))
                    }
                )
            }
        }
        if (editor.repeatCount > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { day ->
                    val name = day.getDisplayName(TextStyle.FULL, locale)
                    FilterChip(
                        selected = day == startDay || day in editor.repeatWeekdays,
                        enabled = day != startDay,
                        onClick = { onChange { it.copy(repeatWeekdays = if (day in it.repeatWeekdays) it.repeatWeekdays - day else it.repeatWeekdays + day) } },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                        modifier = Modifier.semantics { contentDescription = name }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChecklistPresets(onApply: (PlannedJourneyChecklistPreset, (PlannedJourneyChecklistLine) -> String) -> Unit) {
    val labels = PlannedJourneyChecklistLine.entries.associateWith { line -> stringResource(line.labelRes()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.planned_journey_preparation_title), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlannedJourneyChecklistPreset.entries.forEach { preset ->
                val name = stringResource(preset.labelRes())
                val description = stringResource(R.string.planned_journey_preparation_apply_a11y, name)
                AssistChip(
                    onClick = { onApply(preset) { line -> labels.getValue(line) } },
                    label = { Text(name) },
                    modifier = Modifier.semantics { contentDescription = description }
                )
            }
        }
    }
}

private fun PlannedJourneyChecklistPreset.labelRes(): Int = when (this) {
    PlannedJourneyChecklistPreset.COMMUTER -> R.string.planned_journey_preset_commuter
    PlannedJourneyChecklistPreset.CONNECTION -> R.string.planned_journey_preset_connection
    PlannedJourneyChecklistPreset.BIKE -> R.string.planned_journey_preset_bike
    PlannedJourneyChecklistPreset.ASSISTANCE -> R.string.planned_journey_preset_assistance
}

private fun PlannedJourneyChecklistLine.labelRes(): Int = when (this) {
    PlannedJourneyChecklistLine.TIME_MARGIN -> R.string.planned_journey_check_time_margin
    PlannedJourneyChecklistLine.DOCUMENTS -> R.string.planned_journey_check_documents
    PlannedJourneyChecklistLine.BELONGINGS -> R.string.planned_journey_check_belongings
    PlannedJourneyChecklistLine.CONNECTION -> R.string.planned_journey_check_connection
    PlannedJourneyChecklistLine.BIKE -> R.string.planned_journey_check_bike
    PlannedJourneyChecklistLine.ASSISTANCE -> R.string.planned_journey_check_assistance
}

@Composable
private fun StationField(
    value: String,
    label: String,
    search: PlannedJourneyStationSearch?,
    showSuggestions: Boolean,
    tag: String,
    onFocus: () -> Unit,
    onValueChange: (String) -> Unit,
    onSuggestion: (PlannedJourneyStation) -> Unit,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val query = value
    val suggestions by produceState(emptyList<PlannedJourneyStation>(), query, search, showSuggestions) {
        this.value = withContext(Dispatchers.Default) {
            if (showSuggestions) search?.suggestions(query).orEmpty() else emptyList()
        }
    }
    Column {
        OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, trailingIcon = trailingIcon,
            supportingText = { Text(stringResource(R.string.agenda_editor_station_hint)) },
            modifier = Modifier.fillMaxWidth().testTag(tag).onFocusChanged { if (it.isFocused) onFocus() }, singleLine = true)
        suggestions.forEach { station ->
            val code = station.railwayCode ?: station.canonicalId
            val description = stringResource(R.string.agenda_editor_select_station, station.displayName, code)
            TextButton(onClick = { onSuggestion(station) }, modifier = Modifier.fillMaxWidth()
                .testTag("$tag:${station.canonicalId}").semantics { contentDescription = description }) {
                Text("${station.displayName} · $code", modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TextImportDialog(
    state: PlannedJourneyTextImportState,
    viewModel: PlannedJourneyViewModel,
    stations: List<PlannedJourneyStation>
) {
    val locale = LocalConfiguration.current.locales[0]
    val candidates = state.candidates
    AlertDialog(
        onDismissRequest = viewModel::dismissTextImport,
        title = { Text(stringResource(R.string.planned_journey_text_import)) },
        text = {
            LazyColumn(modifier = Modifier.testTag("plannedJourneyTextImportDialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.fromShare) item { AgendaMessage(stringResource(R.string.planned_journey_text_import_shared), isError = false) }
                if (state.fromCalendar) {
                    item { Text(stringResource(R.string.planned_journey_calendar_import_description), style = MaterialTheme.typography.bodySmall) }
                } else {
                    item { Text(stringResource(R.string.planned_journey_text_import_description), style = MaterialTheme.typography.bodySmall) }
                }
                if (!state.fromCalendar) item {
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = viewModel::changeTextImport,
                        label = { Text(stringResource(R.string.planned_journey_text_import_label)) },
                        placeholder = { Text(stringResource(R.string.planned_journey_text_import_example)) },
                        supportingText = { Text(stringResource(R.string.planned_journey_text_import_hint)) },
                        modifier = Modifier.fillMaxWidth().testTag("plannedJourneyTextImportInput"),
                        minLines = 3
                    )
                }
                if (!state.fromCalendar) item {
                    OutlinedButton(onClick = { viewModel.previewTextImport(stations) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.planned_journey_text_import_preview))
                    }
                }
                if (candidates != null) {
                    item { Text(stringResource(R.string.planned_journey_text_import_review), style = MaterialTheme.typography.titleSmall) }
                    items(candidates, key = { it.id }) { journey ->
                        val segment = journey.itinerary.segments.single()
                        val route = stringResource(R.string.planned_journey_route, segment.origin.displayName, segment.destination.displayName)
                        val schedule = LocalizedDates.weekdayDayMonthTime(locale).format(segment.departure.localDateTime())
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(
                                checked = journey.id in state.selected,
                                onCheckedChange = { viewModel.toggleTextImportCandidate(journey.id) },
                                modifier = Modifier.semantics { contentDescription = "$route, $schedule" }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(route, style = MaterialTheme.typography.bodyMedium)
                                Text(schedule, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            val reviewed = stringResource(R.string.planned_journey_text_import_reviewed)
                            Checkbox(
                                checked = state.reviewed,
                                onCheckedChange = viewModel::setTextImportReviewed,
                                modifier = Modifier.semantics { contentDescription = reviewed }.testTag("plannedJourneyTextImportReviewed")
                            )
                            Text(reviewed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (state.skipped > 0) {
                    item {
                        Text(
                            pluralStringResource(R.plurals.planned_journey_text_import_skipped, state.skipped, state.skipped),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                state.message?.let { message -> item { AgendaMessage(message.localized(), isError = true) } }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::applyTextImport, enabled = candidates != null) {
                Text(stringResource(R.string.planned_journey_text_import_apply))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissTextImport) { Text(stringResource(R.string.planned_journey_close)) } }
    )
}

@Composable
private fun ImportPreviewDialog(
    state: PlannedJourneyImportState,
    onApply: (PlannedJourneyConflictPolicy) -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        PlannedJourneyImportState.Idle -> Unit
        is PlannedJourneyImportState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.planned_journey_import_error_title)) },
            text = { Text(state.message.localized()) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.planned_journey_close)) } }
        )
        is PlannedJourneyImportState.Ics -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.planned_journey_ics_preview_title)) },
            text = {
                Text(stringResource(
                    R.string.planned_journey_ics_preview_body,
                    state.preview.matchingEvents.size,
                    state.preview.conflictingEvents.size,
                    state.preview.unlinkedEvents.size
                ))
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.planned_journey_close)) } }
        )
        is PlannedJourneyImportState.Json -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.planned_journey_json_preview_title)) },
            text = {
                Text(stringResource(
                    R.string.planned_journey_json_preview_body,
                    state.preview.accepted.size,
                    state.preview.duplicates.size,
                    state.preview.conflicts.size
                ))
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { onApply(PlannedJourneyConflictPolicy.KEEP_EXISTING) }) {
                        Text(stringResource(R.string.planned_journey_keep_existing))
                    }
                    TextButton(onClick = { onApply(PlannedJourneyConflictPolicy.REPLACE_EXISTING) }) {
                        Text(stringResource(R.string.planned_journey_replace_existing))
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.planned_journey_cancel)) } }
        )
    }
}

@Composable
private fun PlannedJourneyAgendaFilter.label(): String = stringResource(
    when (this) {
        PlannedJourneyAgendaFilter.UPCOMING -> R.string.planned_journey_filter_upcoming
        PlannedJourneyAgendaFilter.REMINDERS -> R.string.planned_journey_filter_reminders
        PlannedJourneyAgendaFilter.ARCHIVED -> R.string.planned_journey_filter_archived
        PlannedJourneyAgendaFilter.ALL -> R.string.planned_journey_filter_all
    }
)
