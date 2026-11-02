package it.binario.app.ui.planner

import it.binario.app.data.TrainService
import it.binario.app.data.core.LegMode
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.journeys.JourneyResultsFilter
import it.binario.app.ui.common.JourneyTimes
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

    data class Train(
        val service: TrainService,
        override val departure: LocalDateTime?,
        override val arrival: LocalDateTime?,
        override val durationMinutes: Long?
    ) : ResultEntry {
        override val key: String get() = "train-${service.id}"
        override val changes: Int get() = service.transferCount
    }

    data class Journey(val row: JourneyOptionRow) : ResultEntry {
        override val key: String get() = "journey-${row.id}"
        override val departure: LocalDateTime get() = row.departure.toLocalDateTime()
        override val arrival: LocalDateTime get() = row.arrival.toLocalDateTime()
        override val durationMinutes: Long get() = row.durationMinutes
        override val changes: Int get() = row.changes
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
     * (same departure minute and same train number, or same departure and arrival when a number is missing) is
     * dropped, so the live or catalogue row with its train detail is the one shown.
     */
    fun entries(services: List<TrainService>, journeys: List<JourneyOptionRow>, serviceDate: LocalDate): List<ResultEntry> {
        val trains = services.map { train(it, serviceDate) }
        val extra = journeys.filterNot { row -> trains.any { sameTrain(it, row) } }.map { ResultEntry.Journey(it) }
        return apply(trains + extra, JourneyResultsFilter.ALL)
    }

    fun apply(entries: List<ResultEntry>, filter: JourneyResultsFilter): List<ResultEntry> {
        val byDeparture = compareBy<ResultEntry, Long?>(nullsLast()) { it.departure?.toEpochSecond(ZoneOffset.UTC) }
            .thenBy(nullsLast()) { it.arrival?.toEpochSecond(ZoneOffset.UTC) }
        return when (filter) {
            JourneyResultsFilter.ALL -> entries.sortedWith(byDeparture)
            JourneyResultsFilter.DIRECT -> entries.filter { it.changes == 0 }.sortedWith(byDeparture)
            JourneyResultsFilter.FEWEST_CHANGES -> entries.sortedWith(compareBy<ResultEntry> { it.changes }.then(byDeparture))
            JourneyResultsFilter.FASTEST -> entries.sortedWith(compareBy<ResultEntry, Long?>(nullsLast()) { it.durationMinutes }.then(byDeparture))
        }
    }

    /** Options each chip shows, announced with the chip. */
    fun counts(entries: List<ResultEntry>): Map<JourneyResultsFilter, Int> =
        JourneyResultsFilter.entries.associateWith { apply(entries, it).size }

    internal fun sameTrain(train: ResultEntry.Train, row: JourneyOptionRow): Boolean {
        if (row.changes != 0) return false
        val legs = row.legs.filter { it.mode != LegMode.WALK }
        if (legs.size != 1) return false
        if (train.departure == null || train.departure != row.departure.toLocalDateTime()) return false
        val trainNumber = normalizedNumber(train.service.number)
        val legNumber = normalizedNumber(legs.single().number)
        return if (trainNumber != null && legNumber != null) trainNumber == legNumber
        else train.arrival != null && train.arrival == row.arrival.toLocalDateTime()
    }

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
