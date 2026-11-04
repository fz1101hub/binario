package it.binario.app.data.journey

import it.binario.app.TestAssets
import it.binario.app.data.AirportRoute
import it.binario.app.data.AirportRoutes
import it.binario.app.data.Station
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.provider.JourneyEndpoint
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderResult
import java.io.InputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Collegamenti con gli aeroporti» on the catalogue and the packs shipped in the APK: every entry has both stations in
 * the curated catalogue, and the offline planner finds a direct train each way on a weekday with the packs of a store
 * build (no personal pack). A pack refresh may change the trains, never this check.
 */
class AirportRoutesTest {
    private val openAsset: (String) -> InputStream = { path -> TestAssets.open(path) }
    private val stations: Map<String, Station> by lazy { EmbeddedCuratedRailStationCatalog(openAsset).load().associateBy(Station::id) }
    private val zone = ZoneId.of("Europe/Rome")
    /** A Tuesday inside the validity of every bundled pack. */
    private val weekday = LocalDate.of(2026, 11, 10)
    private val storePacks: List<EmbeddedGtfsPack> = EmbeddedGtfsPacks.forBuild(personalSources = false)
    private val provider by lazy {
        EmbeddedGtfsJourneyProvider.create(openAsset, storePacks.filterNot(EmbeddedGtfsPack::isUrban) + storePacks.filter(EmbeddedGtfsPack::isUrban))
    }

    private fun request(from: Station, to: Station, time: String) = JourneySearchRequest(
        origin = StationId(from.id),
        destination = StationId(to.id),
        departureAt = ZonedDateTime.of(weekday, LocalTime.parse(time), zone),
        originEndpoint = JourneyEndpoint(from.name, aliases = from.aliases, railwayCode = from.railwayCode),
        destinationEndpoint = JourneyEndpoint(to.name, aliases = to.aliases, railwayCode = to.railwayCode)
    )

    private fun directTrains(route: AirportRoute, from: Station, to: Station): List<JourneyOption> = runBlocking {
        val result = provider.searchJourneys(request(from, to, "08:00"))
        val options = (result as? ProviderResult.Success)?.value ?: throw AssertionError("${from.name} -> ${to.name}: $result")
        options.filter { option ->
            val vehicles = option.legs.filter { it.mode != LegMode.WALK }
            vehicles.size == 1 && vehicles.single().mode == LegMode.TRAIN &&
                route.packs.any { pack -> vehicles.single().runId?.value?.startsWith("$pack:") == true }
        }.also { found ->
            println("AIRPORT ${from.name} -> ${to.name}: ${found.size} direct of ${options.size}, first ${found.firstOrNull()?.legs?.first { it.mode == LegMode.TRAIN }?.let { "${it.publicNumber} ${it.scheduledDeparture.toLocalTime()}" }}")
        }
    }

    @Test fun `the search day is a weekday`() {
        assertTrue(weekday.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
    }

    @Test fun `both stations of every entry are in the curated catalogue, with distinct names`() {
        AirportRoutes.all.forEach { route ->
            val city = stations[route.cityStationId]
            val airport = stations[route.airportStationId]
            assertTrue("${route.cityStationId} missing", city != null)
            assertTrue("${route.airportStationId} missing", airport != null)
            assertTrue(city!!.name != airport!!.name)
        }
        assertEquals(AirportRoutes.all.size, AirportRoutes.links(AirportRoutes.all, stations::get).size)
        assertEquals(
            listOf(
                "Roma Termini" to "Fiumicino Aeroporto", "Milano Centrale" to "Malpensa Aeroporto", "Milano Nord Cadorna" to "Malpensa Aeroporto",
                "Bari" to "Bari Aeroporto",
                "Palermo Centrale" to "Punta Raisi", "Cagliari" to "Elmas Aeroporto", "Trieste Centrale" to "Trieste Airport",
                "Torino Porta Susa" to "Caselle Torinese Aeroporto"
            ),
            AirportRoutes.links(AirportRoutes.all, stations::get).map { it.city.name to it.airport.name }
        )
    }

    @Test fun `the offline planner finds a direct train each way on a weekday with the packs of a store build`() {
        AirportRoutes.forBuild(storePacks).forEach { route ->
            val city = stations.getValue(route.cityStationId)
            val airport = stations.getValue(route.airportStationId)
            assertTrue("${city.name} -> ${airport.name}", directTrains(route, city, airport).isNotEmpty())
            assertTrue("${airport.name} -> ${city.name}", directTrains(route, airport, city).isNotEmpty())
        }
    }

    @Test fun `an entry shows only with one of its packs in the build, and none needs a personal pack`() {
        assertEquals(AirportRoutes.all, AirportRoutes.forBuild(storePacks))
        val storeIds = storePacks.map { it.sourceId.value }.toSet()
        AirportRoutes.all.forEach { route -> assertTrue("${route.packs}", route.packs.all(storeIds::contains)) }
        val personalOnly = EmbeddedGtfsPacks.forBuild(personalSources = true).filter(EmbeddedGtfsPack::personalOnly)
        assertTrue(AirportRoutes.forBuild(personalOnly).isEmpty())
        val withoutTrenord = storePacks.filterNot { it.sourceId.value == "lombardia-trenord-gtfs" }
        assertEquals(
            listOf("curated:S08411", "curated:NC001565", "curated:S12133", "curated:S12819", "curated:S03213", "curated:S00088"),
            AirportRoutes.forBuild(withoutTrenord).map { it.airportStationId }
        )
        assertTrue(AirportRoutes.forBuild(emptyList()).isEmpty())
        // A station the catalogue does not know leaves its entry out.
        assertEquals(AirportRoutes.all.size - 1, AirportRoutes.links(AirportRoutes.all) { id -> stations[id].takeIf { id != "curated:S12133" } }.size)
    }
}
