package it.binario.app.data.gtfs

import it.binario.app.TestAssets
import it.binario.app.data.RailOperator
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationId
import it.binario.app.data.journey.EmbeddedGtfsJourneyProvider
import it.binario.app.data.provider.JourneyEndpoint
import it.binario.app.ui.departures.DirectConnectionFilter
import it.binario.app.ui.departures.DirectConnectionSearch
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Collegamenti diretti» and the stations of a route, on the planner's day arrays. The real-pack checks use the packs
 * and the catalogue shipped in the APK on Tuesday 10 November 2026 (inside every pack's validity): a pack refresh may
 * change the counts, never the structure checked here.
 */
class GtfsDirectConnectionsTest {
    private val openAsset = { path: String -> TestAssets.open(path) }
    private val stations: List<Station> by lazy { EmbeddedCuratedRailStationCatalog(openAsset).load() }
    private val tuesday = LocalDate.of(2026, 11, 10)
    private val provider by lazy { EmbeddedGtfsJourneyProvider.create(openAsset) }

    private fun station(name: String): Station = stations.single { it.name == name }

    private fun DirectConnections.rail(name: String): DirectDestination = rail.single { it.name == name }

    @Test fun `Milano Centrale reaches Rome, Bergamo and the M2 stops without changes`() = runBlocking {
        val milano = station("Milano Centrale")
        val result = provider.directConnections(StationId(milano.id), null, tuesday)
        assertEquals(tuesday, result.date)
        assertTrue(result.rail.size > 250)
        assertEquals(result.rail.sortedByDescending { it.runs }.map { it.runs }, result.rail.map { it.runs })
        assertTrue(result.rail.none { it.stationId == milano.id } && result.urban.none { it.stationId == milano.id })
        // Frecciarossa and Italo, each train once: 79 a day from 05:10 to 20:40, the fastest in 2 h 50 min. (They were
        // 81 while the Italo 13:40 and 14:40 also counted under the number of an itinerary's first train.)
        with(result.rail("Roma Termini")) {
            assertEquals("curated:S08409", stationId)
            assertEquals(79, runs)
            assertEquals(5 * 60 + 10, firstDepartureMinute)
            assertEquals(20 * 60 + 40, lastDepartureMinute)
            assertEquals(170, fastestMinutes)
            assertTrue(lines.isEmpty() && boardingStop == null && !urban)
        }
        with(result.rail("Bergamo")) {
            assertEquals(19, runs)
            assertEquals(48, fastestMinutes)
        }
        // A night run of the service day keeps its minute after midnight.
        assertTrue(result.rail("Milano Lambrate").lastDepartureMinute < 24 * 60)
        // The metro from the station's own stop, a short walk away: never from Caiazzo, the next stop of the line.
        assertTrue(result.urban.isNotEmpty())
        assertTrue(result.urban.all { it.urban && it.stationId.startsWith("gtfs:milano-atm-gtfs:") && it.lines.isNotEmpty() })
        with(result.urban.single { it.name == "Cadorna Fn M2" }) {
            assertEquals(listOf("M2"), lines)
            assertEquals("Centrale FS", boardingStop)
            assertTrue(runs > 200)
        }
        assertTrue(result.urban.none { it.boardingStop == "Caiazzo" })
    }

    @Test fun `Roma Termini reaches Naples and the airport, then the city lines`() = runBlocking {
        val result = provider.directConnections(StationId(station("Roma Termini").id), null, tuesday)
        with(result.rail("Napoli Centrale")) {
            // 29 Italo trains, not the 43 rows the pack's itinerary copies made of them.
            assertEquals(100, runs)
            assertEquals(68, fastestMinutes)
        }
        with(result.rail("Fiumicino Aeroporto")) {
            assertEquals(67, runs)
            assertEquals(31, fastestMinutes)
        }
        assertEquals(result.rail("Milano Centrale").firstDepartureMinute, 5 * 60 + 10)
        // The Rome metro is a personal-build pack; the JVM tests run the personal (debug) build.
        if (EmbeddedGtfsPacks.urban.any { it.sourceId.value == "roma-mobilita-gtfs" }) {
            assertEquals(listOf("A"), result.urban.single { it.name == "Barberini" }.lines)
        }
        // Asked again: the day arrays are cached.
        val started = System.nanoTime()
        assertEquals(result, provider.directConnections(StationId(station("Roma Termini").id), null, tuesday))
        println("DIRECT_CONNECTIONS Roma Termini again in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    @Test fun `a city stop has its own lines and no trains`() = runBlocking {
        val result = provider.directConnections(StationId("gtfs:milano-atm-gtfs:CENTRALE FS"), JourneyEndpoint("Centrale FS"), tuesday)
        assertTrue(result.rail.isEmpty())
        assertTrue(result.urban.any { it.name == "Cadorna Fn M2" && it.boardingStop == null })
        assertTrue(provider.directConnections(StationId("curated:none"), null, tuesday).isEmpty)
    }

    @Test fun `the direct trains of a route call at the stations between its ends`() = runBlocking {
        val stops = provider.directRunStations(StationId(station("Milano Centrale").id), null, StationId(station("Brescia").id), null, tuesday)
            .map { it.name }
        assertEquals(listOf("Milano Centrale", "Brescia"), stops.take(2))
        assertTrue(stops.containsAll(listOf("Milano Lambrate", "Treviglio", "Rovato")))
        assertTrue("Verona Porta Nuova" !in stops)
        assertTrue(provider.directRunStations(StationId(station("Milano Centrale").id), null, StationId("curated:none"), null, tuesday).isEmpty())
    }

    @Test fun `a train two packs publish counts once, coaches and set-down-less stops do not count`() {
        val origin = "O"
        fun day(source: String, stops: List<String>, build: GtfsDayTimetable.Builder.() -> Unit) =
            GtfsDayTimetable.Builder(SourceId(source), tuesday, stops.size, { stops[it] }, { stops[it].uppercase() }).apply(build).build()
        fun GtfsDayTimetable.Builder.trip(number: String, mode: LegMode, line: String?, vararg hops: Triple<Int, Int, Pair<Int, Int>>, setDown: Boolean = true) {
            beginTrip("$number-${hops.first().third.first}", number, line, null, TrainCategory.REGIONALE, RailOperator.OTHER, mode)
            hops.forEachIndexed { index, (from, to, minutes) ->
                connection(from, to, minutes.first, minutes.second, pickupAllowed = true, dropOffAllowed = setDown || index == hops.lastIndex)
            }
            endTrip()
        }
        val first = day("a", listOf("o", "x", "y", "z")) {
            trip("EC 10", LegMode.TRAIN, "EC", Triple(0, 1, 480 to 510), Triple(1, 2, 512 to 540))
            trip("R 20", LegMode.TRAIN, "R", Triple(0, 1, 540 to 560), Triple(1, 3, 562 to 590), setDown = false)
            trip("Bus 30", LegMode.BUS, null, Triple(0, 2, 600 to 650))
        }
        val second = day("b", listOf("o2", "y2")) {
            trip("10", LegMode.TRAIN, null, Triple(0, 1, 482 to 543))
            trip("10", LegMode.TRAIN, null, Triple(0, 1, 1080 to 1140))
        }
        val city = day("c", listOf("s1", "s2", "s3")) {
            trip("t1", LegMode.METRO, "2", Triple(0, 1, 360 to 365), Triple(1, 2, 366 to 370))
        }
        val nodes = mapOf("o" to origin, "o2" to origin, "x" to "X", "y" to "Y", "y2" to "Y", "z" to "Z", "s1" to "S1", "s2" to "S2", "s3" to "S3")
        fun view(day: GtfsDayTimetable, urban: Boolean, originNodes: Map<String, Int>) = GtfsStationDay(
            day, urban, Array(day.stopIds.size) { nodes.getValue(day.stopIds[it]) }, originNodes, emptySet(),
            { node, stop -> StationId("id:$node") to day.stopNames[stop] }, { null }
        )
        val collector = DirectConnectionsCollector(tuesday)
        collector.add(view(first, urban = false, originNodes = mapOf(origin to 0)))
        collector.add(view(second, urban = false, originNodes = mapOf(origin to 0)))
        collector.add(view(city, urban = true, originNodes = mapOf("S1" to 3)))
        val result = collector.result()
        assertEquals(listOf("id:Y", "id:X", "id:Z"), result.rail.map { it.stationId })
        with(result.rail.first()) {
            // EC 10 at 08:00 and pack b's "10" at 08:02 are one train; the 18:00 run is another. The coach is left out.
            assertEquals(2, runs)
            assertEquals(480, firstDepartureMinute)
            assertEquals(1080, lastDepartureMinute)
            assertEquals(60, fastestMinutes)
        }
        // R 20 does not set down at X: only EC 10 counts there.
        assertEquals(1, result.rail[1].runs)
        assertEquals(30, result.rail[1].fastestMinutes)
        with(result.urban.single { it.stationId == "id:S3" }) {
            assertEquals(listOf("M2"), lines)
            assertEquals("S1", boardingStop)
            assertEquals(10, fastestMinutes)
        }
    }

    @Test fun `the sheet filters by name and opens the route search at the first departure`() {
        val destinations = listOf(
            DirectDestination("a", "Verona Porta Nuova", false, 3, 300, 1300, 70, emptyList(), null),
            DirectDestination("b", "Città di Castello", false, 1, 400, 400, 90, emptyList(), null)
        )
        assertEquals(listOf("b"), DirectConnectionFilter.matching(destinations, "citta CAST").map { it.stationId })
        assertEquals(listOf("a"), DirectConnectionFilter.matching(destinations, " verona  nuova ").map { it.stationId })
        assertEquals(destinations, DirectConnectionFilter.matching(destinations, "  "))
        assertEquals("06:05", DirectConnectionFilter.clock(365))
        assertEquals("00:35", DirectConnectionFilter.clock(24 * 60 + 35))
        val today = LocalDate.of(2026, 11, 4)
        assertEquals(null to null, DirectConnectionSearch.departure(today, 365, today))
        assertEquals(tuesday to LocalTime.of(6, 5), DirectConnectionSearch.departure(tuesday, 365, today))
        assertEquals(tuesday.plusDays(1) to LocalTime.of(0, 10), DirectConnectionSearch.departure(tuesday, 24 * 60 + 10, today))
        assertNull(DirectConnectionSearch.departure(today, 365, today).first)
    }
}
