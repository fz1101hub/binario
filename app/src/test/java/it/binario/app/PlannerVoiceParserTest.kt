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
        assertEquals(
            PlannerVoiceFeedback.Ambiguous(listOf(DictatedStationRole.STATION to listOf("Milano Centrale", "Verona Porta Nuova"))),
            review.feedback(DictationTarget.STATION)
        )
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
        assertEquals(PlannerVoiceFeedback.Unresolved(DictationTarget.TRAIN), review.feedback(DictationTarget.TRAIN))
    }

    private val today = LocalDate.of(2026, 10, 6)

    @Test
    fun `understands an English dictation`() {
        val result = PlannerVoiceParser.parse(
            "From Roma Termini to Torino Porta Nuova on October 9th at 6:30 pm, return trip, train 9512",
            listOf(termini, torino),
            today
        )

        assertEquals(termini, result.origin)
        assertEquals(torino, result.destination)
        assertEquals(LocalDate.of(2026, 10, 9), result.date)
        assertEquals(LocalTime.of(18, 30), result.time)
        assertEquals(true, result.returnJourney)
        assertEquals("9512", result.trainNumber)
    }

    @Test
    fun `understands a German dictation`() {
        val result = PlannerVoiceParser.parse(
            "Von Roma Termini nach Torino Porta Nuova übermorgen um 7 Uhr 45, Zug 9512, einfache Fahrt",
            listOf(termini, torino),
            today
        )

        assertEquals(termini, result.origin)
        assertEquals(LocalDate.of(2026, 10, 8), result.date)
        assertEquals(LocalTime.of(7, 45), result.time)
        assertEquals("9512", result.trainNumber)
        assertEquals(false, result.returnJourney)
    }

    @Test
    fun `understands a French dictation`() {
        val result = PlannerVoiceParser.parse(
            "De Roma Termini à Torino Porta Nuova le 12 octobre à 18 h 15, aller-retour",
            listOf(termini, torino),
            today
        )

        assertEquals(torino, result.destination)
        assertEquals(LocalDate.of(2026, 10, 12), result.date)
        assertEquals(LocalTime.of(18, 15), result.time)
        assertEquals(true, result.returnJourney)
    }

    @Test
    fun `understands a Spanish dictation`() {
        val result = PlannerVoiceParser.parse(
            "De Roma Termini a Torino Porta Nuova mañana a las 8 y media, ida y vuelta, tren 9512",
            listOf(termini, torino),
            today
        )

        assertEquals(LocalDate.of(2026, 10, 7), result.date)
        assertEquals(LocalTime.of(8, 30), result.time)
        assertEquals(true, result.returnJourney)
        assertEquals("9512", result.trainNumber)
    }

    @Test
    fun `relative dates match whole words only`() {
        val poggibonsi = Station("poggibonsi", "Poggibonsi", "Poggibonsi")

        val fromPoggibonsi = PlannerVoiceParser.parse("Da Poggibonsi a Torino Porta Nuova", listOf(poggibonsi, torino), today)
        val dayAfterTomorrow = PlannerVoiceParser.parse("Da Roma Termini a Torino Porta Nuova dopodomani", listOf(termini, torino), today)

        assertNull(fromPoggibonsi.date)
        assertEquals(LocalDate.of(2026, 10, 8), dayAfterTomorrow.date)
    }

    @Test
    fun `numeric dates and clock times survive punctuation`() {
        val result = PlannerVoiceParser.parse("Da Roma Termini a Torino Porta Nuova il 9/10 18:05", listOf(termini, torino), today)

        assertEquals(LocalDate.of(2026, 10, 9), result.date)
        assertEquals(LocalTime.of(18, 5), result.time)
    }

    @Test
    fun `evening and afternoon phrases make a dictated hour a pm time`() {
        val stations = listOf(termini, torino)
        fun time(spoken: String) = PlannerVoiceParser.parse(spoken, stations, today).time

        assertEquals(LocalTime.of(20, 0), time("Da Roma Termini a Torino Porta Nuova alle 8 di sera"))
        assertEquals(LocalTime.of(21, 0), time("Da Roma Termini a Torino Porta Nuova stasera alle 9"))
        assertEquals(LocalTime.of(15, 30), time("Da Roma Termini a Torino Porta Nuova alle 3 e mezza del pomeriggio"))
        assertEquals(LocalTime.of(20, 15), time("Da Roma Termini a Torino Porta Nuova 8:15 di sera"))
        assertEquals(LocalTime.of(20, 0), time("From Roma Termini to Torino Porta Nuova at 8 in the evening"))
        assertEquals(LocalTime.of(20, 0), time("Von Roma Termini nach Torino Porta Nuova um 8 Uhr abends"))
        assertEquals(LocalTime.of(20, 0), time("De Roma Termini à Torino Porta Nuova à 8 heures du soir"))
        assertEquals(LocalTime.of(20, 0), time("De Roma Termini a Torino Porta Nuova a las 8 de la tarde"))
        // A 24-hour time or an explicit am keeps its meaning; "stasera" still dates the journey today.
        assertEquals(LocalTime.of(18, 0), time("Da Roma Termini a Torino Porta Nuova alle 18 di sera"))
        assertEquals(LocalTime.of(8, 0), time("From Roma Termini to Torino Porta Nuova tonight at 8 am"))
        assertEquals(today, PlannerVoiceParser.parse("Da Roma Termini a Torino Porta Nuova stasera alle 9", stations, today).date)
        assertEquals(LocalTime.of(8, 0), time("Da Roma Termini a Torino Porta Nuova alle 8 di mattina"))
    }

    @Test
    fun `a bare number is a train number`() {
        val review = PlannerVoiceParser.review("9512", listOf(termini, torino), DictationTarget.TRAIN, today)

        assertEquals(DictationReviewStatus.CONFIRMED, review.status)
        assertEquals("9512", review.input.trainNumber)
    }
}
