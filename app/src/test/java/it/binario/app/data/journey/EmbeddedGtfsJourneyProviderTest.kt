package it.binario.app.data.journey

import it.binario.app.data.Station
import it.binario.app.data.core.DataWarning
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.SourceId
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
import it.binario.app.data.journey.JourneyFixtures.request
import it.binario.app.data.journey.JourneyFixtures.zeta
import it.binario.app.data.provider.JourneyEndpoint
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import java.time.LocalDateTime
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EmbeddedGtfsJourneyProviderTest {
    private fun options(result: ProviderResult<List<JourneyOption>>): List<JourneyOption> = when (result) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Partial -> result.value
        else -> throw AssertionError("Expected journeys, got $result")
    }

    private fun JourneyOption.summary(): String = legs.joinToString(" | ") { leg ->
        "${leg.publicNumber} ${leg.originLabel} ${leg.scheduledDeparture.toLocalTime()}-${leg.scheduledArrival.toLocalTime()} ${leg.destinationLabel}"
    }

    @Test fun `a direct train is one leg with no change and scheduled-only provenance`() = runBlocking {
        val provider = provider(mapOf(pack() to feed(listOf(Trip("T1", "100", listOf("A" to "08:00", "B" to "08:30", "D" to "09:30"))))))
        val result = provider.searchJourneys(request(alfa, delta))
        val option = options(result).single()
        assertTrue(result is ProviderResult.Success)
        assertEquals(0, option.transfers)
        assertEquals("100 Alfa 08:00-09:30 Delta", option.summary())
        assertEquals(StationId(alfa.id), option.legs.single().origin)
        assertEquals(StationId(delta.id), option.legs.single().destination)
        assertEquals("RE11", option.legs.single().lineLabel)
        assertEquals("Ferrovie di Prova", option.legs.single().operatorName)
        assertEquals(ServiceKind.REGIONAL, option.legs.single().serviceKind)
        assertEquals("lombardia-trenord-gtfs:T1:$monday", option.legs.single().runId?.value)
        assertTrue(DataWarning.STATIC_ONLY in option.snapshot.warnings)
        assertTrue(DataWarning.REALTIME_UNAVAILABLE in option.snapshot.warnings)
        assertEquals(setOf("lombardia-trenord-gtfs"), option.snapshot.contributingSources.map { it.sourceId.value }.toSet())
    }

    @Test fun `one change at a shared station respects the transfer time`() = runBlocking {
        val provider = provider(mapOf(pack() to feed(listOf(
            Trip("T2", "200", listOf("A" to "08:10", "B" to "08:40")),
            Trip("T3", "300", listOf("B" to "08:50", "E" to "09:20"))
        ))))
        val option = options(provider.searchJourneys(request(alfa, epsilon))).single()
        assertEquals(1, option.transfers)
        assertEquals("200 Alfa 08:10-08:40 Beta | 300 Beta 08:50-09:20 Epsilon", option.summary())
        assertEquals(StationId(beta.id), option.legs[0].destination)
        assertEquals(StationId(beta.id), option.legs[1].origin)
    }

    @Test fun `two changes are found only when the request allows them`() = runBlocking {
        val provider = provider(mapOf(pack() to feed(listOf(
            Trip("T2", "200", listOf("A" to "08:10", "B" to "08:40")),
            Trip("T4", "400", listOf("B" to "08:50", "C" to "09:10")),
            Trip("T5", "500", listOf("C" to "09:20", "E" to "09:50"))
        ))))
        val option = options(provider.searchJourneys(request(alfa, epsilon, maxTransfers = 2))).single()
        assertEquals(2, option.transfers)
        assertEquals("200 Alfa 08:10-08:40 Beta | 400 Beta 08:50-09:10 Gamma | 500 Gamma 09:20-09:50 Epsilon", option.summary())
        assertTrue(options(provider.searchJourneys(request(alfa, epsilon, maxTransfers = 1))).isEmpty())
        assertTrue(options(provider.searchJourneys(request(alfa, epsilon, maxTransfers = 0))).isEmpty())
    }

    @Test fun `a connection leaving before arrival plus transfer time is never used`() = runBlocking {
        val trips = listOf(
            Trip("T2", "200", listOf("A" to "08:10", "B" to "08:40")),
            Trip("T3", "300", listOf("B" to "08:44", "E" to "09:10")),
            Trip("T6", "600", listOf("B" to "08:48", "E" to "09:15")),
            Trip("T7", "700", listOf("B" to "09:00", "E" to "09:30"))
        )
        val ordinary = provider(mapOf(pack() to feed(trips)))
        // 4 minutes are too few at an ordinary station (5): the 08:44 is missed, the 08:48 is taken.
        assertEquals("200 Alfa 08:10-08:40 Beta | 600 Beta 08:48-09:15 Epsilon",
            options(ordinary.searchJourneys(request(alfa, epsilon))).single().summary())

        val hub = provider(mapOf(pack() to feed(trips)), GtfsJourneyPlannerSettings(largeHubRailwayCodes = setOf(beta.railwayCode!!)))
        // Beta as a large hub needs 10 minutes: only the 09:00 can be reached.
        assertEquals("200 Alfa 08:10-08:40 Beta | 700 Beta 09:00-09:30 Epsilon",
            options(hub.searchJourneys(request(alfa, epsilon))).single().summary())

        val strict = provider(mapOf(pack() to feed(trips)), GtfsJourneyPlannerSettings(defaultTransferMinutes = 21, largeHubRailwayCodes = emptySet()))
        assertTrue(options(strict.searchJourneys(request(alfa, epsilon))).isEmpty())
    }

    @Test fun `staying on the same train needs no transfer time`() = runBlocking {
        val provider = provider(mapOf(pack() to feed(listOf(
            Trip("T1", "100", listOf("A" to "08:00", "B" to "08:30", "C" to "08:31", "D" to "09:00"))
        ))))
        val option = options(provider.searchJourneys(request(alfa, delta))).single()
        assertEquals(0, option.transfers)
    }

    @Test fun `a stop whose time goes back is skipped and never travelled to`() = runBlocking {
        val provider = provider(mapOf(pack() to feed(listOf(
            Trip("T1", "100", listOf("A" to "08:00", "B" to "08:30", "C" to "08:10", "D" to "09:00"))
        ))))
        assertEquals("100 Alfa 08:00-09:00 Delta", options(provider.searchJourneys(request(alfa, delta))).single().summary())
        assertTrue(options(provider.searchJourneys(request(alfa, gamma))).isEmpty())
    }

    @Test fun `after midnight runs of the same and of the next service day are joined`() = runBlocking {
        val provider = provider(mapOf(pack() to feed(listOf(
            // Monday service day, past midnight in GTFS time.
            Trip("N1", "901", listOf("A" to "23:50", "B" to "24:20")),
            // Tuesday service day, early morning.
            Trip("N2", "902", listOf("B" to "00:35", "E" to "01:05"))
        ))))
        val late = options(provider.searchJourneys(request(alfa, epsilon, time = "23:30"))).single()
        assertEquals("901 Alfa 23:50-00:20 Beta | 902 Beta 00:35-01:05 Epsilon", late.summary())
        assertEquals(LocalDateTime.of(2026, 9, 8, 0, 20), late.legs[0].scheduledArrival.toLocalDateTime())
        assertEquals(LocalDateTime.of(2026, 9, 8, 1, 5), late.arrival.toLocalDateTime())
        assertEquals("lombardia-trenord-gtfs:N2:2026-09-08", late.legs[1].runId?.value)
    }

    @Test fun `a previous service day run past midnight is boarded on the requested day`() = runBlocking {
        val provider = provider(mapOf(pack() to feed(
            listOf(Trip("N3", "903", listOf("A" to "25:40", "D" to "26:10"), service = "SUN")),
            calendar = "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
                "SUN,0,0,0,0,0,0,1,20260101,20261231\n"
        )))
        val option = options(provider.searchJourneys(request(alfa, delta, time = "01:00"))).single()
        assertEquals(LocalDateTime.of(2026, 9, 7, 1, 40), option.departure.toLocalDateTime())
        assertEquals("lombardia-trenord-gtfs:N3:2026-09-06", option.legs.single().runId?.value)
    }

    @Test fun `calendar exceptions remove and add runs on the requested day`() = runBlocking {
        val provider = provider(mapOf(pack() to feed(
            listOf(
                Trip("W1", "101", listOf("A" to "08:00", "D" to "09:00"), service = "WEEKDAY"),
                Trip("X1", "111", listOf("A" to "10:00", "D" to "11:00"), service = "EXTRA")
            ),
            calendarDates = "service_id,date,exception_type\nWEEKDAY,20260907,2\nEXTRA,20260907,1\n"
        )))
        val monday = options(provider.searchJourneys(request(alfa, delta))).map { it.legs.single().publicNumber }
        assertEquals(listOf("111"), monday)
        val tuesday = options(provider.searchJourneys(request(alfa, delta, date = JourneyFixtures.monday.plusDays(1))))
            .map { it.legs.single().publicNumber }
        assertEquals(listOf("101"), tuesday)
    }

    @Test fun `no solution is an empty success and an unknown station is not covered`() = runBlocking {
        val provider = provider(mapOf(pack() to feed(listOf(
            Trip("T1", "100", listOf("A" to "08:00", "D" to "09:00")),
            // No train goes to Zeta: one only leaves from it.
            Trip("T9", "900", listOf("Z" to "06:00", "A" to "06:30"))
        ), stopNames = mapOf("A" to "Alfa", "D" to "Delta", "Z" to "Zeta"))))
        val none = provider.searchJourneys(request(alfa, zeta))
        assertTrue(none is ProviderResult.Success)
        assertTrue(options(none).isEmpty())
        // Gamma is in the catalogue but in no pack.
        val uncovered = provider.searchJourneys(request(alfa, gamma))
        assertEquals(ProviderErrorKind.NOT_SUPPORTED, (uncovered as ProviderResult.Failure).error.kind)
        // An id unknown to the catalogue and without names cannot be matched.
        val unknown = provider.searchJourneys(request(alfa, Station("custom:x", "?", "?")).copy(destination = StationId("custom:x")))
        assertTrue(unknown is ProviderResult.Failure)
    }

    @Test fun `options are sorted by departure without dominated options or repeated trains`() = runBlocking {
        val provider = provider(mapOf(pack() to feed(listOf(
            Trip("D1", "1", listOf("A" to "08:00", "D" to "10:00")),
            // Faster with one change: not dominated by the direct train (it leaves later and arrives earlier).
            Trip("C1", "2", listOf("A" to "08:15", "B" to "08:45")),
            Trip("C2", "3", listOf("B" to "08:55", "D" to "09:40")),
            // Leaves earlier and arrives later than the 08:15 option with the same changes: dominated.
            Trip("C3", "4", listOf("A" to "08:05", "B" to "08:30")),
            Trip("C4", "5", listOf("B" to "08:52", "D" to "09:45")),
            Trip("D2", "6", listOf("A" to "09:00", "D" to "11:00")),
            Trip("D3", "7", listOf("A" to "10:00", "D" to "12:00"))
        ))))
        val found = options(provider.searchJourneys(request(alfa, delta, time = "07:55", maxResults = 3)))
        assertEquals(listOf("1", "2|3", "6"), found.map { option -> option.legs.joinToString("|") { it.publicNumber.orEmpty() } })
        assertEquals(found.sortedBy { it.departure }, found)
        assertEquals(found.size, found.map { option -> option.legs.map { it.runId } }.distinct().size)
        // The dominated combination (08:05 then 08:52) never appears, whatever the limit.
        val all = options(provider.searchJourneys(request(alfa, delta, time = "07:55", maxResults = 20)))
        assertFalse(all.any { option -> option.legs.any { it.publicNumber == "4" } })
        assertEquals(listOf("1", "2|3", "6", "7"), all.map { option -> option.legs.joinToString("|") { it.publicNumber.orEmpty() } })
    }

    @Test fun `stops of different packs and platforms resolving to one catalogue station are one transfer point`() = runBlocking {
        val first = pack("lombardia-trenord-gtfs")
        val second = pack("ch-otd-gtfs")
        val provider = provider(mapOf(
            first to feed(listOf(Trip("L1", "25107", listOf("A" to "08:00", "B" to "08:30"))),
                stopNames = mapOf("A" to "Alfa", "B" to "Beta")),
            // Platform 3 of "Beta" in the second pack, under a parent station with a long STA-style name.
            second to feed(listOf(Trip("S1", "IR 21", listOf("B3" to "08:40", "E" to "09:10"))),
                stopNames = mapOf("PB" to "Beta, Stazione di Beta", "B3" to "Beta 3", "E" to "Epsilon"),
                parents = mapOf("B3" to "PB"))
        ))
        val option = options(provider.searchJourneys(request(alfa, epsilon))).single()
        assertEquals("25107 Alfa 08:00-08:30 Beta | IR 21 Beta 08:40-09:10 Epsilon", option.summary())
        assertEquals(setOf("lombardia-trenord-gtfs", "ch-otd-gtfs"), option.snapshot.contributingSources.map { it.sourceId.value }.toSet())
    }

    @Test fun `no boarding where pickup is forbidden and no alighting where drop off is forbidden`() = runBlocking {
        val provider = provider(mapOf(pack() to feed(listOf(
            Trip("T1", "100", listOf("A" to "08:00", "B" to "08:30", "D" to "09:00"), noDropOff = setOf("B")),
            Trip("T2", "200", listOf("B" to "08:40", "E" to "09:10")),
            Trip("T3", "300", listOf("A" to "08:10", "B" to "08:35", "D" to "09:05"), noPickup = setOf("A"))
        ))))
        assertTrue(options(provider.searchJourneys(request(alfa, epsilon))).isEmpty())
        assertEquals(listOf("100"), options(provider.searchJourneys(request(alfa, delta))).map { it.legs.single().publicNumber })
    }

    @Test fun `a replacement bus is a leg of its own and counts as a change`() = runBlocking {
        val provider = provider(mapOf(pack() to feed(listOf(
            Trip("B1", "B1", listOf("A" to "08:00", "B" to "08:30"), route = "BUS"),
            Trip("T2", "200", listOf("B" to "08:40", "E" to "09:10"))
        ))))
        val option = options(provider.searchJourneys(request(alfa, epsilon))).single()
        assertEquals(listOf(LegMode.REPLACEMENT_BUS, LegMode.TRAIN), option.legs.map { it.mode })
        assertEquals(ServiceKind.REPLACEMENT_BUS, option.legs.first().serviceKind)
        assertEquals(1, option.transfers)
        assertNull("The line equal to the number is not repeated", option.legs.first().lineLabel)
    }

    @Test fun `a bus of a pack of scheduled coaches is a bus leg, not a replacement bus`() = runBlocking {
        val coaches = EmbeddedGtfsPack(SourceId("italo-gtfs"), "gtfs/italo-gtfs.zip", busRoutesAreScheduledCoaches = true)
        val provider = provider(mapOf(coaches to feed(listOf(
            Trip("T1", "8959", listOf("A" to "08:00", "B" to "08:30")),
            Trip("B1", "1060", listOf("B" to "08:40", "E" to "10:10"), route = "BUS")
        ))))
        val option = options(provider.searchJourneys(request(alfa, epsilon))).single()
        assertEquals(listOf(LegMode.TRAIN, LegMode.BUS), option.legs.map { it.mode })
        assertEquals(ServiceKind.BUS, option.legs[1].serviceKind)
        assertEquals("B1", option.legs[1].lineLabel)
        // A train to bus transfer is a change, with the station's transfer time.
        assertEquals(1, option.transfers)
    }

    @Test fun `the same run in two packs is scanned once and staying on board across both copies is no change`() = runBlocking {
        // The ÖBB pack (preferred) publishes EC 85 Alfa-Beta-Gamma, the Trenitalia pack Beta-Gamma-Delta: Beta-Gamma
        // is the same connection, so Alfa to Delta is one train, boarded in one pack and left in the other.
        val provider = provider(mapOf(
            pack("trenitalia-gtfs") to feed(listOf(
                Trip("TI85", "85", listOf("B" to "09:00", "C" to "09:30", "D" to "10:00")),
                Trip("TI90", "90", listOf("A" to "08:05", "C" to "09:40"))
            )),
            pack("oebb-gtfs") to feed(listOf(Trip("OBB85", "EC 85", listOf("A" to "08:00", "B" to "09:00", "C" to "09:30"))))
        ))
        val through = options(provider.searchJourneys(request(alfa, delta))).single()
        assertEquals(0, through.transfers)
        val leg = through.legs.single()
        assertEquals("EC 85", leg.publicNumber)
        assertEquals("oebb-gtfs:OBB85:$monday", leg.runId?.value)
        assertEquals(LocalDateTime.of(monday, java.time.LocalTime.of(10, 0)), leg.scheduledArrival.toLocalDateTime())
        assertEquals(setOf("oebb-gtfs", "trenitalia-gtfs"), through.snapshot.contributingSources.map { it.sourceId.value }.toSet())

        // Between two stations both copies serve, the run is one option, the preferred pack's.
        val shared = options(provider.searchJourneys(request(beta, gamma)))
        assertEquals(listOf("oebb-gtfs"), shared.map { it.legs.single().runId!!.value.substringBefore(':') })
    }

    @Test fun `runs of two packs with the same number but other times stay two trains`() = runBlocking {
        val provider = provider(mapOf(
            pack("trenitalia-gtfs") to feed(listOf(Trip("TI85", "85", listOf("B" to "09:05", "C" to "09:35")))),
            pack("oebb-gtfs") to feed(listOf(Trip("OBB85", "85", listOf("B" to "09:00", "C" to "09:30"))))
        ))
        val found = options(provider.searchJourneys(request(beta, gamma)))
        assertEquals(listOf("oebb-gtfs", "trenitalia-gtfs"), found.map { it.legs.single().runId!!.value.substringBefore(':') })
    }

    @Test fun `run numbers compare by their digits across packs`() {
        assertEquals("85", EmbeddedGtfsJourneyProvider.runNumberKey("EC 85"))
        assertEquals("2153", EmbeddedGtfsJourneyProvider.runNumberKey("RE_11 - 2153"))
        assertEquals("1060", EmbeddedGtfsJourneyProvider.runNumberKey("1060"))
        assertEquals("42", EmbeddedGtfsJourneyProvider.runNumberKey("AO042"))
        assertNull(EmbeddedGtfsJourneyProvider.runNumberKey("IT::VehicleJourney:railTRENITALIA:0083_10083_0_v_1-5655"))
        assertNull(EmbeddedGtfsJourneyProvider.runNumberKey(" "))
    }

    @Test fun `a station picked from a pack and not in the catalogue is matched by its stop id and name`() = runBlocking {
        val provider = provider(mapOf(pack("ch-otd-gtfs") to feed(
            listOf(Trip("S1", "IR 21", listOf("Q:0:1" to "08:00", "B" to "08:30"))),
            stopNames = mapOf("Q:0:1" to "Quarto HB", "B" to "Beta")
        )))
        val request = request(alfa, beta).copy(
            origin = StationId("gtfs:ch-otd-gtfs:Q:0:1"),
            originEndpoint = JourneyEndpoint("Quarto HB")
        )
        val option = options(provider.searchJourneys(request)).single()
        assertEquals("Quarto HB", option.legs.single().originLabel)
        assertEquals(StationId("gtfs:ch-otd-gtfs:Q:0:1"), option.origin)
    }

    @Test fun `packs expired before the requested day are reported as stale, never as results`() = runBlocking {
        val provider = provider(mapOf(pack(lastServiceDate = monday.minusDays(1)) to feed(listOf(
            Trip("T1", "100", listOf("A" to "08:00", "D" to "09:00"))
        ))))
        val result = provider.searchJourneys(request(alfa, delta))
        assertTrue(result is ProviderResult.Partial)
        result as ProviderResult.Partial
        assertTrue(result.value.isEmpty())
        assertEquals(setOf(ProviderWarning.STALE_SOURCE), result.warnings)
    }

    @Test fun `the search window bounds departures`() = runBlocking {
        val provider = provider(mapOf(pack() to feed(listOf(
            Trip("T0", "90", listOf("A" to "08:30", "D" to "09:30")),
            Trip("T1", "100", listOf("A" to "17:00", "D" to "18:00"))
        ))), GtfsJourneyPlannerSettings(searchWindowMinutes = 120, largeHubRailwayCodes = emptySet()))
        assertEquals(listOf("90"), options(provider.searchJourneys(request(alfa, delta, time = "08:00"))).map { it.legs.single().publicNumber })
        assertEquals(listOf("100"), options(provider.searchJourneys(request(alfa, delta, time = "15:30"))).map { it.legs.single().publicNumber })
        // Nothing in the window: the first train after it follows, alone.
        assertEquals(listOf("100"), options(provider.searchJourneys(request(alfa, delta, time = "10:00"))).map { it.legs.single().publicNumber })
    }

    @Test fun `day connection arrays are cached and bounded`() = runBlocking {
        val bytes = feed(listOf(Trip("T1", "100", listOf("A" to "08:00", "D" to "09:00"))))
        val provider = provider(mapOf(pack() to bytes), GtfsJourneyPlannerSettings(maxCachedDays = 2, largeHubRailwayCodes = emptySet()))
        provider.searchJourneys(request(alfa, delta))
        assertEquals(2, provider.cachedDaysForTest())
        provider.searchJourneys(request(alfa, delta, date = monday.plusDays(3)))
        assertEquals(2, provider.cachedDaysForTest())
        assertTrue(provider.cachedBytesForTest() > 0)
    }

    @Test fun `cancelling a running search stops the provider`() = runBlocking {
        val bytes = feed(listOf(Trip("T1", "100", listOf("A" to "08:00", "D" to "09:00"))))
        val started = CompletableDeferred<Unit>()
        val provider = EmbeddedGtfsJourneyProvider(
            adapter = EmbeddedGtfsRouteAdapter(openAsset = { ByteArrayInputStream(bytes) }, packs = listOf(pack()), indexCacheCapacity = 1),
            packs = listOf(pack()),
            loadStations = {
                started.complete(Unit)
                awaitCancellation()
            }
        )
        val search = launch(Dispatchers.Default) { provider.searchJourneys(request(alfa, delta)) }
        withTimeout(5_000) { started.await() }
        search.cancelAndJoin()
        assertTrue(search.isCancelled)
    }

    @Test fun `the scan checks for cancellation while it runs`() {
        val size = 10_000
        val timetable = ConnectionScanTimetable(
            nodeCount = 2, tripCount = size, transferMinutes = IntArray(2) { 5 },
            departureNode = IntArray(size), arrivalNode = IntArray(size) { 1 },
            departureMinute = IntArray(size) { it / 10 }, arrivalMinute = IntArray(size) { it / 10 + 1 },
            trip = IntArray(size) { it }, boarding = ByteArray(size)
        )
        var checks = 0
        try {
            ConnectionScanPlanner(timetable).plan(
                ConnectionScanQuery(booleanArrayOf(true, false), booleanArrayOf(false, true), 0, 2_000, 2, 6, 32)
            ) { if (++checks > 1) throw CancellationException("cancelled") }
            fail("The scan must stop when cancelled")
        } catch (_: CancellationException) {
        }
        assertTrue(checks > 1)
    }
}
