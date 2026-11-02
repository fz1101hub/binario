package it.binario.app.data.journey

import it.binario.app.data.Station
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderResult
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner on the packs and the catalogue shipped in the APK. The expected trains belong to the bundled
 * timetables (valid until December 2026): a pack refresh may change them, never the structure checked here.
 */
class EmbeddedGtfsJourneyRealPacksTest {
    private val openAsset = { path: String -> File("src/main/assets", path).inputStream() }
    private val stations: List<Station> by lazy { EmbeddedCuratedRailStationCatalog(openAsset).load() }
    private val zone = ZoneId.of("Europe/Rome")
    /** A Tuesday inside the validity of every bundled pack. */
    private val tuesday = LocalDate.of(2026, 11, 10)

    private fun station(name: String): Station = stations.single { it.name == name }

    private fun request(from: String, to: String, time: String) = JourneySearchRequest(
        origin = StationId(station(from).id),
        destination = StationId(station(to).id),
        departureAt = ZonedDateTime.of(tuesday, LocalTime.parse(time), zone)
    )

    private fun options(result: ProviderResult<List<JourneyOption>>): List<JourneyOption> =
        (result as? ProviderResult.Success)?.value ?: throw AssertionError("Expected a complete result, got $result")

    private fun JourneyOption.summary(): String = legs.joinToString(" | ") { leg ->
        "${leg.lineLabel} ${leg.publicNumber} ${leg.originLabel} ${leg.scheduledDeparture.toLocalTime()}-" +
            "${leg.scheduledArrival.toLocalTime()} ${leg.destinationLabel}"
    }

    @Test fun `Lodi to Lecco needs one change in Milan on the Trenord pack`() = runBlocking {
        val provider = EmbeddedGtfsJourneyProvider.create(openAsset)
        val found = options(provider.searchJourneys(request("Lodi", "Lecco", "08:00")))
        assertEquals(6, found.size)
        assertEquals("R38 10894 Lodi 08:09-08:50 Milano Greco Pirelli | S8 24828 Milano Greco Pirelli 08:59-09:54 Lecco", found[0].summary())
        assertEquals(1, found[0].transfers)
        assertEquals(StationId("curated:S01326"), found[0].legs[0].destination)
        // Milano Centrale is a large hub: the change there keeps at least ten minutes.
        val viaCentrale = found.first { option -> option.legs.any { it.destinationLabel == "Milano Centrale" } }
        assertEquals("RE11 2158 Lodi 08:14-08:45 Milano Centrale | RE8 2818 Milano Centrale 09:20-09:59 Lecco", viaCentrale.summary())
        assertTrue(found.all { option -> option.legs.all { it.runId!!.value.startsWith("lombardia-trenord-gtfs:") } })
        assertEquals(found.sortedBy { it.departure }, found)
        assertTrue(found.all { it.transfers in 1..2 })
    }

    @Test fun `Brunico to Merano changes at Fortezza and Bolzano on the South Tyrol pack`() = runBlocking {
        val provider = EmbeddedGtfsJourneyProvider.create(openAsset, EmbeddedGtfsPacks.all.filter { it.sourceId.value == "alto-adige-sta-gtfs" })
        val first = options(provider.searchJourneys(request("Brunico Bruneck", "Merano Meran", "08:00"))).first()
        assertEquals(
            "REG 1838 Brunico Bruneck 08:01-08:40 Fortezza Franzensfeste | REG 17167 Fortezza Franzensfeste 08:45-09:29 Bolzano Bozen | " +
                "REG 17119 Bolzano Bozen 09:35-10:15 Merano Meran",
            first.summary()
        )
        assertEquals(2, first.transfers)
    }

    @Test fun `Merano to Innsbruck changes between the South Tyrol and the OeBB packs at the Brenner`() = runBlocking {
        val provider = EmbeddedGtfsJourneyProvider.create(openAsset)
        val first = options(provider.searchJourneys(request("Merano Meran", "Innsbruck Hbf.", "08:00"))).first()
        assertEquals("REG 17128 Merano Meran 08:16-10:22 Brennero Brenner | S3 S 5211 Brennero Brenner 10:33-11:11 Innsbruck Hbf.", first.summary())
        assertEquals(StationId("curated:S02001"), first.legs[0].destination)
        assertEquals(
            listOf("alto-adige-sta-gtfs", "oebb-gtfs"),
            first.legs.map { it.runId!!.value.substringBefore(':') }
        )
    }

    @Test fun `search times on the bundled packs`() = runBlocking {
        val provider = EmbeddedGtfsJourneyProvider.create(openAsset)
        fun timed(block: suspend () -> Unit): Long = runBlocking {
            val started = System.nanoTime()
            block()
            (System.nanoTime() - started) / 1_000_000
        }
        val cold = timed { options(provider.searchJourneys(request("Lodi", "Lecco", "08:00"))) }
        val warm = timed { options(provider.searchJourneys(request("Lodi", "Lecco", "08:00"))) }
        val otherTime = timed { options(provider.searchJourneys(request("Lodi", "Lecco", "13:00"))) }
        val nextDayWindow = timed { options(provider.searchJourneys(request("Lodi", "Lecco", "21:00"))) }
        val swissCold = timed { options(provider.searchJourneys(request("Milano Centrale", "Mendrisio", "08:00"))) }
        val swissWarm = timed { options(provider.searchJourneys(request("Milano Centrale", "Mendrisio", "09:00"))) }
        val crossPackCold = timed { options(provider.searchJourneys(request("Merano Meran", "Innsbruck Hbf.", "08:00"))) }
        val crossPackWarm = timed { options(provider.searchJourneys(request("Bolzano Bozen", "Innsbruck Hbf.", "08:00"))) }
        println(
            "JOURNEY_PERF coldFirstSearchMs=$cold warmMs=$warm otherTimeMs=$otherTime nextDayWindowMs=$nextDayWindow " +
                "swissColdMs=$swissCold swissWarmMs=$swissWarm crossPackColdMs=$crossPackCold crossPackWarmMs=$crossPackWarm " +
                "cachedDays=${provider.cachedDaysForTest()} cachedBytes=${provider.cachedBytesForTest()}"
        )
        // Once the day arrays are cached a search is a scan of a few tens of thousands of connections; the bound is
        // loose for slow CI machines, the measured values are reported above.
        assertTrue("warm search took $warm ms", warm < 1_000)
        assertTrue("warm Swiss search took $swissWarm ms", swissWarm < 1_000)
        assertTrue("warm cross-pack search took $crossPackWarm ms", crossPackWarm < 1_000)
        assertTrue(provider.cachedBytesForTest() <= GtfsJourneyPlannerSettings().maxCachedBytes)
    }
}
