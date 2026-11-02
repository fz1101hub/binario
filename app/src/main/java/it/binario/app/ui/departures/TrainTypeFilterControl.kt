package it.binario.app.ui.departures

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import it.binario.app.ui.common.ScreenMargin
import it.binario.app.ui.common.TonalButton
import it.binario.app.widget.TrainTypeGroup

/** The excluded train types of [stationId], read from and written to this device; empty for no station. */
@Composable
internal fun rememberTrainTypeExclusions(stationId: String?): Pair<Set<TrainTypeGroup>, (Set<TrainTypeGroup>) -> Unit> {
    val context = LocalContext.current
    val store = remember { TrainTypeExclusionStore(context) }
    var excluded by remember(stationId) { mutableStateOf(stationId?.let(store::read).orEmpty()) }
    return excluded to { value: Set<TrainTypeGroup> ->
        excluded = value
        stationId?.let { store.save(it, value) }
    }
}

/**
 * "Tipi di treno" of a Partenze board: what is hidden at [stationName], how many trains that hides now, "Mostra tutti",
 * and the sheet to choose. Trains whose type the source does not tell are never hidden.
 */
@Composable
internal fun TrainTypeFilterControl(
    stationName: String,
    excluded: Set<TrainTypeGroup>,
    hiddenCount: Int,
    onChange: (Set<TrainTypeGroup>) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    if (showSheet) {
        TrainTypeSheet(stationName = stationName, excluded = excluded, onChange = onChange, onDismiss = { showSheet = false })
    }
    val labels = TrainTypeGroup.entries.filter { it in excluded }.map { it.partenzeLabel() }
    Column(modifier.fillMaxWidth().padding(horizontal = ScreenMargin, vertical = 4.dp).testTag("partenze-train-types")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    if (labels.isEmpty()) stringResource(R.string.partenze_types_all) else stringResource(R.string.partenze_types_hidden, labels.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (labels.isEmpty()) null else FontWeight.Bold
                )
                if (hiddenCount > 0) {
                    Text(
                        pluralStringResource(R.plurals.partenze_hidden_trains, hiddenCount, hiddenCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (excluded.isNotEmpty()) {
                TextButton(onClick = { onChange(emptySet()) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.partenze_types_show_all))
                }
            }
            TonalButton(
                text = stringResource(R.string.partenze_types_action),
                onClick = { showSheet = true },
                leadingIcon = Icons.Default.FilterList,
                modifier = Modifier.testTag("partenze-train-types-open")
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrainTypeSheet(
    stationName: String,
    excluded: Set<TrainTypeGroup>,
    onChange: (Set<TrainTypeGroup>) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .testTag("partenze-train-types-sheet"),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.partenze_types_sheet_title, stationName),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.partenze_types_sheet_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TrainTypeGroup.entries.forEach { group ->
                val shown = group !in excluded
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = shown, role = Role.Checkbox, onValueChange = { show ->
                            onChange(if (show) excluded - group else excluded + group)
                        }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = shown, onCheckedChange = null)
                    Text(group.partenzeLabel(), modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.partenze_types_done))
            }
        }
    }
}

@Composable
private fun TrainTypeGroup.partenzeLabel(): String = stringResource(
    when (this) {
        TrainTypeGroup.HIGH_SPEED -> R.string.widget_settings_type_high_speed
        TrainTypeGroup.INTERCITY_NIGHT -> R.string.widget_settings_type_intercity
        TrainTypeGroup.REGIONAL_SUBURBAN -> R.string.widget_settings_type_regional
        TrainTypeGroup.ITALO -> R.string.widget_settings_type_italo
        TrainTypeGroup.REPLACEMENT_BUS -> R.string.partenze_type_bus
        TrainTypeGroup.INTERNATIONAL -> R.string.widget_settings_type_international
    }
)
