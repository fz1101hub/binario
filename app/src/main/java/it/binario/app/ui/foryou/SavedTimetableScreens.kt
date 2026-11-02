package it.binario.app.ui.foryou

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.TabularFigures
import it.binario.app.timetables.SavedTimetable
import it.binario.app.timetables.SavedTimetableCodec
import it.binario.app.timetables.SavedTimetableStore
import it.binario.app.timetables.TimetableRow
import it.binario.app.timetables.TimetableShareFormatter
import it.binario.app.ui.common.BinarioCard
import it.binario.app.ui.common.ChangesBadge
import it.binario.app.ui.common.HeaderOverlapBox
import it.binario.app.ui.common.InfoNoticeCard
import it.binario.app.ui.common.Pill
import it.binario.app.ui.common.RowDivider
import it.binario.app.ui.common.RowIcon
import it.binario.app.ui.common.ScreenHeader
import it.binario.app.ui.common.ScreenMargin
import it.binario.app.ui.common.SectionTitle
import it.binario.app.ui.common.TonalButton
import it.binario.app.ui.common.platformColor
import it.binario.app.ui.common.platformText
import it.binario.app.ui.planner.savedAtLabel
import it.binario.app.ui.planner.searchDateLabel
import it.binario.app.ui.planner.startTextShare
import it.binario.app.ui.planner.timetableShareStrings
import java.time.LocalTime

// "Orari salvati" in Per te: result lists saved from the route results, read offline, never updated.

/** The "Orari salvati" card of Per te: one row per saved list, with rename and delete; empty, it says how to save. */
@Composable
internal fun SavedTimetablesSection(onOpen: (SavedTimetable) -> Unit) {
    val context = LocalContext.current
    val timetables = SavedTimetableStore.all(context)
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    renaming?.let { id ->
        timetables.firstOrNull { it.id == id }?.let { timetable ->
            SavedTimetableNameDialog(
                initial = timetable.name,
                onSave = { name -> SavedTimetableStore.rename(context, id, name); renaming = null },
                onDismiss = { renaming = null }
            )
        } ?: run { renaming = null }
    }
    Column(Modifier.testTag("for-you-saved-timetables")) {
        SectionTitle(stringResource(R.string.saved_timetables_title))
        BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
            if (timetables.isEmpty()) {
                Text(
                    stringResource(R.string.saved_timetables_empty),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            timetables.forEachIndexed { index, timetable ->
                if (index > 0) RowDivider()
                SavedTimetableRow(
                    timetable = timetable,
                    onOpen = { onOpen(timetable) },
                    onRename = { renaming = timetable.id },
                    onDelete = { SavedTimetableStore.delete(context, timetable.id) }
                )
            }
        }
    }
}

@Composable
private fun SavedTimetableRow(timetable: SavedTimetable, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val detail = listOf(
        searchDateLabel(timetable.serviceDate, fromTime(timetable)),
        pluralStringResource(R.plurals.saved_timetable_solutions, timetable.solutionCount, timetable.solutionCount),
        stringResource(R.string.saved_timetable_saved_at, savedAtLabel(timetable.savedAtEpochMillis, locale))
    ).joinToString(" · ")
    Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RowIcon(Icons.Default.Bookmark)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(timetable.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.saved_timetable_actions, timetable.name))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.favourite_train_rename)) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = { menuOpen = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.saved_timetable_delete)) },
                    leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }
    }
}

@Composable
private fun SavedTimetableNameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.saved_timetable_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= SavedTimetableCodec.MAX_NAME_LENGTH) name = it },
                modifier = Modifier.fillMaxWidth().testTag("saved-timetable-name"),
                singleLine = true,
                label = { Text(stringResource(R.string.saved_timetable_name_label)) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.saved_timetable_save_name)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/**
 * A saved list, read-only: "Salvato il … · non aggiornato" at the top, the solutions as they were (times, trains,
 * changes, platform when known, source), share, rename and delete. Leaves when the list no longer exists.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SavedTimetableScreen(modifier: Modifier, id: String, onGone: () -> Unit) {
    val context = LocalContext.current
    val timetable = SavedTimetableStore.all(context).firstOrNull { it.id == id }
    if (timetable == null) {
        LaunchedEffect(id) { onGone() }
        return
    }
    val locale = LocalConfiguration.current.locales[0]
    var renaming by rememberSaveable { mutableStateOf(false) }
    if (renaming) {
        SavedTimetableNameDialog(
            initial = timetable.name,
            onSave = { name -> SavedTimetableStore.rename(context, id, name); renaming = false },
            onDismiss = { renaming = false }
        )
    }
    val savedAt = savedAtLabel(timetable.savedAtEpochMillis, locale)
    val dateLabel = searchDateLabel(timetable.serviceDate, fromTime(timetable))
    val shareStrings = timetableShareStrings()
    val chooser = stringResource(R.string.share_timetable_chooser)
    LazyColumn(modifier.fillMaxSize().testTag("saved-timetable")) {
        item {
            ScreenHeader(
                title = timetable.name,
                subtitle = dateLabel,
                bottomPadding = 12.dp
            ) {
                Pill(stringResource(R.string.saved_timetable_saved_at, savedAt))
            }
        }
        item {
            HeaderOverlapBox {
                BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
                    FlowRow(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TonalButton(
                            text = stringResource(R.string.action_share),
                            onClick = {
                                startTextShare(
                                    context,
                                    TimetableShareFormatter.list(timetable.origin, timetable.destination, dateLabel, timetable.rows, timetable.returnRows, shareStrings, savedAt),
                                    chooser
                                )
                            },
                            leadingIcon = Icons.Default.Share
                        )
                        TonalButton(text = stringResource(R.string.favourite_train_rename), onClick = { renaming = true }, leadingIcon = Icons.Default.Edit)
                        TonalButton(
                            text = stringResource(R.string.saved_timetable_delete),
                            onClick = { SavedTimetableStore.delete(context, id) },
                            leadingIcon = Icons.Default.DeleteOutline,
                            modifier = Modifier.testTag("saved-timetable-delete")
                        )
                    }
                }
            }
        }
        item {
            InfoNoticeCard(
                text = stringResource(R.string.saved_timetable_offline_notice),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (timetable.returnRows.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.planner_outbound) + " · " + stringResource(R.string.share_route, timetable.origin, timetable.destination)) }
        }
        items(timetable.rows) { row -> SavedTimetableRowCard(row) }
        if (timetable.returnRows.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.planner_return) + " · " + stringResource(R.string.share_route, timetable.destination, timetable.origin)) }
            items(timetable.returnRows) { row -> SavedTimetableRowCard(row) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** One saved solution, in the card of the results, without any live claim. */
@Composable
private fun SavedTimetableRowCard(row: TimetableRow) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenMargin, vertical = 6.dp).semantics(mergeDescendants = true) {},
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(row.departure, row.arrival).joinToString(" → "),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = TabularFigures)
                )
                row.durationMinutes?.let {
                    Text(TimetableShareFormatter.duration(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (row.arrivalDayOffset > 0) {
                Text(stringResource(R.string.journey_arrival_next_day), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (row.trains.isNotBlank()) {
                Text(row.trains, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            row.transfers.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (row.source.isNotBlank()) {
                Text(row.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ChangesBadge(row.changes, Modifier.weight(1f))
                Text(
                    platformText(row.platform, row.scheduledPlatform, long = true) ?: stringResource(R.string.platform_label, "—"),
                    style = MaterialTheme.typography.bodySmall,
                    color = platformColor(row.platform, row.scheduledPlatform, MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
        }
    }
}

private fun fromTime(timetable: SavedTimetable): LocalTime =
    runCatching { LocalTime.parse(timetable.fromTime.padStart(5, '0')) }.getOrDefault(LocalTime.MIDNIGHT)
