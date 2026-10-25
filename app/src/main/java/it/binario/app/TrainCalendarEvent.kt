package it.binario.app

import android.content.Intent
import android.provider.CalendarContract
import it.binario.app.data.RailTime
import it.binario.app.data.TrainService
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Calendar-only templates; title, category and platform reuse the [TrainShareStrings] of the share action. */
data class TrainCalendarStrings(
    /** "Operator: %1$s". */
    val operator: String,
    /** States that the event carries the scheduled times, which can change. */
    val timesMayChange: String
)

data class TrainCalendarEvent(
    val title: String,
    val beginEpochMillis: Long,
    /** Null when the arrival time is unknown: the calendar app then applies its default duration. */
    val endEpochMillis: Long?,
    val location: String,
    val description: String
)

/**
 * Builds the single-train calendar event. Begin and end are the scheduled departure and arrival
 * of the train shown in the detail: an event outlives the current delay, so expected times are
 * never written into it, and the description says the times can change.
 */
object TrainCalendarEventFactory {
    private val clockTime = DateTimeFormatter.ofPattern("H:mm")

    fun create(
        service: TrainService,
        share: TrainShareStrings,
        calendar: TrainCalendarStrings,
        fallbackDate: LocalDate,
        zone: ZoneId = RailTime.defaultZone
    ): TrainCalendarEvent? {
        val begin = service.scheduledDepartureDateTime?.atZone(zone)
            ?: parse(service.scheduledDeparture)?.let { LocalDateTime.of(fallbackDate, it).atZone(zone) }
            ?: return null
        val category = share.categoryLabels[service.trainCategory].orEmpty()
        val description = listOfNotNull(
            service.displayOperator.takeIf(String::isNotBlank)?.let { share.format(calendar.operator, it) },
            service.platform?.takeIf(String::isNotBlank)?.let { share.format(share.platform, it) },
            calendar.timesMayChange
        ).joinToString("\n")
        return TrainCalendarEvent(
            title = share.format(share.route, listOf(category, service.number).filter(String::isNotBlank).joinToString(" "), service.destination),
            beginEpochMillis = begin.toInstant().toEpochMilli(),
            endEpochMillis = arrival(service, begin, zone)?.toInstant()?.toEpochMilli(),
            location = service.origin,
            description = description
        )
    }

    /** Extras of the ACTION_INSERT intent, keyed by the CalendarContract names. */
    fun intentExtras(event: TrainCalendarEvent): Map<String, Any> = buildMap {
        put(CalendarContract.Events.TITLE, event.title)
        put(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.beginEpochMillis)
        event.endEpochMillis?.let { put(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        put(CalendarContract.Events.EVENT_LOCATION, event.location)
        put(CalendarContract.Events.DESCRIPTION, event.description)
    }

    /** Scheduled time at the destination stop; a clock time not after the departure is the next day. */
    private fun arrival(service: TrainService, begin: ZonedDateTime, zone: ZoneId): ZonedDateTime? {
        val stop = service.stops.drop(1).lastOrNull { it.station.equals(service.destination, ignoreCase = true) }
            ?: service.stops.drop(1).lastOrNull()
            ?: return null
        val time = parse(stop.scheduled) ?: return null
        val sameDay = LocalDateTime.of(begin.toLocalDate(), time).atZone(zone)
        return when {
            sameDay.isAfter(begin) -> sameDay
            time == begin.toLocalTime().withSecond(0).withNano(0) -> null
            else -> sameDay.plusDays(1)
        }
    }

    private fun parse(value: String): LocalTime? = runCatching { LocalTime.parse(value.trim(), clockTime) }.getOrNull()
}

internal fun TrainCalendarEvent.insertIntent(): Intent =
    Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
        TrainCalendarEventFactory.intentExtras(this@insertIntent).forEach { (key, value) ->
            when (value) {
                is Long -> putExtra(key, value)
                is String -> putExtra(key, value)
            }
        }
    }
