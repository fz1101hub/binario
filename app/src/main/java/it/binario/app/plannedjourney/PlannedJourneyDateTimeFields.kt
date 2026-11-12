package it.binario.app.plannedjourney

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.data.RailTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun PlannedJourneyDateTimeFields(
    editor: PlannedJourneyEditorState,
    onChange: ((PlannedJourneyEditorState) -> PlannedJourneyEditorState) -> Unit
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    // A return or replan starts with no date and time: the pickers open on today in the journey's zone.
    val chosenDate = runCatching { LocalDate.parse(editor.date) }.getOrNull()
    val chosenTime = runCatching { LocalTime.parse(editor.time) }.getOrNull()
    val date = chosenDate ?: LocalDate.now(runCatching { ZoneId.of(editor.timeZoneId) }.getOrDefault(RailTime.defaultZone))
    val time = chosenTime ?: LocalTime.of(9, 0)
    val unset = stringResource(R.string.agenda_editor_unset)
    val is24Hour = DateFormat.is24HourFormat(context)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().testTag("plannedJourneyDate"),
            onClick = {
                DatePickerDialog(context, { _, year, month, day ->
                    onChange { it.copy(date = LocalDate.of(year, month + 1, day).toString()) }
                }, date.year.coerceIn(1900, 2100), date.monthValue - 1, date.dayOfMonth).show()
            }
        ) {
            Text(stringResource(R.string.agenda_editor_date_value,
                chosenDate?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) ?: unset))
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().testTag("plannedJourneyTime"),
            onClick = {
                TimePickerDialog(context, { _, hour, minute ->
                    onChange { it.copy(time = LocalTime.of(hour, minute).toString()) }
                }, time.hour, time.minute, is24Hour).show()
            }
        ) {
            Text(stringResource(R.string.agenda_editor_time_value,
                chosenTime?.format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale)) ?: unset))
        }
        Text(editor.timeZoneId, style = MaterialTheme.typography.labelSmall)
        if (editor.isMultiSegment) Text(stringResource(R.string.agenda_editor_first_segment), style = MaterialTheme.typography.bodySmall)
    }
}
