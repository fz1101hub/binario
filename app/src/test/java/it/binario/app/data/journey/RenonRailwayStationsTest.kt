package it.binario.app.data.journey

import it.binario.app.TestAssets
import it.binario.app.data.Station
import it.binario.app.data.StationAutocompleteEngine
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.gtfs.GtfsStopCodeScheme
import it.binario.app.data.gtfs.GtfsStopDirectory
import it.binario.app.data.provider.JourneySearchRequest
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
 * The Renon railway (route 160 of the South Tyrol pack): its ten stops are catalogue stations without codes,
 * NC001810-NC001819, with the names and positions the bundled STA pack publishes. The search offers them, the planner
 * reaches them on the embedded packs, and no other stop of any pack changes station because of them.
 */
class RenonRailwayStationsTest {
    private val openAsset = { path: String -> TestAssets.open(path) }
    private val stations: List<Station> by lazy { EmbeddedCuratedRailStationCatalog(openAsset).load() }
    private val renonIds = (1810..1819).map { "curated:NC00$it" }.toSet()
    private val zone = ZoneId.of("Europe/Rome")
    /** A Tuesday inside the validity of the bundled South Tyrol pack, as in [EmbeddedGtfsJourneyRealPacksTest]. */
    private val tuesday = LocalDate.of(2026, 11, 24)

    private fun station(name: String): Station = stations.single { it.name == name }

    private fun options(result: ProviderResult<List<JourneyOption>>): List<JourneyOption> =
        (result as? ProviderResult.Success)?.value ?: throw AssertionError("Expected a complete result, got $result")

    @Test fun `the ten Renon stops are catalogue stations without codes`() {
        val renon = stations.filter { it.id in renonIds }
        assertEquals(
            listOf("Collalbo", "Colle Renon", "Costalovara", "Ebenhof", "L'Assunta", "Linzbach", "Rinner", "Soprabolzano", "Stella", "Weidacher"),
            renon.map(Station::name).sorted()
        )
        assertTrue(renon.all { it.railwayCode == null && it.trenitaliaLocationId == null })
        // All within the few kilometres of the line above Bolzano.
        assertTrue(renon.all { it.latitude!! in 46.52..46.54 && it.longitude!! in 11.39..11.46 })
    }

    @Test fun `the station search finds the Renon stations`() {
        val search = StationAutocompleteEngine(stations)
        for (name in listOf("Soprabolzano", "Collalbo", "Costalovara", "Colle Renon", "L'Assunta", "Stella")) {
            assertEquals(name, search.suggest(name).first().name)
            assertTrue(search.suggest(name).first().id in renonIds)
        }
        assertEquals("curated:NC001811", search.suggest("soprab").first().id)
        assertEquals("curated:NC001819", search.suggest("collal").first().id)
    }

    @Test fun `Soprabolzano to Collalbo is a direct Renon train of the South Tyrol pack`() = runBlocking {
        val provider = EmbeddedGtfsJourneyProvider.create(openAsset)
        val result = provider.searchJourneys(
            JourneySearchRequest(
                origin = StationId(station("Soprabolzano").id),
                destination = StationId(station("Collalbo").id),
                departureAt = ZonedDateTime.of(tuesday, LocalTime.of(8, 0), zone)
            )
        )
        val found = options(result)
        val leg = found.first().legs.single()
        // Train 6041 of the pack: Soprabolzano 08:36, Collalbo 08:52.
        assertEquals("6041", leg.publicNumber)
        assertEquals(LocalTime.of(8, 36), leg.scheduledDeparture.toLocalTime())
        assertEquals(LocalTime.of(8, 52), leg.scheduledArrival.toLocalTime())
        assertEquals(StationId("curated:NC001811"), leg.origin)
        assertEquals(StationId("curated:NC001819"), leg.destination)
        assertTrue(found.all { option -> option.transfers == 0 && option.legs.all { it.runId!!.value.startsWith("alto-adige-sta-gtfs:") } })
    }

    @Test fun `the Renon stations take the Renon stops and no other stop of any pack`() = runBlocking {
        val adapter = EmbeddedGtfsRouteAdapter(openAsset = openAsset)
        val withRenon = GtfsStopStationResolver(stations)
        val withoutRenon = GtfsStopStationResolver(stations.filterNot { it.id in renonIds })
        val changed = ArrayList<String>()
        for (pack in EmbeddedGtfsPacks.all) {
            val directory = adapter.stopDirectory(pack)
            for (index in 0 until directory.size) {
                val before = resolved(withoutRenon, pack, directory, index)?.id
                val after = resolved(withRenon, pack, directory, index)?.id
                if (before != after) changed += "${pack.sourceId.value} ${directory.ids[index]} $before -> $after"
            }
        }
        val sta = "alto-adige-sta-gtfs"
        assertEquals(
            listOf(
                "$sta Parentit:22021:2526 null -> curated:NC001812",
                "$sta Parentit:22021:2704 null -> curated:NC001819",
                "$sta Parentit:22021:595 null -> curated:NC001814",
                "$sta Parentit:22021:597 null -> curated:NC001811",
                "$sta it:22021:2526:50:1648 null -> curated:NC001812",
                "$sta it:22021:2704:50:1192 null -> curated:NC001819",
                "$sta it:22021:595:50:1172 null -> curated:NC001814",
                "$sta it:22021:597:50:1170 null -> curated:NC001811",
                "$sta it:22021:598:50:1171 null -> curated:NC001810",
                "$sta it:22021:599:50:1649 null -> curated:NC001813",
                "$sta it:22021:600:50:1183 null -> curated:NC001815",
                "$sta it:22021:601:50:1181 null -> curated:NC001816",
                "$sta it:22021:602:50:1650 null -> curated:NC001817",
                "$sta it:22021:603:50:1651 null -> curated:NC001818"
            ),
            changed.sorted()
        )
    }

    /** The catalogue station of one stop, by the rules of the planner's stop identities (PackStops.build). */
    private fun resolved(resolver: GtfsStopStationResolver, pack: EmbeddedGtfsPack, directory: GtfsStopDirectory, index: Int): Station? {
        val name = directory.names[index]
        val latitude = directory.latitude(index)
        val longitude = directory.longitude(index)
        val byCode = if (pack.stopCodeScheme == GtfsStopCodeScheme.NONE) null
        else resolver.resolveCode(pack.stopCodeScheme, directory.codes[index], latitude, longitude, name)
        return byCode
            ?: pack.catalogueNameSuffixes.firstNotNullOfOrNull { suffix -> resolver.resolve("$name $suffix", latitude, longitude) }
            ?: resolver.resolve(name, latitude, longitude)
            ?: resolver.nearbyStation(name, latitude, longitude)
    }
}
