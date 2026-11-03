package it.binario.app.plannedjourney

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class PlannedJourneyIcsTest {
    @Test
    fun `exports and parses owned events with deterministic reminder metadata`() {
        val journey = journey("ics-one", "Roma, andata; \\ andata con bagaglio")
            .copy(reminder = PlannedJourneyReminder(PlannedJourneyReminderMinutes.FIFTEEN))
        val generatedAt = Instant.parse("2026-09-23T07:00:00Z")

        val first = PlannedJourneyIcs.export(listOf(journey), generatedAt)
        val second = PlannedJourneyIcs.export(listOf(journey), generatedAt)
        val result = PlannedJourneyIcs.parseOwnedCalendar(first)

        assertEquals(first, second)
        assertTrue(first.contains("\r\n"))
        assertTrue(first.contains("TRIGGER:-PT15M"))
        assertTrue(first.contains("SUMMARY:Roma\\, andata\\; \\\\ andata con bagaglio"))
        val event = (result as PlannedJourneyIcsParseResult.Valid).events.single()
        assertEquals("ics-one", event.journeyId)
        assertEquals(LocalDate.of(2026, 9, 23), event.occurrenceDate)
        assertEquals(Instant.parse("2026-09-23T07:15:00Z"), event.startsAt)
        assertEquals("Roma, andata; \\ andata con bagaglio", event.summary)
        assertEquals(15, event.reminderMinutes)
    }

    @Test
    fun `folds every physical line at no more than 75 UTF8 octets`() {
        val longUnicodeLabel = "Viaggio " + "è lungo ".repeat(10)

        val calendar = PlannedJourneyIcs.export(listOf(journey("long", longUnicodeLabel)), Instant.EPOCH)

        calendar.split("\r\n").filter { it.isNotEmpty() }.forEach { line ->
            assertTrue("Line exceeds 75 octets: $line", line.toByteArray(StandardCharsets.UTF_8).size <= 75)
        }
        val event = (PlannedJourneyIcs.parseOwnedCalendar(calendar) as PlannedJourneyIcsParseResult.Valid).events.single()
        assertEquals(longUnicodeLabel, event.summary)
    }

    @Test
    fun `rejects foreign oversized and tampered calendars`() {
        val foreign = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Other//EN\r\nEND:VCALENDAR\r\n"
        assertTrue(PlannedJourneyIcs.parseOwnedCalendar(foreign) is PlannedJourneyIcsParseResult.Invalid)

        val oversized = "x".repeat(PlannedJourneyIcsContract.MAX_BYTES + 1)
        assertTrue(PlannedJourneyIcs.parseOwnedCalendar(oversized) is PlannedJourneyIcsParseResult.Invalid)

        val owned = PlannedJourneyIcs.export(listOf(journey("tampered", "Viaggio")), Instant.EPOCH)
        val tampered = owned.replace(Regex("UID:[^\\r]+"), "UID:wrong@binario.local")
        assertTrue(PlannedJourneyIcs.parseOwnedCalendar(tampered) is PlannedJourneyIcsParseResult.Invalid)
    }

    private fun journey(id: String, label: String): PlannedJourney = PlannedJourney(
        id = id,
        label = label,
        itinerary = PlannedJourneyItinerary(
            listOf(
                PlannedJourneySegment(
                    id = "$id-segment",
                    origin = PlannedJourneyStation("roma-termini", "Roma Termini"),
                    destination = PlannedJourneyStation("milano-centrale", "Milano Centrale"),
                    departure = PlannedJourneyDateTime(
                        LocalDate.of(2026, 9, 23),
                        LocalTime.of(9, 15),
                        ZoneId.of("Europe/Rome")
                    )
                )
            )
        ),
        createdAt = Instant.parse("2026-09-01T08:00:00Z")
    )
}
