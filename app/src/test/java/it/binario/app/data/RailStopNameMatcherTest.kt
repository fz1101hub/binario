package it.binario.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RailStopNameMatcherTest {
    private fun station(name: String, vararg aliases: String) =
        Station(id = name, name = name, city = name.substringBefore(' '), aliases = aliases.toList())

    private val latina = station("Latina")
    private val firenze = station("Firenze S. M. Novella", "FIRENZE SANTA MARIA NOVELLA", "Firenze Santa Maria Novella")
    private val milano = station("Milano Centrale", "Mailand", "Milán", "Milão")
    private val roma = station("Roma Termini")
    private val torino = station("Torino Porta Nuova", "@ITTRNTOP", "Turín")
    private val reggio = station("Reggio Di Calabria Centrale", "Reggio de Calabre")
    private val bari = station("Bari ( Tutte Le Stazioni )", "BARI CENTRALE", "Bari Centrale")
    private val genova = station("Genova Piazza Principe", "G__")
    private val piacenza = station("Piacenza", "P__")
    private val ferrara = station("Ferrara", "F__")
    private val ancona = station("Ancona ( Tutte Le Stazioni )")
    private val anconaMarittima = station("Ancona M.ma", "ANCONA MARITTIMA")

    @Test
    fun `exact names and RFI abbreviations match`() {
        assertTrue(latina.isNamedBy("LATINA"))
        assertTrue(firenze.isNamedBy("Firenze S.M.N."))
        assertTrue(firenze.isNamedBy("FIRENZE SMN"))
        assertTrue(firenze.isNamedBy("Firenze Santa Maria Novella"))
        assertTrue(milano.isNamedBy("Milano Centrale"))
        assertTrue(milano.isNamedBy("MILANO C.LE"))
        assertTrue(roma.isNamedBy("Roma Termini"))
        assertTrue(torino.isNamedBy("TORINO P.NUOVA"))
        assertTrue(torino.isNamedBy("Torino P. Nuova"))
        assertTrue(reggio.isNamedBy("Reggio Calabria C.le"))
        assertTrue(reggio.isNamedBy("REGGIO DI CALABRIA CENTRALE"))
        assertTrue(bari.isNamedBy("Bari C.le"))
        assertTrue(genova.isNamedBy("Genova P.Principe"))
        assertTrue(genova.isNamedBy("GENOVA P.ZA PRINCIPE"))
        assertTrue(ancona.isNamedBy("ANCONA"))
        assertTrue(anconaMarittima.isNamedBy("Ancona Marittima"))
        // Very short real names (Re, in Val Vigezzo) still match themselves exactly.
        assertTrue(station("Re").isNamedBy("RE"))
        assertFalse(station("Re").isNamedBy("Reggio Di Calabria Centrale"))
    }

    @Test
    fun `a longer or different stop never names the station`() {
        assertFalse(latina.isNamedBy("Cisterna di Latina"))
        assertFalse(roma.isNamedBy("Roma Tiburtina"))
        assertFalse(station("Roma").isNamedBy("Roma Termini"))
        assertFalse(milano.isNamedBy("Milano Rogoredo"))
        assertFalse(torino.isNamedBy("Torino Porta Susa"))
        assertFalse(firenze.isNamedBy("Firenze Rifredi"))
        assertFalse(ancona.isNamedBy("Ancona Stadio"))
    }

    @Test
    fun `junk one-letter and code aliases match nothing`() {
        assertFalse(piacenza.isNamedBy("Pavia"))
        assertFalse(piacenza.isNamedBy("P"))
        assertFalse(ferrara.isNamedBy("Firenze S.M.N."))
        assertFalse(genova.isNamedBy("Genova Brignole"))
        assertFalse(torino.isNamedBy("ITTRNTOP"))
        assertFalse(latina.isNamedBy(""))
    }
}
