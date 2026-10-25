package it.binario.app

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.binario.app.data.RailTime
import it.binario.app.data.TrainService
import java.time.LocalDate

/**
 * "Add to calendar" action of the train detail. It hands a prefilled event to the calendar app
 * the user picks, so Binario needs no calendar permission and never writes a calendar itself.
 */
@Composable
internal fun TrainCalendarAction(service: TrainService, shareStrings: TrainShareStrings) {
    val context = LocalContext.current
    var message by remember(service.id) { mutableStateOf<String?>(null) }
    val calendarStrings = TrainCalendarStrings(
        operator = stringResource(R.string.calendar_event_operator),
        timesMayChange = stringResource(R.string.calendar_event_times_note)
    )
    val noCalendarApp = stringResource(R.string.calendar_no_app)
    val timeUnavailable = stringResource(R.string.calendar_time_unavailable)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedButton(
            onClick = {
                val event = TrainCalendarEventFactory.create(service, shareStrings, calendarStrings, LocalDate.now(RailTime.defaultZone))
                message = if (event == null) {
                    timeUnavailable
                } else {
                    try {
                        context.startActivity(event.insertIntent())
                        null
                    } catch (_: ActivityNotFoundException) {
                        noCalendarApp
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.CalendarMonth, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.calendar_add))
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
