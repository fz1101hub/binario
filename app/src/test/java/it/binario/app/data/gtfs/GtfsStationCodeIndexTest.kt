package it.binario.app.data.gtfs

import it.binario.app.data.Station
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GtfsStationCodeIndexTest {
    private val scheme = GtfsStopCodeScheme.TRENITALIA_LOCATION_ID

    private fun station(id: String, name: String, railwayCode: String?, locationId: String?, latitude: Double?, longitude: Double?) =
        Station("curated:$id", name, name, railwayCode = railwayCode, trenitaliaLocationId = locationId, latitude = latitude, longitude = longitude)

    // Fidene's location id is 830008225 but its ViaggiaTreno code is S08235; S08225 is another place.
    private val fidene = station("S08235", "Fidene", "S08235", "830008225", 41.979773, 12.508451)
    private val orvieto = station("S08225", "Pc Orvieto", "S08225", null, null, null)
    private val bologna = station("S05043", "Bologna Centrale", "S05043", null, 44.506145, 11.342267)
    private val wrongFiera = station("S12137", "Fiera", "S12137", "830008415", 38.144004, 13.351336)
    private val laRustica = station("S08514", "La Rustica Uir", "S08514", "830008505", 41.91, 12.60)
    private val noCoordinates = station("S07000", "Castel Giubileo", "S07000", "830007000", null, null)
    private val index = GtfsStationCodeIndex(listOf(fidene, orvieto, bologna, wrongFiera, laRustica, noCoordinates))

    @Test fun `the location id wins over the derived ViaggiaTreno code`() {
        assertEquals(fidene, index.resolve(scheme, "830008225", 41.9797, 12.5084, "FIDENE"))
    }

    @Test fun `the derived code binds a station without a location id when the coordinates agree`() {
        assertEquals(bologna, index.resolve(scheme, "830005043", 44.506234, 11.342267, "BOLOGNA C.LE"))
        // Too far away for a guess: Bologna is not 20 km from its own platform.
        assertNull(index.resolve(scheme, "830005043", 44.70, 11.40, "BOLOGNA C.LE"))
    }

    @Test fun `a derived code never takes a station that has another location id`() {
        // S08514 is La Rustica (830008505); "SERENISSIMA" 830008514 must not become La Rustica.
        assertNull(index.resolve(scheme, "830008514", 41.91, 12.60, "SERENISSIMA"))
    }

    @Test fun `a catalogue location id far from the stop is rejected`() {
        // The catalogue gives Palermo's "Fiera" the location id of Fiera di Roma, 417 km away.
        assertNull(index.resolve(scheme, "830008415", 41.79, 12.33, "FIERA DI ROMA"))
    }

    @Test fun `without coordinates a shared word of the name corroborates the code`() {
        assertEquals(noCoordinates, index.resolve(scheme, "830007000", null, null, "CASTEL GIUBILEO"))
        assertNull(index.resolve(scheme, "830007000", null, null, "SETTEBAGNI"))
    }

    @Test fun `foreign codes, malformed codes and packs without a scheme resolve nothing`() {
        assertNull(index.resolve(scheme, "850001008", 46.21, 6.14, "GENEVE"))
        assertNull(index.resolve(scheme, "8300", null, null, "x"))
        assertNull(index.resolve(GtfsStopCodeScheme.NONE, "830008225", 41.9797, 12.5084, "FIDENE"))
    }

    @Test fun `every reviewed Italo code names a station of the shipped catalogue`() {
        val catalogue = EmbeddedCuratedRailStationCatalog({ File("src/main/assets", it).inputStream() }).load()
        val byId = catalogue.associateBy(Station::id)
        val real = GtfsStationCodeIndex(catalogue)
        assertEquals(63, ItaloGtfsStationCodes.size)
        val italoStops = mapOf(
            "MC_" to "Milano Centrale", "PD_" to "Padova", "BC_" to "Bologna Centrale", "AAV" to "Reggio Emilia AV",
            "RCE" to "Reggio Di Calabria Centrale", "VEM" to "Venezia Mestre", "CDZ" to "Cortina D'Ampezzo", "SGM" to "Villa S. Giovanni"
        )
        italoStops.forEach { (code, name) ->
            assertEquals(code, name, real.resolve(GtfsStopCodeScheme.ITALO_STATION_CODE, code, null, null, "")?.name)
        }
        assertNull(real.resolve(GtfsStopCodeScheme.ITALO_STATION_CODE, "LUX", 43.84, 10.51, "Lucca Comics and Games BUS"))
        assertTrue(ItaloGtfsStationCodes.unmapped.all { ItaloGtfsStationCodes.catalogueId(it) == null })
        listOf("AGR", "BAC", "BEN", "NAF", "SDX", "TDX", "TRX").forEach { code ->
            assertTrue(code, ItaloGtfsStationCodes.catalogueId(code)!! in byId)
        }
    }

    @Test fun `samples of the Trenitalia location ids on the shipped catalogue`() {
        val real = GtfsStationCodeIndex(EmbeddedCuratedRailStationCatalog({ File("src/main/assets", it).inputStream() }).load())
        fun name(code: String, latitude: Double, longitude: Double, published: String) = real.resolve(scheme, code, latitude, longitude, published)?.name
        assertEquals("Milano Centrale", name("830001700", 45.4863, 9.2045, "MILANO CENTRALE"))
        assertEquals("Fidene", name("830008225", 41.9797, 12.5084, "FIDENE"))
        assertEquals("Bologna Centrale", name("830005043", 44.506234, 11.342267, "BOLOGNA C.LE"))
        assertEquals("Napoli Afragola", name("830009988", 40.930568, 14.328634, "NA Afragola PES"))
        assertEquals("Firenze S. M. Novella", name("830006421", 43.7765, 11.2482, "FIRENZE S.MARIA NOVELLA"))
        assertNull(name("830008415", 41.79, 12.33, "FIERA DI ROMA"))
    }
}
