package it.binario.app

import it.binario.app.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PlannerVoiceParserTest {
    private val termini = Station("roma-termini", "Roma Termini", "Roma")
    private val torino = Station("torino-pn", "Torino Porta Nuova", "Torino", aliases = listOf("Torino PN"))

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
        assertNull(result.destination)
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

    @Test
    fun `confirms a complete route and permits application`() {
        val review = PlannerVoiceParser.review(
            "Da Roma Termini a Torino PN domani alle 18",
            listOf(termini, torino),
            DictationTarget.ROUTE,
            LocalDate.of(2026, 8, 13)
        )

        assertEquals(DictationReviewStatus.CONFIRMED, review.status)
        assertTrue(review.canApply)
        assertEquals(termini, review.input.origin)
        assertEquals(torino, review.input.destination)
    }

    @Test
    fun `does not apply ambiguous station aliases and returns candidates`() {
        val portaNuova = Station("verona-pn", "Verona Porta Nuova", "Verona", aliases = listOf("Centrale"))
        val centrale = Station("milano-c", "Milano Centrale", "Milano", aliases = listOf("Centrale"))

        val review = PlannerVoiceParser.review(
            "Stazione Centrale",
            listOf(portaNuova, centrale),
            DictationTarget.STATION,
            LocalDate.of(2026, 8, 13)
        )

        assertEquals(DictationReviewStatus.NEEDS_REVIEW, review.status)
        assertFalse(review.canApply)
        assertNull(review.input.station)
        assertEquals(listOf(centrale, portaNuova), review.stationCandidates.single().candidates)
        assertTrue(review.feedbackMessage(DictationTarget.STATION).contains("Milano Centrale"))
        assertTrue(review.feedbackMessage(DictationTarget.STATION).contains("Verona Porta Nuova"))
    }

    @Test
    fun `marks partial route details as requiring review without changing fields`() {
        val review = PlannerVoiceParser.review(
            "Domani alle 8",
            listOf(termini, torino),
            DictationTarget.ROUTE,
            LocalDate.of(2026, 8, 13)
        )

        assertEquals(DictationReviewStatus.NEEDS_REVIEW, review.status)
        assertFalse(review.canApply)
        assertEquals(LocalDate.of(2026, 8, 14), review.input.date)
        assertEquals(LocalTime.of(8, 0), review.input.time)
    }

    @Test
    fun `reports unresolved text for the selected target`() {
        val review = PlannerVoiceParser.review(
            "Vorrei partire presto",
            listOf(termini, torino),
            DictationTarget.TRAIN,
            LocalDate.of(2026, 8, 13)
        )

        assertEquals(DictationReviewStatus.UNRESOLVED, review.status)
        assertFalse(review.canApply)
        assertTrue(review.feedbackMessage(DictationTarget.TRAIN).contains("9512"))
    }
}
