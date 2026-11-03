package it.binario.app.data.journey

import it.binario.app.data.Station
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
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
import it.binario.app.data.journey.JourneyFixtures.request
import it.binario.app.data.journey.JourneyFixtures.zone
import it.binario.app.data.provider.JourneyPreferences
import it.binario.app.data.provider.JourneyRanking
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.TravelMode
import it.binario.app.data.provider.WalkingSpeed
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Resta a bordo", travel preferences, wheelchair access and the last and first departures of the offline planner. */
class EmbeddedGtfsJourneyPlannerFeaturesTest {
    private fun options(result: ProviderResult<List<JourneyOption>>): List<JourneyOption> = when (result) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Partial -> result.value
        else -> throw AssertionError("Expected journeys, got $result")
    }

    private fun JourneyOption.summary(): String = legs.joinToString(" | ") { leg ->
        "${leg.publicNumber} ${leg.originLabel} ${leg.scheduledDeparture.toLocalTime()}-${leg.scheduledArrival.toLocalTime()} ${leg.destinationLabel}"
    }

    // --- "Resta a bordo" ---

    @Test fun `trips of one GTFS block are one leg with no change and no change time`() = runBlocking {
        // Two minutes at Beta: too few for a change (5), enough to stay on board.
        val trips = listOf(
            Trip("T1", "1826", listOf("A" to "08:00", "B" to "08:30"), block = "K1"),
            Trip("T2", "5730", listOf("B" to "08:32", "D" to "09:10"), block = "K1")
        )
        val option = options(provider(mapOf(pack() to feed(trips))).searchJourneys(request(alfa, delta))).single()
        assertEquals(0, option.transfers)
        val leg = option.legs.single()
        assertEquals("1826 Alfa 08:00-09:10 Delta", option.summary())
        val continuation = leg.continuations.single()
        assertEquals("5730", continuation.publicNumber)
        assertEquals(StationId(beta.id), continuation.station)
        assertEquals("Beta", continuation.stationLabel)
        assertEquals("lombardia-trenord-gtfs:T2:$monday", continuation.runId?.value)

        // Without the block the two-minute change cannot be made.
        val unlinked = trips.map { it.copy(block = "") }
        assertTrue(options(provider(mapOf(pack() to feed(unlinked))).searchJourneys(request(alfa, delta))).isEmpty())
    }

    @Test fun `a leg left before the block continues carries no note`() = runBlocking {
        val trips = listOf(
            Trip("T1", "1826", listOf("A" to "08:00", "C" to "08:15", "B" to "08:30"), block = "K1"),
            Trip("T2", "5730", listOf("B" to "08:32", "D" to "09:10"), block = "K1")
        )
        val leg = options(provider(mapOf(pack() to feed(trips))).searchJourneys(request(alfa, gamma))).single().legs.single()
        assertTrue(leg.continuations.isEmpty())
    }

    @Test fun `a block that turns back or waits too long is not a continuation`() = runBlocking {
        // T2 turns straight back over T1's last section (Gamma → Beta): no staying on board through the terminus.
        val turning = listOf(
            Trip("T1", "100", listOf("A" to "08:00", "B" to "08:20", "C" to "08:40"), block = "K1"),
            Trip("T2", "200", listOf("C" to "08:42", "B" to "09:00", "E" to "09:30"), block = "K1")
        )
        val option = options(provider(mapOf(pack() to feed(turning))).searchJourneys(request(alfa, epsilon))).single()
        assertEquals(1, option.transfers)
        assertTrue(option.legs.all { it.continuations.isEmpty() })
        // 45 minutes at Beta: more than the 30 minutes a traveller stays on board.
        val waiting = listOf(
            Trip("T1", "100", listOf("A" to "08:00", "B" to "08:20"), block = "K1"),
            Trip("T2", "200", listOf("B" to "09:05", "D" to "09:30"), block = "K1")
        )
        val split = options(provider(mapOf(pack() to feed(waiting))).searchJourneys(request(alfa, delta))).single()
        assertEquals(1, split.transfers)
    }

    @Test fun `the same train number continuing in another pack at a shared station needs no change`() = runBlocking {
        // STA's EC 88 ends at Beta (the border), ÖBB's EC 88 leaves from there two minutes later.
        val sta = pack("alto-adige-sta-gtfs")
        val oebb = pack("oebb-gtfs")
        val staFeed = feed(
            listOf(Trip("S88", "88", listOf("A" to "08:00", "B" to "08:30"), route = "EC")),
            routes = "route_id,agency_id,route_short_name,route_long_name,route_type\nEC,T,EC,EuroCity,2\n"
        )
        val oebbFeed = feed(
            listOf(Trip("O88", "RJ 88", listOf("B" to "08:32", "D" to "09:40"), route = "RJ")),
            routes = "route_id,agency_id,route_short_name,route_long_name,route_type\nRJ,T,RJ,Railjet,2\n"
        )
        val option = options(provider(mapOf(sta to staFeed, oebb to oebbFeed)).searchJourneys(request(alfa, delta))).single()
        assertEquals(0, option.transfers)
        val leg = option.legs.single()
        assertEquals("88 Alfa 08:00-09:40 Delta", option.summary())
        val continuation = leg.continuations.single()
        assertEquals("RJ 88", continuation.publicNumber)
        assertEquals("RJ", continuation.lineLabel)
        assertEquals("oebb-gtfs:O88:$monday", continuation.runId?.value)
        assertEquals(setOf("alto-adige-sta-gtfs", "oebb-gtfs"), option.snapshot.contributingSources.map { it.sourceId.value }.toSet())

        // Another number is another train: the two minutes are a change, too short.
        val otherNumber = feed(
            listOf(Trip("O89", "RJ 89", listOf("B" to "08:32", "D" to "09:40"), route = "RJ")),
            routes = "route_id,agency_id,route_short_name,route_long_name,route_type\nRJ,T,RJ,Railjet,2\n"
        )
        assertTrue(options(provider(mapOf(sta to staFeed, oebb to otherNumber)).searchJourneys(request(alfa, delta))).isEmpty())
    }

    @Test fun `the other pack's copy may start where the first run only stops`() = runBlocking {
        // STA publishes the run on to Delta; ÖBB publishes it from Beta to Epsilon (its own section): one train.
        val sta = pack("alto-adige-sta-gtfs")
        val oebb = pack("oebb-gtfs")
        val staFeed = feed(listOf(Trip("S186", "186", listOf("A" to "07:44", "B" to "08:46", "D" to "09:36"))))
        val oebbFeed = feed(listOf(Trip("O186", "RJX 186", listOf("B" to "08:56", "E" to "09:36"))))
        val option = options(provider(mapOf(sta to staFeed, oebb to oebbFeed)).searchJourneys(request(alfa, epsilon))).single()
        assertEquals(0, option.transfers)
        assertEquals("186 Alfa 07:44-09:36 Epsilon", option.summary())
        assertEquals("RJX 186", option.legs.single().continuations.single().publicNumber)
        // Staying on the first pack's own section needs no note.
        val own = options(provider(mapOf(sta to staFeed, oebb to oebbFeed)).searchJourneys(request(alfa, delta))).single()
        assertTrue(own.legs.single().continuations.isEmpty())
    }

    @Test fun `a continuation never leaves before the first train arrives`() = runBlocking {
        val sta = pack("alto-adige-sta-gtfs")
        val oebb = pack("oebb-gtfs")
        val staFeed = feed(listOf(Trip("S88", "88", listOf("A" to "08:00", "B" to "08:30"))))
        val early = feed(listOf(Trip("O88", "88", listOf("B" to "08:28", "D" to "09:40"))))
        assertTrue(options(provider(mapOf(sta to staFeed, oebb to early)).searchJourneys(request(alfa, delta))).isEmpty())
    }

    // --- Travel preferences ---

    /** Two stations 500 m apart: Città (arrival 08:30) and Borgo (departure 08:45). */
    private val citta = Station("curated:W1", "Città", "Città", railwayCode = "S70001", latitude = 45.0, longitude = 9.0)
    private val borgo = Station("curated:W2", "Borgo", "Città", railwayCode = "S70002", latitude = 45.0, longitude = 9.00635)
    private val monte = Station("curated:W3", "Monte", "Monte", railwayCode = "S70003", latitude = 45.3, longitude = 9.0)
    private val lago = Station("curated:W4", "Lago", "Lago", railwayCode = "S70004", latitude = 45.3, longitude = 9.3)

    private fun walkingProvider(): EmbeddedGtfsJourneyProvider {
        val content = linkedMapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\nT,Ferrovie di Prova,https://example.test,Europe/Rome\n",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nM,Monte,45.3,9.0\nC,Città,45.0,9.0\nB,Borgo,45.0,9.00635\nL,Lago,45.3,9.3\n",
            "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type\nR,T,R,,2\n",
            "trips.txt" to "route_id,service_id,trip_id,trip_short_name\nR,ALL,T1,101\nR,ALL,T2,202\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "T1,08:00:00,08:00:00,M,1\nT1,08:30:00,08:30:00,C,2\nT2,08:45:00,08:45:00,B,1\nT2,09:15:00,09:15:00,L,2\n",
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
        val bytes = output.toByteArray()
        val rail = EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "gtfs/walk.zip")
        return EmbeddedGtfsJourneyProvider(
            adapter = EmbeddedGtfsRouteAdapter(openAsset = { ByteArrayInputStream(bytes) }, packs = listOf(rail), indexCacheCapacity = 1),
            packs = listOf(rail),
            loadStations = { listOf(citta, borgo, monte, lago) },
            settings = GtfsJourneyPlannerSettings(largeHubRailwayCodes = emptySet()),
            clock = Clock.fixed(Instant.parse("2026-09-01T06:00:00Z"), zone)
        )
    }

    private fun walkRequest(preferences: JourneyPreferences?) = JourneySearchRequest(
        origin = StationId(monte.id),
        destination = StationId(lago.id),
        departureAt = ZonedDateTime.of(monday, LocalTime.of(7, 30), zone),
        preferences = preferences
    )

    @Test fun `the walking speed and the longest walk decide the walks between stations`() = runBlocking {
        val provider = walkingProvider()
        // Without preferences the planner keeps its 400 m: Città and Borgo are 500 m apart.
        assertTrue(options(provider.searchJourneys(walkRequest(null))).isEmpty())
        // Normal speed, 10 minutes: up to 600 m; 500 m take ceil(500 / 75) + 2 = 9 minutes.
        val normal = options(provider.searchJourneys(walkRequest(JourneyPreferences()))).single()
        val walk = normal.legs.single { it.mode == LegMode.WALK }
        assertEquals(9L, java.time.Duration.between(walk.scheduledDeparture, walk.scheduledArrival).toMinutes())
        assertEquals(1, normal.transfers)
        // Fast: ceil(500 / 91.7) + 2 = 8 minutes.
        val fast = options(provider.searchJourneys(walkRequest(JourneyPreferences(walkingSpeed = WalkingSpeed.FAST)))).single()
        val fastWalk = fast.legs.single { it.mode == LegMode.WALK }
        assertEquals(8L, java.time.Duration.between(fastWalk.scheduledDeparture, fastWalk.scheduledArrival).toMinutes())
        // Slow (3.5 km/h) leaves 8 minutes for 466 m, and 5 minutes leave 3 minutes for 225 m: no walk of 500 m.
        assertTrue(options(provider.searchJourneys(walkRequest(JourneyPreferences(walkingSpeed = WalkingSpeed.SLOW)))).isEmpty())
        assertTrue(options(provider.searchJourneys(walkRequest(JourneyPreferences(maxWalkMinutes = 5)))).isEmpty())
        // Slow with 15 minutes: 13 minutes for 758 m; 500 m take ceil(500 / 58.3) + 2 = 11 minutes.
        val slow = options(provider.searchJourneys(walkRequest(JourneyPreferences(walkingSpeed = WalkingSpeed.SLOW, maxWalkMinutes = 15)))).single()
        val slowWalk = slow.legs.single { it.mode == LegMode.WALK }
        assertEquals(11L, java.time.Duration.between(slowWalk.scheduledDeparture, slowWalk.scheduledArrival).toMinutes())
    }

    @Test fun `an avoided kind of train is left out and another journey is searched`() = runBlocking {
        val routes = "route_id,agency_id,route_short_name,route_long_name,route_type\nFR,T,FR,Frecciarossa,2\nR,T,REG,Regionale,2\n"
        val trips = listOf(
            Trip("F1", "9501", listOf("A" to "08:00", "D" to "08:40"), route = "FR"),
            Trip("R1", "2001", listOf("A" to "08:05", "D" to "09:30"), route = "R")
        )
        val provider = provider(mapOf(pack() to feed(trips, routes = routes)))
        val any = options(provider.searchJourneys(request(alfa, delta)))
        assertEquals("9501", any.first().legs.single().publicNumber)
        val avoiding = options(provider.searchJourneys(request(alfa, delta, preferences = JourneyPreferences(avoidedModes = setOf(TravelMode.HIGH_SPEED)))))
        assertEquals(listOf("2001"), avoiding.map { it.legs.single().publicNumber })
        // A preferred kind only ranks: the high-speed train stays.
        val preferring = options(provider.searchJourneys(request(alfa, delta, preferences = JourneyPreferences(preferredModes = setOf(TravelMode.REGIONAL)))))
        assertEquals(any.map { it.id }, preferring.map { it.id })
    }

    @Test fun `travel preferences reject a kind both preferred and avoided and only offered walking limits`() {
        try {
            JourneyPreferences(preferredModes = setOf(TravelMode.METRO), avoidedModes = setOf(TravelMode.METRO))
            throw AssertionError("expected a rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            JourneyPreferences(maxWalkMinutes = 7)
            throw AssertionError("expected a rejection")
        } catch (_: IllegalArgumentException) {
        }
        val chosen = JourneyPreferences().withMode(TravelMode.ITALO, it.binario.app.data.provider.ModeChoice.AVOID)
        assertEquals(setOf(TravelMode.ITALO), chosen.avoidedModes)
        val switched = chosen.withMode(TravelMode.ITALO, it.binario.app.data.provider.ModeChoice.PREFER)
        assertEquals(setOf(TravelMode.ITALO), switched.preferredModes)
        assertTrue(switched.avoidedModes.isEmpty())
        assertEquals(JourneyRanking.FASTEST, JourneyPreferences().ranking)
    }

    // --- Wheelchair access ---

    private val accessibleStops = mapOf("A" to "1", "B" to "1", "D" to "1", "E" to "2")

    @Test fun `accessible only keeps the journeys whose trips and stops are all published accessible`() = runBlocking {
        val trips = listOf(
            // Faster, but its trip has no wheelchair data.
            Trip("F1", "100", listOf("A" to "08:00", "D" to "08:40")),
            Trip("W1", "200", listOf("A" to "08:10", "D" to "09:00"), wheelchair = "1"),
            Trip("W2", "300", listOf("A" to "08:20", "E" to "09:00"), wheelchair = "1")
        )
        val provider = provider(mapOf(pack() to feed(trips, stopWheelchair = accessibleStops)))
        val accessibleOnly = JourneyPreferences(wheelchairOnly = true)
        val option = options(provider.searchJourneys(request(alfa, delta, preferences = accessibleOnly))).single()
        assertEquals("200", option.legs.single().publicNumber)
        val wheelchair = option.legs.single().wheelchair!!
        assertTrue(wheelchair.accessible)
        // Epsilon is published as not accessible: never a destination of an accessible journey.
        assertTrue(options(provider.searchJourneys(request(alfa, epsilon, preferences = accessibleOnly))).isEmpty())
        // Without the filter every journey stays, and the unknown trip is not called accessible.
        val all = options(provider.searchJourneys(request(alfa, delta)))
        val unknown = all.first { it.legs.single().publicNumber == "100" }.legs.single().wheelchair!!
        assertFalse(unknown.vehicle)
        assertTrue(unknown.boarding && unknown.alighting)
        assertTrue(provider.hasWheelchairData(request(alfa, delta, preferences = accessibleOnly)))
    }

    @Test fun `an accessible trip that only passes an inaccessible stop is still accessible`() = runBlocking {
        val trips = listOf(Trip("W1", "200", listOf("A" to "08:10", "E" to "08:30", "D" to "09:00"), wheelchair = "1"))
        val provider = provider(mapOf(pack() to feed(trips, stopWheelchair = accessibleStops)))
        val option = options(provider.searchJourneys(request(alfa, delta, preferences = JourneyPreferences(wheelchairOnly = true)))).single()
        assertEquals("200", option.legs.single().publicNumber)
    }

    @Test fun `a platform without its own value takes the station's and unknown stops never qualify`() = runBlocking {
        val stopNames = mapOf("A" to "Alfa", "A1" to "Alfa", "D" to "Delta", "G" to "Gamma")
        val trips = listOf(
            Trip("W1", "200", listOf("A1" to "08:10", "D" to "09:00"), wheelchair = "1"),
            Trip("W2", "300", listOf("A1" to "08:10", "G" to "09:00"), wheelchair = "1")
        )
        val provider = provider(
            mapOf(pack() to feed(trips, stopNames = stopNames, parents = mapOf("A1" to "A"), stopWheelchair = mapOf("A" to "1", "D" to "1")))
        )
        val accessibleOnly = JourneyPreferences(wheelchairOnly = true)
        assertEquals("200", options(provider.searchJourneys(request(alfa, delta, preferences = accessibleOnly))).single().legs.single().publicNumber)
        // Gamma has no wheelchair_boarding: unknown is never accessible.
        assertTrue(options(provider.searchJourneys(request(alfa, gamma, preferences = accessibleOnly))).isEmpty())
        val ids = provider.wheelchairAccessibleStationIds()
        assertTrue(alfa.id in ids && delta.id in ids)
        assertFalse(gamma.id in ids)
    }

    @Test fun `a pack without wheelchair data says so`() = runBlocking {
        val provider = provider(mapOf(pack() to feed(listOf(Trip("T1", "100", listOf("A" to "08:00", "D" to "09:00"))))))
        val accessibleOnly = JourneyPreferences(wheelchairOnly = true)
        assertTrue(options(provider.searchJourneys(request(alfa, delta, preferences = accessibleOnly))).isEmpty())
        assertFalse(provider.hasWheelchairData(request(alfa, delta, preferences = accessibleOnly)))
        assertTrue(provider.wheelchairAccessibleStationIds().isEmpty())
    }

    // --- Last and first departures ---

    @Test fun `the last train of the day is marked and a search after it shows the next day's first`() = runBlocking {
        val trips = listOf(
            Trip("T1", "100", listOf("A" to "22:30", "D" to "23:10")),
            Trip("T2", "102", listOf("A" to "07:15", "D" to "08:00"))
        )
        val provider = provider(mapOf(pack() to feed(trips)))
        // 22:00 + 8 hours ends at 06:00, after the last departure of the day (22:30): the 22:30 is the last.
        val late = options(provider.searchJourneys(request(alfa, delta, time = "22:00")))
        assertEquals(listOf("100" to monday), late.map { it.legs.single().publicNumber to it.legs.single().serviceDate })
        assertTrue(late.single().lastOfServiceDay)
        // After the last train: nothing in the window, so the next day's first one follows it.
        val afterLast = options(provider.searchJourneys(request(alfa, delta, time = "23:00")))
        assertEquals(listOf(LocalDateTime.of(monday.plusDays(1), LocalTime.of(7, 15))), afterLast.map { it.departure.toLocalDateTime() })
        assertEquals(monday.plusDays(1), afterLast.single().legs.single().serviceDate)
        assertFalse(afterLast.single().lastOfServiceDay)
        // A window that ends before the day's last departure proves nothing; it is otherwise unchanged.
        val day = options(provider.searchJourneys(request(alfa, delta, time = "06:00")))
        assertEquals(listOf("102"), day.map { it.legs.single().publicNumber })
        assertFalse(day.single().lastOfServiceDay)
        // With the next day in the window the day's latest option is the last.
        val evening = options(provider.searchJourneys(request(alfa, delta, time = "21:00", maxResults = 6)))
        assertEquals(listOf(true), evening.filter { it.legs.single().serviceDate == monday }.map { it.lastOfServiceDay })
    }

    @Test fun `the static day departures give the last run per terminus and the next day's first`() = runBlocking {
        val trips = listOf(
            Trip("T1", "100", listOf("A" to "20:00", "E" to "20:40")),
            Trip("T2", "102", listOf("A" to "21:00", "B" to "21:20", "D" to "21:50")),
            Trip("T3", "104", listOf("A" to "22:30", "B" to "22:50", "D" to "23:20")),
            Trip("T4", "106", listOf("A" to "06:10", "D" to "07:00"), service = "WEEKDAY"),
            Trip("T5", "108", listOf("B" to "23:00", "A" to "23:30"))
        )
        val provider = provider(mapOf(pack() to feed(trips)))
        val station = provider.staticDayDepartures(StationId(alfa.id), null, null, null, monday)
        assertEquals(
            listOf("100 Epsilon 20:00", "104 Delta 22:30"),
            station.lastByTerminus.map { "${it.publicNumber} ${it.terminus} ${it.departure.toLocalTime()}" }
        )
        assertEquals(LocalDateTime.of(monday.plusDays(1), LocalTime.of(6, 10)), station.firstNextDay?.departure)
        val route = provider.staticDayDepartures(StationId(alfa.id), null, StationId(beta.id), null, monday)
        assertEquals(listOf("104"), route.lastByTerminus.map { it.publicNumber })
        // Friday's next day is Saturday: the weekday train does not run, the daily ones do.
        val friday = provider.staticDayDepartures(StationId(alfa.id), null, StationId(delta.id), null, monday.plusDays(4))
        assertEquals(LocalDateTime.of(monday.plusDays(5), LocalTime.of(21, 0)), friday.firstNextDay?.departure)
        val unknown = provider.staticDayDepartures(StationId("curated:none"), null, null, null, monday)
        assertTrue(unknown.lastByTerminus.isEmpty())
        assertNull(unknown.firstNextDay)
    }
}
