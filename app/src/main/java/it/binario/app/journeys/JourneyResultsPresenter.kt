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
import it.binario.app.data.provider.JourneyPreferences
import it.binario.app.data.provider.JourneyRanking
import it.binario.app.data.provider.TravelMode
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Filters and sorts of the results screen: "Tutti", "Diretti", "Meno cambi", "Più veloce", "Meno a piedi".
 * [resourceName] is the string resource of the chip label.
 */
enum class JourneyResultsFilter(val resourceName: String) {
    /** Every option, by departure. */
    ALL("journey_filter_all"),
    /** Options without changes, by departure. */
    DIRECT("journey_filter_direct"),
    /** Every option, fewest changes first, then by departure. */
    FEWEST_CHANGES("journey_filter_fewest_changes"),
    /** Every option, shortest total duration first, then by departure. */
    FASTEST("journey_filter_fastest"),
    /** Every option, least walking first, then by departure. */
    LEAST_WALKING("journey_filter_least_walking");

    companion object {
        /**
         * The chip the results open on for "Preferisci" ([JourneyPreferences.ranking]): the default ("percorso più
         * rapido") keeps "Tutti", the planner's earliest arrivals in order of departure.
         */
        fun forRanking(ranking: JourneyRanking): JourneyResultsFilter = when (ranking) {
            JourneyRanking.FASTEST -> ALL
            JourneyRanking.FEWEST_CHANGES -> FEWEST_CHANGES
            JourneyRanking.LEAST_WALKING -> LEAST_WALKING
        }
    }
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
    /** The trip is published as wheelchair accessible (GTFS 1); false when unknown or not accessible. */
    val wheelchairTrip: Boolean = false,
    /** The whole leg (trip and the two stops, or both ends of a walk) is published as wheelchair accessible. */
    val wheelchairAccessible: Boolean = false,
    /** "Resta a bordo: il treno prosegue come …", only where the vehicle goes on under another label. */
    val continuations: List<JourneyContinuationRow> = emptyList()
) {
    val isBus: Boolean get() = vehicle.isBus
}

/** The vehicle of a leg going on as another run at [stationLabel] ("Resta a bordo"), labelled like a leg. */
data class JourneyContinuationRow(
    val stationLabel: String,
    val lineLabel: String?,
    val number: String?,
    val operatorName: String?,
    val trainCategory: TrainCategory
)

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
    val transferRisk: TransferRiskLevel = TransferRiskLevel.UNKNOWN,
    /** Minutes on foot (to the metro, between stations): the "Meno a piedi" order. */
    val walkMinutes: Long = 0,
    /** Every leg published as wheelchair accessible (trips, boarding and alighting stops, both ends of each walk). */
    val wheelchairAccessible: Boolean = false,
    /** GTFS service day of the first vehicle; null when the source does not say. */
    val serviceDate: LocalDate? = null,
    /** The kinds of service the option uses ([TravelMode]), for the preferred kinds. */
    val modes: Set<TravelMode> = emptySet(),
    /** The last departure of the static timetable's service day for this origin and destination ("Ultimo di oggi"). */
    val lastOfDay: Boolean = false
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
    val showIncompleteNotice: Boolean,
    /**
     * "Non ci sono più treni oggi: primo domani alle HH:mm": no option of the requested service day is left and this is
     * the first of the next one ("HH:mm"); null otherwise or without a requested day.
     */
    val firstDepartureNextDay: String? = null,
    /** "Solo accessibili" found nothing and there is no wheelchair data around the two ends (`journey_accessibility_unavailable`). */
    val accessibilityDataUnavailable: Boolean = false
)

/** Plain, resource-free presentation of a [JourneySearchResult] for the results screen. */
object JourneyResultsPresenter {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    fun present(
        result: JourneySearchResult,
        filter: JourneyResultsFilter = JourneyResultsFilter.ALL,
        zone: ZoneId = RailTime.defaultZone,
        /** Live data of the legs ([TransferRiskCalculator.legKey]), from [JourneyLiveLookup]; empty on the timetable only. */
        live: Map<String, JourneyLegLive> = emptyMap(),
        /**
         * The requested day, for "Ultimo di oggi" and "primo domani": pass it only when the search is for today. Null
         * marks nothing.
         */
        serviceDate: LocalDate? = null,
        /** Travel preferences: the preferred kinds and "Preferisci" break the ties of the sorted chips. */
        preferences: JourneyPreferences? = null
    ): JourneyResultsPresentation {
        val unmarked = result.options.map { row(it, zone, live) }
        val bounds = serviceDate?.let { dayBounds(unmarked, it) }
        val all = unmarked.map { if (it.id == bounds?.lastOfDayId) it.copy(lastOfDay = true) else it }
        val rows = apply(all, filter, preferences)
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
            showIncompleteNotice = result.incompleteCoverage,
            firstDepartureNextDay = bounds?.firstNextDay?.departureTime,
            accessibilityDataUnavailable = result.accessibilityDataUnavailable
        )
    }

    /** The last option of [serviceDate] and, when none is left, the first of the next day. */
    internal class DayBounds(val lastOfDayId: String?, val firstNextDay: JourneyOptionRow?)

    /**
     * "Ultimo di oggi" from the static timetable: the offline planner scans the window in order of departure and, when
     * nothing more leaves in it, adds the first option of the next service day. An option of the next day in the list
     * therefore proves that no later option of [serviceDate] exists: the latest one of that day is its last. Without
     * such an option nothing is marked; live sources are never used for it.
     */
    internal fun dayBounds(rows: List<JourneyOptionRow>, serviceDate: LocalDate): DayBounds {
        val offline = rows.filter { it.sourceLabel == JourneySourceLabel.OFFLINE_TIMETABLE }
        fun dayOf(row: JourneyOptionRow): LocalDate = row.serviceDate ?: row.departure.toLocalDate()
        val later = offline.filter { dayOf(it) > serviceDate }
        if (later.isEmpty()) return DayBounds(null, null)
        val today = offline.filter { dayOf(it) <= serviceDate }
        return DayBounds(
            lastOfDayId = today.maxByOrNull { it.departure.toInstant() }?.id,
            firstNextDay = if (today.isEmpty()) later.minBy { it.departure.toInstant() } else null
        )
    }

    /**
     * The order of a chip. "Tutti" and "Diretti" stay in order of departure; the sorted chips keep their own key first,
     * then the options with a preferred kind of service, then the "Preferisci" key, then the departure.
     */
    fun apply(rows: List<JourneyOptionRow>, filter: JourneyResultsFilter, preferences: JourneyPreferences? = null): List<JourneyOptionRow> {
        val byDeparture = compareBy<JourneyOptionRow> { it.departure.toInstant() }.thenBy { it.arrival.toInstant() }
        val ties = tieBreak(preferences).then(byDeparture)
        return when (filter) {
            JourneyResultsFilter.ALL -> rows.sortedWith(byDeparture)
            JourneyResultsFilter.DIRECT -> rows.filter { it.changes == 0 }.sortedWith(byDeparture)
            JourneyResultsFilter.FEWEST_CHANGES -> rows.sortedWith(compareBy<JourneyOptionRow> { it.changes }.then(ties))
            JourneyResultsFilter.FASTEST -> rows.sortedWith(compareBy<JourneyOptionRow> { it.durationMinutes }.then(ties))
            JourneyResultsFilter.LEAST_WALKING -> rows.sortedWith(compareBy<JourneyOptionRow> { it.walkMinutes }.then(ties))
        }
    }

    private fun tieBreak(preferences: JourneyPreferences?): Comparator<JourneyOptionRow> {
        val preferred = preferences?.preferredModes.orEmpty()
        val byPreferred = compareByDescending<JourneyOptionRow> { row -> row.modes.count { it in preferred } }
        return when (preferences?.ranking ?: JourneyRanking.FASTEST) {
            JourneyRanking.FASTEST -> byPreferred.thenBy { it.durationMinutes }
            JourneyRanking.FEWEST_CHANGES -> byPreferred.thenBy { it.changes }
            JourneyRanking.LEAST_WALKING -> byPreferred.thenBy { it.walkMinutes }
        }
    }

    fun row(sourced: SourcedJourneyOption, zone: ZoneId = RailTime.defaultZone, live: Map<String, JourneyLegLive> = emptyMap()): JourneyOptionRow {
        val option = sourced.option
        val legs = option.legs.map { leg(it, zone) }
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
        val vehicles = option.legs.filter { it.mode != LegMode.WALK }
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
            transferRisk = TransferRiskLevel.worst(transfers.map { it.risk }),
            walkMinutes = legs.filter { it.mode == LegMode.WALK }.sumOf { it.durationMinutes },
            wheelchairAccessible = option.legs.isNotEmpty() && option.legs.all { it.wheelchair?.accessible == true },
            serviceDate = vehicles.firstOrNull()?.serviceDate,
            modes = vehicles.mapNotNullTo(linkedSetOf()) { leg ->
                TravelMode.of(leg.mode, leg.serviceKind, leg.operatorName)
            } + vehicles.flatMap { leg -> leg.continuations.mapNotNull { TravelMode.of(leg.mode, it.serviceKind, it.operatorName) } }
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
        runId = leg.runId?.value,
        wheelchairTrip = leg.mode != LegMode.WALK && leg.wheelchair?.vehicle == true,
        wheelchairAccessible = leg.wheelchair?.accessible == true,
        continuations = continuations(leg)
    )

    /**
     * The "Resta a bordo" notes of a leg: one per change of label (line, number or operator) on the way. A run that
     * goes on under the very same label in another pack is still one train and needs no note.
     */
    private fun continuations(leg: JourneyLeg): List<JourneyContinuationRow> {
        var line = leg.lineLabel
        var number = leg.publicNumber
        var operator = leg.operatorName
        return leg.continuations.mapNotNull { next ->
            val same = sameLabel(line, next.lineLabel) && sameLabel(number, next.publicNumber) && sameLabel(operator, next.operatorName)
            line = next.lineLabel
            number = next.publicNumber
            operator = next.operatorName
            if (same) null
            else JourneyContinuationRow(
                stationLabel = next.stationLabel ?: next.station.value,
                lineLabel = next.lineLabel,
                number = next.publicNumber,
                operatorName = next.operatorName,
                trainCategory = trainCategory(leg.copy(lineLabel = next.lineLabel, operatorName = next.operatorName, serviceKind = next.serviceKind))
            )
        }
    }

    private fun sameLabel(first: String?, second: String?): Boolean =
        first.orEmpty().replace(" ", "").equals(second.orEmpty().replace(" ", ""), ignoreCase = true)

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
