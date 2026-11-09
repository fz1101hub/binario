package it.binario.app.ui.foryou

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.FavouriteTrains
import it.binario.app.R
import it.binario.app.ServiceMonitor
import it.binario.app.ui.common.BinarioCard
import it.binario.app.ui.common.RowDivider
import it.binario.app.ui.common.RowIcon
import it.binario.app.ui.common.ScreenMargin
import it.binario.app.ui.common.SectionTitle

/**
 * "I miei treni" in Per te: the trains saved with the heart of the train detail, by custom name when
 * the user gave one. A tap opens the train; the menu renames or removes it. The same list feeds the
 * "I miei treni" home-screen widget.
 */
@Composable
internal fun FavouriteTrainsSection(
    monitors: List<ServiceMonitor>,
    onOpen: (ServiceMonitor) -> Unit,
    onRename: (ServiceMonitor, String) -> Unit,
    onRemove: (ServiceMonitor) -> Unit
) {
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    monitors.firstOrNull { it.number == renaming }?.let { monitor ->
        FavouriteTrainNameDialog(
            monitor = monitor,
            title = stringResource(R.string.favourite_train_rename_title),
            description = null,
            dismissLabel = stringResource(R.string.action_cancel),
            onSave = { name -> onRename(monitor, name); renaming = null },
            onDismiss = { renaming = null }
        )
    }
    Column(Modifier.testTag("for-you-favourite-trains")) {
        SectionTitle(stringResource(R.string.favourite_trains_title))
        BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
            Text(
                stringResource(if (monitors.isEmpty()) R.string.favourite_trains_empty else R.string.favourite_trains_description),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            monitors.forEachIndexed { index, monitor ->
                if (index > 0) RowDivider()
                FavouriteTrainRow(
                    monitor = monitor,
                    onOpen = { onOpen(monitor) },
                    onRename = { renaming = monitor.number },
                    onRemove = { onRemove(monitor) }
                )
            }
        }
    }
}

/** How a favourite train is named: its custom name, else «Treno 9558», and the line under it (number, route, departure). */
internal data class FavouriteTrainNames(val title: String, val detail: String)

@Composable
internal fun favouriteTrainNames(monitor: ServiceMonitor): FavouriteTrainNames {
    val trainLabel = stringResource(R.string.train_number_label, monitor.number)
    val detail = listOfNotNull(
        trainLabel.takeIf { monitor.displayName != null },
        monitor.label.takeIf(String::isNotBlank),
        monitor.scheduledDeparture.takeIf(String::isNotBlank)?.let { stringResource(R.string.favourite_train_departure, it) }
    ).joinToString(" · ")
    return FavouriteTrainNames(monitor.displayName ?: trainLabel, detail)
}

@Composable
private fun FavouriteTrainRow(monitor: ServiceMonitor, onOpen: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val (title, detail) = favouriteTrainNames(monitor)
    Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RowIcon(Icons.Default.Train)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.favourite_train_actions, title))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.favourite_train_open)) },
                    leadingIcon = { Icon(Icons.Default.Train, contentDescription = null) },
                    onClick = { menuOpen = false; onOpen() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.favourite_train_rename)) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = { menuOpen = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.favourite_train_remove)) },
                    leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                    onClick = { menuOpen = false; onRemove() }
                )
            }
        }
    }
}

/**
 * Name of a favourite train ("Il mio treno delle 7:42"). Shown after the heart saves a train and from
 * "Rinomina"; an empty name keeps the train label.
 */
@Composable
internal fun FavouriteTrainNameDialog(
    monitor: ServiceMonitor,
    title: String,
    description: String?,
    dismissLabel: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable(monitor.number) { mutableStateOf(monitor.customName) }
    val placeholder = monitor.scheduledDeparture.takeIf(String::isNotBlank)
        ?.let { stringResource(R.string.favourite_train_name_placeholder, it) }
        ?: stringResource(R.string.favourite_train_name_placeholder_generic)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= FavouriteTrains.MAX_NAME_LENGTH) name = it },
                    modifier = Modifier.fillMaxWidth().testTag("favourite-train-name"),
                    singleLine = true,
                    label = { Text(stringResource(R.string.favourite_train_name_label)) },
                    placeholder = { Text(placeholder) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.favourite_train_save_name))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(dismissLabel) }
        }
    )
}
