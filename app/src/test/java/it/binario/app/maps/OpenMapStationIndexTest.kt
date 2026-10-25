package it.binario.app.maps

import it.binario.app.data.Station
import it.binario.app.data.StationAutocompleteEngine
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.stationFromFreeText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMapStationIndexTest {
    private val milano = Station("curated:S01700", "Milano Centrale", "Milano Centrale", railwayCode = "S01700", latitude = 45.486347, longitude = 9.204528)
    private val lodi = Station("curated:S01825", "Lodi", "Lodi", railwayCode = "S01825", latitude = 45.308966, longitude = 9.497585)
    private val firenze = Station(
        "curated:S06421", "Firenze S. M. Novella", "Firenze S. M. Novella", railwayCode = "S06421",
        aliases = listOf("FIRENZE SANTA MARIA NOVELLA"), latitude = 43.77658, longitude = 11.247527
    )
    private val noCoordinates = Station("curated:S99999", "Borgo Senza Coordinate", "Borgo", railwayCode = "S99999")
    private val sanGiorgioNord = Station("curated:N1", "San Giorgio", "San Giorgio", railwayCode = "N1", latitude = 45.0, longitude = 10.0)
    private val sanGiorgioSud = Station("curated:N2", "San Giorgio", "San Giorgio", railwayCode = "N2", latitude = 40.0, longitude = 16.0)

    /** The lookup the app uses for station names: `TrainViewModel.stationFromName`. */
    private fun appLookup(stations: List<Station>): (String) -> Station? {
        val engine = StationAutocompleteEngine(stations)
        return { name -> engine.findExact(name) ?: stationFromFreeText(name) }
    }

    private fun index(vararg stations: Station) = OpenMapStationIndex(stations.toList(), appLookup(stations.toList()))

    @Test
    fun `names the app resolves exactly keep that station`() {
        val index = index(milano, lodi, firenze)

        assertEquals(milano, index.locate("Milano Centrale"))
        assertEquals(lodi, index.locate("LODI"))
        assertEquals(firenze, index.locate("Firenze Santa Maria Novella"))
    }

    @Test
    fun `operator abbreviations fall back to the strict whole name matcher`() {
        val index = index(milano, lodi, firenze)

        assertEquals(milano, index.locate("MILANO C.LE"))
        assertEquals(firenze, index.locate("FIRENZE S.M.N."))
        assertNull(index.locate("Milano"))
        assertNull(index.locate("Lodi Vecchio"))
        assertNull(index.locate(" "))
    }

    @Test
    fun `a catalogue station without coordinates is not replaced by a guess`() {
        val homonym = Station("curated:S99998", "Borgo Senza Coordinate", "Borgo", railwayCode = "S99998", latitude = 44.0, longitude = 11.0)
        val index = OpenMapStationIndex(listOf(noCoordinates, homonym)) { name -> if (name == noCoordinates.name) noCoordinates else null }

        assertNull(index.locate("Borgo Senza Coordinate"))
    }

    @Test
    fun `a name shared by stations with different codes stays unresolved`() {
        val index = OpenMapStationIndex(listOf(sanGiorgioNord, sanGiorgioSud)) { null }

        assertNull(index.locate("SAN GIORGIO"))
    }

    @Test
    fun `coordinates of a station without them come from the catalogue entry with the same id`() {
        val index = index(milano, lodi)

        assertEquals(milano, index.coordinatesOf(milano.copy(latitude = null, longitude = null)))
        assertNull(index.coordinatesOf(noCoordinates))
    }

    @Test
    fun `neighbours are the nearest other stations, one per railway code`() {
        val milanoDuplicate = milano.copy(id = "custom:milano", aliases = listOf("Milano C.le"))
        val index = index(milano, milanoDuplicate, lodi, firenze, noCoordinates)

        val neighbours = index.neighbours(milano, limit = 5)

        assertEquals(listOf(lodi.id, firenze.id), neighbours.map { it.station.id })
        assertEquals(listOf(lodi.id), index.neighbours(milano, limit = 1).map { it.station.id })
        assertTrue(index.neighbours(milano, limit = 0).isEmpty())
        assertTrue(index.neighbours(noCoordinates, limit = 3).isEmpty())
    }

    @Test
    fun `real operator stop names resolve against the embedded catalogue`() {
        val stations = EmbeddedCuratedRailStationCatalog(openAsset = { path -> File("src/main/assets", path).inputStream() }).load()
        val index = OpenMapStationIndex(stations, appLookup(stations))

        listOf("MILANO CENTRALE", "MILANO C.LE", "BOLOGNA C.LE", "FIRENZE S.M.N.", "ROMA TERMINI", "LODI").forEach { name ->
            val station = index.locate(name)
            assertNotNull(name, station)
            assertNotNull(name, station!!.mapPoint())
        }
        assertEquals("S01700", index.locate("MILANO C.LE")?.railwayCode)
        assertEquals("S06421", index.locate("FIRENZE S.M.N.")?.railwayCode)
    }
}
