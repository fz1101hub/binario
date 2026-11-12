package it.binario.app.data.journey

import it.binario.app.TestAssets
import it.binario.app.data.Station
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
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
 * timetables (valid until December 2026; Italo until the timetable change of 13 December): a pack refresh may change
 * them, never the structure checked here.
 */
class EmbeddedGtfsJourneyRealPacksTest {
    private val openAsset = { path: String -> TestAssets.open(path) }
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

    @Test fun `Merano to Innsbruck changes at the Brenner, on the South Tyrol copy of the Austrian train`() = runBlocking {
        val provider = EmbeddedGtfsJourneyProvider.create(openAsset)
        val first = options(provider.searchJourneys(request("Merano Meran", "Innsbruck Hbf.", "08:00"))).first()
        // 5211 Brennero - Innsbruck is in the South Tyrol and in the ÖBB packs. No national pack serves Innsbruck, so
        // the packs serving it come first, and among them the Italian regional one before the foreign ones: the
        // South Tyrol copy (REX 5211) is kept, not the ÖBB one (S3 S 5211), as it was before the planned pack order.
        assertEquals("REG 17128 Merano Meran 08:16-10:22 Brennero Brenner | REX 5211 Brennero Brenner 10:33-11:11 Innsbruck Hbf.", first.summary())
        assertEquals(StationId("curated:S02001"), first.legs[0].destination)
        assertEquals(
            listOf("alto-adige-sta-gtfs", "alto-adige-sta-gtfs"),
            first.legs.map { it.runId!!.value.substringBefore(':') }
        )
    }

    @Test fun `Domodossola to Milano adds the Trenitalia regionals to the EuroCity trains, each run once`() = runBlocking {
        val found = options(EmbeddedGtfsJourneyProvider.create(openAsset).searchJourneys(request("Domodossola", "Milano Centrale", "07:00")))
        assertEquals(
            "REG 10217 Domodossola 07:25-09:10 Rho-Fiera Milano | RV 2017 Rho-Fiera Milano 09:31-09:49 Milano Centrale",
            found[0].summary()
        )
        assertEquals("EC 41 Domodossola 08:17-09:50 Milano Centrale", found[1].summary())
        // EC 41 is in the Swiss and in the Trenitalia pack: one option, from the Trenitalia pack (a national pack serves
        // both Domodossola and Milano, so the national packs come first).
        assertEquals(1, found.count { option -> option.legs.any { it.publicNumber == "41" } })
        assertEquals("trenitalia-gtfs", found[1].legs.single().runId!!.value.substringBefore(':'))
        assertTrue(found.flatMap { it.legs }.any { it.runId!!.value.startsWith("trenitalia-gtfs:") })
        assertEquals(found.map { it.id }.distinct(), found.map { it.id })
    }

    @Test fun `Merano to Roma combines the South Tyrol, Trenitalia and Italo packs`() = runBlocking {
        val found = options(EmbeddedGtfsJourneyProvider.create(openAsset).searchJourneys(request("Merano Meran", "Roma Termini", "07:00")))
        assertEquals(
            "REG 17106 Merano Meran 07:46-08:26 Bolzano Bozen | FR 9721 Bolzano Bozen 08:45-11:45 Milano Centrale | " +
                "FR 9627 Milano Centrale 11:58-15:10 Roma Termini",
            found[0].summary()
        )
        assertEquals(6, found.size)
        val sources = found.flatMap { it.legs }.filter { it.mode != LegMode.WALK }.map { it.runId!!.value.substringBefore(':') }.toSet()
        assertTrue(sources.containsAll(listOf("alto-adige-sta-gtfs", "trenitalia-gtfs", "italo-gtfs")))
        assertTrue(found.all { it.transfers <= 2 })
        // The EuroCity's pack calls its stop "Verona", a name the catalogue does not know, 14 m from Verona Porta
        // Nuova: it is that station, so there is no walk between two names of it and the change keeps the large hub's
        // 10 minutes.
        assertTrue(found.flatMap { it.legs }.none { it.mode == LegMode.WALK })
        val viaVerona = found.single { option -> option.legs.any { it.publicNumber == "81" } }
        assertEquals(
            "REG 17118 Merano Meran 10:46-11:26 Bolzano Bozen | EC 81 Bolzano Bozen 11:31-12:59 Verona Porta Nuova | " +
                "Italo 8959 Verona Porta Nuova 14:22-17:40 Roma Termini",
            viaVerona.summary()
        )
    }

    @Test fun `Venezia to Cortina takes the ItaloBus from Mestre`() = runBlocking {
        val option = options(EmbeddedGtfsJourneyProvider.create(openAsset).searchJourneys(request("Venezia S. Lucia", "Cortina D'Ampezzo", "08:00"))).single()
        assertEquals(
            "REG 17180 Venezia S. Lucia 10:04-10:15 Venezia Mestre | ItaloBus 1060 Venezia Mestre 10:20-13:10 Cortina D'Ampezzo",
            option.summary()
        )
        assertEquals(listOf(LegMode.TRAIN, LegMode.BUS), option.legs.map { it.mode })
    }

    @Test fun `Firenze to Siena on the Trenitalia regionals and Aosta to Ivrea on its buses`() = runBlocking {
        val provider = EmbeddedGtfsJourneyProvider.create(openAsset)
        val siena = options(provider.searchJourneys(request("Firenze S. M. Novella", "Siena", "08:00")))
        assertEquals("REG 18203 Firenze S. M. Novella 08:10-09:38 Siena", siena[0].summary())
        assertEquals(6, siena.size)
        val ivrea = options(provider.searchJourneys(request("Aosta", "Ivrea", "08:00")))
        assertEquals("BUS AO042 Aosta 08:16-09:26 Ivrea", ivrea[0].summary())
        assertTrue(ivrea.all { option -> option.legs.all { it.mode == LegMode.BUS && it.serviceKind == ServiceKind.BUS } })
    }

    @Test fun `Matera is covered by the FAL pack, Gallipoli by none`() = runBlocking {
        // Matera is served by FAL trains (gtfs-transit/, from the National Access Point, when the build has the pack)
        // and FrecciaLink coaches, which are not in the Trenitalia feed.
        val provider = EmbeddedGtfsJourneyProvider.create(openAsset)
        if (EmbeddedGtfsPacks.all.any { it.sourceId.value == "fal-basilicata-puglia" }) {
            val matera = provider.searchJourneys(request("Bari", "Matera Centrale FAL", "07:00"))
            val legs = options(matera).flatMap { it.legs }
            assertTrue(legs.any { it.runId?.value?.startsWith("fal-basilicata-puglia:") == true })
        }
        // Ferrovie del Sud Est publishes no timetable on the access point.
        val gallipoli = provider.searchJourneys(request("Lecce", "Gallipoli", "07:00"))
        assertEquals(ProviderErrorKind.NOT_SUPPORTED, (gallipoli as ProviderResult.Failure).error.kind)
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
        // The national packs on a fresh provider: first search, the same search cached, then other national searches.
        val national = EmbeddedGtfsJourneyProvider.create(openAsset)
        val nationalCold = timed { options(national.searchJourneys(request("Merano Meran", "Roma Termini", "07:00"))) }
        val nationalWarm = timed { options(national.searchJourneys(request("Merano Meran", "Roma Termini", "07:00"))) }
        val nationalOther = timed { options(national.searchJourneys(request("Domodossola", "Milano Centrale", "07:00"))) }
        val busCrossPack = timed { options(national.searchJourneys(request("Venezia S. Lucia", "Cortina D'Ampezzo", "08:00"))) }
        val nationalBus = timed { options(national.searchJourneys(request("Milano Centrale", "Potenza Centrale", "07:00"))) }
        println(
            "JOURNEY_PERF coldFirstSearchMs=$cold warmMs=$warm otherTimeMs=$otherTime nextDayWindowMs=$nextDayWindow " +
                "swissColdMs=$swissCold swissWarmMs=$swissWarm crossPackColdMs=$crossPackCold crossPackWarmMs=$crossPackWarm " +
                "cachedDays=${provider.cachedDaysForTest()} cachedBytes=${provider.cachedBytesForTest()} " +
                "nationalColdMs=$nationalCold nationalWarmMs=$nationalWarm nationalOtherMs=$nationalOther " +
                "busCrossPackMs=$busCrossPack nationalBusMs=$nationalBus nationalCachedBytes=${national.cachedBytesForTest()}"
        )
        // Once the day arrays are cached a search is a scan of a few tens of thousands of connections; the bound is
        // loose for slow CI machines, the measured values are reported above.
        assertTrue("warm search took $warm ms", warm < 1_000)
        assertTrue("warm Swiss search took $swissWarm ms", swissWarm < 1_000)
        assertTrue("warm cross-pack search took $crossPackWarm ms", crossPackWarm < 1_000)
        assertTrue("warm national search took $nationalWarm ms", nationalWarm < 1_000)
        assertTrue(provider.cachedBytesForTest() <= GtfsJourneyPlannerSettings().maxCachedBytes)
        assertTrue(national.cachedBytesForTest() <= GtfsJourneyPlannerSettings().maxCachedBytes)
    }
}
