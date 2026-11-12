package it.binario.app.plannedjourney

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calendar files of other apps (EXPO_PORT_PLAN F25, R22; §3.2 import cases with audit improvements 7 and 8). */
class PlannedJourneyForeignIcsTest {
    private val rome = PlannedJourneyStation("S08409", "Roma Termini", "RMTE")
    private val milan = PlannedJourneyStation("S01700", "Milano Centrale", "MICT")
    private val bologna = PlannedJourneyStation("S05043", "Bologna Centrale", "BOCE")
    private val italy = ZoneId.of("Europe/Rome")
    private val createdAt = Instant.parse("2026-09-01T08:00:00Z")

    private fun departure(line: String): LocalDateTime? =
        PlannedJourneyForeignIcs.italianDeparture(requireNotNull(PlannedJourneyForeignIcs.property(line)))

    @Test fun `start times become Italian wall-clock times and unknown zones are refused`() {
        assertEquals(LocalDateTime.parse("2026-11-12T08:00"), departure("DTSTART:20261112T070000Z"))
        assertEquals(LocalDateTime.parse("2026-07-12T17:30"), departure("DTSTART:20260712T153000Z"))
        assertEquals(LocalDateTime.parse("2026-11-12T00:30"), departure("DTSTART:20261111T233000Z"))
        assertEquals(LocalDateTime.parse("2026-07-12T09:00"), departure("DTSTART;TZID=Europe/Rome:20260712T090000"))
        assertEquals(LocalDateTime.parse("2026-07-12T10:00"), departure("DTSTART;TZID=\"Europe/London\":20260712T090000"))
        assertEquals(LocalDateTime.parse("2026-07-12T09:00"), departure("DTSTART:20260712T090000"))
        assertEquals(LocalDateTime.parse("2026-11-12T08:15"), departure("DTSTART;VALUE=DATE-TIME:20261112T081500"))
        assertNull("all-day", departure("DTSTART;VALUE=DATE:20261112"))
        assertNull("not an IANA zone: refused, not read as Italian time", departure("DTSTART;TZID=W. Europe Standard Time:20260712T090000"))
        assertNull("time missing in New York's spring gap", departure("DTSTART;TZID=America/New_York:20270314T023000"))
    }

    @Test fun `a quoted TZID with a colon is read as one parameter`() {
        val property = requireNotNull(PlannedJourneyForeignIcs.property("DTSTART;TZID=\"(UTC+01:00) Amsterdam, Berlin, Bern, Rome\":20260712T090000"))
        assertEquals("DTSTART", property.name)
        assertEquals("(UTC+01:00) Amsterdam, Berlin, Bern, Rome", property.parameters["TZID"])
        assertEquals("20260712T090000", property.value)
        assertNull("a Windows zone name is not guessed", PlannedJourneyForeignIcs.italianDeparture(property))
    }

    @Test fun `route events become reviewed proposals, everything else is counted as skipped`() {
        val calendar = calendar(
            event("SUMMARY:Roma Termini → Milano Centrale", "DTSTART:20261112T070000Z"),
            event("SUMMARY:Roma Termini → Milano Centrale", "DTSTART;TZID=Europe/Rome:20261112T080000"),
            event("SUMMARY:Bologna Centrale -> Milano Centrale", "DTSTART:20261112T070000Z"),
            event("SUMMARY:Riunione", "DTSTART:20261112T100000"),
            event("SUMMARY:Roma Termini → Bologna Centrale", "DTSTART;VALUE=DATE:20261113"),
            event("SUMMARY:Roma Termini → Bologna Centrale", "DTSTART:20270328T023000"),
            event("SUMMARY:Milano Centrale → Bologna Centrale", "DTSTART;TZID=W. Europe Standard Time:20261114T090000"),
            event("SUMMARY:Bologna Centrale → Roma Termini", "DTSTART:20261115T090000")
        )
        assertTrue(PlannedJourneyForeignIcs.isForeign(calendar))
        val read = requireNotNull(PlannedJourneyForeignIcs.read(calendar))
        assertEquals(3, read.skipped)
        val already = PlannedJourney(
            id = "saved",
            itinerary = PlannedJourneyItinerary(listOf(PlannedJourneySegment("s", bologna, rome,
                PlannedJourneyDateTime(LocalDate.of(2026, 11, 15), LocalTime.of(9, 0), italy)))),
            createdAt = createdAt
        )
        var ids = 0
        val preview = PlannedJourneyTextImport.previewLines(read.lines, read.skipped, listOf(rome, milan, bologna), listOf(already), italy, createdAt) { "c-${++ids}" }
        assertEquals(
            "identical events merged, two trips at the same time kept",
            listOf("Roma Termini 08:00", "Bologna Centrale 08:00"),
            preview.candidates.map { "${it.itinerary.segments.single().origin.displayName} ${it.itinerary.segments.single().departure.time}" }
        )
        assertEquals("3 unreadable + duplicate + spring gap + already in the Agenda", 6, preview.skipped)
    }

    @Test fun `folded and escaped summaries are joined, the file is bounded by bytes and events`() {
        val lines = PlannedJourneyForeignIcs.unfold("SUMMARY:Roma Termini → Milano Centrale via Ro\r\n ma\\, binari regionali\r\n")
        assertEquals("Roma Termini → Milano Centrale via Roma, binari regionali",
            PlannedJourneyForeignIcs.unescape(requireNotNull(PlannedJourneyForeignIcs.property(lines.single())).value))
        assertNull(PlannedJourneyForeignIcs.read("BEGIN:VCALENDAR\n" + "X".repeat(PlannedJourneyForeignIcs.MAX_BYTES) + "\nEND:VCALENDAR"))
        val many = calendar(*(1..PlannedJourneyForeignIcs.MAX_EVENTS + 5).map { index ->
            event("SUMMARY:Roma Termini → Milano Centrale", "DTSTART:2026111${index % 10}T0${index % 10}0000Z")
        }.toTypedArray())
        val read = requireNotNull(PlannedJourneyForeignIcs.read(many))
        assertEquals(PlannedJourneyForeignIcs.MAX_EVENTS + 5, read.lines.size + read.skipped)
        assertTrue(read.skipped >= 5)
    }

    @Test fun `Binario's own export keeps its integrity preview and other calendars open the reviewed import`() {
        val repository = PlannedJourneyRepository(FakeStorage())
        repository.save(PlannedJourney(
            id = "own",
            itinerary = PlannedJourneyItinerary(listOf(PlannedJourneySegment("s", rome, milan,
                PlannedJourneyDateTime(LocalDate.of(2026, 10, 1), LocalTime.of(8, 0), italy)))),
            createdAt = createdAt
        ))
        val own = repository.exportIcs(createdAt)
        assertFalse(PlannedJourneyForeignIcs.isForeign(own))
        val model = PlannedJourneyViewModel(repository, now = { createdAt }, zone = italy)
        model.previewImport(own, isIcs = true, stations = listOf(rome, milan))
        assertTrue(model.importState is PlannedJourneyImportState.Ics)
        assertNull(model.textImport)

        model.dismissImportPreview()
        model.previewImport(calendar(event("SUMMARY:Milano Centrale → Roma Termini", "DTSTART:20261002T150000Z")), isIcs = true, stations = listOf(rome, milan))
        val state = requireNotNull(model.textImport)
        assertTrue(state.fromCalendar)
        assertEquals(1, state.candidates?.size)
        assertEquals(1, repository.read().snapshot.journeys.size)
    }

    private fun event(vararg properties: String) = (listOf("BEGIN:VEVENT", "UID:${properties.joinToString().hashCode()}@test") + properties + "END:VEVENT").joinToString("\r\n")

    private fun calendar(vararg events: String) =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Other//Calendar//EN") + events + "END:VCALENDAR").joinToString("\r\n")

    private class FakeStorage(var value: String? = null) : PlannedJourneyStorage {
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }
}
