package it.binario.app.data.reliability

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.reliability.ReliabilityFixtures.FR_9558
import it.binario.app.data.reliability.ReliabilityFixtures.REG_2041
import it.binario.app.data.reliability.ReliabilityFixtures.REG_2153_LOMBARDIA
import it.binario.app.data.reliability.ReliabilityFixtures.REG_2153_SICILIA
import it.binario.app.data.reliability.ReliabilityFixtures.stats
import it.binario.app.data.reliability.ReliabilityFixtures.stop
import it.binario.app.data.reliability.ReliabilityFixtures.train
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkReliabilityTest {
    private fun station(code: String?, name: String) = Station("curated:${code ?: name}", name, name, railwayCode = code)

    @Test fun `high-speed and international categories are on time within 5 minutes, every other within 15`() {
        for (category in listOf("FR", "FA", "FB", "EC", "ES", "ESC", "EN", "Italo", "ITALO", "fr", " EC ")) {
            assertEquals(category, 5, NetworkReliability.onTimeLimitMinutes(category))
        }
        for (category in listOf("REG", "RV", "IC", "ICN", "RE", "S", "")) {
            assertEquals(category, 15, NetworkReliability.onTimeLimitMinutes(category))
        }
    }

    @Test fun `on time counts the samples within the limit of the category, exactly at a bucket`() {
        val stats = stats(FR_9558, train("9558", "S99999", "REG", 31, 1, stop("S00219", 30, 6.2, listOf(8, 12, 20, 25, 27, 28, 29, 30))))
        val frecciarossa = stats.trainsNumbered("9558").single { it.originCode == "S08409" }
        val regional = stats.trainsNumbered("9558").single { it.originCode == "S99999" }
        // The same delays: 20 of 30 within 5 minutes, 27 of 30 within 15.
        assertEquals(20, NetworkReliability.at(frecciarossa, 2)?.onTime)
        assertEquals(5, NetworkReliability.at(frecciarossa, 2)?.onTimeLimitMinutes)
        assertEquals(27, NetworkReliability.at(regional, 0)?.onTime)
        assertEquals(15, NetworkReliability.at(regional, 0)?.onTimeLimitMinutes)
    }

    @Test fun `the stop measured needs at least five samples`() {
        val stats = stats(train("100", "S00001", "REG", 6, 0,
            stop("S00001", 5, 0.0, listOf(5, 5, 5, 5, 5, 5, 5, 5)),
            stop("S00002", 4, 1.0, listOf(4, 4, 4, 4, 4, 4, 4, 4))))
        val train = stats.trainsNumbered("100").single()
        assertEquals(5, NetworkReliability.at(train, 0)?.samples)
        assertNull(NetworkReliability.at(train, 1))
        assertNull(NetworkReliability.connection(train, 1, 10.0))
        assertNull(NetworkReliability.at(train, 7))
    }

    @Test fun `delays between two bucket limits are interpolated linearly`() {
        val train = stats(FR_9558).trainsNumbered("9558").single()
        // Milano Centrale: 9, 14, 22, 26, 28, 29, 30, 30 at 0, 2, 5, 10, 15, 20, 30, 60 minutes.
        assertEquals(9.0, train.countAtMost(1, 0.0), 1e-9)
        assertEquals(22.0, train.countAtMost(1, 5.0), 1e-9)
        assertEquals(23.6, train.countAtMost(1, 7.0), 1e-9)
        assertEquals(11.5, train.countAtMost(1, 1.0), 1e-9)
        assertEquals(30.0, train.countAtMost(1, 60.0), 1e-9)
        assertEquals(30.0, train.countAtMost(1, 240.0), 1e-9)
        // Below the first limit the counts say nothing: a negative slack counts as missed.
        assertEquals(0.0, train.countAtMost(1, -1.0), 1e-9)
        val odds = requireNotNull(NetworkReliability.connection(train, 1, 7.0))
        assertEquals(30, odds.samples)
        assertEquals(23, odds.successes)
        assertEquals(23.6 / 30, odds.probability, 1e-9)
    }

    @Test fun `mean delay, cancellations and the score of a stop`() {
        val stop = requireNotNull(NetworkReliability.at(stats(FR_9558).trainsNumbered("9558").single(), 2))
        assertEquals(6.2, stop.meanDelayMinutes, 1e-9)
        assertEquals(6, stop.shownMeanDelayMinutes)
        assertEquals(1.0 / 31, stop.cancelledShare, 1e-9)
        assertEquals(20.0 / 30, stop.onTimeShare, 1e-9)
        assertEquals((30.0 / 31) * (20.0 / 30), stop.score, 1e-9)
        // An early average shows as no delay.
        val early = stats(train("7", "S00001", "REG", 10, 0, stop("S00001", 10, -1.4, listOf(10, 10, 10, 10, 10, 10, 10, 10))))
        assertEquals(0, NetworkReliability.at(early.trainsNumbered("7").single(), 0)?.shownMeanDelayMinutes)
    }

    @Test fun `the same number with another origin is another train`() {
        val stats = stats(REG_2153_LOMBARDIA, REG_2153_SICILIA)
        assertEquals(2, stats.trainsNumbered("2153").size)
        val lombardia = requireNotNull(NetworkReliability.match(stats, "2153", "S01860", boardingCode = "S01700"))
        assertEquals("S01700", lombardia.train.originCode)
        assertEquals(0, lombardia.boardingIndex)
        assertEquals(2, lombardia.alightingIndex)
        assertEquals("S12001", NetworkReliability.match(stats, "2153", "S12002")?.train?.originCode)
        // A station neither train serves, a missing code or a number nobody runs match nothing.
        assertNull(NetworkReliability.match(stats, "2153", "S05043"))
        assertNull(NetworkReliability.match(stats, "2153", null))
        assertNull(NetworkReliability.match(stats, "2153", "Milano Centrale"))
        assertNull(NetworkReliability.match(stats, "9999", "S01860"))
        assertNull(NetworkReliability.match(stats, null, "S01860"))
        // Boarded after the stop: the ride goes the other way, so it is not this train.
        assertNull(NetworkReliability.match(stats, "2153", "S01700", boardingCode = "S01860"))
    }

    @Test fun `between trains serving the same stop the boarding stop, then the origin decide`() {
        val throughBologna = train("600", "S05043", "IC", 30, 0,
            stop("S05043", 30, 1.0, listOf(20, 24, 27, 29, 30, 30, 30, 30)),
            stop("S06421", 30, 3.0, listOf(10, 15, 22, 27, 29, 30, 30, 30)))
        val fromMilano = train("600", "S01700", "IC", 12, 0,
            stop("S01700", 12, 1.0, listOf(10, 11, 12, 12, 12, 12, 12, 12)),
            stop("S05043", 12, 2.0, listOf(6, 8, 10, 12, 12, 12, 12, 12)),
            stop("S06421", 12, 2.5, listOf(5, 7, 10, 11, 12, 12, 12, 12)))
        val stats = stats(throughBologna, fromMilano)
        // Boarded at Milano: only one of them stops there, even with fewer samples.
        assertEquals("S01700", NetworkReliability.match(stats, "600", "S06421", boardingCode = "S01700")?.train?.originCode)
        // Both stop at Bologna and Firenze: the origin given wins, else the one with more samples.
        assertEquals("S01700", NetworkReliability.match(stats, "600", "S06421", boardingCode = "S05043", originCode = "S01700")?.train?.originCode)
        assertEquals("S05043", NetworkReliability.match(stats, "600", "S06421", boardingCode = "S05043")?.train?.originCode)
        // Measured at the terminus from a stop it serves.
        val terminus = requireNotNull(NetworkReliability.matchTerminus(stats, "600", "S01700"))
        assertEquals(2, terminus.alightingIndex)
        assertNull(NetworkReliability.matchTerminus(stats, "600", "S06421"))
        assertNull(NetworkReliability.matchTerminus(stats, "600", null))
    }

    @Test fun `a train cancelled every day shows its cancellations from its origin, never an on-time count`() {
        val stats = stats(ReliabilityFixtures.REG_2155_CANCELLED, train("2159", "S01700", "REG", 4, 4))
        val cancelled = requireNotNull(NetworkReliability.cancelledEveryDay(stats, "2155", "S01700"))
        assertTrue(cancelled.cancelledEveryDay)
        assertEquals(12, cancelled.daysCancelled)
        assertEquals(12, cancelled.daysObserved)
        assertEquals(0.0, cancelled.score, 0.0)
        // Boarded elsewhere the stops are unknown; fewer than five days say nothing; it never matches a stop.
        assertNull(NetworkReliability.cancelledEveryDay(stats, "2155", "S01820"))
        assertNull(NetworkReliability.cancelledEveryDay(stats, "2159", "S01700"))
        assertNull(NetworkReliability.match(stats, "2155", "S01820", boardingCode = "S01700"))
    }

    @Test fun `the days covered never exceed the window and follow the days observed in the first weeks`() {
        assertEquals(30, stats(FR_9558).coveredDays)
        assertEquals(30, stats(FR_9558).windowDays)
        val firstWeeks = stats(REG_2153_LOMBARDIA.replace("\"d\":28", "\"d\":9"), ReliabilityFixtures.REG_2155_CANCELLED)
        assertEquals(12, firstWeeks.coveredDays)
        assertEquals(30, firstWeeks.windowDays)
    }

    @Test fun `the stop where the delay grows most skips stops with too few samples`() {
        val train = stats(FR_9558).trainsNumbered("9558").single()
        assertEquals(WorstStop("S01700", 4, 30), NetworkReliability.worstStop(train))
        val flat = stats(REG_2041.replace("3.0", "0.6")).trainsNumbered("2041").single()
        assertNull(NetworkReliability.worstStop(flat))
        val gap = stats(train("8", "S00001", "REG", 30, 0,
            stop("S00001", 30, 0.0, listOf(30, 30, 30, 30, 30, 30, 30, 30)),
            stop("S00002", 3, 20.0, listOf(0, 0, 0, 0, 3, 3, 3, 3)),
            stop("S00003", 30, 2.0, listOf(20, 25, 30, 30, 30, 30, 30, 30))))
        assertEquals(WorstStop("S00003", 2, 30), NetworkReliability.worstStop(gap.trainsNumbered("8").single()))
    }

    @Test fun `stations map to their ViaggiaTreno railway code, as the live adapters use it`() {
        val stations = NetworkReliabilityStations(listOf(station("S01700", "Milano Centrale"), station(null, "Abfaltersbach"), station("N00001", "Nowhere")))
        assertEquals("S01700", stations.code("curated:S01700"))
        assertEquals("Milano Centrale", stations.name("S01700"))
        assertNull(stations.code("curated:Abfaltersbach"))
        assertNull(stations.code("curated:N00001"))
        assertEquals("S08409", NetworkReliability.stationCode(station(" S08409 ", "Roma Termini")))
        assertTrue(NetworkReliability.isStationCode("S00219"))
        assertEquals("9558", NetworkReliability.numberKey("FR 9558"))
        assertEquals("ABC", NetworkReliability.numberKey("a bc"))
    }

    @Test fun `the train detail measures the last stop shown, or the terminus, and names the worst stop`() {
        val stats = stats(FR_9558)
        val catalogue = listOf(station("S08409", "Roma Termini"), station("S01700", "Milano Centrale"), station("S00219", "Torino Porta Nuova"))
        val byName = catalogue.associateBy { it.name }
        val stations = NetworkReliabilityStations(catalogue)
        fun service(vararg stops: String) = TrainService(
            "s", "9558", RailOperator.TRENITALIA_AV, TrainCategory.FRECCE, stops.first(), stops.last(), "07:00", "07:00", null, 0,
            ServiceStatus.ON_TIME, stops.map { TrainStop(it, "07:00", "07:00", null, 0, false) }
        )
        val toMilano = requireNotNull(NetworkReliabilityDetail.of(stats, service("Roma Termini", "Milano Centrale"), byName::get, stations))
        assertEquals("Milano Centrale", toMilano.arrivalName)
        assertEquals(22, toMilano.arrival.onTime)
        assertEquals(30, toMilano.windowDays)
        assertEquals(LocalDate.of(2026, 11, 9), toMilano.windowEnd)
        assertEquals(31, toMilano.daysObserved)
        assertEquals(1, toMilano.daysCancelled)
        assertEquals("Milano Centrale", toMilano.worstStopName)
        // A last stop the catalogue does not know: the terminus of the statistics, named by the catalogue.
        val unknownEnd = requireNotNull(NetworkReliabilityDetail.of(stats, service("Roma Termini", "Somewhere"), byName::get, stations))
        assertEquals("Torino Porta Nuova", unknownEnd.arrivalName)
        assertEquals(20, unknownEnd.arrival.onTime)
        // A train cancelled on every day observed: its cancellations only, over the days really covered.
        val cancelledStats = stats(ReliabilityFixtures.REG_2155_CANCELLED)
        val cancelled = requireNotNull(NetworkReliabilityDetail.of(
            cancelledStats, service("Milano Centrale", "Torino Porta Nuova").copy(number = "2155"), byName::get, stations
        ))
        assertTrue(cancelled.arrival.cancelledEveryDay)
        assertEquals(12, cancelled.daysCancelled)
        assertEquals(12, cancelled.windowDays)
        assertNull(cancelled.worstStop)
        // No code at either end, or a coach: no card.
        assertNull(NetworkReliabilityDetail.of(stats, service("Here", "There"), byName::get, stations))
        assertNull(NetworkReliabilityDetail.of(stats, service("Roma Termini", "Milano Centrale").copy(scheduledBus = true), byName::get, stations))
    }
}
