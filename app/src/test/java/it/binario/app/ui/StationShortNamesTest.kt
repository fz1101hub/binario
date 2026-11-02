package it.binario.app.ui

import it.binario.app.ui.common.RouteHeadline
import it.binario.app.ui.common.StationShortNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationShortNamesTest {
    @Test
    fun `main station designators give the city of the render`() {
        assertEquals(RouteHeadline("Roma → Torino", "Roma Termini → Torino Porta Nuova"), StationShortNames.route("Roma Termini", "Torino Porta Nuova"))
        assertEquals("Firenze", StationShortNames.cityOf("Firenze S. M. Novella"))
        assertEquals("Reggio di Calabria", StationShortNames.cityOf("Reggio di Calabria Centrale"))
        assertEquals("München", StationShortNames.cityOf("München Hbf"))
        assertEquals("Genova", StationShortNames.cityOf("Genova Piazza Principe"))
    }

    @Test
    fun `names without a known designator stay whole`() {
        assertNull(StationShortNames.cityOf("Civitavecchia"))
        assertNull(StationShortNames.cityOf("Montecatini Terme"))
        assertNull(StationShortNames.cityOf("Venezia Mestre"))
        assertNull(StationShortNames.cityOf("Centrale"))
        assertEquals("Roma → Civitavecchia", StationShortNames.route("Roma Termini", "Civitavecchia").short)
    }

    @Test
    fun `the short form is never ambiguous or empty`() {
        assertEquals("Roma Termini → Roma Ostiense", StationShortNames.route("Roma Termini", "Roma Ostiense").short)
        assertEquals("Milano Centrale → Milano Porta Garibaldi", StationShortNames.route("Milano Centrale", "Milano Porta Garibaldi").short)
        assertEquals("X Centrale → Bologna", StationShortNames.route("X Centrale", "Bologna Centrale").short)
    }
}
