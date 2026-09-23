package it.binario.app.plannedjourney

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.binario.app.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PlannedJourneyAgendaScreen(
    modifier: Modifier = Modifier,
    viewModel: PlannedJourneyViewModel,
    stations: List<PlannedJourneyStation>,
    onExportJson: (String) -> Unit,
    onExportIcs: (String) -> Unit,
    onImportRequested: () -> Unit
) {
    val entries = viewModel.agendaEntries()
    val filters = listOf(
        PlannedJourneyAgendaFilter.UPCOMING,
        PlannedJourneyAgendaFilter.REMINDERS,
        PlannedJourneyAgendaFilter.ARCHIVED,
        PlannedJourneyAgendaFilter.ALL
    )
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onExportJson(viewModel.exportJson()) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.planned_journey_export_json))
                }
                OutlinedButton(onClick = { onExportIcs(viewModel.exportIcs()) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.planned_journey_export_ics))
                }
            }
        }
        viewModel.warning?.let { warning ->
            item { AgendaMessage(warning, isError = true) }
        }
        viewModel.feedback?.let { feedback ->
            item { AgendaMessage(feedback, isError = false) }
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
                    onComplete = { viewModel.complete(entry) },
                    onArchive = { viewModel.archive(entry.journey.id) },
                    onToggleChecklist = { item -> viewModel.toggleChecklist(entry.journey.id, item) }
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    viewModel.editor?.let { editor ->
        PlannedJourneyEditorDialog(
            editor = editor,
            stations = stations,
            onChange = viewModel::changeEditor,
            onSave = { viewModel.saveEditor(stations) },
            onDismiss = viewModel::dismissEditor
        )
    }
    ImportPreviewDialog(
        state = viewModel.importState,
        onApply = viewModel::applyJsonImport,
        onDismiss = viewModel::dismissImportPreview
    )
}

@Composable
private fun AgendaMessage(text: String, isError: Boolean) {
    Text(
        text,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { contentDescription = text }
    )
}

@Composable
private fun AgendaEntryCard(
    entry: PlannedJourneyAgendaEntry,
    onEdit: () -> Unit,
    onComplete: () -> Unit,
    onArchive: () -> Unit,
    onToggleChecklist: (PlannedJourneyChecklistItem) -> Unit
) {
    val first = entry.occurrence.itinerary.segments.first()
    val last = entry.occurrence.itinerary.segments.last()
    val zone = first.departure.timeZone
    val time = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(zone).format(entry.occurrence.scheduledAt)
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlannedJourneyEditorDialog(
    editor: PlannedJourneyEditorState,
    stations: List<PlannedJourneyStation>,
    onChange: ((PlannedJourneyEditorState) -> PlannedJourneyEditorState) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    var suggestionsFor by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editor.isEditing) R.string.planned_journey_edit else R.string.planned_journey_add)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(
                        value = editor.label,
                        onValueChange = { value -> onChange { it.copy(label = value) } },
                        label = { Text(stringResource(R.string.planned_journey_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                item {
                    StationField(
                        value = editor.originQuery,
                        label = stringResource(R.string.planned_journey_origin),
                        suggestions = if (suggestionsFor == "origin") stations else emptyList(),
                        onValueChange = { value -> onChange { it.copy(originQuery = value) }; suggestionsFor = "origin" },
                        onSuggestion = { station -> onChange { it.copy(originQuery = station.displayName) }; suggestionsFor = null }
                    )
                }
                item {
                    StationField(
                        value = editor.destinationQuery,
                        label = stringResource(R.string.planned_journey_destination),
                        suggestions = if (suggestionsFor == "destination") stations else emptyList(),
                        onValueChange = { value -> onChange { it.copy(destinationQuery = value) }; suggestionsFor = "destination" },
                        onSuggestion = { station -> onChange { it.copy(destinationQuery = station.displayName) }; suggestionsFor = null }
                    )
                }
                item {
                    OutlinedTextField(
                        value = editor.date,
                        onValueChange = { value -> onChange { it.copy(date = value) } },
                        label = { Text(stringResource(R.string.planned_journey_date)) },
                        supportingText = { Text(stringResource(R.string.planned_journey_date_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        value = editor.time,
                        onValueChange = { value -> onChange { it.copy(time = value) } },
                        label = { Text(stringResource(R.string.planned_journey_time)) },
                        supportingText = { Text(ZoneId.systemDefault().id) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                item {
                    Text(stringResource(R.string.planned_journey_reminder), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(null, PlannedJourneyReminderMinutes.TEN, PlannedJourneyReminderMinutes.THIRTY, PlannedJourneyReminderMinutes.SIXTY).forEach { option ->
                            FilterChip(
                                selected = editor.reminderMinutes == option,
                                onClick = { onChange { it.copy(reminderMinutes = option) } },
                                label = { Text(option?.minutes?.toString() ?: stringResource(R.string.planned_journey_none)) }
                            )
                        }
                    }
                }
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
                editor.validationMessage?.let { message -> item { AgendaMessage(message, true) } }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.planned_journey_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.planned_journey_cancel)) } }
    )
}

@Composable
private fun StationField(
    value: String,
    label: String,
    suggestions: List<PlannedJourneyStation>,
    onValueChange: (String) -> Unit,
    onSuggestion: (PlannedJourneyStation) -> Unit
) {
    Column {
        OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (value.isNotBlank()) {
            suggestions.filter { it.displayName.contains(value, true) || it.canonicalId.contains(value, true) }
                .take(4)
                .forEach { station ->
                    TextButton(onClick = { onSuggestion(station) }, modifier = Modifier.fillMaxWidth()) {
                        Text(station.displayName, modifier = Modifier.fillMaxWidth())
                    }
                }
        }
    }
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
            text = { Text(state.detail) },
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
