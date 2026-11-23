package it.binario.app.data.journey

import it.binario.app.TestAssets
import it.binario.app.data.Station
import it.binario.app.data.StationAutocompleteEngine
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.gtfs.GtfsStationCodeIndex
import it.binario.app.data.gtfs.GtfsStopCodeScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two catalogue entries had the name or the position of another station; their own codes and the bundled packs say
 * which is right, and their ids stay (favourites and saved journeys store them). See CURATED_STATION_CATALOG.md.
 */
class CuratedCatalogueCorrectionsTest {
    private val stations: List<Station> by lazy { EmbeddedCuratedRailStationCatalog({ path -> TestAssets.open(path) }).load() }
    private val resolver by lazy { GtfsStopStationResolver(stations) }

    private fun station(id: String): Station = stations.single { it.id == "curated:$id" }

    @Test fun `S00239 is Tronzano, the Trenitalia stop its code names`() {
        val tronzano = station("S00239")
        assertEquals("Tronzano", tronzano.name)
        assertEquals("830000239", tronzano.trenitaliaLocationId)
        // The Trenitalia pack's TRONZANO (stop_code 830000239, 45.34293, 8.168396) binds it by its location id.
        assertEquals(tronzano.id, resolver.resolveCode(GtfsStopCodeScheme.TRENITALIA_LOCATION_ID, "830000239", 45.34293, 8.168396, "TRONZANO")?.id)
        // No entry is called Pino-Tronzano in Piedmont any more: the two left are on the Luino line.
        val pino = stations.filter { it.name.replace(' ', '-') == "Pino-Tronzano" }
        assertEquals(listOf("curated:S01110", "curated:S19006"), pino.map(Station::id).sorted())
        assertTrue(pino.all { it.latitude == null || it.latitude!! > 46.0 })
        assertEquals("curated:S00239", StationAutocompleteEngine(stations).findExact("Tronzano")?.id)
    }

    @Test fun `S09526 Borgo Lombardo sits at Borgolombardo, the station its location id names`() {
        val borgo = station("S09526")
        assertEquals("Borgo Lombardo", borgo.name)
        assertEquals("830001830", borgo.trenitaliaLocationId)
        // The Trenord stop S01830 "Borgolombardo" (45.404507, 9.270465): the same place, no longer 335 km away in Umbria.
        val km = GtfsStationCodeIndex.distanceKm(borgo.latitude!!, borgo.longitude!!, 45.404507, 9.270465)
        assertTrue("$km km", km < 0.01)
        // The Trenord stop keeps its station, S01830, by its position: the two entries are written differently.
        assertNull(resolver.resolve("Borgolombardo", 45.404507, 9.270465))
        assertEquals("curated:S01830", resolver.nearbyStation("Borgolombardo", 45.404507, 9.270465)?.id)
    }
}
