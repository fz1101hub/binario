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

    @Test fun `the default lead is proposed to new journeys and returns`() {
        val preferences = object : PlannedJourneyPreferences {
            var stored: PlannedJourneyReminderMinutes? = null
            override fun defaultReminder() = stored
            override fun setDefaultReminder(value: PlannedJourneyReminderMinutes?) { stored = value }
        }
        val repository = PlannedJourneyRepository(FakeStorage())
        val model = PlannedJourneyViewModel(repository, now = { createdAt }, zone = italy, preferences = preferences)
        model.beginCreate()
        assertEquals(null, model.editor?.reminderMinutes)
        model.selectDefaultReminder(PlannedJourneyReminderMinutes.FIFTEEN)
        assertEquals(PlannedJourneyReminderMinutes.FIFTEEN, preferences.stored)
        val restored = PlannedJourneyViewModel(repository, now = { createdAt }, zone = italy, preferences = preferences)
        restored.beginCreate()
        assertEquals(PlannedJourneyReminderMinutes.FIFTEEN, restored.editor?.reminderMinutes)
        restored.beginReturn(journey("out", "2026-10-01", "08:00").copy(reminder = PlannedJourneyReminder(PlannedJourneyReminderMinutes.SIXTY)))
        assertEquals(PlannedJourneyReminderMinutes.FIFTEEN, restored.editor?.reminderMinutes)
    }

    @Test fun `next journey skips the departed one and counts down in rounded-up minutes`() {
        val now = LocalDate.parse("2026-10-02").atTime(8, 20).atZone(italy).toInstant()
        val early = journey("early", "2026-10-02", "08:15")
        val later = journey("later", "2026-10-02", "09:30")
        val next = requireNotNull(nextPlannedJourney(listOf(early, later), now))
        assertEquals("later", next.journey.id)
        assertEquals(PlannedJourneyCountdown(1, 10), PlannedJourneyCountdown.between(now, next.occurrence.scheduledAt))
        assertEquals(null, nextPlannedJourney(listOf(early), now))
        assertEquals(PlannedJourneyCountdown(0, 1), PlannedJourneyCountdown.between(now, now.plusSeconds(1)))
        assertEquals(PlannedJourneyCountdown(2, 0), PlannedJourneyCountdown.between(now, now.plusSeconds(7_200)))
        assertEquals(PlannedJourneyCountdown(0, 0), PlannedJourneyCountdown.between(now, now.minusSeconds(60)))
        val completed = PlannedJourneyLifecycle.complete(later, createdAt)
        assertEquals(null, nextPlannedJourney(listOf(early, completed), now))
    }

    @Test fun `the countdown follows the Italian departure whatever the phone's zone`() {
        val original = java.util.TimeZone.getDefault()
        try {
            listOf("Europe/London", "America/New_York", "Asia/Tokyo").forEach { phone ->
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(phone))
                val train = journey("summer", "2026-07-12", "09:00")
                val next = requireNotNull(nextPlannedJourney(listOf(train), Instant.parse("2026-07-12T06:30:00Z")))
                assertEquals(phone, PlannedJourneyCountdown(0, 30), PlannedJourneyCountdown.between(Instant.parse("2026-07-12T06:30:00Z"), next.occurrence.scheduledAt))
                assertEquals(phone, null, nextPlannedJourney(listOf(train), Instant.parse("2026-07-12T07:30:00Z")))
            }
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    @Test fun `a repeated journey always includes its start day, is stored once and the same recurrence is a duplicate`() {
        val storage = FakeStorage()
        var ids = 0
        val model = PlannedJourneyViewModel(PlannedJourneyRepository(storage), now = { createdAt }, zone = italy, newId = { "r-${++ids}" })
        repeat(2) {
            model.beginCreate()
            // 2026-10-06 is a Tuesday: Monday and Thursday are added, Tuesday comes with the date.
            model.changeEditor { it.copy(originQuery = rome.displayName, destinationQuery = milan.displayName, date = "2026-10-06",
                time = "07:10", repeatCount = 4, repeatWeekdays = setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.THURSDAY)) }
            model.saveEditor(listOf(rome, milan))
        }
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_DUPLICATE), model.editor?.validationMessage)
        val saved = model.snapshot.journeys.single()
        assertEquals(listOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.THURSDAY), saved.recurrence?.weekdays)
        assertEquals(
            listOf("2026-10-06", "2026-10-08", "2026-10-12", "2026-10-13"),
            PlannedJourneyAgenda.ordered(listOf(saved), PlannedJourneyAgendaFilter.UPCOMING, createdAt).map { it.occurrence.occurrenceDate.toString() }
        )
        assertEquals(emptyList<LocalDate>(), saved.completedOccurrenceDates)

        model.dismissEditor()
        model.complete(PlannedJourneyAgenda.ordered(listOf(saved), PlannedJourneyAgendaFilter.UPCOMING, createdAt)[2])
        model.beginEdit(model.snapshot.journeys.single())
        assertEquals(4, model.editor?.repeatCount)
        model.changeEditor { it.copy(repeatCount = 2) }
        model.saveEditor(listOf(rome, milan))
        val edited = model.snapshot.journeys.single()
        assertEquals(2, edited.recurrence?.occurrenceLimit)
        assertEquals("a completed date the shorter recurrence no longer generates is dropped", emptyList<LocalDate>(), edited.completedOccurrenceDates)
        model.beginEdit(edited)
        model.changeEditor { it.copy(repeatCount = 1) }
        model.saveEditor(listOf(rome, milan))
        assertEquals(null, model.snapshot.journeys.single().recurrence)
    }

    @Test fun `a calendar event of one occurrence starts at the Italian departure and invents no end`() {
        val original = java.util.TimeZone.getDefault()
        try {
            listOf("UTC", "America/New_York").forEach { phone ->
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(phone))
                val entry = requireNotNull(nextPlannedJourney(listOf(journey("late", "2026-07-12", "23:30")), Instant.parse("2026-07-01T00:00:00Z")))
                val event = entry.calendarEvent(PlannedJourneyIcsText.ITALIAN, "Milano; binario, da verificare")
                assertEquals(phone, Instant.parse("2026-07-12T21:30:00Z").toEpochMilli(), event.beginEpochMillis)
                assertEquals(null, event.endEpochMillis)
                assertEquals("Roma Termini", event.location)
                assertEquals("Viaggio: Roma Termini → Milano Centrale, partenza 2026-07-12 alle 23:30 (Europe/Rome)", event.description)
                assertEquals("Milano; binario, da verificare", event.title)
            }
        } finally {
            java.util.TimeZone.setDefault(original)
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
