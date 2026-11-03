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

/**
 * What a leg travels on, for its icon and its vehicle label: [resourceName] is the string resource of the label (null
 * for a train, which shows its category and number), [isBus] asks for a bus icon.
 */
enum class JourneyLegVehicle(val resourceName: String?, val isBus: Boolean) {
    TRAIN(null, false),
    /** Scheduled Trenitalia bus ("Bus Trenitalia"). */
    BUS_TRENITALIA("journey_leg_bus_trenitalia", true),
    /** Trenitalia FrecciaLink coach, when the timetable names it so. */
    FRECCIALINK("journey_leg_freccialink", true),
    /** ItaloBus coach. */
    ITALOBUS("journey_leg_italobus", true),
    /** Scheduled coach of another operator. */
    BUS("journey_leg_bus", true),
    /** Bus replacing a train. */
    REPLACEMENT_BUS("journey_leg_replacement_bus", true),
    /** City metro line ("Metro M1"). */
    METRO("journey_leg_metro", false),
    /** City tram line ("Tram 14"). */
    TRAM("journey_leg_tram", false),
    /** Funicular or lift of a city network. */
    FUNICULAR("journey_leg_funicular", false),
    /** Walking or unknown: no vehicle label. */
    OTHER(null, false);

    companion object {
        fun of(mode: LegMode, operatorName: String?, lineLabel: String?): JourneyLegVehicle {
            val operator = operatorName.orEmpty().lowercase(Locale.ROOT)
            val line = lineLabel.orEmpty().uppercase(Locale.ROOT).replace(" ", "")
            return when (mode) {
                LegMode.TRAIN -> TRAIN
                LegMode.REPLACEMENT_BUS -> REPLACEMENT_BUS
                LegMode.BUS -> when {
                    line == "FL" || line.contains("FRECCIALINK") -> FRECCIALINK
                    operator.contains("italo") || line.contains("ITALOBUS") -> ITALOBUS
                    operator.contains("trenitalia") -> BUS_TRENITALIA
                    else -> BUS
                }
                LegMode.METRO -> METRO
                LegMode.TRAM -> TRAM
                LegMode.FUNICULAR -> FUNICULAR
                LegMode.WALK, LegMode.UNKNOWN -> OTHER
            }
        }
    }
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
    val runId: String?,
    /** Vehicle icon and label: a bus leg ([JourneyLegVehicle.isBus]) shows a bus and its label, never a train. */
    val vehicle: JourneyLegVehicle = JourneyLegVehicle.of(mode, operatorName, lineLabel),
    /** Live times of the leg (train lookup or city realtime feed); null on the timetable only. */
    val live: JourneyLegLive? = null,
    /** "HH:mm" expected departure and arrival from [live], set only when they differ from nothing scheduled. */
    val expectedDepartureTime: String? = null,
    val expectedArrivalTime: String? = null,
    /** Minutes from the scheduled to the expected time (negative when early), 0 without live data. */
    val departureDelayMinutes: Int = 0,
    val arrivalDelayMinutes: Int = 0,
    /** "HH:mm" of the city realtime observation shown next to [JourneyLegLive.sourceLabel]. */
    val liveObservedTime: String? = null
) {
    val isBus: Boolean get() = vehicle.isBus
}

/**
 * A change between two vehicles: where the next one leaves, when the traveller gets there (the first vehicle's
 * arrival, or the end of the walk between them), when the next one leaves, and the wait. [afterLegIndex] is the leg
 * the change follows in [JourneyOptionRow.legs]: the arriving vehicle, or the walk when the change needs one.
 */
data class JourneyTransferRow(
    val stationLabel: String,
    val stationId: String,
    val arrivalTime: String,
    val departureTime: String,
    val waitMinutes: Long,
    val afterLegIndex: Int = -1,
    /** "Coincidenza a rischio" / "Coincidenza persa" with live data; [TransferRiskLevel.UNKNOWN] on the timetable only. */
    val risk: TransferRiskLevel = TransferRiskLevel.UNKNOWN,
    /** "HH:mm" expected arrival at the change and expected next departure, set only with live data. */
    val expectedArrivalTime: String? = null,
    val expectedDepartureTime: String? = null
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
    val scheduledOnly: Boolean,
    /** The worst change of the option with live data (see [TransferRiskLevel]); UNKNOWN without live data. */
    val transferRisk: TransferRiskLevel = TransferRiskLevel.UNKNOWN
) {
    /** Some leg is a bus (scheduled coach or replacement): for a bus icon on the row or a "no bus" filter. */
    val hasBusLeg: Boolean get() = legs.any(JourneyLegRow::isBus)
}

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
        zone: ZoneId = RailTime.defaultZone,
        /** Live data of the legs ([TransferRiskCalculator.legKey]), from [JourneyLiveLookup]; empty on the timetable only. */
        live: Map<String, JourneyLegLive> = emptyMap()
    ): JourneyResultsPresentation {
        val all = result.options.map { row(it, zone, live) }
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

    fun row(sourced: SourcedJourneyOption, zone: ZoneId = RailTime.defaultZone, live: Map<String, JourneyLegLive> = emptyMap()): JourneyOptionRow {
        val option = sourced.option
        val legs = option.legs.map { leg(it, zone, TransferRiskCalculator.liveOf(it, live)) }
        // A change joins two vehicles; a walk between them (to the metro, to another station) is its own leg and the
        // change follows it, at the place where the next vehicle leaves.
        val transfers = option.legs.indices.zipWithNext().mapNotNull { (arrivingIndex, leavingIndex) ->
            val arriving = option.legs[arrivingIndex]
            val leaving = option.legs[leavingIndex]
            if (leaving.mode == LegMode.WALK) return@mapNotNull null
            val walked = arriving.mode == LegMode.WALK
            if (walked && arrivingIndex == 0) return@mapNotNull null
            val assessment = TransferRiskCalculator.assessChange(option.legs, leavingIndex, live)
            val risk = assessment?.level ?: TransferRiskLevel.UNKNOWN
            JourneyTransferRow(
                stationLabel = (if (walked) leaving.originLabel else arriving.destinationLabel ?: leaving.originLabel)
                    ?: arriving.destination.value,
                stationId = if (walked) leaving.origin.value else arriving.destination.value,
                arrivalTime = time(arriving.scheduledArrival, zone),
                departureTime = time(leaving.scheduledDeparture, zone),
                waitMinutes = Duration.between(arriving.scheduledArrival, leaving.scheduledDeparture).toMinutes(),
                afterLegIndex = arrivingIndex,
                risk = risk,
                expectedArrivalTime = assessment?.takeIf { risk != TransferRiskLevel.UNKNOWN }?.let { time(it.expectedArrival.atZone(zone), zone) },
                expectedDepartureTime = assessment?.takeIf { risk != TransferRiskLevel.UNKNOWN }?.let { time(it.expectedDeparture.atZone(zone), zone) }
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
            scheduledOnly = sourced.scheduledOnly,
            transferRisk = TransferRiskLevel.worst(transfers.map { it.risk })
        )
    }

    private fun leg(leg: JourneyLeg, zone: ZoneId, live: JourneyLegLive? = null): JourneyLegRow = JourneyLegRow(
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
        runId = leg.runId?.value,
        live = live,
        expectedDepartureTime = live?.expectedDeparture?.takeIf { !live.cancelled }?.let { time(it.atZone(zone), zone) },
        expectedArrivalTime = live?.expectedArrival?.takeIf { !live.cancelled }?.let { time(it.atZone(zone), zone) },
        departureDelayMinutes = live?.expectedDeparture?.takeIf { !live.cancelled }?.let { delayMinutes(leg.scheduledDeparture, it) } ?: 0,
        arrivalDelayMinutes = live?.expectedArrival?.takeIf { !live.cancelled }?.let { delayMinutes(leg.scheduledArrival, it) } ?: 0,
        liveObservedTime = live?.takeIf { it.sourceLabel != null }?.observedAt?.let { time(it.atZone(zone), zone) }
    )

    /** Whole minutes late, rounded down like the boards (a run 59 seconds late is on time). */
    private fun delayMinutes(scheduled: ZonedDateTime, expected: java.time.Instant): Int =
        Math.floorDiv(Duration.between(scheduled.toInstant(), expected).seconds, 60L).toInt()

    /** The app's category for a leg; a foreign high-speed train is never called a "Freccia", a bus is never a train. */
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
