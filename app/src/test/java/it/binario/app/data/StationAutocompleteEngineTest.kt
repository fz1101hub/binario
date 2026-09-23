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
}
