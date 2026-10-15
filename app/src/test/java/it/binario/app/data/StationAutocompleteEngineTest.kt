package it.binario.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationAutocompleteEngineTest {
    private val portaNuova = Station(
        id = "torino-porta-nuova",
        name = "Torino Porta Nuova",
        city = "Torino",
        aliases = listOf("Torino P.N.")
    )
    private val reggio = Station(
        id = "reggio-emilia-av",
        name = "Reggio Emilia AV Mediopadana",
        city = "Reggio nell'Emilia",
        aliases = listOf("Mediopadana")
    )
    private val engine = StationAutocompleteEngine(listOf(portaNuova, reggio))

    @Test
    fun `normalization removes accents punctuation and repeated separators`() {
        assertEquals("reggio nell emilia", StationSearchNormalizer.normalize("  Réggio nell’Emilia  "))
    }

    @Test
    fun `suggest searches pre-normalized aliases and respects limit`() {
        assertEquals(listOf(reggio), engine.suggest("medio"))
        assertEquals(listOf(portaNuova), engine.suggest("torino pn"))
        assertEquals(1, engine.suggest("torino", limit = 1).size)
    }

    @Test
    fun `exact lookup is normalized and short queries do not scan`() {
        assertEquals(portaNuova, engine.findExact("TORINO P.N."))
        assertTrue(engine.suggest("t").isEmpty())
        assertNull(engine.findExact("stazione inesistente"))
    }

    @Test
    fun `a station's own name wins over an earlier station's alias`() {
        val malpensa = Station(id = "S01999", name = "Milano Malpensa Aeroporto", city = "Ferno", railwayCode = "S01999", aliases = listOf("Milano Centrale", "Milan"))
        val centrale = Station(id = "S01700", name = "Milano Centrale", city = "Milano", railwayCode = "S01700", aliases = listOf("Milan"))
        val engine = StationAutocompleteEngine(listOf(malpensa, centrale))

        assertEquals(centrale, engine.findExact("Milano Centrale"))
        assertEquals(malpensa, engine.findExact("milano malpensa aeroporto"))
        // "Milan" names two different stations: resolve to nothing rather than guess.
        assertNull(engine.findExact("Milan"))
    }

    @Test
    fun `same-name catalogue duplicates prefer the curated entry with a railway code`() {
        val bare = Station(id = "NC000001", name = "Andora", city = "Andora")
        val curated = Station(id = "S04400", name = "Andora", city = "Andora", railwayCode = "S04400")
        assertEquals(curated, StationAutocompleteEngine(listOf(bare, curated)).findExact("ANDORA"))
    }
}
