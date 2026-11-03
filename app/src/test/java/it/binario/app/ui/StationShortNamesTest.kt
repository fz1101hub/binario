package it.binario.app.ui

import it.binario.app.data.Station
import it.binario.app.ui.common.RouteHeadline
import it.binario.app.ui.common.StationShortNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationShortNamesTest {
    @Test
    fun `main station designators give the city of the render`() {
        assertEquals(RouteHeadline("Roma → Torino", "Roma Termini → Torino Porta Nuova"), StationShortNames.route("Roma Termini", "Torino Porta Nuova"))
        assertEquals("Firenze", StationShortNames.cityOf("Firenze S. M. Novella"))
        assertEquals("Firenze", StationShortNames.cityOf("Firenze Santa Maria Novella"))
        assertEquals("Reggio di Calabria", StationShortNames.cityOf("Reggio di Calabria Centrale"))
        assertEquals("Venezia", StationShortNames.cityOf("Venezia Santa Lucia"))
        assertEquals("München", StationShortNames.cityOf("München Hbf"))
        assertEquals("Genova", StationShortNames.cityOf("Genova Piazza Principe"))
        assertEquals("TORINO", StationShortNames.cityOf("TORINO PORTA NUOVA"))
    }

    @Test
    fun `the catalogue city label wins when it starts the station name`() {
        val roma = Station("roma-tiburtina", "Roma Tiburtina", "Roma")
        val viterbo = Station("viterbo-pf", "Viterbo Porta Fiorentina", "Viterbo")
        assertEquals(RouteHeadline("Roma → Viterbo", "Roma Tiburtina → Viterbo Porta Fiorentina"), StationShortNames.route(roma, viterbo))
        // A city that is not the start of the name, or the name itself, is not used.
        assertEquals("Lingotto", StationShortNames.shortName("Lingotto", "Torino"))
        assertEquals("Roma Tiburtina", StationShortNames.shortName("Roma Tiburtina", "Roma Tiburtina"))
        assertEquals("Romano Canavese", StationShortNames.shortName("Romano Canavese", "Roma"))
        // Without a city label the designator rule applies (the curated catalogue repeats the name as city).
        assertEquals("Torino", StationShortNames.shortName("Torino Porta Nuova", "Torino Porta Nuova"))
    }

    @Test
    fun `names without a main-station designator stay whole`() {
        assertNull(StationShortNames.cityOf("Civitavecchia"))
        assertNull(StationShortNames.cityOf("Montecatini Terme"))
        assertNull(StationShortNames.cityOf("Venezia Mestre"))
        assertNull(StationShortNames.cityOf("Roma Tiburtina"))
        assertNull(StationShortNames.cityOf("Genova Brignole"))
        assertNull(StationShortNames.cityOf("Centrale"))
        assertNull(StationShortNames.cityOf("X Centrale"))
        assertEquals("Roma → Civitavecchia", StationShortNames.route("Roma Termini", "Civitavecchia").short)
    }

    @Test
    fun `the short form is never ambiguous or empty`() {
        assertEquals("Roma Termini → Roma Ostiense", StationShortNames.route("Roma Termini", "Roma Ostiense").short)
        assertEquals("Milano Centrale → Milano Porta Garibaldi", StationShortNames.route("Milano Centrale", "Milano Porta Garibaldi").short)
        assertEquals("Napoli Campi Flegrei → Napoli Centrale", StationShortNames.route("Napoli Campi Flegrei", "Napoli Centrale").short)
        assertEquals(
            "Roma Termini → Roma San Pietro",
            StationShortNames.route(Station("a", "Roma Termini", "Roma"), Station("b", "Roma San Pietro", "Roma")).short
        )
        assertEquals("X Centrale → Bologna", StationShortNames.route("X Centrale", "Bologna Centrale").short)
        // Not the same city: "Reggio Emilia" and "Reggio di Calabria" only share a word.
        assertEquals("Reggio Emilia → Reggio di Calabria", StationShortNames.route("Reggio Emilia", "Reggio di Calabria Centrale").short)
        val headline = StationShortNames.route("  Bologna Centrale ", "Milano Centrale")
        assertEquals("Bologna → Milano", headline.short)
        assertEquals("Bologna Centrale → Milano Centrale", headline.full)
        assertTrue(headline.short.isNotBlank())
    }
}
