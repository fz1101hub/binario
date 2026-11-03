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
    fun `suggestions rank exact and prefix names first regardless of catalogue order`() {
        val romano = Station(id = "romano", name = "Romano di Lombardia", city = "Romano di Lombardia")
        val ladispoli = Station(id = "ladispoli", name = "Ladispoli-Cerveteri", city = "Ladispoli", aliases = listOf("Roma Ladispoli"))
        val tiburtina = Station(id = "tiburtina", name = "Roma Tiburtina", city = "Roma")
        val fiumicino = Station(id = "fiumicino", name = "Fiumicino Aeroporto", city = "Fiumicino", aliases = listOf("Roma Fiumicino"))
        val pomezia = Station(id = "pomezia", name = "Pomezia-Santa Palomba", city = "Pomezia", aliases = listOf("Santa Palomba Roma"))
        val termini = Station(id = "termini", name = "Roma Termini", city = "Roma")
        val roma = Station(id = "roma", name = "Roma", city = "Roma")
        val engine = StationAutocompleteEngine(listOf(romano, ladispoli, tiburtina, fiumicino, pomezia, termini, roma))

        // Exact name, then name prefixes in alphabetical order, then alias prefixes; the catalogue order is irrelevant.
        assertEquals(listOf(roma, termini, tiburtina, romano, fiumicino), engine.suggest("Roma", limit = 5))
        assertEquals(listOf(roma, termini, tiburtina, romano, fiumicino, ladispoli, pomezia), engine.suggest("roma"))
        // A query spread over several words still matches, after every contiguous match.
        assertEquals(listOf(pomezia), engine.suggest("palomba roma"))
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
