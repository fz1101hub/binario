package it.binario.app.plannedjourney

import it.binario.app.UiMessage
import it.binario.app.UiMessageKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored Agenda is never overwritten by a write it cannot read back (ported from the companion corruption cases). */
class PlannedJourneyAgendaSafetyTest {
    private val rome = PlannedJourneyStation("IT:ROMA-TERMINI", "Roma Termini", "RMTE")
    private val milan = PlannedJourneyStation("IT:MILANO-CENTRALE", "Milano Centrale", "MICT")
    private val createdAt = Instant.parse("2026-09-01T08:00:00Z")

    @Test fun `one unreadable record keeps the readable ones on screen and makes the stored Agenda read-only`() {
        val stored = storedWithBrokenMiddleRecord()
        val storage = FakeStorage(stored)
        val repository = PlannedJourneyRepository(storage)

        val read = repository.read()
        assertTrue(read.readOnly)
        assertEquals(1, read.unreadableRecords)
        assertEquals(listOf("first", "third"), read.snapshot.journeys.map { it.id })

        val added = repository.save(journey("added", "2026-10-03", "10:00"))
        assertEquals(PlannedJourneyRejection.READ_ONLY, (added as PlannedJourneyRepositorySaveResult.Rejected).reason)
        val removed = repository.remove("first")
        assertEquals(PlannedJourneyRejection.READ_ONLY, (removed as PlannedJourneyRepositorySaveResult.Rejected).reason)
        assertTrue(repository.complete("third", createdAt) is PlannedJourneyRepositorySaveResult.Rejected)
        assertEquals(stored, storage.value)
        assertEquals(0, storage.writes)
    }

    @Test fun `the readable journeys of a read-only Agenda can still be exported`() {
        val repository = PlannedJourneyRepository(FakeStorage(storedWithBrokenMiddleRecord()))
        val exported = PlannedJourneyTransfer.decode(repository.exportJson()) as PlannedJourneyTransferDecodeResult.Valid
        assertEquals(listOf("first", "third"), exported.snapshot.journeys.map { it.id })
    }

    @Test fun `a document of another kind or version recovers nothing and is never overwritten`() {
        listOf(
            "{not json",
            JSONObject().put("kind", "other").put("schemaVersion", 1).put("journeys", org.json.JSONArray()).toString(),
            JSONObject().put("kind", PlannedJourneyTransferContract.KIND).put("schemaVersion", 2).put("journeys", org.json.JSONArray()).toString()
        ).forEach { stored ->
            val storage = FakeStorage(stored)
            val repository = PlannedJourneyRepository(storage)
            val read = repository.read()
            assertTrue(stored, read.readOnly)
            assertTrue(stored, read.snapshot.journeys.isEmpty())
            assertTrue(repository.save(journey("added", "2026-10-03", "10:00")) is PlannedJourneyRepositorySaveResult.Rejected)
            assertEquals(stored, storage.value)
        }
    }

    @Test fun `duplicate stored ids keep the first record only`() {
        val valid = JSONObject(PlannedJourneyTransfer.encode(PlannedJourneyPersistenceSnapshot(journeys = listOf(
            journey("same", "2026-10-01", "08:00"), journey("other", "2026-10-02", "09:00")
        ))))
        valid.getJSONArray("journeys").getJSONObject(1).put("id", "same")
        val read = PlannedJourneyRepository(FakeStorage(valid.toString())).read()
        assertTrue(read.readOnly)
        assertEquals(listOf(LocalDate.parse("2026-10-01")), read.snapshot.journeys.map { it.itinerary.segments.single().departure.date })
        assertEquals(1, read.unreadableRecords)
    }

    @Test fun `a readable Agenda is writable and carries no warning`() {
        val storage = FakeStorage()
        val repository = PlannedJourneyRepository(storage)
        assertTrue(repository.save(journey("first", "2026-10-01", "08:00")) is PlannedJourneyRepositorySaveResult.Saved)
        val read = repository.read()
        assertFalse(read.readOnly)
        assertNull(read.warning)
        assertEquals(0, read.unreadableRecords)
    }

    @Test fun `the view model shows the read-only message, not a field error, and keeps the editor open`() {
        val storage = FakeStorage(storedWithBrokenMiddleRecord())
        val model = PlannedJourneyViewModel(PlannedJourneyRepository(storage), now = { createdAt }, zone = ZoneId.of("Europe/Rome"))
        assertTrue(model.readOnly)
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_STORED_UNREADABLE), model.warning)
        assertEquals(2, model.snapshot.journeys.size)

        model.remove("first")
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_READ_ONLY), model.feedback)
        model.beginCreate()
        model.changeEditor { it.copy(originQuery = rome.displayName, destinationQuery = milan.displayName, date = "2026-10-05", time = "08:00") }
        model.saveEditor(listOf(rome, milan))
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_READ_ONLY), model.editor?.validationMessage)
        assertEquals(storedWithBrokenMiddleRecord(), storage.value)
    }

    @Test fun `a 41st journey is refused as full and the stored text stays byte-identical`() {
        val storage = FakeStorage()
        val repository = PlannedJourneyRepository(storage)
        val start = LocalDate.parse("2026-10-01")
        repeat(PlannedJourneyPersistenceSchema.MAX_JOURNEYS) { index ->
            assertTrue(repository.save(journey("j$index", start.plusDays(index.toLong()).toString(), "08:00")) is PlannedJourneyRepositorySaveResult.Saved)
        }
        val stored = storage.value
        listOf("2026-09-01", "2027-03-01").forEach { date ->
            val result = repository.save(journey("extra-$date", date, "07:00"))
            assertEquals(PlannedJourneyRejection.FULL, (result as PlannedJourneyRepositorySaveResult.Rejected).reason)
        }
        assertEquals(stored, storage.value)
        val model = PlannedJourneyViewModel(repository, now = { createdAt }, zone = ZoneId.of("Europe/Rome"), newId = { "extra" })
        model.beginCreate()
        model.changeEditor { it.copy(originQuery = rome.displayName, destinationQuery = milan.displayName, date = "2027-04-01", time = "08:00") }
        model.saveEditor(listOf(rome, milan))
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_FULL), model.editor?.validationMessage)
        assertEquals(stored, storage.value)
    }

    @Test fun `the same stations date and time are refused as a duplicate, not as a field error`() {
        val storage = FakeStorage()
        val repository = PlannedJourneyRepository(storage)
        repository.save(journey("first", "2026-10-01", "08:00"))
        val stored = storage.value
        val result = repository.save(journey("second", "2026-10-01", "08:00"))
        assertEquals(PlannedJourneyRejection.DUPLICATE, (result as PlannedJourneyRepositorySaveResult.Rejected).reason)
        assertEquals(stored, storage.value)
        val model = PlannedJourneyViewModel(repository, now = { createdAt }, zone = ZoneId.of("Europe/Rome"), newId = { "again" })
        model.beginCreate()
        model.changeEditor { it.copy(originQuery = rome.displayName, destinationQuery = milan.displayName, date = "2026-10-01", time = "08:00") }
        model.saveEditor(listOf(rome, milan))
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_DUPLICATE), model.editor?.validationMessage)
    }

    @Test fun `the same origin and destination name the station field`() {
        val model = PlannedJourneyViewModel(PlannedJourneyRepository(FakeStorage()), now = { createdAt }, zone = ZoneId.of("Europe/Rome"))
        model.beginCreate()
        model.changeEditor { it.copy(originQuery = rome.displayName, destinationQuery = rome.displayName, date = "2026-10-01", time = "08:00") }
        model.saveEditor(listOf(rome, milan))
        assertEquals(UiMessage(UiMessageKey.ROUTE_STATIONS_MUST_DIFFER), model.editor?.validationMessage)
    }

    @Test fun `a departure already past is saved only after a second tap and an edit asks again`() {
        val repository = PlannedJourneyRepository(FakeStorage())
        var ids = 0
        val model = PlannedJourneyViewModel(repository, now = { Instant.parse("2026-10-02T06:00:00Z") }, zone = ZoneId.of("Europe/Rome"), newId = { "late-${++ids}" })
        model.beginCreate()
        model.changeEditor { it.copy(originQuery = rome.displayName, destinationQuery = milan.displayName, date = "2026-10-01", time = "08:00") }
        model.saveEditor(listOf(rome, milan))
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_DEPARTURE_PAST), model.editor?.validationMessage)
        assertTrue(model.snapshot.journeys.isEmpty())
        model.changeEditor { it.copy(time = "07:59") }
        model.saveEditor(listOf(rome, milan))
        assertTrue("a change asks again", model.snapshot.journeys.isEmpty())
        model.saveEditor(listOf(rome, milan))
        assertNull(model.editor)
        assertEquals(LocalTime.of(7, 59), model.snapshot.journeys.single().itinerary.segments.single().departure.time)

        // Italian wall clock: 08:30 in Rome is 06:30Z, still ahead of 06:00Z whatever the phone's zone.
        model.beginCreate()
        model.changeEditor { it.copy(originQuery = milan.displayName, destinationQuery = rome.displayName, date = "2026-10-02", time = "08:30") }
        model.saveEditor(listOf(rome, milan))
        assertNull(model.editor)
        assertEquals(2, model.snapshot.journeys.size)
    }

    @Test fun `editing the label of a past journey does not warn again`() {
        val repository = PlannedJourneyRepository(FakeStorage())
        val past = journey("past", "2026-10-01", "08:00")
        repository.save(past)
        val model = PlannedJourneyViewModel(repository, now = { Instant.parse("2026-10-05T06:00:00Z") }, zone = ZoneId.of("Europe/Rome"))
        model.beginEdit(past)
        model.changeEditor { it.copy(label = "Rinominato") }
        model.saveEditor(listOf(rome, milan))
        assertNull(model.editor)
        assertEquals("Rinominato", model.snapshot.journeys.single().label)
    }

    private fun storedWithBrokenMiddleRecord(): String {
        val valid = JSONObject(PlannedJourneyTransfer.encode(PlannedJourneyPersistenceSnapshot(journeys = listOf(
            journey("first", "2026-10-01", "08:00"),
            journey("second", "2026-10-02", "08:00"),
            journey("third", "2026-10-04", "09:00")
        ))))
        valid.getJSONArray("journeys").getJSONObject(1).getJSONObject("itinerary").getJSONArray("segments")
            .getJSONObject(0).put("origin", 42)
        return valid.toString()
    }

    private fun journey(id: String, date: String, time: String) = PlannedJourney(
        id = id,
        label = "Viaggio $id",
        itinerary = PlannedJourneyItinerary(listOf(PlannedJourneySegment(
            "segment-$id", rome, milan,
            PlannedJourneyDateTime(LocalDate.parse(date), LocalTime.parse(time), ZoneId.of("Europe/Rome"))
        ))),
        createdAt = createdAt
    )

    private class FakeStorage(var value: String? = null) : PlannedJourneyStorage {
        var writes = 0
        override fun read(): String? = value
        override fun write(value: String) {
            writes++
            this.value = value
        }
    }
}
