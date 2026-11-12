package it.binario.app.plannedjourney

import it.binario.app.UiMessage
import it.binario.app.UiMessageKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cases of the companion's local-route contract, on the Android Agenda (EXPO_PORT_PLAN §3.5). */
class PlannedJourneyCompanionContractTest {
    private val rome = PlannedJourneyStation("S08409", "Roma Termini", "RMTE")
    private val milan = PlannedJourneyStation("S01700", "Milano Centrale", "MICT")
    private val createdAt = Instant.parse("2026-09-01T08:00:00Z")
    private val italy = ZoneId.of("Europe/Rome")

    @Test fun `upcoming and archive split on the Italian departure, departed journeys in the archive newest first`() {
        val now = LocalDate.parse("2026-10-02").atTime(8, 0).atZone(italy).toInstant()
        val journeys = listOf(
            journey("a", "2026-10-01", "08:00"),
            journey("b", "2026-10-02", "08:15"),
            journey("c", "2026-10-09", "08:15")
        )
        assertEquals(listOf("b", "c"), PlannedJourneyAgenda.ordered(journeys, PlannedJourneyAgendaFilter.UPCOMING, now).map { it.journey.id })
        assertEquals(listOf("a"), PlannedJourneyAgenda.ordered(journeys, PlannedJourneyAgendaFilter.ARCHIVED, now).map { it.journey.id })
        val completed = PlannedJourneyLifecycle.complete(journeys[2], createdAt)
        assertEquals(
            listOf("c", "a"),
            PlannedJourneyAgenda.ordered(listOf(journeys[0], journeys[1], completed), PlannedJourneyAgendaFilter.ARCHIVED, now).map { it.journey.id }
        )
    }

    @Test fun `return swaps the stations, blanks date and time, unticks the checklist and drops the reminder`() {
        val repository = PlannedJourneyRepository(FakeStorage())
        val source = journey("out", "2026-10-01", "08:00").copy(
            reminder = PlannedJourneyReminder(PlannedJourneyReminderMinutes.THIRTY),
            checklist = listOf(PlannedJourneyChecklistItem("check-1", "Biglietto", completed = true))
        )
        repository.save(source)
        var ids = 0
        val model = PlannedJourneyViewModel(repository, now = { createdAt }, zone = italy, newId = { "new-${++ids}" })

        model.beginReturn(source)
        val editor = requireNotNull(model.editor)
        assertEquals(milan.canonicalId, editor.originStationId)
        assertEquals(rome.canonicalId, editor.destinationStationId)
        assertEquals("", editor.date)
        assertEquals("", editor.time)
        assertEquals(null, editor.reminderMinutes)
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_RETURN_PREPARED), editor.notice)
        assertEquals(null, editor.journeyId)

        model.saveEditor(listOf(rome, milan))
        assertEquals("the planner refuses until a date and time are chosen", 1, model.snapshot.journeys.size)
        model.changeEditor { it.copy(date = "2026-10-01", time = "18:30") }
        model.saveEditor(listOf(rome, milan))
        val back = model.snapshot.journeys.single { it.id != "out" }
        assertEquals(milan, back.itinerary.segments.single().origin)
        assertEquals(listOf(PlannedJourneyChecklistItem("check-1", "Biglietto", completed = false)), back.checklist)
        assertEquals(null, back.reminder)
    }

    @Test fun `replan keeps the stations and asks for a new date and time`() {
        val source = journey("out", "2026-10-01", "08:00")
        val model = PlannedJourneyViewModel(PlannedJourneyRepository(FakeStorage()), now = { createdAt }, zone = italy)
        model.beginReplan(source)
        val editor = requireNotNull(model.editor)
        assertEquals(rome.canonicalId, editor.originStationId)
        assertEquals(milan.canonicalId, editor.destinationStationId)
        assertEquals("" to "", editor.date to editor.time)
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_REPLAN_PREPARED), editor.notice)
    }

    @Test fun `the bike preset adds time margin, documents, belongings and bike after the user's own lines, once`() {
        val label: (PlannedJourneyChecklistLine) -> String = { it.name.lowercase() }
        val once = PlannedJourneyChecklistPresets.apply("  Acqua \n\nbelongings", PlannedJourneyChecklistPreset.BIKE, label)
        assertEquals("Acqua\nbelongings\ntime_margin\ndocuments\nbike", once)
        assertEquals(once, PlannedJourneyChecklistPresets.apply(once, PlannedJourneyChecklistPreset.BIKE, label))
        val full = (1..PlannedJourneyPersistenceSchema.MAX_CHECKLIST_ITEMS).joinToString("\n") { "voce $it" }
        assertEquals(full, PlannedJourneyChecklistPresets.apply(full, PlannedJourneyChecklistPreset.ASSISTANCE, label))
    }

    @Test fun `every localized preset line is a valid checklist label in all 17 languages`() {
        val folders = listOf(null, "en", "de", "es", "fr", "ar", "hi", "in", "ja", "ko", "my", "pt", "ru", "th", "tr", "vi", "zh-rCN")
        val keys = listOf("time_margin", "documents", "belongings", "connection", "bike", "assistance").map { "planned_journey_check_$it" }
        folders.forEach { folder ->
            val strings = it.binario.app.TestStringCatalogue.of(folder)
            val checklist = keys.mapIndexed { index, key ->
                PlannedJourneyChecklistItem("check-$index", requireNotNull(strings[key]) { "$folder: $key" })
            }
            val issues = PlannedJourneyValidator.validate(journey("preset", "2026-10-01", "08:00").copy(checklist = checklist))
            assertEquals("$folder: $issues", emptyList<PlannedJourneyValidationIssue>(), issues)
        }
    }

    private class FakeStorage(var value: String? = null) : PlannedJourneyStorage {
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }

    private fun journey(id: String, date: String, time: String) = PlannedJourney(
        id = id,
        itinerary = PlannedJourneyItinerary(listOf(PlannedJourneySegment(
            "segment-$id", rome, milan, PlannedJourneyDateTime(LocalDate.parse(date), LocalTime.parse(time), italy)
        ))),
        createdAt = createdAt
    )
}
