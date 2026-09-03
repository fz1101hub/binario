package it.binario.app

import it.binario.app.data.Station
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PlannerVoiceParserTest {
    private val termini = Station("roma-termini", "Roma Termini", "Roma")
    private val torino = Station("torino-pn", "Torino Porta Nuova", "Torino")

    @Test
    fun `parses a complete dictated journey locally`() {
        val result = PlannerVoiceParser.parse(
            "Da Roma Termini a Torino Porta Nuova domani alle 18 e 30, treno 9512",
            listOf(termini, torino),
            LocalDate.of(2026, 8, 13)
        )

        assertEquals(termini, result.origin)
        assertEquals(torino, result.destination)
        assertEquals("9512", result.trainNumber)
        assertEquals(LocalDate.of(2026, 8, 14), result.date)
        assertEquals(LocalTime.of(18, 30), result.time)
    }

    @Test
    fun `recognizes a single dictated station without inventing a route`() {
        val result = PlannerVoiceParser.parse("Stazione Roma Termini", listOf(termini, torino), LocalDate.of(2026, 8, 13))

        assertEquals(termini, result.station)
        assertEquals(null, result.destination)
    }

    @Test
    fun `parses return journey and named date`() {
        val result = PlannerVoiceParser.parse(
            "Da Roma Termini a Torino Porta Nuova andata e ritorno il 18 agosto alle 8",
            listOf(termini, torino),
            LocalDate.of(2026, 8, 13)
        )

        assertEquals(true, result.returnJourney)
        assertEquals(LocalDate.of(2026, 8, 18), result.date)
        assertEquals(LocalTime.of(8, 0), result.time)
    }
}
