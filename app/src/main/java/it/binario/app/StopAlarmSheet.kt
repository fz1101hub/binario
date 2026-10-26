package it.binario.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun StopAlarmSheet(
    service: TrainService,
    stop: TrainStop,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text by rememberSaveable(service.id, stop.station, stop.scheduled) {
        mutableStateOf(StopAlarmPlanner.DEFAULT_LEAD_MINUTES.toString())
    }
    val minutes = text.toIntOrNull()
    val valid = minutes != null && minutes in StopAlarmPlanner.leadMinutesRange
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.stop_alarm_setup_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text(stop.station, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.stop_alarm_setup_description, service.number, stop.expected),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 30).forEach { value ->
                    FilterChip(
                        selected = minutes == value,
                        onClick = { text = value.toString() },
                        label = { Text(pluralStringResource(R.plurals.minutes_short, value, value)) }
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { character -> character in '0'..'9' }.take(3) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.stop_alarm_lead_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = text.isNotBlank() && !valid,
                supportingText = {
                    if (text.isNotBlank() && !valid) Text(stringResource(R.string.stop_alarm_lead_error))
                }
            )
            Text(stringResource(R.string.stop_alarm_lead_elapsed), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.stop_alarm_notice), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                enabled = valid,
                onClick = { minutes?.takeIf { it in StopAlarmPlanner.leadMinutesRange }?.let(onConfirm) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.stop_alarm_confirm)) }
        }
    }
}
