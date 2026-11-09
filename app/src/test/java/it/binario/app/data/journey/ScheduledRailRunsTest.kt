package it.binario.app.data.journey

import it.binario.app.data.core.StationId
import it.binario.app.data.journey.JourneyFixtures.Trip
import it.binario.app.data.journey.JourneyFixtures.alfa
import it.binario.app.data.journey.JourneyFixtures.beta
import it.binario.app.data.journey.JourneyFixtures.delta
import it.binario.app.data.journey.JourneyFixtures.epsilon
import it.binario.app.data.journey.JourneyFixtures.feed
import it.binario.app.data.journey.JourneyFixtures.gamma
import it.binario.app.data.journey.JourneyFixtures.monday
import it.binario.app.data.journey.JourneyFixtures.pack
import it.binario.app.data.journey.JourneyFixtures.provider
import it.binario.app.data.journey.JourneyFixtures.zeta
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledRailRunsTest {
    private val stations = listOf(
        alfa.copy(latitude = 45.0, longitude = 9.0),
        beta.copy(latitude = 45.1, longitude = 9.1),
        gamma.copy(latitude = 45.2, longitude = 9.2),
        delta.copy(latitude = 46.0, longitude = 10.0),
        epsilon,
        zeta
    )

    private val trips = listOf(
        Trip("T1", "100", listOf("A" to "10:00", "B" to "10:20/10:22", "C" to "10:40")),
        Trip("T2", "102", listOf("C" to "10:05", "B" to "10:25", "A" to "10:45")),
        Trip("T3", "104", listOf("A" to "06:00", "C" to "06:40")),
        Trip("T4", "106", listOf("D" to "23:30", "E" to "24:10", "Z" to "25:30")),
        Trip("T5", "B1", listOf("A" to "10:00", "C" to "10:30"), route = "BUS"),
        // A night train leaving Alfa at 19:00, before the evening the morning map reads of yesterday's service day.
        Trip("T6", "108", listOf("A" to "19:00", "B" to "20:30", "D" to "22:30", "Z" to "25:00"))
    )

    private val planner = provider(
        mapOf(pack() to feed(trips, stopPoints = mapOf("Z" to (47.0 to 11.0), "A" to (45.0001 to 9.0001)))),
        stations = stations
    )

    private fun at(time: String, date: java.time.LocalDate = monday) = LocalDateTime.of(date, LocalTime.parse(time))

    private fun around(latitude: Double, longitude: Double) =
        ScheduledRunScope.Area(north = latitude + 0.05, south = latitude - 0.05, east = longitude + 0.05, west = longitude - 0.05)

    @Test
    fun `the route scope keeps the trains that call at the origin and then at the destination`() = runBlocking {
        val scope = ScheduledRunScope.Route(StationId(alfa.id), null, StationId(gamma.id), null)
        val runs = planner.scheduledRailRuns(ScheduledRunRequest(at("10:15"), scope))
        val run = runs.single()
        assertEquals("100", run.publicNumber)
        assertEquals("lombardia-trenord-gtfs:T1:$monday", run.id)
        assertEquals(monday, run.serviceDate)
        assertEquals("RE11", run.lineLabel)
        assertEquals(listOf("Alfa", "Beta", "Gamma"), run.stops.map { it.name })
        assertEquals(listOf(600, 620, 640), run.stops.map { it.arrivalMinute })
        assertEquals(listOf(600, 622, 640), run.stops.map { it.departureMinute })
        // Catalogue coordinates win over the pack's own.
        assertEquals(45.0, run.stops.first().latitude!!, 0.0)
        assertEquals(9.1, run.stops[1].longitude!!, 0.0)
        // The other direction is the other board.
        val back = ScheduledRunScope.Route(StationId(gamma.id), null, StationId(alfa.id), null)
        assertEquals(listOf("102"), planner.scheduledRailRuns(ScheduledRunRequest(at("10:15"), back)).map { it.publicNumber })
    }

    @Test
    fun `the area scope keeps the trains with a stop inside, within the time window, and no buses`() = runBlocking {
        val runs = planner.scheduledRailRuns(ScheduledRunRequest(at("10:15"), around(45.0, 9.0)))
        // 104 ended at 06:40, more than the three-hour look-back before 10:15; B1 is a replacement bus.
        assertEquals(listOf("100", "102"), runs.mapNotNull { it.publicNumber }.sorted())
        assertTrue(planner.scheduledRailRuns(ScheduledRunRequest(at("10:15"), around(40.0, 5.0))).isEmpty())
        // 104 is back with a longer look-back.
        val longer = planner.scheduledRailRuns(ScheduledRunRequest(at("10:15"), around(45.0, 9.0), lookBackMinutes = 300))
        assertEquals(listOf("100", "102", "104"), longer.mapNotNull { it.publicNumber }.sorted())
    }

    @Test
    fun `a night train of yesterday's service day is read after midnight with its 24 plus minutes`() = runBlocking {
        val tuesday = monday.plusDays(1)
        val runs = planner.scheduledRailRuns(ScheduledRunRequest(at("00:30", tuesday), around(46.0, 10.0)))
        val night = runs.single { it.publicNumber == "106" }
        assertEquals(monday, night.serviceDate)
        assertEquals(listOf(1410, 1450, 1530), night.stops.map { it.arrivalMinute })
        // Epsilon has no coordinates anywhere; Zeta only in the pack.
        assertNull(night.stops[1].latitude)
        assertEquals(47.0, night.stops[2].latitude!!, 0.0)
        // In the afternoon yesterday's runs are no longer read.
        assertTrue(planner.scheduledRailRuns(ScheduledRunRequest(at("15:00", tuesday), around(46.0, 10.0))).none { it.serviceDate == monday })
    }

    @Test
    fun `a night train read from yesterday's evening keeps its true origin and departure`() = runBlocking {
        val tuesday = monday.plusDays(1)
        val runs = planner.scheduledRailRuns(ScheduledRunRequest(at("00:30", tuesday), around(46.0, 10.0)))
        val night = runs.single { it.publicNumber == "108" }
        assertEquals(monday, night.serviceDate)
        // Only the stops from 20:00 on are read, but the run still says it left Alfa at 19:00.
        assertEquals(listOf("Beta", "Delta", "Zeta"), night.stops.map { it.name })
        assertEquals(ScheduledRunOrigin("Alfa", 19 * 60), night.origin)
        assertEquals("Alfa", night.originName)
        assertEquals(19 * 60, night.originDepartureMinute)
        // A run read from its first stop has no separate origin.
        val fromStart = runs.single { it.publicNumber == "106" }
        assertNull(fromStart.origin)
        assertEquals("Delta", fromStart.originName)
        assertEquals(fromStart.firstDepartureMinute, fromStart.originDepartureMinute)
        // Today's whole day is read from midnight: its runs never carry one.
        assertTrue(planner.scheduledRailRuns(ScheduledRunRequest(at("10:15"), around(45.0, 9.0))).all { it.origin == null })
    }

    @Test
    fun `a station unknown to every pack gives no route runs`() = runBlocking {
        val scope = ScheduledRunScope.Route(StationId("curated:none"), null, StationId(gamma.id), null)
        assertTrue(planner.scheduledRailRuns(ScheduledRunRequest(at("10:15"), scope)).isEmpty())
    }

    @Test
    fun `the route order check needs the destination after the origin`() {
        assertTrue(ScheduledRunCollector.servesInOrder(listOf("a", "b", "c"), setOf("a"), setOf("c")))
        assertTrue(!ScheduledRunCollector.servesInOrder(listOf("c", "b", "a"), setOf("a"), setOf("c")))
        assertTrue(!ScheduledRunCollector.servesInOrder(listOf("a", "b"), setOf("a"), setOf("a")))
    }
}
