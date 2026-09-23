package it.binario.app.plannedjourney

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannedJourneyDomainTest {
    private val rome = station("IT:ROMA-TERMINI", "Roma Termini", "RMTE")
    private val bologna = station("IT:BOLOGNA-CENTRALE", "Bologna Centrale", "BOLC")
    private val milan = station("IT:MILANO-CENTRALE", "Milano Centrale", "MICT")
    private val zone = ZoneId.of("Europe/Rome")
    private val createdAt = Instant.parse("2026-09-01T10:00:00Z")

    @Test
    fun `persistence contract is versioned and nominally separate from saved routes`() {
        assertEquals(1, PlannedJourneyPersistenceSchema.VERSION)
        assertEquals("binario-planned-journeys-v1", PlannedJourneyPersistenceSchema.STORAGE_KEY)
        assertEquals("planned_journey", PlannedJourneyPersistenceSchema.PERSISTENCE_KIND)
        assertNotEquals("binario-local-routes-v1", PlannedJourneyPersistenceSchema.STORAGE_KEY)

        val snapshot = PlannedJourneyPersistenceSnapshot(journeys = listOf(singleJourney()))
        assertTrue(PlannedJourneyValidator.validate(snapshot).isEmpty())
    }

    @Test
    fun `factory creates a valid single-segment journey with canonical stations timezone and reminder`() {
        val outcome = PlannedJourneyFactory.create(
            PlannedJourneyDraft(
                id = "commute-1",
                label = "Ufficio",
                itinerary = PlannedJourneyItineraryDraft(listOf(segmentDraft("out", rome, bologna, "2026-09-10", "08:15"))),
                reminder = PlannedJourneyReminder(PlannedJourneyReminderMinutes.FIFTEEN),
                checklist = listOf(PlannedJourneyChecklistItem("ticket", "Prepara il titolo di viaggio")),
                createdAt = createdAt
            )
        )

        val journey = assertValid(outcome)
        assertEquals(PlannedJourneyStatus.ACTIVE, journey.status)
        assertEquals("Europe/Rome", journey.itinerary.segments.single().departure.timeZone.id)
        assertEquals(15, journey.reminder?.minutesBeforeDeparture?.minutes)
        assertTrue(PlannedJourneyValidator.validate(journey).isEmpty())
    }

    @Test
    fun `factory rejects a draft that leaves a segment departure unspecified`() {
        val result = PlannedJourneyFactory.create(
            PlannedJourneyDraft(
                id = "draft-without-time",
                itinerary = PlannedJourneyItineraryDraft(listOf(PlannedJourneySegmentDraft("out", rome, bologna))),
                createdAt = createdAt
            )
        )

        assertHasIssue(result, PlannedJourneyValidationCode.MISSING_DEPARTURE)
    }

    @Test
    fun `itinerary validation requires canonical stations distinct endpoints connectivity and chronology`() {
        val invalid = journey(
            id = "invalid-itinerary",
            segments = listOf(
                segment("one", rome, rome, "2026-09-10", "10:00"),
                segment("two", milan, bologna, "2026-09-10", "09:30"),
                segment("two", bologna, milan, "2026-09-10", "11:00")
            )
        )

        val codes = PlannedJourneyValidator.validate(invalid).map { it.code }.toSet()
        assertTrue(PlannedJourneyValidationCode.SAME_ORIGIN_AND_DESTINATION in codes)
        assertTrue(PlannedJourneyValidationCode.DISCONNECTED_ITINERARY in codes)
        assertTrue(PlannedJourneyValidationCode.NON_CHRONOLOGICAL_ITINERARY in codes)
        assertTrue(PlannedJourneyValidationCode.DUPLICATE_SEGMENT_ID in codes)
    }

    @Test
    fun `invalid canonical station or daylight saving gap is rejected`() {
        val noCanonicalIdentity = journey(
            id = "bad-station",
            segments = listOf(segment("one", PlannedJourneyStation("", ""), bologna, "2026-09-10", "08:00"))
        )
        val dstGap = journey(
            id = "dst-gap",
            segments = listOf(segment("one", rome, bologna, "2026-03-29", "02:30"))
        )

        assertTrue(PlannedJourneyValidator.validate(noCanonicalIdentity).any { it.code == PlannedJourneyValidationCode.INVALID_STATION })
        assertTrue(PlannedJourneyValidator.validate(dstGap).any { it.code == PlannedJourneyValidationCode.INVALID_LOCAL_TIME })
    }

    @Test
    fun `recurrence must be explicit finite unique and start at the first departure`() {
        val base = journey(
            id = "recurrence",
            segments = listOf(segment("out", rome, bologna, "2026-09-07", "08:00")),
            recurrence = PlannedJourneyRecurrence(
                weekdays = listOf(DayOfWeek.MONDAY, DayOfWeek.MONDAY),
                startsOn = LocalDate.of(2026, 9, 8),
                occurrenceLimit = 13
            )
        )
        val codes = PlannedJourneyValidator.validate(base).map { it.code }.toSet()

        assertTrue(PlannedJourneyValidationCode.INVALID_RECURRENCE in codes)
        assertTrue(PlannedJourneyValidationCode.RECURRENCE_START_MISMATCH in codes)
    }

    @Test
    fun `recurrence generation is bounded by both weekday and date range`() {
        val recurrence = PlannedJourneyRecurrence(
            weekdays = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            startsOn = LocalDate.of(2026, 9, 7),
            endsOn = LocalDate.of(2026, 9, 16),
            occurrenceLimit = 12
        )

        assertEquals(
            listOf("2026-09-07", "2026-09-09", "2026-09-14", "2026-09-16"),
            PlannedJourneyOccurrences.dates(recurrence).map(LocalDate::toString)
        )
    }

    @Test
    fun `recurring multi-segment occurrences preserve segment day offsets`() {
        val multiDay = journey(
            id = "multi-day",
            segments = listOf(
                segment("out", rome, bologna, "2026-09-07", "23:30"),
                segment("onward", bologna, milan, "2026-09-08", "06:30")
            ),
            recurrence = PlannedJourneyRecurrence(
                weekdays = listOf(DayOfWeek.MONDAY),
                startsOn = LocalDate.of(2026, 9, 7),
                occurrenceLimit = 2
            )
        )

        val occurrences = PlannedJourneyOccurrences.forJourney(multiDay)
        assertEquals(listOf("2026-09-07", "2026-09-14"), occurrences.map { it.occurrenceDate.toString() })
        assertEquals("2026-09-15", occurrences[1].itinerary.segments[1].departure.date.toString())
        assertTrue(occurrences[1].isGeneratedOccurrence)
    }

    @Test
    fun `agenda filters active upcoming completed occurrences archives and reminders in stable chronological order`() {
        val recurring = journey(
            id = "recurring",
            segments = listOf(segment("out", rome, bologna, "2026-09-07", "08:00")),
            recurrence = PlannedJourneyRecurrence(
                weekdays = listOf(DayOfWeek.MONDAY),
                startsOn = LocalDate.of(2026, 9, 7),
                occurrenceLimit = 3
            ),
            completedOccurrenceDates = listOf(LocalDate.of(2026, 9, 14))
        )
        val reminder = journey(
            id = "reminder",
            segments = listOf(segment("out", bologna, milan, "2026-09-09", "08:00")),
            reminder = PlannedJourneyReminder(PlannedJourneyReminderMinutes.THIRTY)
        )
        val completed = PlannedJourneyLifecycle.complete(
            journey("completed", listOf(segment("out", milan, bologna, "2026-09-10", "08:00"))),
            Instant.parse("2026-09-02T10:00:00Z")
        )
        val archived = PlannedJourneyLifecycle.archive(
            journey("archived", listOf(segment("out", milan, rome, "2026-09-11", "08:00"))),
            Instant.parse("2026-09-03T10:00:00Z")
        )
        val now = Instant.parse("2026-09-08T00:00:00Z")

        val upcoming = PlannedJourneyAgenda.ordered(listOf(archived, completed, recurring, reminder), now = now)
        assertEquals(listOf("reminder", "recurring"), upcoming.map { it.journey.id })
        assertEquals(listOf("2026-09-09", "2026-09-21"), upcoming.map { it.occurrence.occurrenceDate.toString() })

        assertEquals(listOf("reminder"), PlannedJourneyAgenda.ordered(listOf(recurring, reminder), PlannedJourneyAgendaFilter.REMINDERS, now).map { it.journey.id })
        assertEquals(listOf("archived"), PlannedJourneyAgenda.ordered(listOf(archived, completed), PlannedJourneyAgendaFilter.ARCHIVED, now).map { it.journey.id })
        assertEquals(6, PlannedJourneyAgenda.ordered(listOf(archived, completed, recurring, reminder), PlannedJourneyAgendaFilter.ALL, now).size)
    }

    @Test
    fun `lifecycle enforces coherent status timestamps and recurrence completion`() {
        val active = singleJourney()
        val completed = PlannedJourneyLifecycle.complete(active, Instant.parse("2026-09-02T10:00:00Z"))
        assertEquals(PlannedJourneyStatus.COMPLETED, completed.status)
        assertTrue(PlannedJourneyValidator.validate(completed).isEmpty())

        val invalid = active.copy(status = PlannedJourneyStatus.ARCHIVED)
        assertTrue(PlannedJourneyValidator.validate(invalid).any { it.code == PlannedJourneyValidationCode.INVALID_STATUS_TIMESTAMPS })

        val recurring = active.copy(
            recurrence = PlannedJourneyRecurrence(
                weekdays = listOf(DayOfWeek.THURSDAY),
                startsOn = LocalDate.of(2026, 9, 10),
                occurrenceLimit = 2
            )
        )
        val occurrenceCompleted = PlannedJourneyLifecycle.completeOccurrence(recurring, LocalDate.of(2026, 9, 17))
        assertEquals(listOf(LocalDate.of(2026, 9, 17)), occurrenceCompleted.completedOccurrenceDates)
    }

    @Test
    fun `checklist remains small plain and rejects common sensitive forms`() {
        val sensitive = singleJourney().copy(
            checklist = listOf(
                PlannedJourneyChecklistItem("one", "Password: topsecret"),
                PlannedJourneyChecklistItem("two", "Scrivi a alice@example.com"),
                PlannedJourneyChecklistItem("three", "Carta 4111 1111 1111 1111")
            )
        )
        val codes = PlannedJourneyValidator.validate(sensitive).map { it.code }

        assertEquals(3, codes.count { it == PlannedJourneyValidationCode.SENSITIVE_CHECKLIST_CONTENT })
    }

    @Test
    fun `deduplication keys canonical station identities schedule and timezone rather than labels`() {
        val initial = singleJourney().copy(id = "one")
        val displayVariant = initial.copy(
            id = "two",
            itinerary = PlannedJourneyItinerary(
                initial.itinerary.segments.map { it.copy(origin = it.origin.copy(displayName = "ROMA TERMINI")) }
            )
        )
        val otherZone = initial.copy(
            id = "three",
            itinerary = PlannedJourneyItinerary(
                initial.itinerary.segments.map { it.copy(departure = it.departure.copy(timeZone = ZoneId.of("UTC"))) }
            )
        )

        assertEquals(listOf("one", "three"), PlannedJourneyDeduplication.deduplicate(listOf(initial, displayVariant, otherZone)).map { it.id })
        assertTrue(PlannedJourneyValidator.validate(PlannedJourneyPersistenceSnapshot(journeys = listOf(initial, displayVariant))).any { it.code == PlannedJourneyValidationCode.DUPLICATE_JOURNEY })
    }

    @Test
    fun `snapshot validation catches schema id count and duplicate journey constraints`() {
        val base = singleJourney()
        val invalidVersion = PlannedJourneyPersistenceSnapshot(schemaVersion = 99, journeys = listOf(base))
        assertTrue(PlannedJourneyValidator.validate(invalidVersion).any { it.code == PlannedJourneyValidationCode.UNSUPPORTED_SCHEMA_VERSION })

        val oversized = PlannedJourneyPersistenceSnapshot(journeys = (0..PlannedJourneyPersistenceSchema.MAX_JOURNEYS).map { base.copy(id = "j$it", itinerary = PlannedJourneyItinerary(base.itinerary.segments.map { s -> s.copy(id = "s$it") })) })
        assertTrue(PlannedJourneyValidator.validate(oversized).any { it.code == PlannedJourneyValidationCode.TOO_MANY_JOURNEYS })
    }

    @Test
    fun `normalizing snapshot keeps valid first canonical journeys with a local cap`() {
        val base = singleJourney()
        val invalid = base.copy(id = "invalid", itinerary = PlannedJourneyItinerary(emptyList()))
        val distinct = base.copy(id = "distinct", itinerary = PlannedJourneyItinerary(listOf(segment("out", bologna, milan, "2026-09-11", "08:00"))))

        val normalized = PlannedJourneyDeduplication.normalizedSnapshot(listOf(base, base.copy(id = "duplicate"), invalid, distinct))
        assertEquals(listOf(base.id, distinct.id), normalized.journeys.map { it.id })
    }

    @Test
    fun `replanning and return reverse or preserve only canonical route geometry with scheduling reset`() {
        val original = journey(
            id = "original",
            segments = listOf(
                segment("out", rome, bologna, "2026-09-10", "08:00"),
                segment("onward", bologna, milan, "2026-09-10", "10:00")
            ),
            reminder = PlannedJourneyReminder(PlannedJourneyReminderMinutes.TEN),
            checklist = listOf(PlannedJourneyChecklistItem("x", "Porta acqua", true))
        )
        val replanCreatedAt = Instant.parse("2026-09-12T10:00:00Z")
        val replanned = PlannedJourneyDrafts.replanFrom(original, "replanned", replanCreatedAt)
        val returned = PlannedJourneyDrafts.returnFrom(original, "returned", replanCreatedAt)

        assertEquals("replanned", replanned.id)
        assertEquals(listOf(rome, bologna), replanned.itinerary.segments.map { it.origin })
        assertTrue(replanned.itinerary.segments.all { it.departure == null })
        assertEquals(null, replanned.reminder)
        assertTrue(replanned.checklist.isEmpty())
        assertEquals("", replanned.label)
        assertEquals(replanCreatedAt, replanned.createdAt)
        assertEquals(listOf(milan, bologna), returned.itinerary.segments.map { it.origin })
        assertEquals(listOf(bologna, rome), returned.itinerary.segments.map { it.destination })
        assertTrue(returned.itinerary.segments.all { it.departure == null })
        assertTrue(returned.itinerary.segments.all { it.id.startsWith("return-") })
    }

    private fun assertValid(result: PlannedJourneyValidationResult<PlannedJourney>): PlannedJourney = when (result) {
        is PlannedJourneyValidationResult.Valid -> result.value
        is PlannedJourneyValidationResult.Invalid -> throw AssertionError("Expected valid journey, got ${result.issues}")
    }

    private fun assertHasIssue(result: PlannedJourneyValidationResult<*>, code: PlannedJourneyValidationCode) {
        assertTrue(result is PlannedJourneyValidationResult.Invalid && result.issues.any { it.code == code })
    }

    private fun station(id: String, name: String, code: String? = null) = PlannedJourneyStation(id, name, code)

    private fun segmentDraft(id: String, origin: PlannedJourneyStation, destination: PlannedJourneyStation, date: String, time: String) =
        PlannedJourneySegmentDraft(id, origin, destination, departure(date, time))

    private fun segment(id: String, origin: PlannedJourneyStation, destination: PlannedJourneyStation, date: String, time: String) =
        PlannedJourneySegment(id, origin, destination, departure(date, time))

    private fun departure(date: String, time: String) = PlannedJourneyDateTime(LocalDate.parse(date), LocalTime.parse(time), zone)

    private fun singleJourney(): PlannedJourney = journey("single", listOf(segment("out", rome, bologna, "2026-09-10", "08:00")))

    private fun journey(
        id: String,
        segments: List<PlannedJourneySegment>,
        reminder: PlannedJourneyReminder? = null,
        recurrence: PlannedJourneyRecurrence? = null,
        checklist: List<PlannedJourneyChecklistItem> = emptyList(),
        completedOccurrenceDates: List<LocalDate> = emptyList()
    ) = PlannedJourney(
        id = id,
        itinerary = PlannedJourneyItinerary(segments),
        reminder = reminder,
        recurrence = recurrence,
        checklist = checklist,
        createdAt = createdAt,
        completedOccurrenceDates = completedOccurrenceDates
    )
}
