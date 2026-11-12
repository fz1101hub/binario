package it.binario.app.plannedjourney

import it.binario.app.UiMessage
import it.binario.app.UiMessageKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Agenda editor with up to four legs (EXPO_PORT_PLAN F19): the domain's own checks give every refusal. */
class PlannedJourneyMultiLegEditorTest {
    private val rome = PlannedJourneyStation("IT:ROMA-TERMINI", "Roma Termini", "RMTE")
    private val florence = PlannedJourneyStation("IT:FIRENZE-SMN", "Firenze Santa Maria Novella", "FISMN")
    private val milan = PlannedJourneyStation("IT:MILANO-CENTRALE", "Milano Centrale", "MICT")
    private val catalogue = listOf(rome, florence, milan)
    private val createdAt = Instant.parse("2026-09-01T08:00:00Z")
    private val italy = ZoneId.of("Europe/Rome")

    private fun model(storage: FakeStorage = FakeStorage(), templates: PlannedJourneyTemplateStore? = null): PlannedJourneyViewModel {
        var ids = 0
        return PlannedJourneyViewModel(PlannedJourneyRepository(storage), now = { createdAt }, zone = italy,
            newId = { "new-${++ids}" }, templateStore = templates)
    }

    private fun PlannedJourneyEditorLeg.from(station: PlannedJourneyStation) = copy(originQuery = station.displayName, originStationId = station.canonicalId)
    private fun PlannedJourneyEditorLeg.to(station: PlannedJourneyStation) = copy(destinationQuery = station.displayName, destinationStationId = station.canonicalId)

    @Test fun `a second leg starts where the first arrives, on its date, and both are saved in order`() {
        val model = model()
        model.beginCreate()
        model.changeEditor { it.withLeg(0) { leg -> leg.from(rome).to(florence).copy(date = "2026-10-01", time = "08:00") } }
        model.changeEditor { it.addLeg() }
        val second = requireNotNull(model.editor).legs[1]
        assertEquals(florence.displayName to florence.canonicalId, second.originQuery to second.originStationId)
        assertEquals("2026-10-01" to "", second.date to second.time)
        assertEquals(italy.id, second.timeZoneId)

        model.changeEditor { it.withLeg(1) { leg -> leg.to(milan).copy(time = "10:30") } }
        model.saveEditor(catalogue)

        assertNull(model.editor)
        val segments = model.snapshot.journeys.single().itinerary.segments
        assertEquals(listOf("segment-1", "segment-2"), segments.map { it.id })
        assertEquals(listOf(rome to florence, florence to milan), segments.map { it.origin to it.destination })
        assertEquals(LocalTime.of(10, 30), segments[1].departure.time)
    }

    @Test fun `disconnected and non chronological legs are refused with the factory's reason and nothing is stored`() {
        val model = model()
        model.beginCreate()
        model.changeEditor { it.withLeg(0) { leg -> leg.from(rome).to(florence).copy(date = "2026-10-01", time = "08:00") } }
        model.changeEditor { it.addLeg() }
        model.changeEditor { it.withLeg(1) { leg -> leg.from(milan).to(rome).copy(time = "10:30") } }
        model.saveEditor(catalogue)
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_LEGS_DISCONNECTED), model.editor?.validationMessage)

        model.changeEditor { it.withLeg(1) { leg -> leg.from(florence).to(milan).copy(time = "07:30") } }
        model.saveEditor(catalogue)
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_LEGS_NOT_CHRONOLOGICAL), model.editor?.validationMessage)

        model.changeEditor { it.withLeg(1) { leg -> leg.copy(time = "") } }
        model.saveEditor(catalogue)
        assertEquals("a leg without a time is incomplete", UiMessage(UiMessageKey.PLANNED_JOURNEY_EDITOR_INVALID), model.editor?.validationMessage)
        assertTrue(model.snapshot.journeys.isEmpty())
    }

    @Test fun `at most four legs, removal keeps the others in order and the only leg stays`() {
        var editor = PlannedJourneyEditorState(date = "2026-10-01", time = "08:00", timeZoneId = italy.id)
            .withLeg(0) { it.from(rome).to(florence) }
        assertFalse(editor.isMultiLeg)
        assertEquals(editor, editor.removeLeg(0))
        repeat(5) { editor = editor.addLeg() }
        assertEquals(PlannedJourneyPersistenceSchema.MAX_ITINERARY_SEGMENTS, editor.legs.size)
        assertFalse(editor.canAddLeg)

        editor = editor.withLeg(1) { it.to(milan) }.removeLeg(0)
        assertEquals(3, editor.legs.size)
        assertEquals(florence.displayName to milan.displayName, editor.originQuery to editor.destinationQuery)
        assertEquals("the first leg's fields are the promoted leg", "", editor.time)
        assertEquals(listOf(milan.displayName, ""), editor.nextLegs.map { it.originQuery })
    }

    @Test fun `the prefilled next origin follows its destination, a deliberate one stays, blank dates take the new date`() {
        var editor = PlannedJourneyEditorState(date = "", time = "08:00", timeZoneId = italy.id).addLeg().addLeg()
        editor = editor.withLeg(0) { it.from(rome).to(florence) }
        assertEquals(florence.canonicalId, editor.legs[1].originStationId)
        editor = editor.withLeg(0) { it.copy(date = "2026-10-03") }
        assertEquals(listOf("2026-10-03", "2026-10-03", "2026-10-03"), editor.legs.map { it.date })
        editor = editor.withLeg(1) { it.copy(date = "2026-10-04") }.withLeg(0) { it.copy(date = "2026-10-02") }
        assertEquals("a date already chosen is kept", listOf("2026-10-02", "2026-10-04", "2026-10-03"), editor.legs.map { it.date })

        editor = editor.withLeg(1) { it.from(milan) }.withLeg(0) { it.to(rome) }
        assertEquals(milan.canonicalId, editor.legs[1].originStationId)
    }

    @Test fun `editing a multi leg journey shows every leg, keeps their ids and can add one`() {
        val storage = FakeStorage()
        val source = journey("trip", rome to florence to "08:00", florence to milan to "10:30")
        PlannedJourneyRepository(storage).save(source)
        val model = model(storage)

        model.beginEdit(source)
        val editor = requireNotNull(model.editor)
        assertEquals(2, editor.legs.size)
        assertEquals(listOf("segment-1", "segment-2"), editor.legs.map { it.segmentId })
        assertEquals(listOf("08:00", "10:30"), editor.legs.map { it.time })

        model.changeEditor { it.withLeg(1) { leg -> leg.copy(time = "11:00") }.addLeg() }
        model.changeEditor { it.withLeg(2) { leg -> leg.to(rome).copy(time = "15:00") } }
        model.saveEditor(catalogue)

        val saved = model.snapshot.journeys.single()
        assertEquals("trip", saved.id)
        assertEquals(source.createdAt, saved.createdAt)
        assertEquals(listOf("segment-1", "segment-2", "segment-3"), saved.itinerary.segments.map { it.id })
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(11, 0), LocalTime.of(15, 0)), saved.itinerary.segments.map { it.departure.time })
    }

    @Test fun `a leg still showing a stored station the catalogue lacks keeps it`() {
        val storage = FakeStorage()
        val source = journey("trip", rome to florence to "08:00", florence to milan to "10:30")
        PlannedJourneyRepository(storage).save(source)
        val model = model(storage)
        model.beginEdit(source)
        model.changeEditor { it.copy(label = "Lavoro") }
        model.saveEditor(listOf(rome, milan))
        assertEquals(source.itinerary, model.snapshot.journeys.single().itinerary)
        assertEquals("Lavoro", model.snapshot.journeys.single().label)
    }

    @Test fun `return reverses the legs with swapped stations, replan keeps them, both ask for every date and time`() {
        val source = journey("trip", rome to florence to "08:00", florence to milan to "10:30")
        val model = model()

        model.beginReplan(source)
        val replan = requireNotNull(model.editor)
        assertEquals(listOf(rome to florence, florence to milan).map { (a, b) -> a.canonicalId to b.canonicalId },
            replan.legs.map { it.originStationId to it.destinationStationId })
        assertTrue(replan.legs.all { it.date.isEmpty() && it.time.isEmpty() && it.segmentId == null })
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_REPLAN_PREPARED), replan.notice)

        model.beginReturn(source)
        val back = requireNotNull(model.editor)
        assertEquals(listOf(milan to florence, florence to rome).map { (a, b) -> a.canonicalId to b.canonicalId },
            back.legs.map { it.originStationId to it.destinationStationId })
        assertNull(back.journeyId)
        model.saveEditor(catalogue)
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_EDITOR_INVALID), model.editor?.validationMessage)

        model.changeEditor { it.withLeg(0) { leg -> leg.copy(date = "2026-10-01", time = "18:00") } }
        model.changeEditor { it.withLeg(1) { leg -> leg.copy(time = "20:00") } }
        model.saveEditor(catalogue)
        val saved = model.snapshot.journeys.single()
        assertEquals(listOf(milan to florence, florence to rome), saved.itinerary.segments.map { it.origin to it.destination })
        assertEquals(listOf(LocalDate.parse("2026-10-01")), saved.itinerary.segments.map { it.departure.date }.distinct())
    }

    @Test fun `the stored Agenda format is the existing one`() {
        val storage = FakeStorage()
        val model = model(storage)
        model.beginCreate()
        model.changeEditor { it.withLeg(0) { leg -> leg.from(rome).to(florence).copy(date = "2026-10-01", time = "08:00") }.addLeg() }
        model.changeEditor { it.withLeg(1) { leg -> leg.to(milan).copy(time = "10:30") } }
        model.saveEditor(catalogue)
        val stored = requireNotNull(storage.value)
        val reread = PlannedJourneyRepository(FakeStorage(stored)).read()
        assertFalse(reread.readOnly)
        assertEquals(model.snapshot, reread.snapshot)
        assertEquals("a written Agenda encodes to the same bytes", PlannedJourneyTransfer.encode(reread.snapshot), stored)
    }

    @Test fun `a template is one leg, so applying one keeps one leg and a multi leg form cannot become one`() {
        val templates = object : PlannedJourneyTemplateStore {
            var value: String? = null
            override fun read(): String? = value
            override fun write(value: String?) { this.value = value }
        }
        val model = model(templates = templates)
        model.beginCreate()
        model.changeEditor { it.withLeg(0) { leg -> leg.from(rome).to(florence).copy(time = "08:00") } }
        model.saveTemplate("Firenze", catalogue)
        model.changeEditor { it.addLeg() }
        model.saveTemplate("Due tratte", catalogue)
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_TEMPLATE_INVALID), model.editor?.validationMessage)
        assertEquals(listOf("Firenze"), model.templates.map { it.name })

        model.applyTemplate(model.templates.single().id)
        assertEquals(1, requireNotNull(model.editor).legs.size)
    }

    private fun journey(id: String, vararg legs: Pair<Pair<PlannedJourneyStation, PlannedJourneyStation>, String>) = PlannedJourney(
        id = id,
        itinerary = PlannedJourneyItinerary(legs.mapIndexed { index, (stations, time) ->
            PlannedJourneySegment("segment-${index + 1}", stations.first, stations.second,
                PlannedJourneyDateTime(LocalDate.parse("2026-10-01"), LocalTime.parse(time), italy))
        }),
        createdAt = createdAt
    )

    private class FakeStorage(var value: String? = null) : PlannedJourneyStorage {
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }
}
