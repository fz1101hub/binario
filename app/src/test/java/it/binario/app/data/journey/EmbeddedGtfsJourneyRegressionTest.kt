package it.binario.app.data.journey

import it.binario.app.data.Station
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.journey.JourneyFixtures.Trip
import it.binario.app.data.journey.JourneyFixtures.alfa
import it.binario.app.data.journey.JourneyFixtures.beta
import it.binario.app.data.journey.JourneyFixtures.catalogue
import it.binario.app.data.journey.JourneyFixtures.delta
import it.binario.app.data.journey.JourneyFixtures.feed
import it.binario.app.data.journey.JourneyFixtures.monday
import it.binario.app.data.journey.JourneyFixtures.pack
import it.binario.app.data.journey.JourneyFixtures.provider
import it.binario.app.data.journey.JourneyFixtures.request
import it.binario.app.data.provider.JourneyPreferences
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.TravelMode
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner's answers to the November 2026 audit: in-seat continuations that never ride a train boarded after the
 * junction, long runs followed past the horizon, long waits at a change, change times at large hubs and stops placed at
 * the station they stand in, preferences inside GTFS blocks, and the first train after an empty window.
 */
class EmbeddedGtfsJourneyRegressionTest {
    private fun options(result: ProviderResult<List<JourneyOption>>): List<JourneyOption> = when (result) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Partial -> result.value
        else -> throw AssertionError("Expected journeys, got $result")
    }

    private fun JourneyOption.summary(): String = legs.joinToString(" | ") { leg ->
        "${leg.publicNumber ?: leg.mode} ${leg.originLabel} ${leg.scheduledDeparture.toLocalTime()}-${leg.scheduledArrival.toLocalTime()} ${leg.destinationLabel}"
    }

    private fun minutes(from: java.time.ZonedDateTime, to: java.time.ZonedDateTime) = Duration.between(from, to).toMinutes()

    // --- "Resta a bordo" in the scan ---

    @Test fun `a traveller who boards after the junction is never on board of the trip the vehicle goes on as`() {
        // Nodes: 0 Alfa, 1 Beta, 2 Gamma, 3 Delta, 4 Zeta, 5 Epsilon. Trip 0 Alfa → Beta → Gamma → Delta; the vehicle
        // also goes on from Beta as trip 1 (Beta → Zeta → Epsilon), linked after trip 0's connection into Beta.
        val timetable = ConnectionScanTimetable(
            nodeCount = 6,
            tripCount = 2,
            transferMinutes = IntArray(6) { 5 },
            departureNode = intArrayOf(0, 1, 1, 2, 4),
            arrivalNode = intArrayOf(1, 2, 4, 3, 5),
            departureMinute = intArrayOf(480, 511, 520, 570, 590),
            arrivalMinute = intArrayOf(510, 570, 590, 600, 620),
            trip = intArrayOf(0, 0, 1, 0, 1),
            boarding = ByteArray(5),
            continuationStart = intArrayOf(0, -1, -1, -1, -1),
            continuationTrip = intArrayOf(1),
            continuationNext = intArrayOf(-1)
        )
        fun query(origin: Int, destination: Int) = ConnectionScanQuery(
            BooleanArray(6) { it == origin }, BooleanArray(6) { it == destination }, 0, 700, maxTransfers = 2, maxResults = 6, maxIterations = 32
        )
        val planner = ConnectionScanPlanner(timetable)
        // Boarded at Alfa: on board through Beta, then on to Epsilon with no change.
        val through = planner.plan(query(0, 5)).single()
        assertEquals(listOf(ScannedLeg(0, 4)), through.legs)
        assertEquals(620, through.arrivalMinute)
        // Boarded at Gamma, after the junction: trip 1 left Beta before, so Epsilon is out of reach.
        assertTrue(planner.plan(query(2, 5)).isEmpty())
    }

    @Test fun `the train another pack continues is reached only through the junction, never by time travel`() = runBlocking {
        // The STA copy passes Beta (08:46-08:56) and goes on through Gamma and Gi to Delta; the ÖBB copy leaves Beta at
        // 08:56 and goes through Zeta, Gamma (the same minute) and Ypsilon to Epsilon. The vehicle is one train up to
        // Gamma; a traveller boarding the STA copy at Gi (09:30) is not on the ÖBB copy, which is at Ypsilon at 09:40.
        val gi = Station("curated:G", "Gi", "Gi", railwayCode = "S90007")
        val ypsilon = Station("curated:Y", "Ypsilon", "Ypsilon", railwayCode = "S90008")
        val names = mapOf("A" to "Alfa", "B" to "Beta", "C" to "Gamma", "D" to "Delta", "E" to "Epsilon", "Z" to "Zeta", "G" to "Gi", "Y" to "Ypsilon")
        val sta = pack("alto-adige-sta-gtfs")
        val oebb = pack("oebb-gtfs")
        val staFeed = feed(
            listOf(Trip("S186", "186", listOf("A" to "07:44", "B" to "08:46/08:56", "C" to "09:20", "G" to "09:30", "D" to "09:36"), route = "RJX")),
            stopNames = names,
            routes = "route_id,agency_id,route_short_name,route_long_name,route_type\nRJX,T,RJX,Railjet Xpress,2\n"
        )
        val oebbFeed = feed(
            listOf(Trip("O186", "RJX 186", listOf("B" to "08:56", "Z" to "09:05", "C" to "09:20", "Y" to "09:40", "E" to "09:50"))),
            stopNames = names
        )
        val provider = provider(mapOf(sta to staFeed, oebb to oebbFeed), stations = catalogue + gi + ypsilon)
        val through = options(provider.searchJourneys(request(alfa, JourneyFixtures.epsilon))).single()
        assertEquals("186 Alfa 07:44-09:50 Epsilon", through.summary())
        assertEquals("RJX 186", through.legs.single().continuations.single().publicNumber)
        assertTrue(options(provider.searchJourneys(request(gi, JourneyFixtures.epsilon, time = "09:00"))).isEmpty())
    }

    // --- Long runs past the horizon ---

    @Test fun `a long run leaving inside the window is followed to its end`() = runBlocking {
        val trips = listOf(Trip("L1", "1963", listOf("A" to "10:00", "B" to "28:00", "D" to "29:30")))
        val found = options(provider(mapOf(pack() to feed(trips))).searchJourneys(request(alfa, delta, time = "09:30"))).single()
        assertEquals(LocalDateTime.of(monday.plusDays(1), LocalTime.of(5, 30)), found.arrival.toLocalDateTime())
        assertEquals(0, found.transfers)
    }

    @Test fun `a night train boarded at a change before the horizon reaches the next afternoon`() = runBlocking {
        // Milano 12:00 → Palermo: the Frecciarossa leaves at 18:00 (inside the eight-hour window), the night train at
        // 23:00 (inside the ten-hour horizon) and arrives at 09:13, past the horizon.
        val trips = listOf(
            Trip("FR", "9653", listOf("A" to "18:00", "B" to "22:33")),
            Trip("ICN", "1955", listOf("B" to "23:01", "C" to "28:00", "D" to "33:13"))
        )
        val found = options(provider(mapOf(pack() to feed(trips))).searchJourneys(request(alfa, delta, time = "12:00"))).single()
        assertEquals("9653 Alfa 18:00-22:33 Beta | 1955 Beta 23:01-09:13 Delta", found.summary())
    }

    @Test fun `no vehicle is boarded after the horizon`() = runBlocking {
        val trips = listOf(
            Trip("L1", "1", listOf("A" to "10:00", "B" to "28:00")),
            Trip("L2", "2", listOf("B" to "28:10", "D" to "29:00"))
        )
        // Window 09:30-17:30, horizon 03:30 the next day: the change at Beta at 04:10 is past it.
        assertTrue(options(provider(mapOf(pack() to feed(trips))).searchJourneys(request(alfa, delta, time = "09:30"))).isEmpty())
    }

    // --- Long waits at a change ---

    @Test fun `a night in a station is left out when the first train of the morning arrives soon after`() = runBlocking {
        val trips = listOf(
            Trip("N1", "24186", listOf("A" to "23:53", "B" to "24:44")),
            Trip("N2", "24814", listOf("B" to "05:22", "D" to "06:24")),
            Trip("M1", "10852", listOf("A" to "05:39", "B" to "06:20")),
            Trip("M2", "24818", listOf("B" to "06:29", "D" to "07:26"))
        )
        val found = options(provider(mapOf(pack() to feed(trips))).searchJourneys(request(alfa, delta, time = "23:30")))
        assertEquals("10852 Alfa 05:39-06:20 Beta | 24818 Beta 06:29-07:26 Delta", found.first().summary())
        assertTrue(found.none { option -> option.legs.any { it.publicNumber == "24186" } })
        assertTrue(found.none { it.lastOfServiceDay })
    }

    @Test fun `a long wait that is the only way stays, and is never the last of the day`() = runBlocking {
        val trips = listOf(
            Trip("N1", "24186", listOf("A" to "23:53", "B" to "24:44")),
            Trip("N2", "24814", listOf("B" to "05:22", "D" to "06:24"))
        )
        val only = options(provider(mapOf(pack() to feed(trips))).searchJourneys(request(alfa, delta, time = "23:30"))).single()
        assertEquals("24186 Alfa 23:53-00:44 Beta | 24814 Beta 05:22-06:24 Delta", only.summary())
        assertFalse(only.lastOfServiceDay)
        // Without the limit on waits the same option is the last of the day.
        val unlimited = provider(
            mapOf(pack() to feed(trips)),
            GtfsJourneyPlannerSettings(largeHubRailwayCodes = emptySet(), maxChangeWaitMinutes = 24 * 60)
        )
        assertTrue(options(unlimited.searchJourneys(request(alfa, delta, time = "23:30"))).single().lastOfServiceDay)
    }

    @Test fun `one change fewer is not worth hours of waiting when another journey arrives first`() = runBlocking {
        // Domodossola → Bolzano: the EuroCity, then nearly seven hours in Milano for a direct train (one change), or
        // two changes arriving five hours earlier.
        val trips = listOf(
            Trip("T1", "61", listOf("A" to "09:17", "B" to "10:50")),
            Trip("T2", "9751", listOf("B" to "17:45", "D" to "20:48")),
            Trip("T3", "9723", listOf("B" to "11:45", "C" to "12:58")),
            Trip("T4", "3842", listOf("C" to "13:50", "D" to "15:30"))
        )
        val found = options(provider(mapOf(pack() to feed(trips))).searchJourneys(request(alfa, delta, time = "07:00")))
        assertEquals(listOf("61 Alfa 09:17-10:50 Beta | 9723 Beta 11:45-12:58 Gamma | 3842 Gamma 13:50-15:30 Delta"), found.map { it.summary() })
    }

    // --- Change times at large hubs, stops that are a catalogue station ---

    private val hub = Station("curated:H", "Grande Hub", "Grande Hub", railwayCode = "S90010", latitude = 45.0, longitude = 9.0)
    /** 200 m east of the hub. */
    private val near = Station("curated:N", "Vicina", "Vicina", railwayCode = "S90011", latitude = 45.0, longitude = 9.00254)

    @Test fun `walking out of a large hub takes the hub's change time`() = runBlocking {
        val trips = listOf(
            Trip("T1", "100", listOf("A" to "08:00", "H" to "09:00")),
            Trip("T2", "200", listOf("N" to "09:06", "D" to "10:00")),
            Trip("T3", "300", listOf("N" to "09:12", "D" to "10:10"))
        )
        val stops = mapOf("A" to "Alfa", "H" to "Grande Hub", "N" to "Vicina", "D" to "Delta")
        val provider = provider(
            mapOf(pack() to feed(trips, stopNames = stops)),
            GtfsJourneyPlannerSettings(largeHubRailwayCodes = setOf("S90010")),
            stations = catalogue + hub + near
        )
        val option = options(provider.searchJourneys(request(alfa, delta))).single()
        // 200 m take five minutes on foot, but leaving the hub takes its ten: the 09:06 is out of reach.
        assertEquals("100 Alfa 08:00-09:00 Grande Hub | WALK Grande Hub 09:00-09:10 Vicina | 300 Vicina 09:12-10:10 Delta", option.summary())
    }

    /** Verona Porta Nuova, and the stop another pack publishes 14 m away as plain "Verona". */
    private val porta = Station("curated:V", "Verona Porta Nuova", "Verona", railwayCode = "S90020", latitude = 45.4287, longitude = 10.9823)

    @Test fun `a stop named after the station it stands in is that station, with its change time and no walk`() = runBlocking {
        val national = pack("lombardia-trenord-gtfs")
        val regional = pack("alto-adige-sta-gtfs")
        val nationalFeed = feed(
            listOf(Trip("R1", "2613", listOf("A" to "06:25", "V" to "08:17"))),
            stopNames = mapOf("A" to "Alfa", "V" to "Verona Porta Nuova"),
            stopPoints = mapOf("V" to (45.4287 to 10.9823))
        )
        val regionalFeed = feed(
            listOf(
                Trip("S1", "8953", listOf("VR" to "08:22", "D" to "09:14")),
                Trip("S2", "8955", listOf("VR" to "08:30", "D" to "09:22"))
            ),
            stopNames = mapOf("VR" to "Verona", "D" to "Delta"),
            stopPoints = mapOf("VR" to (45.42858 to 10.98234))
        )
        val provider = provider(
            mapOf(national to nationalFeed, regional to regionalFeed),
            GtfsJourneyPlannerSettings(largeHubRailwayCodes = setOf("S90020")),
            stations = catalogue + porta
        )
        val option = options(provider.searchJourneys(request(alfa, delta, time = "06:00"))).single()
        // Five minutes at a large hub used to be enough through a walk from "Verona Porta Nuova" to "Verona".
        assertEquals("2613 Alfa 06:25-08:17 Verona Porta Nuova | 8955 Verona Porta Nuova 08:30-09:22 Delta", option.summary())
        assertTrue(option.legs.none { it.mode == LegMode.WALK })
        // Leaving from Verona Porta Nuova, the trains of the other pack leave from it, with no walk first.
        val fromVerona = options(provider.searchJourneys(request(porta, delta, time = "08:00")))
        assertEquals(listOf("8953", "8955"), fromVerona.map { it.legs.single().publicNumber })
    }

    @Test fun `only a stop that names nothing more than the station is placed at it`() {
        val locarno = Station("curated:L", "Locarno", "Locarno", latitude = 46.1725, longitude = 8.8012)
        val innsbruck = Station("curated:I", "Innsbruck Hbf.", "Innsbruck", latitude = 47.2632, longitude = 11.4008)
        val resolver = GtfsStopStationResolver(listOf(locarno, innsbruck, porta))
        assertEquals(locarno, resolver.nearbyStation("Locarno", 46.1726, 8.8013))
        assertEquals(innsbruck, resolver.nearbyStation("Innsbruck, Stazione di Innsbruck Centrale", 47.2635, 11.4010))
        assertEquals(porta, resolver.nearbyStation("Verona", 45.42858, 10.98234))
        // Another railway's station next door, a stop too far, or no coordinates: not that station.
        assertNull(resolver.nearbyStation("Locarno FART", 46.1726, 8.8013))
        assertNull(resolver.nearbyStation("Locarno", 46.1755, 8.8012))
        assertNull(resolver.nearbyStation("Locarno", null, null))
        // Two stations of that name nearby: no guess.
        val twice = GtfsStopStationResolver(listOf(locarno, Station("curated:L2", "Locarno Muralto", "Locarno", latitude = 46.1727, longitude = 8.8014)))
        assertNull(twice.nearbyStation("Locarno", 46.1726, 8.8013))
    }

    // --- Travel preferences inside GTFS blocks ---

    @Test fun `accessible only never rides through an inaccessible trip of the same block`() = runBlocking {
        val trips = listOf(
            Trip("T1", "100", listOf("A" to "08:00", "B" to "08:30"), block = "K1", wheelchair = "1"),
            Trip("T2", "200", listOf("B" to "08:32", "C" to "08:50"), block = "K1", wheelchair = "2"),
            Trip("T3", "300", listOf("C" to "08:52", "D" to "09:20"), block = "K1", wheelchair = "1")
        )
        val stops = mapOf("A" to "1", "B" to "1", "C" to "1", "D" to "1")
        val provider = provider(mapOf(pack() to feed(trips, stopWheelchair = stops)))
        assertTrue(options(provider.searchJourneys(request(alfa, delta, preferences = JourneyPreferences(wheelchairOnly = true)))).isEmpty())
        // Without the preference the block is one vehicle from Alfa to Delta.
        assertEquals(0, options(provider.searchJourneys(request(alfa, delta))).single().transfers)
    }

    @Test fun `an avoided kind is never ridden through inside a block`() = runBlocking {
        val routes = "route_id,agency_id,route_short_name,route_long_name,route_type\nR,T,RE11,Linea,2\nIC,T,IC,Intercity,2\n"
        val trips = listOf(
            Trip("T1", "100", listOf("A" to "08:00", "B" to "08:30"), block = "K1"),
            Trip("T2", "200", listOf("B" to "08:32", "C" to "08:50"), block = "K1", route = "IC"),
            Trip("T3", "300", listOf("C" to "08:52", "D" to "09:20"), block = "K1")
        )
        val provider = provider(mapOf(pack() to feed(trips, routes = routes)))
        val avoiding = JourneyPreferences(avoidedModes = setOf(TravelMode.INTERCITY))
        assertTrue(options(provider.searchJourneys(request(alfa, delta, preferences = avoiding))).isEmpty())
    }

    // --- The first train after an empty window ---

    @Test fun `after the last train of the afternoon the first of the next morning follows`() = runBlocking {
        val trips = listOf(
            Trip("M1", "1", listOf("A" to "07:00", "D" to "08:00")),
            Trip("M2", "2", listOf("A" to "14:00", "D" to "15:00"))
        )
        val option = options(provider(mapOf(pack() to feed(trips))).searchJourneys(request(alfa, delta, time = "15:30"))).single()
        assertEquals(LocalDateTime.of(monday.plusDays(1), LocalTime.of(7, 0)), option.departure.toLocalDateTime())
        assertEquals(monday.plusDays(1), option.legs.single().serviceDate)
    }

    @Test fun `on a line with one morning train a late morning search finds the next day's`() = runBlocking {
        val trips = listOf(Trip("M1", "1", listOf("A" to "07:00", "D" to "08:00")))
        val provider = provider(mapOf(pack() to feed(trips)))
        // Nothing until 04:00 tomorrow, the end of the horizon: the next morning is searched on its own.
        val option = options(provider.searchJourneys(request(alfa, delta, time = "10:00"))).single()
        assertEquals(LocalDateTime.of(monday.plusDays(1), LocalTime.of(7, 0)), option.departure.toLocalDateTime())
        assertEquals(60L, minutes(option.departure, option.arrival))
        // A station no train serves at all stays without options.
        val none = provider(mapOf(pack() to feed(listOf(Trip("M1", "1", listOf("A" to "07:00", "B" to "08:00"))))))
        assertTrue(options(none.searchJourneys(request(alfa, delta, time = "10:00"))).isEmpty())
        assertTrue(options(none.searchJourneys(request(beta, delta, time = "10:00"))).isEmpty())
    }
}
