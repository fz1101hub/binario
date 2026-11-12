package it.binario.app.plannedjourney

import it.binario.app.DictationTarget
import it.binario.app.PlannerVoiceParser
import it.binario.app.PlannerVoiceReview
import it.binario.app.data.Station
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dictation in the Agenda editor (EXPO_PORT_PLAN F2): the planner's parser, put in the leg whose microphone was tapped. */
class PlannedJourneyEditorDictationTest {
    private val termini = Station("roma-termini", "Roma Termini", "Roma", railwayCode = "RMTE")
    private val torino = Station("torino-pn", "Torino Porta Nuova", "Torino", aliases = listOf("Torino PN"))
    private val catalogue = listOf(termini, torino)
    private val agendaStations = catalogue.map { PlannedJourneyStation(it.id, it.name, it.railwayCode) }
    private val today = LocalDate.of(2026, 8, 13)
    private val italy = ZoneId.of("Europe/Rome")

    private fun review(spoken: String): PlannerVoiceReview = PlannerVoiceParser.review(spoken, catalogue, DictationTarget.ROUTE, today, language = "it")

    private fun editor() = PlannedJourneyEditorState(date = "2026-08-13", time = "09:00", timeZoneId = italy.id)

    @Test fun `a confirmed route fills the first leg's stations, day and time, and the prefilled next origin follows`() {
        val dictated = editor().addLeg().withDictation(review("Da Roma Termini a Torino PN domani alle 18"), legIndex = 0)
        assertEquals(termini.name to termini.id, dictated.originQuery to dictated.originStationId)
        assertEquals(torino.name to torino.id, dictated.destinationQuery to dictated.destinationStationId)
        assertEquals("2026-08-14" to "18:00", dictated.date to dictated.time)
        assertEquals(torino.id, dictated.legs[1].originStationId)
    }

    @Test fun `a dictation for the second leg changes that leg only`() {
        val start = editor().withLeg(0) { it.copy(originQuery = "Milano Centrale", originStationId = "milano-c") }.addLeg()
        val dictated = start.withDictation(review("Da Roma Termini a Torino Porta Nuova il 18 agosto alle 8"), legIndex = 1)
        assertEquals(start.legs[0], dictated.legs[0])
        val second = dictated.legs[1]
        assertEquals(listOf(termini.id, torino.id), listOf(second.originStationId, second.destinationStationId))
        assertEquals("2026-08-18" to "08:00", second.date to second.time)
    }

    @Test fun `stations not found leave the station fields and bring the day and the time`() {
        val start = editor().withLeg(0) { it.copy(originQuery = "Roma", originStationId = null) }
        val dictated = start.withDictation(review("Domani alle 8"))
        assertEquals("Roma" to null, dictated.originQuery to dictated.originStationId)
        assertEquals("" to null, dictated.destinationQuery to dictated.destinationStationId)
        assertEquals("2026-08-14" to "08:00", dictated.date to dictated.time)
    }

    @Test fun `ambiguous, incomplete or unread dictations change nothing`() {
        val portaNuova = Station("verona-pn", "Verona Porta Nuova", "Verona", aliases = listOf("Centrale"))
        val centrale = Station("milano-c", "Milano Centrale", "Milano", aliases = listOf("Centrale"))
        val ambiguous = PlannerVoiceParser.review("Da Centrale a Roma Termini domani alle 8", listOf(portaNuova, centrale, termini),
            DictationTarget.ROUTE, today, language = "it")
        val start = editor()
        assertEquals(start, start.withDictation(ambiguous))
        assertEquals(start, start.withDictation(review("Da Roma Termini")))
        assertEquals(start, start.withDictation(review("Vorrei partire presto")))
        assertEquals("a leg that does not exist", start, start.withDictation(review("Da Roma Termini a Torino PN domani alle 18"), legIndex = 3))
    }

    @Test fun `a dictated route is saved through the Agenda catalogue`() {
        val model = PlannedJourneyViewModel(PlannedJourneyRepository(FakeStorage()), now = { Instant.parse("2026-08-13T08:00:00Z") },
            zone = italy, newId = { "dictated" })
        model.beginCreate()
        model.changeEditor { it.withDictation(review("Da Roma Termini a Torino PN domani alle 18")) }
        model.saveEditor(agendaStations)
        val segment = model.snapshot.journeys.single().itinerary.segments.single()
        assertEquals(agendaStations, listOf(segment.origin, segment.destination))
        assertEquals(LocalDate.of(2026, 8, 14), segment.departure.date)
    }

    @Test fun `the microphone is offered where the planner offers the route one`() {
        assertTrue(PlannerVoiceParser.offers(DictationTarget.ROUTE, "it"))
        assertFalse(PlannerVoiceParser.offers(DictationTarget.ROUTE, "xx"))
    }

    private class FakeStorage(var value: String? = null) : PlannedJourneyStorage {
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }
}
