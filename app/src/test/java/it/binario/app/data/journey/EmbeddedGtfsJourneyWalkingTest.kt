package it.binario.app.data.journey

import it.binario.app.data.Station
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsPackKind
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.gtfs.TransitArea
import it.binario.app.data.journey.JourneyFixtures.monday
import it.binario.app.data.journey.JourneyFixtures.zone
import it.binario.app.data.provider.JourneyEndpoint
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walking transfers and the city packs (metro, tram) of the offline planner. A small railway around "Città" and a
 * metro with a stop 200 m from its station: the walk takes ceil(200 m / 75 m per minute) + 2 = 5 minutes.
 */
class EmbeddedGtfsJourneyWalkingTest {
    /** Città station at 45.0000 N 9.0000 E; Paese 30 km south. */
    private val citta = Station("curated:C1", "Città Centrale", "Città", railwayCode = "S80001", latitude = 45.0, longitude = 9.0)
    private val paese = Station("curated:P1", "Paese", "Paese", railwayCode = "S80002", latitude = 44.73, longitude = 9.0)
    /** Another station 300 m east of Città Centrale (0.0038° of longitude at 45° N is about 300 m). */
    private val cittaEst = Station("curated:C2", "Città Est", "Città", railwayCode = "S80003", latitude = 45.0, longitude = 9.0038)
    private val catalogue = listOf(citta, paese, cittaEst)

    private val railPack = EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "gtfs/rail.zip")
    private val cityArea = TransitArea("Città", 45.0, 9.0, 15.0)
    private val metroPack = EmbeddedGtfsPack(
        SourceId("test-city-metro"), "gtfs-transit/metro.zip", kind = EmbeddedGtfsPackKind.URBAN, areas = listOf(cityArea)
    )

    private data class Stop(val id: String, val name: String, val latitude: Double, val longitude: Double)
    private data class Trip(val id: String, val route: String, val stops: List<Pair<String, String>>, val number: String = "")

    private fun feed(stops: List<Stop>, routes: List<Triple<String, String, Int>>, trips: List<Trip>): ByteArray {
        val content = linkedMapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\nA,Rete di Prova,https://example.test,Europe/Rome\n",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\n" + stops.joinToString("") { "${it.id},${it.name},${it.latitude},${it.longitude}\n" },
            "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type\n" +
                routes.joinToString("") { (id, short, type) -> "$id,A,$short,,$type\n" },
            "trips.txt" to "route_id,service_id,trip_id,trip_short_name\n" + trips.joinToString("") { "${it.route},ALL,${it.id},${it.number}\n" },
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" + trips.joinToString("") { trip ->
                trip.stops.mapIndexed { index, (stop, time) -> "${trip.id},$time:00,$time:00,$stop,${index + 1}\n" }.joinToString("")
            },
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
                "ALL,1,1,1,1,1,1,1,20260101,20261231\n"
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            content.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    /** Paese → Città Centrale at 08:00-08:30, Città Est → Paese at 09:00-09:30. */
    private val railFeed = feed(
        stops = listOf(Stop("PA", "Paese", 44.73, 9.0), Stop("CC", "Città Centrale", 45.0, 9.0), Stop("CE", "Città Est", 45.0, 9.0038)),
        routes = listOf(Triple("R", "R1", 2)),
        trips = listOf(
            Trip("T1", "R", listOf("PA" to "08:00", "CC" to "08:30"), number = "1001"),
            Trip("T2", "R", listOf("CE" to "09:00", "PA" to "09:30"), number = "1002")
        )
    )

    /**
     * Metro M1 from "Centrale M1" (200 m north of Città Centrale) to "Duomo" (2 km north) every 10 minutes from
     * 08:00, and a tram 14 from "Duomo" to "Castello". A metro stop named like the station ("Città Centrale", 1 km
     * away) must not be taken for it.
     */
    private val metroFeed = feed(
        stops = listOf(
            Stop("CM", "Centrale M1", 45.0018, 9.0),
            Stop("DU", "Duomo", 45.018, 9.0),
            Stop("CA", "Castello", 45.03, 9.0),
            Stop("FAKE", "Città Centrale", 45.009, 9.0)
        ),
        routes = listOf(Triple("M1", "M1", 1), Triple("T14", "14", 0)),
        trips = (0 until 12).map { index ->
            val minute = 8 * 60 + index * 10
            Trip("M$index", "M1", listOf("CM" to hhmm(minute), "FAKE" to hhmm(minute + 2), "DU" to hhmm(minute + 6)), number = "trip-$index")
        } + Trip("TR1", "T14", listOf("DU" to "08:50", "CA" to "09:00"))
    )

    private fun hhmm(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

    private fun provider(
        feeds: Map<EmbeddedGtfsPack, ByteArray>,
        settings: GtfsJourneyPlannerSettings = GtfsJourneyPlannerSettings(largeHubRailwayCodes = emptySet()),
        opened: MutableMap<String, AtomicInteger> = HashMap()
    ): EmbeddedGtfsJourneyProvider {
        val byPath = feeds.mapKeys { it.key.assetPath }
        val packs = feeds.keys.toList()
        val open = { path: String ->
            opened.getOrPut(path) { AtomicInteger() }.incrementAndGet()
            ByteArrayInputStream(byPath.getValue(path))
        }
        return EmbeddedGtfsJourneyProvider(
            adapter = EmbeddedGtfsRouteAdapter(openAsset = open, packs = packs, indexCacheCapacity = 1),
            packs = packs,
            loadStations = { catalogue },
            settings = settings,
            clock = Clock.fixed(Instant.parse("2026-09-01T06:00:00Z"), zone)
        )
    }

    private fun request(origin: String, destination: String, time: String = "07:30", destinationName: String? = null) = JourneySearchRequest(
        origin = StationId(origin),
        destination = StationId(destination),
        departureAt = ZonedDateTime.of(monday, LocalTime.parse(time), zone),
        destinationEndpoint = destinationName?.let { JourneyEndpoint(it) }
    )

    private fun options(result: ProviderResult<List<JourneyOption>>): List<JourneyOption> = when (result) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Partial -> result.value
        else -> throw AssertionError("Expected journeys, got $result")
    }

    private fun JourneyOption.summary(): String = legs.joinToString(" | ") { leg ->
        "${leg.mode} ${listOfNotNull(leg.lineLabel, leg.publicNumber).joinToString(" ")} ${leg.originLabel} " +
            "${leg.scheduledDeparture.toLocalTime()}-${leg.scheduledArrival.toLocalTime()} ${leg.destinationLabel}"
    }

    @Test fun `a train then a short walk to the metro reaches a city stop`() = runBlocking {
        val provider = provider(mapOf(railPack to railFeed, metroPack to metroFeed))
        val found = options(provider.searchJourneys(request(paese.id, "gtfs:test-city-metro:DU", destinationName = "Duomo")))
        val first = found.first()
        assertEquals(
            "TRAIN R1 1001 Paese 08:00-08:30 Città Centrale | WALK  Città Centrale 08:30-08:35 Centrale M1 | " +
                "METRO M1 Centrale M1 08:40-08:46 Duomo",
            first.summary()
        )
        // A walk is not a change, a metro is a vehicle: one change.
        assertEquals(1, first.transfers)
        val metro = first.legs.last()
        assertEquals(ServiceKind.URBAN, metro.serviceKind)
        assertEquals(null, metro.publicNumber)
        assertEquals(StationId("gtfs:test-city-metro:DU"), metro.destination)
        assertEquals("Duomo", metro.destinationLabel)
        assertEquals(setOf("lombardia-trenord-gtfs", "test-city-metro"), first.snapshot.contributingSources.map { it.sourceId.value }.toSet())
    }

    @Test fun `a journey can start with a walk from the station to a nearby metro stop`() = runBlocking {
        val provider = provider(mapOf(railPack to railFeed, metroPack to metroFeed))
        val first = options(provider.searchJourneys(request(citta.id, "gtfs:test-city-metro:CA", time = "08:20", destinationName = "Castello"))).first()
        // The 08:30 metro reaches the same 08:50 tram: the later 08:40 one is the better option (leaves later).
        assertEquals(
            "WALK  Città Centrale 08:35-08:40 Centrale M1 | METRO M1 Centrale M1 08:40-08:46 Duomo | " +
                "TRAM 14 Duomo 08:50-09:00 Castello",
            first.summary()
        )
        assertEquals(listOf(LegMode.WALK, LegMode.METRO, LegMode.TRAM), first.legs.map { it.mode })
        assertEquals(StationId(citta.id), first.legs.first().origin)
        assertEquals(first.legs.first().scheduledDeparture, first.departure)
        assertEquals(1, first.transfers)
    }

    @Test fun `a metro stop named like a station is never that station`() = runBlocking {
        // "Città Centrale" of the metro is 1 km from the station: boarding there needs no walk only for the stop itself.
        val provider = provider(mapOf(railPack to railFeed, metroPack to metroFeed))
        val found = options(provider.searchJourneys(request(paese.id, "gtfs:test-city-metro:DU", destinationName = "Duomo")))
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { option -> option.legs.none { it.mode == LegMode.METRO && it.originLabel == "Città Centrale" } })
    }

    @Test fun `two stations a few hundred metres apart are joined by a walk`() = runBlocking {
        val provider = provider(mapOf(railPack to railFeed))
        // Paese → Città Centrale, walk 300 m to Città Est, back to Paese: a round trip only makes sense here as a test.
        val roundTrip = options(provider.searchJourneys(request(paese.id, cittaEst.id))).first()
        assertEquals(
            "TRAIN R1 1001 Paese 08:00-08:30 Città Centrale | WALK  Città Centrale 08:30-08:36 Città Est",
            roundTrip.summary()
        )
        // ceil(300 / 75) + 2 = 6 minutes.
        assertEquals(6L, Duration.between(roundTrip.legs[1].scheduledDeparture, roundTrip.legs[1].scheduledArrival).toMinutes())
    }

    @Test fun `walks longer than the limit are not used`() = runBlocking {
        val provider = provider(
            mapOf(railPack to railFeed, metroPack to metroFeed),
            GtfsJourneyPlannerSettings(largeHubRailwayCodes = emptySet(), maxWalkMeters = 150)
        )
        // Centrale M1 is 200 m away: without the walk the metro cannot be reached from the train.
        val result = provider.searchJourneys(request(paese.id, "gtfs:test-city-metro:DU", destinationName = "Duomo"))
        assertTrue(result !is ProviderResult.Success || result.value.none { option -> option.legs.any { it.mode == LegMode.METRO } })
    }

    @Test fun `walking speed and margin are settings`() = runBlocking {
        val provider = provider(
            mapOf(railPack to railFeed, metroPack to metroFeed),
            GtfsJourneyPlannerSettings(largeHubRailwayCodes = emptySet(), walkingSpeedKmh = 3.0, walkMarginMinutes = 6)
        )
        val first = options(provider.searchJourneys(request(paese.id, "gtfs:test-city-metro:DU", destinationName = "Duomo"))).first()
        // ceil(200.2 m / 50 m per minute) + 6 = 11 minutes: the 08:40 metro is missed, the 08:50 is taken.
        assertEquals("WALK  Città Centrale 08:30-08:41 Centrale M1", first.summary().split(" | ")[1])
        assertEquals("08:50", first.legs.last().scheduledDeparture.toLocalTime().toString())
    }

    @Test fun `a city pack is not read for a search outside its area`() = runBlocking {
        val opened = HashMap<String, AtomicInteger>()
        val farAway = Station("curated:F1", "Lontano", "Lontano", latitude = 41.0, longitude = 14.0)
        val farFeed = feed(
            stops = listOf(Stop("PA", "Paese", 44.73, 9.0), Stop("LO", "Lontano", 41.0, 14.0)),
            routes = listOf(Triple("R", "R9", 2)),
            trips = listOf(Trip("T9", "R", listOf("PA" to "08:00", "LO" to "13:00"), number = "9"))
        )
        val provider = EmbeddedGtfsJourneyProvider(
            adapter = EmbeddedGtfsRouteAdapter(
                openAsset = { path ->
                    opened.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                    ByteArrayInputStream(if (path == metroPack.assetPath) metroFeed else farFeed)
                },
                packs = listOf(railPack, metroPack), indexCacheCapacity = 1
            ),
            packs = listOf(railPack, metroPack),
            loadStations = { catalogue + farAway },
            settings = GtfsJourneyPlannerSettings(largeHubRailwayCodes = emptySet()),
            clock = Clock.fixed(Instant.parse("2026-09-01T06:00:00Z"), zone)
        )
        assertEquals("TRAIN R9 9 Paese 08:00-13:00 Lontano", options(provider.searchJourneys(request(paese.id, farAway.id))).single().summary())
        // Paese lies outside the 15 km of the city: the metro pack is never opened.
        assertFalse(metroPack.assetPath in opened)
    }

    @Test fun `a city stop the packs do not serve is not covered`() = runBlocking {
        val provider = provider(mapOf(railPack to railFeed, metroPack to metroFeed))
        val result = provider.searchJourneys(request(paese.id, "gtfs:test-city-metro:NOPE", destinationName = "Nowhere"))
        assertEquals(ProviderErrorKind.NOT_SUPPORTED, (result as ProviderResult.Failure).error.kind)
    }

    @Test fun `city line names follow the operators' public names`() {
        // Milan publishes its metro lines as "1".."5", Rome as "MEA", "MEB1"; tram numbers and "M1" stay as published.
        assertEquals("M1", urbanLineName(LegMode.METRO, "1"))
        assertEquals("M5", urbanLineName(LegMode.METRO, " 5 "))
        assertEquals("A", urbanLineName(LegMode.METRO, "MEA"))
        assertEquals("B1", urbanLineName(LegMode.METRO, "MEB1"))
        assertEquals("M1", urbanLineName(LegMode.METRO, "M1"))
        assertEquals("14", urbanLineName(LegMode.TRAM, "14"))
        assertEquals(null, urbanLineName(LegMode.TRAM, " "))
    }
}
