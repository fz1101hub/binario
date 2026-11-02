package it.binario.app.journeys

import it.binario.app.data.RailTime
import it.binario.app.data.TrainCategory
import it.binario.app.data.core.JourneyLeg
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.journey.EmbeddedGtfsJourneyProvider
import it.binario.app.data.repository.JourneySearchResult
import it.binario.app.data.repository.JourneySearchStatus
import it.binario.app.data.repository.SourcedJourneyOption
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Filters and sorts of the results screen: "Tutti", "Diretti", "Meno cambi", "Più veloce". [resourceName] is the
 * string resource of the chip label.
 */
enum class JourneyResultsFilter(val resourceName: String) {
    /** Every option, by departure. */
    ALL("journey_filter_all"),
    /** Options without changes, by departure. */
    DIRECT("journey_filter_direct"),
    /** Every option, fewest changes first, then by departure. */
    FEWEST_CHANGES("journey_filter_fewest_changes"),
    /** Every option, shortest total duration first, then by departure. */
    FASTEST("journey_filter_fastest")
}

/** "Diretto" ([directResourceName]) or "N cambi" ([changesPluralName] with quantity and argument [Changes.count]). */
sealed interface JourneyChangesLabel {
    data object Direct : JourneyChangesLabel
    data class Changes(val count: Int) : JourneyChangesLabel

    companion object {
        const val directResourceName = "journey_direct"
        const val changesPluralName = "journey_changes"

        fun of(changes: Int): JourneyChangesLabel = if (changes <= 0) Direct else Changes(changes)
    }
}

/** Localized label of the source that computed an option ([resourceName]); [OTHER] shows the provider name. */
enum class JourneySourceLabel(val resourceName: String?) {
    OFFLINE_TIMETABLE("journey_source_offline"),
    OTHER(null)
}

/** Why the list is empty; [resourceName] is the message to show. */
enum class JourneyEmptyReason(val resourceName: String) {
    NO_SOLUTION("journey_empty_no_solution"),
    NOT_COVERED("journey_empty_not_covered"),
    SCHEDULE_EXPIRED("journey_empty_schedule_expired"),
    UNAVAILABLE("journey_empty_unavailable"),
    /** Options exist, but none without changes (the "Diretti" filter). */
    NO_DIRECT("journey_empty_no_direct")
}

data class JourneyLegRow(
    val mode: LegMode,
    /** Published category or line ("RE11", "REG", "EC"), shown before the number; may be null. */
    val lineLabel: String?,
    /** Public train number ("2153"); may be null. */
    val number: String?,
    val operatorName: String?,
    /** For the existing category labels and icons when [lineLabel] is null. */
    val trainCategory: TrainCategory,
    val serviceKind: ServiceKind,
    val fromLabel: String,
    val toLabel: String,
    val fromStationId: String,
    val toStationId: String,
    val departure: ZonedDateTime,
    val arrival: ZonedDateTime,
    /** "HH:mm", railway-local. */
    val departureTime: String,
    val arrivalTime: String,
    val durationMinutes: Long,
    /** Source identity of the run (for example `lombardia-trenord-gtfs:<trip>:<service day>`). */
    val runId: String?
)

/** A change between two legs: where, when the first arrives, when the next leaves, and the wait. */
data class JourneyTransferRow(
    val stationLabel: String,
    val stationId: String,
    val arrivalTime: String,
    val departureTime: String,
    val waitMinutes: Long
)

data class JourneyOptionRow(
    val id: String,
    val departure: ZonedDateTime,
    val arrival: ZonedDateTime,
    val departureTime: String,
    val arrivalTime: String,
    /** Days between departure and arrival (1 = "il giorno dopo"). */
    val arrivalDayOffset: Int,
    val durationMinutes: Long,
    /** [durationMinutes] split for display: hours with `journey_duration_hours`, minutes with `minutes_short`. */
    val durationHours: Long,
    val durationRemainderMinutes: Long,
    val changes: Int,
    val changesLabel: JourneyChangesLabel,
    val legs: List<JourneyLegRow>,
    val transfers: List<JourneyTransferRow>,
    val sourceId: String,
    val sourceLabel: JourneySourceLabel,
    val sourceName: String,
    /** Scheduled times only: no delay, platform or cancellation checked (`journey_scheduled_only_notice`). */
    val scheduledOnly: Boolean
)

data class JourneyFilterChip(
    val filter: JourneyResultsFilter,
    val selected: Boolean,
    /** Options the filter shows. */
    val count: Int
)

data class JourneyResultsPresentation(
    val status: JourneySearchStatus,
    val filter: JourneyResultsFilter,
    val filters: List<JourneyFilterChip>,
    /** Options after [filter], in its order. */
    val rows: List<JourneyOptionRow>,
    /** Set when [rows] is empty. */
    val emptyReason: JourneyEmptyReason?,
    /** Some option is scheduled-only: show `journey_scheduled_only_notice`. */
    val showScheduledOnlyNotice: Boolean,
    /** An offline-timetable option is shown: show `journey_coverage_notice` (the networks the packs cover). */
    val showCoverageNotice: Boolean,
    /** Part of the source data could not be read: show `journey_incomplete_notice`. */
    val showIncompleteNotice: Boolean
)

/** Plain, resource-free presentation of a [JourneySearchResult] for the results screen. */
object JourneyResultsPresenter {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    fun present(
        result: JourneySearchResult,
        filter: JourneyResultsFilter = JourneyResultsFilter.ALL,
        zone: ZoneId = RailTime.defaultZone
    ): JourneyResultsPresentation {
        val all = result.options.map { row(it, zone) }
        val rows = apply(all, filter)
        val emptyReason = when {
            rows.isNotEmpty() -> null
            all.isNotEmpty() -> JourneyEmptyReason.NO_DIRECT
            else -> when (result.status) {
                JourneySearchStatus.FOUND, JourneySearchStatus.NO_SOLUTION -> JourneyEmptyReason.NO_SOLUTION
                JourneySearchStatus.NOT_COVERED -> JourneyEmptyReason.NOT_COVERED
                JourneySearchStatus.SCHEDULE_EXPIRED -> JourneyEmptyReason.SCHEDULE_EXPIRED
                JourneySearchStatus.UNAVAILABLE -> JourneyEmptyReason.UNAVAILABLE
            }
        }
        return JourneyResultsPresentation(
            status = result.status,
            filter = filter,
            filters = JourneyResultsFilter.entries.map { candidate ->
                JourneyFilterChip(candidate, candidate == filter, apply(all, candidate).size)
            },
            rows = rows,
            emptyReason = emptyReason,
            showScheduledOnlyNotice = all.any { it.scheduledOnly },
            showCoverageNotice = all.any { it.sourceLabel == JourneySourceLabel.OFFLINE_TIMETABLE },
            showIncompleteNotice = result.incompleteCoverage
        )
    }

    fun apply(rows: List<JourneyOptionRow>, filter: JourneyResultsFilter): List<JourneyOptionRow> {
        val byDeparture = compareBy<JourneyOptionRow> { it.departure.toInstant() }.thenBy { it.arrival.toInstant() }
        return when (filter) {
            JourneyResultsFilter.ALL -> rows.sortedWith(byDeparture)
            JourneyResultsFilter.DIRECT -> rows.filter { it.changes == 0 }.sortedWith(byDeparture)
            JourneyResultsFilter.FEWEST_CHANGES -> rows.sortedWith(compareBy<JourneyOptionRow> { it.changes }.then(byDeparture))
            JourneyResultsFilter.FASTEST -> rows.sortedWith(compareBy<JourneyOptionRow> { it.durationMinutes }.then(byDeparture))
        }
    }

    fun row(sourced: SourcedJourneyOption, zone: ZoneId = RailTime.defaultZone): JourneyOptionRow {
        val option = sourced.option
        val legs = option.legs.map { leg(it, zone) }
        val transfers = option.legs.zipWithNext { arriving, leaving ->
            JourneyTransferRow(
                stationLabel = arriving.destinationLabel ?: leaving.originLabel ?: arriving.destination.value,
                stationId = arriving.destination.value,
                arrivalTime = time(arriving.scheduledArrival, zone),
                departureTime = time(leaving.scheduledDeparture, zone),
                waitMinutes = Duration.between(arriving.scheduledArrival, leaving.scheduledDeparture).toMinutes()
            )
        }
        val departure = option.departure.withZoneSameInstant(zone)
        val arrival = option.arrival.withZoneSameInstant(zone)
        val duration = Duration.between(option.departure, option.arrival).toMinutes().coerceAtLeast(0)
        return JourneyOptionRow(
            id = option.id.value,
            departure = departure,
            arrival = arrival,
            departureTime = time(departure, zone),
            arrivalTime = time(arrival, zone),
            arrivalDayOffset = ChronoUnit.DAYS.between(departure.toLocalDate(), arrival.toLocalDate()).toInt(),
            durationMinutes = duration,
            durationHours = duration / 60,
            durationRemainderMinutes = duration % 60,
            changes = option.transfers,
            changesLabel = JourneyChangesLabel.of(option.transfers),
            legs = legs,
            transfers = transfers,
            sourceId = sourced.sourceId.value,
            sourceLabel = if (sourced.sourceId == EmbeddedGtfsJourneyProvider.SOURCE_ID) JourneySourceLabel.OFFLINE_TIMETABLE else JourneySourceLabel.OTHER,
            sourceName = sourced.sourceName,
            scheduledOnly = sourced.scheduledOnly
        )
    }

    private fun leg(leg: JourneyLeg, zone: ZoneId): JourneyLegRow = JourneyLegRow(
        mode = leg.mode,
        lineLabel = leg.lineLabel,
        number = leg.publicNumber,
        operatorName = leg.operatorName,
        trainCategory = trainCategory(leg),
        serviceKind = leg.serviceKind,
        fromLabel = leg.originLabel ?: leg.origin.value,
        toLabel = leg.destinationLabel ?: leg.destination.value,
        fromStationId = leg.origin.value,
        toStationId = leg.destination.value,
        departure = leg.scheduledDeparture.withZoneSameInstant(zone),
        arrival = leg.scheduledArrival.withZoneSameInstant(zone),
        departureTime = time(leg.scheduledDeparture, zone),
        arrivalTime = time(leg.scheduledArrival, zone),
        durationMinutes = Duration.between(leg.scheduledDeparture, leg.scheduledArrival).toMinutes().coerceAtLeast(0),
        runId = leg.runId?.value
    )

    /** The app's category for a leg; a foreign high-speed train is never called a "Freccia". */
    internal fun trainCategory(leg: JourneyLeg): TrainCategory {
        val line = leg.lineLabel?.uppercase(Locale.ROOT)?.replace(" ", "").orEmpty()
        val operator = leg.operatorName.orEmpty().lowercase(Locale.ROOT)
        return when {
            leg.mode != LegMode.TRAIN -> TrainCategory.OTHER
            operator.contains("italo") -> TrainCategory.AV_ITALO
            line.startsWith("FR") || line == "FA" || line == "FB" -> TrainCategory.FRECCE
            leg.serviceKind == ServiceKind.INTERCITY -> TrainCategory.INTERCITY
            line.startsWith("RV") -> TrainCategory.REGIONALE_VELOCE
            leg.serviceKind == ServiceKind.REGIONAL || leg.serviceKind == ServiceKind.SUBURBAN ->
                if (operator.contains("trenord")) TrainCategory.TRENORD_REGIO else TrainCategory.REGIONALE
            else -> TrainCategory.OTHER
        }
    }

    private fun time(value: ZonedDateTime, zone: ZoneId): String = value.withZoneSameInstant(zone).format(timeFormat)
}
