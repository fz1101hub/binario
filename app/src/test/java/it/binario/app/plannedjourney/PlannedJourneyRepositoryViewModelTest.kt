package it.binario.app.plannedjourney

import androidx.lifecycle.SavedStateHandle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannedJourneyRepositoryViewModelTest {
    private val rome = PlannedJourneyStation("IT:ROMA-TERMINI", "Roma Termini", "RMTE")
    private val milan = PlannedJourneyStation("IT:MILANO-CENTRALE", "Milano Centrale", "MICT")
    private val createdAt = Instant.parse("2026-09-01T08:00:00Z")

    @Test
    fun `repository persists bounded snapshot separately and merges preview only after approval`() {
        val storage = FakeStorage()
        val repository = PlannedJourneyRepository(storage)
        val initial = journey("saved", "2026-10-01", "08:00")
        assertTrue(repository.save(initial) is PlannedJourneyRepositorySaveResult.Saved)
        assertTrue(storage.value.orEmpty().contains("binario_planned_journeys"))

        val incoming = journey("incoming", "2026-10-02", "09:00")
        val encoded = PlannedJourneyTransfer.encode(PlannedJourneyPersistenceSnapshot(journeys = listOf(incoming)))
        val preview = (repository.previewJsonImport(encoded) as PlannedJourneyJsonImportResult.Preview).preview
        assertEquals(listOf("incoming"), preview.accepted.map { it.id })
        assertEquals(listOf("saved"), repository.read().snapshot.journeys.map { it.id })

        val result = repository.applyJsonPreview(preview, PlannedJourneyConflictPolicy.KEEP_EXISTING)
        assertTrue(result is PlannedJourneyRepositorySaveResult.Saved)
        assertEquals(listOf("saved", "incoming"), repository.read().snapshot.journeys.map { it.id })
    }

    @Test
    fun `repository previews owned ics conflicts without turning calendar into editable plans`() {
        val repository = PlannedJourneyRepository(FakeStorage())
        val journey = journey("saved", "2026-10-01", "08:00", PlannedJourneyReminder(PlannedJourneyReminderMinutes.TEN))
        repository.save(journey)
        val exported = repository.exportIcs(Instant.parse("2026-09-01T09:00:00Z"))

        val preview = (repository.previewIcsImport(exported) as PlannedJourneyIcsImportResult.Preview).preview
        assertEquals(1, preview.matchingEvents.size)
        assertTrue(preview.conflictingEvents.isEmpty())
        assertTrue(preview.unlinkedEvents.isEmpty())
        assertEquals(listOf("saved"), repository.read().snapshot.journeys.map { it.id })
    }

    @Test
    fun `view model creates catalog backed journey updates checklist and lifecycle`() {
        val repository = PlannedJourneyRepository(FakeStorage())
        val model = PlannedJourneyViewModel(
            repository = repository,
            now = { createdAt },
            zone = ZoneId.of("Europe/Rome"),
            newId = { "agenda-1" }
        )
        model.beginCreate()
        model.changeEditor {
            it.copy(
                label = "Riunione",
                originQuery = "Roma Termini",
                destinationQuery = "Milano Centrale",
                date = "2026-10-01",
                time = "08:15",
                reminderMinutes = PlannedJourneyReminderMinutes.THIRTY,
                checklistText = "Biglietto\nAcqua"
            )
        }
        model.saveEditor(listOf(rome, milan))

        val saved = model.snapshot.journeys.single()
        assertEquals("agenda-1", saved.id)
        assertEquals(2, saved.checklist.size)
        assertEquals(30, saved.reminder?.minutesBeforeDeparture?.minutes)

        model.toggleChecklist(saved.id, saved.checklist.first())
        assertTrue(model.snapshot.journeys.single().checklist.first().completed)
        val entry = model.agendaEntries().single()
        model.complete(entry)
        assertEquals(PlannedJourneyStatus.COMPLETED, model.snapshot.journeys.single().status)
    }

    @Test
    fun `view model presents json conflict choice before it writes`() {
        val repository = PlannedJourneyRepository(FakeStorage())
        val existing = journey("same-id", "2026-10-01", "08:00")
        repository.save(existing)
        val conflicting = journey("same-id", "2026-10-02", "09:00")
        val model = PlannedJourneyViewModel(repository, now = { createdAt }, zone = ZoneId.of("Europe/Rome"))
        model.previewImport(
            PlannedJourneyTransfer.encode(PlannedJourneyPersistenceSnapshot(journeys = listOf(conflicting))),
            isIcs = false
        )
        val preview = model.importState as PlannedJourneyImportState.Json
        assertEquals(1, preview.preview.conflicts.size)
        assertEquals("2026-10-01", model.snapshot.journeys.single().itinerary.segments.single().departure.date.toString())

        model.applyJsonImport(PlannedJourneyConflictPolicy.REPLACE_EXISTING)
        assertEquals("2026-10-02", model.snapshot.journeys.single().itinerary.segments.single().departure.date.toString())
    }

    @Test
    fun `agenda filter and pending export descriptor restore without saving document payload`() {
        val savedState = SavedStateHandle()
        val repository = PlannedJourneyRepository(FakeStorage())
        val first = PlannedJourneyViewModel(
            repository = repository,
            savedStateHandle = savedState,
            now = { createdAt },
            zone = ZoneId.of("Europe/Rome")
        )
        first.selectFilter(PlannedJourneyAgendaFilter.ARCHIVED)
        first.prepareExport(PlannedJourneyExportFormat.JSON)

        val restoredState = SavedStateHandle(
            savedState.keys().associateWith { key -> savedState.get<Any>(key) }
        )
        val restored = PlannedJourneyViewModel(
            repository = repository,
            savedStateHandle = restoredState,
            now = { createdAt },
            zone = ZoneId.of("Europe/Rome")
        )

        assertEquals(PlannedJourneyAgendaFilter.ARCHIVED, restored.filter)
        assertEquals(PlannedJourneyExportFormat.JSON, restored.pendingExport)
        assertEquals(
            setOf(PlannedJourneyViewModel.AGENDA_FILTER_KEY, PlannedJourneyViewModel.PENDING_EXPORT_KEY),
            restoredState.keys()
        )
        assertTrue(restoredState.keys().all { restoredState.get<Any>(it) is String })
    }

    @Test
    fun `restored pending export regenerates current repository content at SAF result time`() {
        val savedState = SavedStateHandle()
        val storage = FakeStorage()
        val repository = PlannedJourneyRepository(storage)
        repository.save(journey("before-request", "2026-10-01", "08:00"))
        PlannedJourneyViewModel(
            repository = repository,
            savedStateHandle = savedState,
            now = { createdAt }
        ).prepareExport(PlannedJourneyExportFormat.JSON)

        repository.save(journey("after-request", "2026-10-02", "09:00"))
        val restored = PlannedJourneyViewModel(
            repository = repository,
            savedStateHandle = SavedStateHandle(
                savedState.keys().associateWith { key -> savedState.get<Any>(key) }
            ),
            now = { createdAt }
        )
        val format = restored.consumePendingExport()
        val exported = PlannedJourneyTransfer.decode(restored.export(requireNotNull(format)))
            as PlannedJourneyTransferDecodeResult.Valid

        assertEquals(listOf("before-request", "after-request"), exported.snapshot.journeys.map { it.id })
        assertEquals(null, restored.pendingExport)
    }

    private fun journey(
        id: String,
        date: String,
        time: String,
        reminder: PlannedJourneyReminder? = null
    ): PlannedJourney = PlannedJourney(
        id = id,
        label = "Viaggio $id",
        itinerary = PlannedJourneyItinerary(
            listOf(
                PlannedJourneySegment(
                    "segment-$id",
                    rome,
                    milan,
                    PlannedJourneyDateTime(LocalDate.parse(date), LocalTime.parse(time), ZoneId.of("Europe/Rome"))
                )
            )
        ),
        reminder = reminder,
        createdAt = createdAt
    )

    private class FakeStorage(var value: String? = null) : PlannedJourneyStorage {
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }
}
