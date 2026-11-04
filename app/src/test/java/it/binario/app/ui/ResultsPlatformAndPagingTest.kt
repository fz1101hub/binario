package it.binario.app.ui

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.reliability.NetworkReliabilityStations
import it.binario.app.data.reliability.ReliabilityFixtures
import it.binario.app.data.reliability.ReliabilityFixtures.stop
import it.binario.app.data.reliability.ReliabilityFixtures.stop9
import it.binario.app.data.reliability.ReliabilityFixtures.train
import it.binario.app.data.reliability.UsualPlatform
import it.binario.app.journeys.JourneyChangesLabel
import it.binario.app.journeys.JourneyLegRow
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.journeys.JourneyResultsFilter
import it.binario.app.journeys.JourneySourceLabel
import it.binario.app.journeys.ResultsPaging
import it.binario.app.ui.planner.ResultsMerge
import it.binario.app.ui.planner.ResultsReliability
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The usual platform on the result cards, «Più affidabili» over the pages, «Nessun treno fino alle …». */
class ResultsPlatformAndPagingTest {
    private val day = LocalDate.of(2026, 11, 20)
    private val zone = ZoneId.of("Europe/Rome")
    private val roma = Station("curated:S08409", "Roma Termini", "Roma", railwayCode = "S08409")
    private val milano = Station("curated:S01700", "Milano Centrale", "Milano", railwayCode = "S01700")
    private val torino = Station("curated:S00219", "Torino Porta Nuova", "Torino", railwayCode = "S00219")
    private val stations = NetworkReliabilityStations(listOf(roma, milano, torino))

    private val stats = ReliabilityFixtures.stats(
        train(
            "9558", "S08409", "FR", 30, 0,
            stop9("S08409", 30, 0.5, List(8) { 30 }, platform = "[\"12\",24,30]", weekdays = "null"),
            stop9("S00219", 30, 6.0, listOf(8, 12, 20, 25, 27, 28, 29, 30), platform = "null", weekdays = "null")
        ),
        // At Roma only 4 days on platform 3: not enough.
        train(
            "9560", "S08409", "FR", 30, 0,
            stop9("S08409", 30, 0.5, List(8) { 30 }, platform = "[\"3\",4,5]", weekdays = "null"),
            stop("S00219", 30, 2.0, listOf(21, 25, 27, 30, 30, 30, 30, 30))
        ),
        // Milano → Torino regional with its usual platform at Milano.
        train(
            "2041", "S01700", "REG", 30, 0,
            stop9("S01700", 30, 0.4, List(8) { 30 }, platform = "[\"7\",18,25]", weekdays = "null"),
            stop("S00219", 30, 3.0, listOf(15, 19, 24, 27, 29, 30, 30, 30))
        ),
        // Faster but less punctual: 9 of 30 on time.
        train(
            "9570", "S08409", "FR", 30, 0,
            stop("S08409", 30, 0.5, List(8) { 30 }),
            stop("S00219", 30, 9.0, listOf(3, 6, 9, 20, 25, 28, 29, 30))
        )
    )

    private fun service(number: String, departure: String, arrival: String, platform: String? = null, date: LocalDate = day) = TrainService(
        "train:$number:$date", number, RailOperator.TRENITALIA_AV, TrainCategory.FRECCE, roma.name, torino.name, departure, departure, platform, 0,
        ServiceStatus.ON_TIME,
        listOf(TrainStop(roma.name, departure, departure, platform, 0, false), TrainStop(torino.name, arrival, arrival, null, 0, false)),
        operationalStatusVerified = false, scheduledDepartureDateTime = date.atTime(LocalTime.parse(departure))
    )

    private fun at(time: String, date: LocalDate = day): ZonedDateTime = date.atTime(LocalTime.parse(time)).atZone(zone)

    private fun leg(number: String, from: Station, to: Station, departure: String, arrival: String) = JourneyLegRow(
        LegMode.TRAIN, "REG", number, "Trenitalia", TrainCategory.REGIONALE, ServiceKind.REGIONAL, from.name, to.name, from.id, to.id,
        at(departure), at(arrival), departure, arrival, Duration.between(at(departure), at(arrival)).toMinutes(), "run-$number"
    )

    private fun journey(id: String, vararg legs: JourneyLegRow, afterEmptyWindow: Boolean = false): JourneyOptionRow {
        val minutes = Duration.between(legs.first().departure, legs.last().arrival).toMinutes()
        val changes = legs.size - 1
        return JourneyOptionRow(
            id, legs.first().departure, legs.last().arrival, legs.first().departureTime, legs.last().arrivalTime, 0, minutes,
            minutes / 60, minutes % 60, changes, JourneyChangesLabel.of(changes), legs.toList(), emptyList(),
            "embedded-gtfs-journeys", JourneySourceLabel.OFFLINE_TIMETABLE, "Offline", true, firstAfterEmptyWindow = afterEmptyWindow
        )
    }

    @Test fun `a card shows the usual platform where the row is boarded until its own platform is known`() {
        val entries = ResultsMerge.entries(
            listOf(service("9558", "08:00", "12:00"), service("9560", "09:00", "13:00"), service("9558", "08:00", "12:00", platform = "14", date = day.plusDays(1))),
            listOf(journey("via-milano", leg("2041", milano, torino, "10:17", "11:20"))),
            day
        )
        val platforms = ResultsReliability.usualPlatforms(entries, stats, roma, torino, stations)
        assertEquals(UsualPlatform("12", 24, 30), platforms["train-train:9558:$day"])
        // Four days are not enough; a platform of the run itself always wins.
        assertNull(platforms["train-train:9560:$day"])
        assertNull(platforms["train-train:9558:${day.plusDays(1)}"])
        // A journey: where its first train is boarded.
        assertEquals(UsualPlatform("7", 18, 25), platforms["journey-via-milano"])
        // A coach, or an origin without a code: none.
        val coach = ResultsMerge.entries(listOf(service("9558", "08:00", "12:00").copy(scheduledBus = true)), emptyList(), day)
        assertEquals(emptyMap<String, UsualPlatform>(), ResultsReliability.usualPlatforms(coach, stats, roma, torino, stations))
        assertEquals(emptyMap<String, UsualPlatform>(), ResultsReliability.usualPlatforms(entries.take(1), stats, roma.copy(railwayCode = null), torino, stations))
    }

    @Test fun `«Più affidabili» orders the trains of the pages with the others`() {
        val first = listOf(service("9558", "08:00", "12:00"))
        // «Più tardi» brought the 9560 and, again, the 9558 that is already shown.
        val page = ResultsPaging.newServices(first, listOf(service("9558", "08:00", "12:00"), service("9570", "09:30", "12:40"), service("9560", "10:00", "14:00")), day)
        val entries = ResultsMerge.entries(first + page, emptyList(), day)
        assertEquals(listOf("train-train:9558:$day", "train-train:9570:$day", "train-train:9560:$day"), entries.map { it.key })
        val reliability = ResultsReliability.assess(entries, stats, roma, torino, stations)
        assertEquals(
            listOf("train-train:9560:$day", "train-train:9558:$day", "train-train:9570:$day"),
            ResultsMerge.apply(entries, JourneyResultsFilter.MOST_RELIABLE, reliability = reliability).map { it.key }
        )
        assertEquals(listOf("train-train:9570:$day", "train-train:9558:$day", "train-train:9560:$day"), ResultsMerge.apply(entries, JourneyResultsFilter.FASTEST).map { it.key })
    }

    @Test fun `«Nessun treno fino alle» follows an empty searched window, the same day, with nothing before it`() {
        val requested = day.atTime(7, 30)
        val later = journey("j-19", leg("2041", milano, torino, "19:00", "20:00"), afterEmptyWindow = true)
        val entries = ResultsMerge.entries(emptyList(), listOf(later), day)
        assertEquals(day.atTime(19, 0), ResultsMerge.noTrainsUntil(entries, listOf(later), requested, firstNextDay = null))
        // "Primo domani" already says it; a window with options is not empty.
        assertNull(ResultsMerge.noTrainsUntil(entries, listOf(later), requested, firstNextDay = day.plusDays(1).atTime(6, 0)))
        val ordinary = later.copy(firstAfterEmptyWindow = false)
        assertNull(ResultsMerge.noTrainsUntil(ResultsMerge.entries(emptyList(), listOf(ordinary), day), listOf(ordinary), requested, null))
        // A direct train of another source leaves before it: the window was not empty after all.
        val direct = ResultsMerge.entries(listOf(service("9558", "12:00", "16:00")), listOf(later), day)
        assertNull(ResultsMerge.noTrainsUntil(direct, listOf(later), requested, null))
        // A train of «Più presto» before the search does not count.
        val earlier = ResultsMerge.entries(listOf(service("9558", "06:30", "10:30")), listOf(later), day)
        assertEquals(day.atTime(19, 0), ResultsMerge.noTrainsUntil(earlier, listOf(later), requested, null))
        // The next train only tomorrow: not this notice.
        val tomorrow = journey("j-6", leg("2041", milano, torino, "06:00", "07:00"), afterEmptyWindow = true).let {
            it.copy(departure = at("06:00", day.plusDays(1)), arrival = at("07:00", day.plusDays(1)))
        }
        assertNull(ResultsMerge.noTrainsUntil(ResultsMerge.entries(emptyList(), listOf(tomorrow), day), listOf(tomorrow), requested, null))
    }
}
