package it.binario.app.plannedjourney

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class PlannedJourneyTransferTest {
    @Test
    fun `round trips the complete bounded domain`() {
        val original = journey("journey-1", 0).copy(
            reminder = PlannedJourneyReminder(PlannedJourneyReminderMinutes.THIRTY),
            recurrence = PlannedJourneyRecurrence(
                weekdays = listOf(DayOfWeek.WEDNESDAY),
                startsOn = LocalDate.of(2026, 9, 23),
                occurrenceLimit = 1
            ),
            checklist = listOf(PlannedJourneyChecklistItem("item-1", "Caricabatterie", true)),
            completedOccurrenceDates = listOf(LocalDate.of(2026, 9, 23))
        )
        val snapshot = PlannedJourneyPersistenceSnapshot(journeys = listOf(original))

        val decoded = PlannedJourneyTransfer.decode(PlannedJourneyTransfer.encode(snapshot))

        assertEquals(snapshot, (decoded as PlannedJourneyTransferDecodeResult.Valid).snapshot)
    }

    @Test
    fun `rejects an unknown root field instead of accepting hidden data`() {
        val encoded = PlannedJourneyTransfer.encode(PlannedJourneyPersistenceSnapshot(journeys = listOf(journey("one", 0))))
        val tampered = JSONObject(encoded).put("accessToken", "must-not-cross-boundary").toString()

        val decoded = PlannedJourneyTransfer.decode(tampered)

        val error = (decoded as PlannedJourneyTransferDecodeResult.Invalid).errors.single()
        assertEquals(PlannedJourneyTransferErrorCode.MALFORMED_DOCUMENT, error.code)
        assertTrue(error.detail.contains("Unexpected fields"))
    }

    @Test
    fun `rejects future versions and oversized inputs`() {
        val encoded = PlannedJourneyTransfer.encode(PlannedJourneyPersistenceSnapshot(journeys = listOf(journey("one", 0))))
        val future = JSONObject(encoded).put("schemaVersion", 2).toString()

        val futureError = (PlannedJourneyTransfer.decode(future) as PlannedJourneyTransferDecodeResult.Invalid).errors.single()
        assertEquals(PlannedJourneyTransferErrorCode.UNSUPPORTED_VERSION, futureError.code)

        val oversized = "x".repeat(PlannedJourneyTransferContract.MAX_BYTES + 1)
        val sizeError = (PlannedJourneyTransfer.decode(oversized) as PlannedJourneyTransferDecodeResult.Invalid).errors.single()
        assertEquals(PlannedJourneyTransferErrorCode.TOO_LARGE, sizeError.code)
    }

    @Test
    fun `previews accepted duplicate and conflicting journeys before merge`() {
        val existing = journey("same-id", 0)
        val duplicate = journey("other-id", 0)
        val conflict = journey("same-id", 1)
        val accepted = journey("new-id", 2)
        val imported = PlannedJourneyPersistenceSnapshot(journeys = listOf(duplicate, conflict, accepted))

        val preview = PlannedJourneyTransfer.preview(imported, listOf(existing))

        assertEquals(listOf(accepted), preview.accepted)
        assertEquals(listOf(duplicate), preview.duplicates)
        assertEquals(listOf(PlannedJourneyImportConflict(existing, conflict)), preview.conflicts)

        val kept = PlannedJourneyTransfer.merge(
            listOf(existing),
            preview,
            PlannedJourneyConflictPolicy.KEEP_EXISTING
        )
        assertEquals(existing, kept.journeys.first { it.id == "same-id" })
        assertTrue(kept.journeys.any { it.id == "new-id" })

        val replaced = PlannedJourneyTransfer.merge(
            listOf(existing),
            preview,
            PlannedJourneyConflictPolicy.REPLACE_EXISTING
        )
        assertEquals(conflict, replaced.journeys.first { it.id == "same-id" })
        assertTrue(replaced.journeys.none { it.id == "other-id" })
    }

    @Test
    fun `rejects sensitive checklist data on import`() {
        val unsafe = journey("unsafe", 0).copy(
            checklist = listOf(PlannedJourneyChecklistItem("secret", "PIN 1234"))
        )
        val root = JSONObject()
            .put("kind", PlannedJourneyTransferContract.KIND)
            .put("schemaVersion", PlannedJourneyTransferContract.VERSION)
            .put("journeys", org.json.JSONArray().put(
                JSONObject(PlannedJourneyTransfer.encode(PlannedJourneyPersistenceSnapshot(journeys = listOf(journey("safe", 0)))))
                    .getJSONArray("journeys").getJSONObject(0)
                    .put("id", unsafe.id)
                    .put("checklist", org.json.JSONArray().put(
                        JSONObject().put("id", "secret").put("label", "PIN 1234").put("completed", false)
                    ))
            ))

        val decoded = PlannedJourneyTransfer.decode(root.toString())

        val errors = (decoded as PlannedJourneyTransferDecodeResult.Invalid).errors
        assertTrue(errors.any { it.code == PlannedJourneyTransferErrorCode.INVALID_JOURNEYS })
    }

    private fun journey(id: String, dayOffset: Long): PlannedJourney {
        val date = LocalDate.of(2026, 9, 23).plusDays(dayOffset)
        return PlannedJourney(
            id = id,
            label = "Viaggio $id",
            itinerary = PlannedJourneyItinerary(
                listOf(
                    PlannedJourneySegment(
                        id = "$id-segment",
                        origin = PlannedJourneyStation("roma-termini", "Roma Termini", "8300092"),
                        destination = PlannedJourneyStation("milano-centrale", "Milano Centrale", "8300046"),
                        departure = PlannedJourneyDateTime(date, LocalTime.of(9, 15), ZoneId.of("Europe/Rome"))
                    )
                )
            ),
            createdAt = Instant.parse("2026-09-01T08:00:00Z")
        )
    }
}
