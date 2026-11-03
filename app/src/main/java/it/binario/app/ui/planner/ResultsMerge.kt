package it.binario.app.ui.planner

import it.binario.app.TrainViewModel
import it.binario.app.data.Station
import it.binario.app.data.TrainService
import it.binario.app.data.core.LegMode
import it.binario.app.data.repository.JourneySearchQuery
import it.binario.app.data.repository.JourneySearchResult
import it.binario.app.journeys.JourneyLegLive
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.journeys.JourneyResultsPresentation
import it.binario.app.journeys.JourneyResultsPresenter
import it.binario.app.journeys.JourneyResultsFilter
import it.binario.app.journeys.JourneySourceLabel
import it.binario.app.data.provider.JourneyPreferences
import it.binario.app.data.provider.JourneyRanking
import it.binario.app.data.provider.TravelMode
import it.binario.app.ui.common.JourneyTimes
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * One row of the route results: a direct-train result of the existing route search ([Train], live or catalogue,
 * opens the train detail) or an option of the separate journeys-with-changes search ([Journey], opens the journey
 * detail). Both keep their own source; this only orders them in one list.
 */
internal sealed interface ResultEntry {
    val key: String
    /** Railway-local departure; null when the source published no readable time (listed last). */
    val departure: LocalDateTime?
    val arrival: LocalDateTime?
    val durationMinutes: Long?
    val changes: Int
    /** Minutes on foot ("Meno a piedi"); a direct train has none. */
    val walkMinutes: Long
    /** Kinds of service used, for the preferred kinds of the travel preferences. */
    val modes: Set<TravelMode>
    /** "Ultimo di oggi": the last departure of the static timetable's day for this origin and destination. */
    val lastOfDay: Boolean

    data class Train(
        val service: TrainService,
        override val departure: LocalDateTime?,
        override val arrival: LocalDateTime?,
        override val durationMinutes: Long?,
        override val lastOfDay: Boolean = false
    ) : ResultEntry {
        override val key: String get() = "train-${service.id}"
        override val changes: Int get() = service.transferCount
        override val walkMinutes: Long get() = 0
        override val modes: Set<TravelMode> get() = setOfNotNull(TravelMode.ofTrain(service.trainCategory, service.scheduledBus))
    }

    data class Journey(val row: JourneyOptionRow) : ResultEntry {
        override val key: String get() = "journey-${row.id}"
        override val departure: LocalDateTime get() = row.departure.toLocalDateTime()
        override val arrival: LocalDateTime get() = row.arrival.toLocalDateTime()
        override val durationMinutes: Long get() = row.durationMinutes
        override val changes: Int get() = row.changes
        override val walkMinutes: Long get() = row.walkMinutes
        override val modes: Set<TravelMode> get() = row.modes
        override val lastOfDay: Boolean get() = row.lastOfDay
    }
}

/**
 * Merges the direct-train results with the journeys with changes and applies the four chips of the render with the
 * rules of [it.binario.app.journeys.JourneyResultsPresenter]: "Tutti" by departure, "Diretti" only options without
 * changes, "Meno cambi" fewest changes first, "Più veloce" shortest scheduled duration first (then by departure).
 */
internal object ResultsMerge {
    /**
     * Direct trains first in identity: a journey without changes that is the same train as a direct-train result
     * (its train leaves at the same minute with the same number, or leaves and arrives at the same minutes when a
     * number is missing; a short walk before or after it does not make it another option) is dropped, so the live or
     * catalogue row with its train detail is the one shown.
     */
    fun entries(
        services: List<TrainService>,
        journeys: List<JourneyOptionRow>,
        serviceDate: LocalDate,
        /**
         * Travel preferences: an avoided kind hides its direct trains (a train of unknown kind always stays), and "Solo
         * percorsi accessibili" hides every direct train, which carries no wheelchair data.
         */
        preferences: JourneyPreferences? = null
    ): List<ResultEntry> {
        val trains = shownServices(services, preferences).map { train(it, serviceDate) }
        val (duplicates, extra) = journeys.partition { row -> trains.any { sameTrain(it, row) } }
        // A last-of-day journey shown as its direct train passes the mark on to it.
        val marked = trains.map { train ->
            if (duplicates.any { it.lastOfDay && sameTrain(train, it) }) train.copy(lastOfDay = true) else train
        }
        val merged = marked + extra.map { ResultEntry.Journey(it) }
        return apply(confirmLastOfDay(merged, serviceDate), JourneyResultsFilter.ALL)
    }

    /**
     * The journeys of the merged list: every option (the chips filter the merged list), with "Ultimo di oggi" and
     * "primo domani" only for a search of today ([markedDay]), and the live data of the changes ([live]) once it
     * answers, so that the cards say "Coincidenza a rischio" as the detail does.
     */
    fun journeys(result: JourneySearchResult, markedDay: LocalDate?, live: Map<String, JourneyLegLive>): JourneyResultsPresentation =
        JourneyResultsPresenter.present(result, serviceDate = markedDay, live = live)

    /** The direct trains the travel preferences leave in the results. */
    fun shownServices(services: List<TrainService>, preferences: JourneyPreferences?): List<TrainService> {
        if (preferences == null) return services
        if (preferences.wheelchairOnly) return emptyList()
        if (preferences.avoidedModes.isEmpty()) return services
        return services.filter { service -> TravelMode.ofTrain(service.trainCategory, service.scheduledBus)?.let { it in preferences.avoidedModes } != true }
    }

    /**
     * The mark stays only on the latest entry of the day: a direct train of another source leaving later the same day
     * (not in the offline timetable) takes it away, because the static timetable cannot confirm that train.
     */
    private fun confirmLastOfDay(entries: List<ResultEntry>, serviceDate: LocalDate): List<ResultEntry> {
        val marked = entries.filter { it.lastOfDay }
        if (marked.isEmpty()) return entries
        val keep = marked.singleOrNull()?.takeIf { last ->
            val departure = last.departure ?: return@takeIf false
            entries.none { it !== last && dayOf(it, serviceDate) <= serviceDate && it.departure?.isAfter(departure) == true }
        }
        return entries.map { entry ->
            when {
                !entry.lastOfDay || entry === keep -> entry
                entry is ResultEntry.Train -> entry.copy(lastOfDay = false)
                entry is ResultEntry.Journey -> ResultEntry.Journey(entry.row.copy(lastOfDay = false))
                else -> entry
            }
        }
    }

    /**
     * "Non ci sono più treni oggi: primo domani alle …": nothing of [serviceDate] is left in the results while the
     * offline timetable lists the next day; the earliest entry shown, or null.
     */
    fun firstDepartureNextDay(entries: List<ResultEntry>, journeys: List<JourneyOptionRow>, serviceDate: LocalDate): LocalDateTime? {
        val offline = journeys.filter { it.sourceLabel == JourneySourceLabel.OFFLINE_TIMETABLE }
        if (offline.none { (it.serviceDate ?: it.departure.toLocalDate()) > serviceDate }) return null
        if (entries.any { dayOf(it, serviceDate) <= serviceDate }) return null
        return entries.mapNotNull { it.departure }.minOrNull()
    }

    /** The service day of an entry: the GTFS day of a journey or of a timetable train, else its departure date. */
    private fun dayOf(entry: ResultEntry, serviceDate: LocalDate): LocalDate = when (entry) {
        is ResultEntry.Journey -> entry.row.serviceDate ?: entry.row.departure.toLocalDate()
        // A train without a readable time is listed last and never counts as one of the day.
        is ResultEntry.Train -> entry.service.gtfsIdentity?.serviceDate ?: entry.departure?.toLocalDate() ?: serviceDate.plusDays(1)
    }

    fun apply(entries: List<ResultEntry>, filter: JourneyResultsFilter, preferences: JourneyPreferences? = null): List<ResultEntry> {
        val byDeparture = compareBy<ResultEntry, Long?>(nullsLast()) { it.departure?.toEpochSecond(ZoneOffset.UTC) }
            .thenBy(nullsLast()) { it.arrival?.toEpochSecond(ZoneOffset.UTC) }
        val preferred = preferences?.preferredModes.orEmpty()
        val byPreferred = compareByDescending<ResultEntry> { entry -> entry.modes.count { it in preferred } }
        val ranking = when (preferences?.ranking ?: JourneyRanking.FASTEST) {
            JourneyRanking.FASTEST -> byPreferred.thenBy(nullsLast()) { it.durationMinutes }
            JourneyRanking.FEWEST_CHANGES -> byPreferred.thenBy { it.changes }
            JourneyRanking.LEAST_WALKING -> byPreferred.thenBy { it.walkMinutes }
        }
        val ties = ranking.then(byDeparture)
        return when (filter) {
            JourneyResultsFilter.ALL -> entries.sortedWith(byDeparture)
            JourneyResultsFilter.DIRECT -> entries.filter { it.changes == 0 }.sortedWith(byDeparture)
            JourneyResultsFilter.FEWEST_CHANGES -> entries.sortedWith(compareBy<ResultEntry> { it.changes }.then(ties))
            JourneyResultsFilter.FASTEST -> entries.sortedWith(compareBy<ResultEntry, Long?>(nullsLast()) { it.durationMinutes }.then(ties))
            JourneyResultsFilter.LEAST_WALKING -> entries.sortedWith(compareBy<ResultEntry> { it.walkMinutes }.then(ties))
        }
    }

    /** Options each chip shows, announced with the chip. */
    fun counts(entries: List<ResultEntry>): Map<JourneyResultsFilter, Int> =
        JourneyResultsFilter.entries.associateWith { apply(entries, it).size }

    internal fun sameTrain(train: ResultEntry.Train, row: JourneyOptionRow): Boolean {
        if (row.changes != 0) return false
        val legs = row.legs.filter { it.mode != LegMode.WALK }
        if (legs.size != 1) return false
        val vehicle = legs.single()
        // The train's own times: an option that walks to it first leaves a few minutes earlier than the train.
        if (train.departure == null || train.departure != vehicle.departure.toLocalDateTime()) return false
        if (row.walkMinutes > MAX_DUPLICATE_WALK_MINUTES) return false
        val trainNumber = normalizedNumber(train.service.number)
        val legNumber = normalizedNumber(vehicle.number)
        return if (trainNumber != null && legNumber != null) trainNumber == legNumber
        else train.arrival != null && train.arrival == vehicle.arrival.toLocalDateTime()
    }

    /** Longer walks around the same train make an option of its own (another station, the metro). */
    private const val MAX_DUPLICATE_WALK_MINUTES = 10L

    private fun train(service: TrainService, serviceDate: LocalDate): ResultEntry.Train {
        val departure = service.scheduledDepartureDateTime
            ?: parse(service.scheduledDeparture)?.let { LocalDateTime.of(serviceDate, it) }
        val duration = JourneyTimes.durationMinutes(service)?.toLong()
        return ResultEntry.Train(service, departure, departure?.let { start -> duration?.let { start.plusMinutes(it) } }, duration)
    }

    private fun normalizedNumber(value: String?): String? =
        value?.filter(Char::isDigit)?.trimStart('0')?.takeIf { it.isNotEmpty() }

    private fun parse(value: String): LocalTime? = runCatching { LocalTime.parse(value.trim()) }.getOrNull()
}

/**
 * What the journeys-with-changes search must answer for the route results on screen: the stations of the primary
 * direction and the planned date and time, or the railway time of now when the results follow the current time.
 */
internal data class JourneyRequest(
    val origin: Station,
    val destination: Station,
    val departure: LocalDateTime,
    val followsCurrentTime: Boolean
) {
    /** Changes when another search is asked for; not every minute while the results follow the current time. */
    val key: String
        get() = listOf(origin.id, destination.id, if (followsCurrentTime) CURRENT_TIME_KEY else departure.toString()).joinToString("|")

    /**
     * [searched] (the journeys state) belongs to this request: same stations and, for a planned search, the same
     * minute. A search from the current time keeps answering while the clock moves on (up to [CURRENT_TIME_SHOWN]),
     * so its options do not vanish a minute later.
     */
    fun isAnsweredBy(searched: JourneySearchQuery?): Boolean {
        if (searched == null || searched.origin.id != origin.id || searched.destination.id != destination.id) return false
        if (!followsCurrentTime) return searched.departure == departure
        return !searched.departure.isAfter(departure) && Duration.between(searched.departure, departure) <= CURRENT_TIME_SHOWN
    }

    /** A new search is due: nothing answers this request, or a current-time search is older than [CURRENT_TIME_REUSE]. */
    fun needsSearch(searched: JourneySearchQuery?): Boolean {
        if (searched == null || !isAnsweredBy(searched)) return true
        return followsCurrentTime && Duration.between(searched.departure, departure) > CURRENT_TIME_REUSE
    }

    companion object {
        private const val CURRENT_TIME_KEY = "now"
        /** Coming back to the results within this time reuses a current-time search instead of computing it again. */
        val CURRENT_TIME_REUSE: Duration = Duration.ofMinutes(15)
        val CURRENT_TIME_SHOWN: Duration = Duration.ofHours(12)

        /** The request for the results of [viewModel], or null without an active route. */
        fun of(viewModel: TrainViewModel): JourneyRequest? {
            if (!viewModel.hasActiveRoute) return null
            val route = viewModel.activeRoute
            val query = viewModel.journeyQuery
            return JourneyRequest(
                origin = route.origin(viewModel.direction),
                destination = route.destination(viewModel.direction),
                departure = LocalDateTime.of(query.serviceDate, query.departureTime.withSecond(0).withNano(0)),
                followsCurrentTime = viewModel.journeyFollowsCurrentTime
            )
        }
    }
}
