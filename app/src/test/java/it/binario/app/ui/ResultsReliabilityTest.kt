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
import it.binario.app.data.reliability.ReliabilityFixtures.FR_9558
import it.binario.app.data.reliability.ReliabilityFixtures.REG_2041
import it.binario.app.data.reliability.ReliabilityFixtures.stop
import it.binario.app.data.reliability.ReliabilityFixtures.train
import it.binario.app.journeys.JourneyChangesLabel
import it.binario.app.journeys.JourneyLegRow
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.journeys.JourneyResultsFilter
import it.binario.app.journeys.JourneySourceLabel
import it.binario.app.ui.planner.EntryReliability
import it.binario.app.ui.planner.ResultsMerge
import it.binario.app.ui.planner.ResultsReliability
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsReliabilityTest {
    private val day = LocalDate.of(2026, 11, 21)
    private val zone = ZoneId.of("Europe/Rome")
    private val roma = Station("curated:S08409", "Roma Termini", "Roma", railwayCode = "S08409")
    private val milano = Station("curated:S01700", "Milano Centrale", "Milano", railwayCode = "S01700")
    private val torino = Station("curated:S00219", "Torino Porta Nuova", "Torino", railwayCode = "S00219")
    private val stations = NetworkReliabilityStations(listOf(roma, milano, torino))

    /** Roma → Torino Frecciarossa measured at Torino: [onTime] of 30 within 5 minutes, never cancelled. */
    private fun frecciarossa(number: String, onTime: Int, mean: Double) = train(
        number, "S08409", "FR", 30, 0,
        stop("S08409", 30, 0.5, listOf(25, 28, 30, 30, 30, 30, 30, 30)),
        stop("S00219", 30, mean, listOf(onTime - 6, onTime - 2, onTime, 30, 30, 30, 30, 30))
    )

    private val stats = ReliabilityFixtures.stats(
        FR_9558, REG_2041,
        frecciarossa("9560", 27, 2.0),
        frecciarossa("9562", 27, 3.0),
        frecciarossa("9566", 27, 3.0),
        // Cancelled on each of the 10 days observed: listed without stops.
        train("9568", "S08409", "FR", 10, 10)
    )

    private fun service(number: String, departure: String, arrival: String, transfers: Int = 0) = TrainService(
        "train:$number", number, RailOperator.TRENITALIA_AV, TrainCategory.FRECCE, "Roma Termini", "Torino Porta Nuova",
        departure, departure, null, 0, ServiceStatus.ON_TIME,
        listOf(TrainStop("Roma Termini", departure, departure, null, 0, false), TrainStop("Torino Porta Nuova", arrival, arrival, null, 0, false)),
        transferCount = transfers, operationalStatusVerified = false,
        scheduledDepartureDateTime = day.atTime(LocalTime.parse(departure))
    )

    private fun at(time: String): ZonedDateTime = day.atTime(LocalTime.parse(time)).atZone(zone)

    private fun leg(number: String, from: Station, to: Station, departure: String, arrival: String) = JourneyLegRow(
        LegMode.TRAIN, "REG", number, "Trenitalia", TrainCategory.REGIONALE, ServiceKind.REGIONAL, from.name, to.name, from.id, to.id,
        at(departure), at(arrival), departure, arrival, Duration.between(at(departure), at(arrival)).toMinutes(), "run-$number"
    )

    private fun journey(id: String, vararg legs: JourneyLegRow): JourneyOptionRow {
        val minutes = Duration.between(legs.first().departure, legs.last().arrival).toMinutes()
        val changes = legs.size - 1
        return JourneyOptionRow(
            id, legs.first().departure, legs.last().arrival, legs.first().departureTime, legs.last().arrivalTime, 0, minutes,
            minutes / 60, minutes % 60, changes, JourneyChangesLabel.of(changes), legs.toList(), emptyList(),
            "embedded-gtfs-journeys", JourneySourceLabel.OFFLINE_TIMETABLE, "Offline", true
        )
    }

    private val withChange = journey("j1", leg("9558", roma, milano, "07:00", "10:00"), leg("2041", milano, torino, "10:17", "11:20"))

    private fun entries() = ResultsMerge.entries(
        listOf(service("9558", "17:40", "22:05"), service("9560", "18:40", "23:05"), service("9562", "16:40", "21:05"),
            service("9566", "19:40", "00:05"), service("9564", "15:40", "20:05"), service("9568", "20:40", "01:05")),
        listOf(withChange),
        day
    )

    @Test fun `a direct train is measured at the destination of the search, boarded at its origin`() {
        val reliability = ResultsReliability.assess(entries(), stats, roma, torino, stations)
        val fr9558 = (reliability.getValue("train-train:9558") as EntryReliability.Train).stop
        assertEquals("S00219", fr9558.stationCode)
        assertEquals(20, fr9558.onTime)
        assertEquals(30, fr9558.samples)
        // Not in the statistics: nothing to show.
        assertNull(reliability["train-train:9564"])
        // Cancelled on every day observed, leaving from the origin of the search: its cancellations, never on time.
        val cancelled = (reliability.getValue("train-train:9568") as EntryReliability.Train).stop
        assertTrue(cancelled.cancelledEveryDay)
        assertEquals(10, cancelled.daysCancelled)
        // A search that ends at Milano measures the 9558 there.
        assertEquals(22, ResultsReliability.train(service("9558", "17:40", "20:40"), stats, "S08409", "S01700")?.onTime)
        // A result that holds changes, a coach, or a destination without a code: none.
        assertNull(ResultsReliability.train(service("9558", "17:40", "22:05", transfers = 1), stats, "S08409", "S00219"))
        assertNull(ResultsReliability.train(service("9558", "17:40", "22:05").copy(scheduledBus = true), stats, "S08409", "S00219"))
        assertNull(ResultsReliability.train(service("9558", "17:40", "22:05"), stats, "S08409", null))
        val change = (reliability.getValue("journey-j1") as EntryReliability.Journey).journey
        assertEquals(2, change.legs.size)
        assertEquals(1, change.connections.size)
    }

    @Test fun `«Più affidabili» orders by the chance of arriving on time, then mean delay, then departure, unmeasured last`() {
        val entries = entries()
        val reliability = ResultsReliability.assess(entries, stats, roma, torino, stations)
        // 9560 and 9562/9566: 27 of 30 (0.9); 9558: 20 of 30 and 1 cancellation in 31 days (0.645); the journey: its two
        // legs and the change at Milano (0.71 × 0.97 × 0.79 ≈ 0.54); 9568 is always cancelled (0); 9564 has no statistics.
        assertEquals(
            listOf("train-train:9560", "train-train:9562", "train-train:9566", "train-train:9558", "journey-j1", "train-train:9568", "train-train:9564"),
            ResultsMerge.apply(entries, JourneyResultsFilter.MOST_RELIABLE, reliability = reliability).map { it.key }
        )
        assertTrue(reliability.getValue("train-train:9558").score > reliability.getValue("journey-j1").score)
        // Without statistics the chip keeps the order of departure.
        assertEquals(
            ResultsMerge.apply(entries, JourneyResultsFilter.ALL).map { it.key },
            ResultsMerge.apply(entries, JourneyResultsFilter.MOST_RELIABLE).map { it.key }
        )
    }

    @Test fun `the other chips ignore the statistics`() {
        val entries = entries()
        val reliability = ResultsReliability.assess(entries, stats, roma, torino, stations)
        for (filter in JourneyResultsFilter.entries - JourneyResultsFilter.MOST_RELIABLE) {
            assertEquals(filter.name, ResultsMerge.apply(entries, filter).map { it.key }, ResultsMerge.apply(entries, filter, reliability = reliability).map { it.key })
        }
        assertEquals(
            listOf("journey-j1", "train-train:9564", "train-train:9562", "train-train:9558", "train-train:9560", "train-train:9566", "train-train:9568"),
            ResultsMerge.apply(entries, JourneyResultsFilter.ALL, reliability = reliability).map { it.key }
        )
        assertEquals(7, ResultsMerge.counts(entries).getValue(JourneyResultsFilter.MOST_RELIABLE))
    }
}
