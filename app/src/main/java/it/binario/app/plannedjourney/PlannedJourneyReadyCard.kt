package it.binario.app.plannedjourney

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import it.binario.app.R
import it.binario.app.ui.common.LocalizedDates
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/** «Tra 1 h 10 min»: the time left before a departure, rounded up to the minute and never negative. */
data class PlannedJourneyCountdown(val hours: Long, val minutes: Long) {
    companion object {
        fun between(now: Instant, departure: Instant): PlannedJourneyCountdown {
            val totalMinutes = (Duration.between(now, departure).toMillis().coerceAtLeast(0) + 59_999) / 60_000
            return PlannedJourneyCountdown(totalMinutes / 60, totalMinutes % 60)
        }
    }
}

/** The earliest occurrence not departed and not completed, whatever its pin or label: the journey to get ready for. */
fun nextPlannedJourney(journeys: Iterable<PlannedJourney>, now: Instant): PlannedJourneyAgendaEntry? =
    PlannedJourneyAgenda.ordered(journeys, PlannedJourneyAgendaFilter.UPCOMING, now).firstOrNull()

/**
 * «Pronto per partire» on Per te: the next planned journey with its stored Italian date and time, a countdown that
 * ticks every minute while the screen is visible, the reminder, the checklist left and a way into the Agenda. The
 * Agenda is read again each time the screen returns to the foreground. Nothing is shown without an upcoming journey.
 */
@Composable
fun PlannedJourneyReadyCard(
    viewModel: PlannedJourneyViewModel,
    onOpenAgenda: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.reload()
            viewModel.refreshReminders()
            while (true) {
                now = Instant.now()
                delay(60_000L - now.toEpochMilli() % 60_000L)
            }
        }
    }
    val entry = remember(viewModel.snapshot, now) { nextPlannedJourney(viewModel.snapshot.journeys, now) } ?: return
    val first = entry.occurrence.itinerary.segments.first()
    val last = entry.occurrence.itinerary.segments.last()
    val route = stringResource(R.string.planned_journey_route, first.origin.displayName, last.destination.displayName)
    // The stored Italian wall-clock date and time, never converted to the phone's zone.
    val schedule = LocalizedDates.weekdayDayMonthTime(LocalConfiguration.current.locales[0]).format(first.departure.localDateTime())
    val countdown = PlannedJourneyCountdown.between(now, entry.occurrence.scheduledAt)
    val countdownText = when {
        countdown.hours == 0L -> stringResource(R.string.planned_journey_countdown_minutes, countdown.minutes.toInt())
        countdown.minutes == 0L -> stringResource(R.string.planned_journey_countdown_hours, countdown.hours.toInt())
        else -> stringResource(R.string.planned_journey_countdown_hours_minutes, countdown.hours.toInt(), countdown.minutes.toInt())
    }
    val status = viewModel.reminderStatus
    val lead = entry.journey.reminder?.minutesBeforeDeparture?.minutes
    // Only a reminder the device will really deliver: notifications on and allowed, and its time still ahead.
    val reminderActive = lead != null && status.enabled && status.notificationsAllowed &&
        entry.occurrence.scheduledAt.minusSeconds(lead * 60L).isAfter(now)
    val checklistLeft = entry.journey.checklist.count { !it.completed }
    Card(modifier = modifier.fillMaxWidth().testTag("for-you-ready")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.planned_journey_ready_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() }
            )
            Text(entry.journey.label.ifBlank { route }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (entry.journey.label.isNotBlank()) Text(route, style = MaterialTheme.typography.bodyMedium)
            Text(schedule, style = MaterialTheme.typography.bodyMedium)
            Text(countdownText, style = MaterialTheme.typography.titleSmall)
            Text(
                if (reminderActive && lead != null) pluralStringResource(R.plurals.planned_journey_reminder_value, lead, lead)
                else stringResource(R.string.planned_journey_ready_no_reminder),
                style = MaterialTheme.typography.bodySmall
            )
            if (entry.journey.checklist.isNotEmpty()) {
                Text(
                    if (checklistLeft == 0) stringResource(R.string.planned_journey_ready_checklist_done)
                    else pluralStringResource(R.plurals.planned_journey_ready_checklist_left, checklistLeft, checklistLeft),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                stringResource(R.string.planned_journey_ready_local_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onOpenAgenda) { Text(stringResource(R.string.planned_journey_ready_open_agenda)) }
        }
    }
}
