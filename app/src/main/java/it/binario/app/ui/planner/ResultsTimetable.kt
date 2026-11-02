package it.binario.app.ui.planner

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import it.binario.app.R
import it.binario.app.data.RailTime
import it.binario.app.data.TrainService
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.timetables.ShareText
import it.binario.app.timetables.TimetableRow
import it.binario.app.timetables.TimetableShareStrings
import it.binario.app.ui.common.JourneyTimes
import it.binario.app.ui.common.localizedLabel
import it.binario.app.ui.common.presentedAsDemonstration
import it.binario.app.ui.journeys.journeySourceLabel
import it.binario.app.ui.journeys.journeyTrainsLabel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// The route results as plain rows, for "Salva orario" and the shares: labels resolved in the app language at the
// moment of the save or share, as the user reads them on screen.

/** The share templates in the app language. */
@Composable
internal fun timetableShareStrings(): TimetableShareStrings {
    val resources = LocalResources.current
    return TimetableShareStrings(
        locale = LocalConfiguration.current.locales[0],
        route = stringResource(R.string.share_route),
        listHeader = stringResource(R.string.share_timetable_header),
        nextDay = stringResource(R.string.journey_arrival_next_day),
        platform = stringResource(R.string.platform_label),
        platformChanged = stringResource(R.string.platform_changed_label),
        source = stringResource(R.string.share_timetable_source),
        scheduledOnly = stringResource(R.string.share_scheduled_data),
        updated = stringResource(R.string.share_updated),
        savedNotUpdated = stringResource(R.string.saved_timetable_saved_at),
        returnTitle = stringResource(R.string.planner_return),
        changes = { count ->
            if (count <= 0) resources.getString(R.string.journey_direct) else resources.getQuantityString(R.plurals.journey_changes, count, count)
        },
        stopLine = stringResource(R.string.share_stop_line),
        stopArrival = stringResource(R.string.share_stop_arrival),
        stopDeparture = stringResource(R.string.share_stop_departure),
        stopExpected = stringResource(R.string.share_stop_expected),
        stopPlatform = stringResource(R.string.share_stop_platform_short),
        stopPlatformChanged = stringResource(R.string.share_stop_platform_changed)
    )
}

/** "lun 2 nov · dalle 17:00": the date and starting time of a search, absolute (a share is read later). */
@Composable
internal fun searchDateLabel(date: LocalDate, from: LocalTime): String {
    val locale = LocalConfiguration.current.locales[0]
    return stringResource(
        R.string.results_date_from_time,
        date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)),
        from.format(DateTimeFormatter.ofPattern("HH:mm", locale))
    )
}

/** "2 nov 2026, 17:32": when a timetable was saved. */
internal fun savedAtLabel(epochMillis: Long, locale: Locale): String =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale)
        .withZone(RailTime.defaultZone)
        .format(Instant.ofEpochMilli(epochMillis))

/** The rows of [entries] as they are listed; demonstration data keeps the timetable only. */
@Composable
internal fun timetableRows(
    entries: List<ResultEntry>,
    isDemonstration: Boolean,
    provider: String,
    fetchedAt: Long,
    journeysFetchedAt: Long
): List<TimetableRow> = entries.map { entry ->
    when (entry) {
        is ResultEntry.Train -> trainTimetableRow(entry.service, isDemonstration, provider, fetchedAt)
        is ResultEntry.Journey -> journeyTimetableRow(entry.row, journeysFetchedAt)
    }
}

@Composable
internal fun trainTimetableRow(service: TrainService, isDemonstration: Boolean, provider: String, fetchedAt: Long): TimetableRow {
    val shown = if (isDemonstration) service.presentedAsDemonstration() else service
    return TimetableRow(
        departure = shown.scheduledDeparture,
        arrival = JourneyTimes.arrival(shown),
        durationMinutes = JourneyTimes.durationMinutes(shown),
        trains = stringResource(R.string.service_category_number, shown.trainCategory.localizedLabel(), shown.number),
        changes = shown.transferCount,
        platform = shown.platform,
        scheduledPlatform = shown.scheduledPlatform?.takeIf { shown.platform != null },
        source = provider,
        fetchedAtEpochMillis = fetchedAt,
        scheduledOnly = !shown.operationalStatusVerified
    )
}

@Composable
internal fun journeyTimetableRow(row: JourneyOptionRow, fetchedAt: Long): TimetableRow = TimetableRow(
    departure = row.departureTime,
    arrival = row.arrivalTime,
    arrivalDayOffset = row.arrivalDayOffset,
    durationMinutes = row.durationMinutes.toInt(),
    trains = journeyTrainsLabel(row),
    changes = row.changes,
    transfers = row.transfers.map { transfer ->
        val wait = transfer.waitMinutes.coerceAtLeast(0).toInt()
        stringResource(R.string.journey_transfer_at, transfer.stationLabel) + " · " + pluralStringResource(R.plurals.journey_transfer_wait, wait, wait)
    },
    source = journeySourceLabel(row),
    fetchedAtEpochMillis = fetchedAt,
    scheduledOnly = row.scheduledOnly
)

/** Opens the system share sheet with [share] as plain text, like the train share of the detail. */
internal fun startTextShare(context: Context, share: ShareText, chooserTitle: String) {
    runCatching {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, share.title)
                    .putExtra(Intent.EXTRA_TEXT, share.text),
                chooserTitle
            )
        )
    }
}
