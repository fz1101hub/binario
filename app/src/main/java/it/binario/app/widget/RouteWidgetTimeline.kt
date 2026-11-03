package it.binario.app.widget

import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailTime
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainService
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Resolves the "HH:mm" times the sources publish to instants, without a date of their own. */
object WidgetClock {
    private val pastTolerance: Duration = Duration.ofHours(6)

    /**
     * The instant of [hhmm] closest to [referenceEpochMillis] in the window from six hours before to
     * eighteen hours after it: a 00:15 train listed at 23:30 is tomorrow's, a 07:40 one listed at
     * 08:00 is today's. Null for a text that is not a time.
     */
    fun resolve(hhmm: String, referenceEpochMillis: Long, zone: ZoneId = RailTime.defaultZone): Long? {
        val time = parse(hhmm) ?: return null
        val reference = Instant.ofEpochMilli(referenceEpochMillis).atZone(zone)
        var candidate = ZonedDateTime.of(reference.toLocalDate(), time, zone)
        if (candidate.isBefore(reference.minus(pastTolerance))) candidate = ZonedDateTime.of(reference.toLocalDate().plusDays(1), time, zone)
        else if (candidate.isAfter(reference.plus(Duration.ofHours(18)))) candidate = ZonedDateTime.of(reference.toLocalDate().minusDays(1), time, zone)
        return candidate.toInstant().toEpochMilli()
    }

    /**
     * [hhmm] on [date], or on the next day when it would come more than an hour before
     * [notBeforeEpochMillis] (an arrival or a delayed departure after midnight). Within the hour it
     * stays on [date]: a train may run a few minutes early.
     */
    fun onDate(hhmm: String, date: LocalDate, notBeforeEpochMillis: Long? = null, zone: ZoneId = RailTime.defaultZone): Long? {
        val time = parse(hhmm) ?: return null
        val sameDay = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
        if (notBeforeEpochMillis != null && sameDay < notBeforeEpochMillis - Duration.ofHours(1).toMillis()) {
            return ZonedDateTime.of(date.plusDays(1), time, zone).toInstant().toEpochMilli()
        }
        return sameDay
    }

    fun parse(hhmm: String): LocalTime? = runCatching { LocalTime.parse(hhmm.trim().take(5)) }.getOrNull()
}

/** Turns the services of a route snapshot into persisted widget rows, with resolved instants. */
object WidgetServiceMapper {
    fun fromService(service: TrainService, retrievedAtEpochMillis: Long, zone: ZoneId = RailTime.defaultZone): WidgetService {
        val scheduledDate = service.scheduledDepartureDateTime?.toLocalDate()
            ?: WidgetClock.resolve(service.scheduledDeparture, retrievedAtEpochMillis, zone)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val scheduledEpoch = service.scheduledDepartureDateTime?.atZone(zone)?.toInstant()?.toEpochMilli()
            ?: scheduledDate?.let { WidgetClock.onDate(service.scheduledDeparture, it, zone = zone) }
        val departureEpoch = if (service.operationalStatusVerified && scheduledDate != null) {
            WidgetClock.onDate(service.expectedDeparture, scheduledDate, scheduledEpoch, zone) ?: scheduledEpoch
        } else scheduledEpoch
        val arrivalStop = service.stops.drop(1).lastOrNull()
        val arrivalVerified = arrivalStop?.operationalStatusVerified ?: service.operationalStatusVerified
        val arrivalLabel = arrivalStop?.let { stop -> if (arrivalVerified) stop.expected.ifBlank { stop.scheduled } else stop.scheduled }
            ?.takeIf { WidgetClock.parse(it) != null }
        val arrivalEpoch = if (arrivalLabel != null && scheduledDate != null) {
            WidgetClock.onDate(arrivalLabel, scheduledDate, departureEpoch ?: scheduledEpoch, zone)
        } else null
        return WidgetService(
            number = service.number,
            operator = service.operator,
            category = service.trainCategory,
            destination = service.destination,
            expectedDeparture = service.expectedDeparture,
            status = service.status,
            delayMinutes = service.delayMinutes,
            platform = service.platform,
            operationalStatusVerified = service.operationalStatusVerified,
            scheduledDeparture = service.scheduledDeparture,
            departureEpochMillis = departureEpoch,
            arrivalEpochMillis = arrivalEpoch,
            arrivalLabel = arrivalLabel,
            typeGroup = WidgetTrainTypes.groupOf(service.trainCategory, service.operator, WidgetTrainTypes.isReplacement(service.routeChange?.kind)),
            serviceDate = scheduledDate,
            scheduledPlatform = service.scheduledPlatform?.takeIf { service.platform != null }
        )
    }
}

/** One visible row of the route widget. */
data class RouteWidgetRow(val service: WidgetService, val inTransit: Boolean)

/**
 * What the route widget lists at render time: exclusions, time range, and departed trains, which
 * leave at their (expected, else scheduled) departure or, with "until arrival", at their arrival.
 */
object RouteWidgetPresenter {
    const val DEPARTED_GRACE_MILLIS = 60_000L

    fun visible(services: List<WidgetService>, nowEpochMillis: Long, settings: RouteWidgetSettings): List<RouteWidgetRow> {
        val horizon = settings.timeRange.hours?.let { nowEpochMillis + Duration.ofHours(it).toMillis() }
        return services.mapNotNull { service ->
            if (WidgetTrainTypes.isHidden(service.typeGroup, settings.excludedTypes)) return@mapNotNull null
            val departure = service.departureEpochMillis ?: return@mapNotNull RouteWidgetRow(service, false)
            if (horizon != null && departure > horizon) return@mapNotNull null
            when {
                nowEpochMillis < departure + DEPARTED_GRACE_MILLIS -> RouteWidgetRow(service, false)
                settings.untilArrival && service.status != ServiceStatus.CANCELLED &&
                    service.arrivalEpochMillis != null && nowEpochMillis < service.arrivalEpochMillis -> RouteWidgetRow(service, true)
                else -> null
            }
        }
    }
}

/**
 * The extra timetable requests a refresh may make to fill the chosen time range, beyond the first
 * request for the next departures. Sources that ignore the query return the same trains again and
 * the paging stops; at most [maxExtraPages] more requests are made.
 */
object RouteWidgetPaging {
    fun horizon(range: RouteTimeRange, now: ZonedDateTime): ZonedDateTime? = when (range) {
        RouteTimeRange.NEXT -> null
        RouteTimeRange.NEXT_3H, RouteTimeRange.NEXT_6H -> now.plusHours(range.hours ?: 0)
        RouteTimeRange.WHOLE_DAY -> now.toLocalDate().atTime(23, 59).atZone(now.zone)
    }

    fun maxExtraPages(range: RouteTimeRange): Int = when (range) {
        RouteTimeRange.NEXT -> 0
        RouteTimeRange.NEXT_3H -> 1
        RouteTimeRange.NEXT_6H -> 2
        RouteTimeRange.WHOLE_DAY -> 3
    }

    /** The query of the next page after the latest departure collected so far, or null to stop. */
    fun nextQuery(range: RouteTimeRange, now: ZonedDateTime, latestDepartureEpochMillis: Long?, pagesDone: Int): JourneyQuery? {
        val horizon = horizon(range, now) ?: return null
        if (pagesDone >= maxExtraPages(range) || latestDepartureEpochMillis == null) return null
        val latest = Instant.ofEpochMilli(latestDepartureEpochMillis).atZone(now.zone)
        if (!latest.isBefore(horizon) || latest.toLocalDate() != now.toLocalDate()) return null
        val next = latest.plusMinutes(1)
        if (next.toLocalDate() != now.toLocalDate()) return null
        return JourneyQuery(next.toLocalDate(), next.toLocalTime().withSecond(0).withNano(0))
    }
}

/**
 * "Whole day" keeps the day's list: fresh rows replace the stored rows of the same run (number and
 * scheduled departure) and the other stored rows of that day stay, so the timetable remains readable
 * offline. Rows of another day are dropped.
 */
object RouteWidgetDayCache {
    const val MAX_ROWS = 60

    fun merge(stored: List<WidgetService>, fresh: List<WidgetService>, serviceDate: LocalDate): List<WidgetService> {
        val freshKeys = fresh.mapTo(hashSetOf()) { it.runKey }
        val kept = stored.filter { it.serviceDate == serviceDate && it.runKey !in freshKeys }
        return (kept + fresh)
            .sortedWith(compareBy<WidgetService, Long?>(nullsLast()) { it.departureEpochMillis }.thenBy { it.number })
            .take(MAX_ROWS)
    }
}
