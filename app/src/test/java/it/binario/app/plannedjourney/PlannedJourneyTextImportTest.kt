package it.binario.app.plannedjourney

import it.binario.app.TestStringCatalogue
import it.binario.app.UiMessage
import it.binario.app.UiMessageKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guided text import, ported from the companion's import contract (EXPO_PORT_PLAN F22, §3.4). */
class PlannedJourneyTextImportTest {
    private val rome = PlannedJourneyStation("S08409", "Roma Termini", "RMTE")
    private val bologna = PlannedJourneyStation("S05043", "Bologna Centrale", "BOCE")
    private val milan = PlannedJourneyStation("S01700", "Milano Centrale", "MICT")
    private val stations = listOf(rome, bologna, milan)
    private val italy = ZoneId.of("Europe/Rome")
    private val createdAt = Instant.parse("2026-09-01T08:00:00Z")

    @Test fun `the example of every language parses into the same two trips`() {
        listOf(null, "en", "de", "es", "fr", "ar", "hi", "in", "ja", "ko", "my", "pt", "ru", "th", "tr", "vi", "zh-rCN").forEach { folder ->
            val example = requireNotNull(TestStringCatalogue.of(folder)["planned_journey_text_import_example"]) { "$folder" }
            val preview = preview(example)
            assertEquals("$folder: $example", 0, preview.skipped)
            assertEquals(
                "$folder",
                listOf("Roma Termini|Bologna Centrale|2026-09-12|08:30", "Bologna Centrale|Milano Centrale|2026-09-12|11:05"),
                preview.candidates.map { journey ->
                    val leg = journey.itinerary.segments.single()
                    "${leg.origin.displayName}|${leg.destination.displayName}|${leg.departure.date}|${leg.departure.time}"
                }
            )
        }
    }

    @Test fun `received text is bounded, unified and never blank`() {
        assertEquals(4_000, PlannedJourneyTextImport.normalize("a".repeat(4_000))?.length)
        assertNull(PlannedJourneyTextImport.normalize("a".repeat(4_001)))
        assertEquals("Roma → Napoli\n12/09/2026 alle 08:30", PlannedJourneyTextImport.normalize("  Roma → Napoli\r\n12/09/2026 alle 08:30 "))
        assertNull(PlannedJourneyTextImport.normalize(" \n\t "))
        assertNull(PlannedJourneyTextImport.normalize(null))
        assertEquals("ab", PlannedJourneyTextImport.normalize("a\u0000b"))
    }

    @Test fun `only complete lines are read`() {
        val parse = PlannedJourneyTextImport::parseLine
        assertEquals(
            PlannedJourneyTextLine("Roma Termini", "Milano Centrale", LocalDate.of(2026, 9, 12), LocalTime.of(8, 30)),
            parse("Da Roma Termini a Milano Centrale 12/09/2026 08:30")
        )
        assertEquals(
            PlannedJourneyTextLine("Roma", "Milano", LocalDate.of(2026, 9, 12), LocalTime.of(8, 30)),
            parse("Roma -> Milano · 2026-09-12 ore 8.30")
        )
        assertNull("no time", parse("Roma Termini → Milano Centrale · 12/09/2026"))
        assertNull("no date", parse("Roma Termini → Milano Centrale alle 08:30"))
        assertNull("time before date", parse("Roma Termini → Milano Centrale 08:30 12/09/2026"))
        assertNull("same stations", parse("Roma Termini → roma termini · 12/09/2026 alle 08:30"))
        assertNull("impossible date", parse("Roma Termini → Milano Centrale · 31/02/2026 alle 08:30"))
        assertNull("year required", parse("Roma Termini → Milano Centrale · 12/09 alle 08:30"))
        assertNull("no hour 24", parse("Roma Termini → Milano Centrale · 12/09/2026 alle 24:00"))
        assertNull("no route", parse("ritardo del treno 12/09/2026 alle 08:30"))
    }

    @Test fun `duplicates, unknown stations and journeys already in the Agenda are counted as skipped`() {
        val text = listOf(
            "Roma Termini → Milano Centrale · 12/09/2026 alle 08:30",
            "roma termini -> MILANO CENTRALE 12/09/2026 08:30",
            "Roma → Milano · 12/09/2026 alle 09:30",
            "Bologna Centrale → Milano Centrale · 13/09/2026 alle 10:00",
            "note senza data"
        ).joinToString("\n")
        val already = PlannedJourney(
            id = "saved",
            itinerary = PlannedJourneyItinerary(listOf(PlannedJourneySegment("s", bologna, milan,
                PlannedJourneyDateTime(LocalDate.of(2026, 9, 13), LocalTime.of(10, 0), italy)))),
            createdAt = createdAt
        )
        val preview = preview(text, existing = listOf(already))
        assertEquals(listOf(rome), preview.candidates.map { it.itinerary.segments.single().origin })
        assertEquals(4, preview.skipped)
    }

    @Test fun `the proposal stops at the Agenda's free places and says how many lines were left out`() {
        val existing = (0 until PlannedJourneyPersistenceSchema.MAX_JOURNEYS - 2).map { index ->
            PlannedJourney(
                id = "saved-$index",
                itinerary = PlannedJourneyItinerary(listOf(PlannedJourneySegment("s", rome, milan,
                    PlannedJourneyDateTime(LocalDate.of(2027, 1, 1).plusDays(index.toLong()), LocalTime.of(7, 0), italy)))),
                createdAt = createdAt
            )
        }
        val text = (1..5).joinToString("\n") { day -> "Roma Termini → Bologna Centrale · 0$day/10/2026 alle 08:30" }
        val preview = preview(text, existing = existing)
        assertEquals(2, preview.candidates.size)
        assertEquals(3, preview.skipped)
    }

    @Test fun `nothing is saved before the explicit review and only the selected lines are added`() {
        val storage = FakeStorage()
        var ids = 0
        val model = PlannedJourneyViewModel(PlannedJourneyRepository(storage), now = { createdAt }, zone = italy, newId = { "text-${++ids}" })
        model.beginTextImport()
        model.changeTextImport(requireNotNull(TestStringCatalogue.of()["planned_journey_text_import_example"]))
        model.previewTextImport(stations)
        val state = requireNotNull(model.textImport)
        assertEquals(2, state.candidates?.size)
        model.applyTextImport()
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_TEXT_IMPORT_CONFIRM_NEEDED), model.textImport?.message)
        assertNull(storage.value)

        model.toggleTextImportCandidate(state.candidates!!.last().id)
        model.setTextImportReviewed(true)
        model.applyTextImport()
        assertNull(model.textImport)
        assertEquals(listOf(rome), model.snapshot.journeys.map { it.itinerary.segments.single().origin })

        model.beginTextImport("Testo senza tratte")
        model.previewTextImport(stations)
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_TEXT_IMPORT_NONE), model.textImport?.message)
        assertEquals(1, model.snapshot.journeys.size)
    }

    @Test fun `a hidden import keeps its text until it is completed`() {
        val model = PlannedJourneyViewModel(PlannedJourneyRepository(FakeStorage()), now = { createdAt }, zone = italy)
        model.beginTextImport()
        model.changeTextImport("Roma Termini → Milano Centrale")
        model.dismissTextImport()
        model.beginTextImport()
        assertEquals("Roma Termini → Milano Centrale", model.textImport?.text)
        assertTrue(model.textImport?.visible == true)
    }

    @Test fun `only plain text sent with Condividi is accepted, bounded like a paste`() {
        val send = PlannedJourneySharedText.ACTION_SEND
        assertEquals("Roma → Napoli", PlannedJourneySharedText.accept(send, "text/plain", " Roma → Napoli\r\n"))
        assertEquals("x", PlannedJourneySharedText.accept(send, "TEXT/PLAIN; charset=utf-8", "x"))
        assertNull(PlannedJourneySharedText.accept(send, "text/html", "x"))
        assertNull(PlannedJourneySharedText.accept(send, "image/png", "x"))
        assertNull(PlannedJourneySharedText.accept(send, null, "x"))
        assertNull(PlannedJourneySharedText.accept("android.intent.action.SEND_MULTIPLE", "text/plain", "x"))
        assertNull(PlannedJourneySharedText.accept("android.intent.action.VIEW", "text/plain", "x"))
        assertNull(PlannedJourneySharedText.accept(send, "text/plain", "a".repeat(4_001)))
        assertNull(PlannedJourneySharedText.accept(send, "text/plain", null))
    }

    @Test fun `shared text replaces an earlier draft, opens the preview step and is never written`() {
        val storage = FakeStorage()
        val model = PlannedJourneyViewModel(PlannedJourneyRepository(storage), now = { createdAt }, zone = italy)
        model.beginTextImport()
        model.changeTextImport("bozza")
        model.dismissTextImport()
        model.beginTextImport("Roma Termini → Milano Centrale · 12/09/2026 alle 08:30", fromShare = true)
        val state = requireNotNull(model.textImport)
        assertTrue(state.fromShare && state.visible)
        assertEquals("Roma Termini → Milano Centrale · 12/09/2026 alle 08:30", state.text)
        assertNull(state.candidates)
        assertNull(storage.value)
    }

    private fun preview(text: String, existing: List<PlannedJourney> = emptyList()): PlannedJourneyTextImportPreview {
        var ids = 0
        return PlannedJourneyTextImport.preview(text, stations, existing, italy, createdAt) { "id-${++ids}" }
    }

    private class FakeStorage(var value: String? = null) : PlannedJourneyStorage {
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }
}
