package it.binario.app.plannedjourney

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Persistence contract for planned personal journeys. This namespace is intentionally separate
 * from SavedRoute and from every operational/live rail-data model. It contains no train identity,
 * platform, delay, provider, or realtime state.
 *
 * This is a structural persistence schema only: an outer layer may serialize it locally, but this
 * domain deliberately owns neither Android storage nor synchronization.
 */
object PlannedJourneyPersistenceSchema {
    const val VERSION: Int = 1
    const val STORAGE_KEY: String = "binario-planned-journeys-v1"
    const val PERSISTENCE_KIND: String = "planned_journey"
    const val MAX_JOURNEYS: Int = 40
    const val MAX_ITINERARY_SEGMENTS: Int = 4
    const val MAX_CHECKLIST_ITEMS: Int = 24
    const val MAX_RECURRENCE_OCCURRENCES: Int = 12
}

/** A versioned local-persistence payload, intentionally not a SavedRoute payload. */
data class PlannedJourneyPersistenceSnapshot(
    val schemaVersion: Int = PlannedJourneyPersistenceSchema.VERSION,
    val journeys: List<PlannedJourney>
)

enum class PlannedJourneyStatus {
    ACTIVE,
    COMPLETED,
    ARCHIVED
}

enum class PlannedJourneyReminderMinutes(val minutes: Int) {
    FIVE(5),
    TEN(10),
    FIFTEEN(15),
    TWENTY(20),
    THIRTY(30),
    FORTY_FIVE(45),
    SIXTY(60);

    companion object {
        fun fromMinutes(minutes: Int): PlannedJourneyReminderMinutes? = entries.firstOrNull { it.minutes == minutes }
    }
}

/** A personal preference only; this domain never schedules a notification. */
data class PlannedJourneyReminder(val minutesBeforeDeparture: PlannedJourneyReminderMinutes)

/**
 * A station selected from a canonical catalog by a stable catalog identifier. This deliberately
 * does not reference the app's live-data Station type, so a plan remains independent of providers.
 */
data class PlannedJourneyStation(
    val canonicalId: String,
    val displayName: String,
    val railwayCode: String? = null
) {
    internal fun normalizedId(): String = canonicalId.trim().uppercase(Locale.ROOT)
}

/**
 * A local date and time paired with an IANA time zone. An invalid local time in a daylight-saving
 * gap is rejected by validation; an ambiguous overlap deterministically uses the earlier offset.
 */
data class PlannedJourneyDateTime(
    val date: LocalDate,
    val time: LocalTime,
    val timeZone: ZoneId
) {
    fun localDateTime(): LocalDateTime = LocalDateTime.of(date, time)

    fun isValidLocalTime(): Boolean = timeZone.rules.getValidOffsets(localDateTime()).isNotEmpty()

    fun scheduledAt(): ZonedDateTime {
        val offsets = timeZone.rules.getValidOffsets(localDateTime())
        require(offsets.isNotEmpty()) { "The local date/time falls in a daylight-saving gap" }
        return ZonedDateTime.ofLocal(localDateTime(), timeZone, offsets.first())
    }

    fun shiftDays(days: Long): PlannedJourneyDateTime = copy(date = date.plusDays(days))
}

data class PlannedJourneySegmentDraft(
    val id: String,
    val origin: PlannedJourneyStation,
    val destination: PlannedJourneyStation,
    /** Null is allowed only while preparing a return or a replanned journey. */
    val departure: PlannedJourneyDateTime? = null
)

data class PlannedJourneySegment(
    val id: String,
    val origin: PlannedJourneyStation,
    val destination: PlannedJourneyStation,
    val departure: PlannedJourneyDateTime
)

data class PlannedJourneyItineraryDraft(val segments: List<PlannedJourneySegmentDraft>)

data class PlannedJourneyItinerary(val segments: List<PlannedJourneySegment>)

/**
 * An explicit, finite weekly recurrence. Segment local times and time zones come from the
 * itinerary; [startsOn] is deliberately the first segment's scheduled local date.
 */
data class PlannedJourneyRecurrence(
    val schemaVersion: Int = PlannedJourneyPersistenceSchema.VERSION,
    val weekdays: List<DayOfWeek>,
    val startsOn: LocalDate,
    val endsOn: LocalDate? = null,
    val occurrenceLimit: Int
)

/** Checklist items deliberately have no free-form notes, ticket numbers, contacts, or attachments. */
data class PlannedJourneyChecklistItem(
    val id: String,
    val label: String,
    val completed: Boolean = false
)

data class PlannedJourneyDraft(
    val id: String,
    val label: String = "",
    val itinerary: PlannedJourneyItineraryDraft,
    val reminder: PlannedJourneyReminder? = null,
    val recurrence: PlannedJourneyRecurrence? = null,
    val checklist: List<PlannedJourneyChecklistItem> = emptyList(),
    val createdAt: Instant
)

/**
 * A planned personal itinerary. It has no association with SavedRoute and never contains live
 * running information. Use [PlannedJourneyFactory.create] before persistence.
 */
data class PlannedJourney(
    val schemaVersion: Int = PlannedJourneyPersistenceSchema.VERSION,
    val id: String,
    val label: String = "",
    val itinerary: PlannedJourneyItinerary,
    val reminder: PlannedJourneyReminder? = null,
    val recurrence: PlannedJourneyRecurrence? = null,
    val checklist: List<PlannedJourneyChecklistItem> = emptyList(),
    val status: PlannedJourneyStatus = PlannedJourneyStatus.ACTIVE,
    val createdAt: Instant,
    val completedAt: Instant? = null,
    val archivedAt: Instant? = null,
    /** Completed recurrence dates in the first segment's local calendar. */
    val completedOccurrenceDates: List<LocalDate> = emptyList()
)

enum class PlannedJourneyValidationCode {
    UNSUPPORTED_SCHEMA_VERSION,
    BLANK_ID,
    DUPLICATE_ID,
    INVALID_LABEL,
    EMPTY_ITINERARY,
    TOO_MANY_SEGMENTS,
    DUPLICATE_SEGMENT_ID,
    INVALID_SEGMENT_ID,
    INVALID_STATION,
    SAME_ORIGIN_AND_DESTINATION,
    DISCONNECTED_ITINERARY,
    MISSING_DEPARTURE,
    INVALID_LOCAL_TIME,
    NON_CHRONOLOGICAL_ITINERARY,
    INVALID_RECURRENCE,
    RECURRENCE_START_MISMATCH,
    INVALID_CHECKLIST,
    SENSITIVE_CHECKLIST_CONTENT,
    INVALID_STATUS_TIMESTAMPS,
    INVALID_COMPLETED_OCCURRENCES,
    TOO_MANY_JOURNEYS,
    DUPLICATE_JOURNEY
}

data class PlannedJourneyValidationIssue(
    val code: PlannedJourneyValidationCode,
    val field: String,
    val detail: String
)

sealed interface PlannedJourneyValidationResult<out T> {
    data class Valid<T>(val value: T) : PlannedJourneyValidationResult<T>
    data class Invalid(val issues: List<PlannedJourneyValidationIssue>) : PlannedJourneyValidationResult<Nothing>
}

/** Construction and validation boundary for the persisted domain model. */
object PlannedJourneyFactory {
    fun create(draft: PlannedJourneyDraft): PlannedJourneyValidationResult<PlannedJourney> {
        val draftIssues = PlannedJourneyValidator.validate(draft)
        if (draftIssues.isNotEmpty()) return PlannedJourneyValidationResult.Invalid(draftIssues)

        val journey = PlannedJourney(
            id = draft.id.trim(),
            label = draft.label.trim(),
            itinerary = PlannedJourneyItinerary(draft.itinerary.segments.map { segment ->
                PlannedJourneySegment(
                    id = segment.id.trim(),
                    origin = segment.origin,
                    destination = segment.destination,
                    departure = requireNotNull(segment.departure)
                )
            }),
            reminder = draft.reminder,
            recurrence = draft.recurrence,
            checklist = draft.checklist,
            createdAt = draft.createdAt
        )
        val persistedIssues = PlannedJourneyValidator.validate(journey)
        return if (persistedIssues.isEmpty()) PlannedJourneyValidationResult.Valid(journey)
        else PlannedJourneyValidationResult.Invalid(persistedIssues)
    }
}

object PlannedJourneyValidator {
    fun validate(draft: PlannedJourneyDraft): List<PlannedJourneyValidationIssue> = buildList {
        validateIdentity(draft.id, draft.label, this)
        validateDraftItinerary(draft.itinerary, this)
        val scheduledSegments = draft.itinerary.segments.mapNotNull { segment ->
            segment.departure?.let { departure -> PlannedJourneySegment(segment.id, segment.origin, segment.destination, departure) }
        }
        validateRecurrence(draft.recurrence, scheduledSegments, this)
        validateChecklist(draft.checklist, this)
    }

    fun validate(journey: PlannedJourney): List<PlannedJourneyValidationIssue> = buildList {
        if (journey.schemaVersion != PlannedJourneyPersistenceSchema.VERSION) {
            add(issue(PlannedJourneyValidationCode.UNSUPPORTED_SCHEMA_VERSION, "schemaVersion", "Unsupported journey schema"))
        }
        validateIdentity(journey.id, journey.label, this)
        validateItinerary(journey.itinerary, this)
        validateRecurrence(journey.recurrence, journey.itinerary.segments, this)
        validateChecklist(journey.checklist, this)
        validateState(journey, this)
    }

    fun validate(snapshot: PlannedJourneyPersistenceSnapshot): List<PlannedJourneyValidationIssue> = buildList {
        if (snapshot.schemaVersion != PlannedJourneyPersistenceSchema.VERSION) {
            add(issue(PlannedJourneyValidationCode.UNSUPPORTED_SCHEMA_VERSION, "schemaVersion", "Unsupported snapshot schema"))
        }
        if (snapshot.journeys.size > PlannedJourneyPersistenceSchema.MAX_JOURNEYS) {
            add(issue(PlannedJourneyValidationCode.TOO_MANY_JOURNEYS, "journeys", "At most ${PlannedJourneyPersistenceSchema.MAX_JOURNEYS} journeys are supported"))
        }
        val seenIds = mutableSetOf<String>()
        val seenJourneys = mutableSetOf<String>()
        snapshot.journeys.forEachIndexed { index, journey ->
            validate(journey).forEach { nested -> add(nested.copy(field = "journeys[$index].${nested.field}")) }
            if (!seenIds.add(journey.id.trim())) {
                add(issue(PlannedJourneyValidationCode.DUPLICATE_ID, "journeys[$index].id", "Journey ids must be unique"))
            }
            if (!seenJourneys.add(PlannedJourneyDeduplication.fingerprint(journey))) {
                add(issue(PlannedJourneyValidationCode.DUPLICATE_JOURNEY, "journeys[$index]", "Duplicate canonical itinerary and schedule"))
            }
        }
    }

    private fun validateIdentity(id: String, label: String, issues: MutableList<PlannedJourneyValidationIssue>) {
        if (id.trim().isEmpty() || id.trim().length > 120) {
            issues += issue(PlannedJourneyValidationCode.BLANK_ID, "id", "An id of at most 120 characters is required")
        }
        if (label.length > 96 || label.any { it.isISOControl() }) {
            issues += issue(PlannedJourneyValidationCode.INVALID_LABEL, "label", "Label must be plain text of at most 96 characters")
        }
    }

    private fun validateDraftItinerary(itinerary: PlannedJourneyItineraryDraft, issues: MutableList<PlannedJourneyValidationIssue>) {
        val scheduled = itinerary.segments.map { segment ->
            if (segment.departure == null) {
                issues += issue(PlannedJourneyValidationCode.MISSING_DEPARTURE, "itinerary.segments[${segment.id}].departure", "A schedule is required before creation")
                null
            } else {
                PlannedJourneySegment(segment.id, segment.origin, segment.destination, segment.departure)
            }
        }
        validateItinerary(PlannedJourneyItinerary(scheduled.filterNotNull()), issues, declaredSegmentCount = itinerary.segments.size)
    }

    private fun validateItinerary(
        itinerary: PlannedJourneyItinerary,
        issues: MutableList<PlannedJourneyValidationIssue>,
        declaredSegmentCount: Int = itinerary.segments.size
    ) {
        if (declaredSegmentCount == 0) {
            issues += issue(PlannedJourneyValidationCode.EMPTY_ITINERARY, "itinerary.segments", "At least one segment is required")
            return
        }
        if (declaredSegmentCount > PlannedJourneyPersistenceSchema.MAX_ITINERARY_SEGMENTS) {
            issues += issue(PlannedJourneyValidationCode.TOO_MANY_SEGMENTS, "itinerary.segments", "At most ${PlannedJourneyPersistenceSchema.MAX_ITINERARY_SEGMENTS} segments are supported")
        }
        val ids = mutableSetOf<String>()
        itinerary.segments.forEachIndexed { index, segment ->
            val field = "itinerary.segments[$index]"
            if (segment.id.trim().isEmpty() || segment.id.trim().length > 120) {
                issues += issue(PlannedJourneyValidationCode.INVALID_SEGMENT_ID, "$field.id", "A segment id of at most 120 characters is required")
            } else if (!ids.add(segment.id.trim())) {
                issues += issue(PlannedJourneyValidationCode.DUPLICATE_SEGMENT_ID, "$field.id", "Segment ids must be unique")
            }
            validateStation(segment.origin, "$field.origin", issues)
            validateStation(segment.destination, "$field.destination", issues)
            if (segment.origin.normalizedId().isNotEmpty() && segment.origin.normalizedId() == segment.destination.normalizedId()) {
                issues += issue(PlannedJourneyValidationCode.SAME_ORIGIN_AND_DESTINATION, field, "A segment must connect two distinct canonical stations")
            }
            if (!segment.departure.isValidLocalTime()) {
                issues += issue(PlannedJourneyValidationCode.INVALID_LOCAL_TIME, "$field.departure", "The local date/time is in a daylight-saving gap")
            }
        }
        itinerary.segments.zipWithNext().forEachIndexed { index, (previous, next) ->
            if (previous.destination.normalizedId() != next.origin.normalizedId()) {
                issues += issue(PlannedJourneyValidationCode.DISCONNECTED_ITINERARY, "itinerary.segments[${index + 1}].origin", "Adjacent segments must meet at the same canonical station")
            }
            if (previous.departure.isValidLocalTime() && next.departure.isValidLocalTime() && !next.departure.scheduledAt().toInstant().isAfter(previous.departure.scheduledAt().toInstant())) {
                issues += issue(PlannedJourneyValidationCode.NON_CHRONOLOGICAL_ITINERARY, "itinerary.segments[${index + 1}].departure", "Segment departures must be strictly chronological")
            }
        }
    }

    private fun validateStation(station: PlannedJourneyStation, field: String, issues: MutableList<PlannedJourneyValidationIssue>) {
        val code = station.railwayCode
        if (station.canonicalId.trim().length !in 2..120 || station.displayName.trim().length !in 2..120 || station.displayName.any { it.isISOControl() } ||
            (code != null && (code.trim().length !in 2..32 || code.any { it.isWhitespace() || it.isISOControl() }))
        ) {
            issues += issue(PlannedJourneyValidationCode.INVALID_STATION, field, "A canonical id and display name are required")
        }
    }

    private fun validateRecurrence(
        recurrence: PlannedJourneyRecurrence?,
        segments: List<PlannedJourneySegment>,
        issues: MutableList<PlannedJourneyValidationIssue>
    ) {
        recurrence ?: return
        val firstDepartureDate = segments.firstOrNull()?.departure?.date
        val structurallyValid = recurrence.schemaVersion == PlannedJourneyPersistenceSchema.VERSION &&
            recurrence.weekdays.isNotEmpty() && recurrence.weekdays.size <= 7 &&
            recurrence.weekdays.distinct().size == recurrence.weekdays.size &&
            recurrence.startsOn.dayOfWeek in recurrence.weekdays &&
            recurrence.occurrenceLimit in 1..PlannedJourneyPersistenceSchema.MAX_RECURRENCE_OCCURRENCES &&
            (recurrence.endsOn == null || !recurrence.endsOn.isBefore(recurrence.startsOn))
        if (!structurallyValid) {
            issues += issue(PlannedJourneyValidationCode.INVALID_RECURRENCE, "recurrence", "Recurrence must include its start weekday, have unique weekdays, a valid bound, and at most ${PlannedJourneyPersistenceSchema.MAX_RECURRENCE_OCCURRENCES} occurrences")
        }
        if (firstDepartureDate != null && recurrence.startsOn != firstDepartureDate) {
            issues += issue(PlannedJourneyValidationCode.RECURRENCE_START_MISMATCH, "recurrence.startsOn", "Recurrence must start on the first segment's scheduled local date")
        }
        if (structurallyValid && firstDepartureDate == recurrence.startsOn && segments.isNotEmpty() &&
            !allOccurrencesAreRepresentable(recurrence, segments)
        ) {
            issues += issue(
                PlannedJourneyValidationCode.INVALID_RECURRENCE,
                "recurrence",
                "Every generated occurrence must have valid, in-range, chronological local times"
            )
        }
    }

    private fun allOccurrencesAreRepresentable(
        recurrence: PlannedJourneyRecurrence,
        segments: List<PlannedJourneySegment>
    ): Boolean {
        val rootDate = segments.first().departure.date
        return PlannedJourneyOccurrences.dates(recurrence).all { occurrenceDate ->
            val dayOffset = ChronoUnit.DAYS.between(rootDate, occurrenceDate)
            val shifted = segments.map { segment ->
                runCatching { segment.departure.shiftDays(dayOffset) }.getOrNull()
            }
            shifted.none { it == null } && shifted.filterNotNull().all { it.isValidLocalTime() } &&
                shifted.filterNotNull().zipWithNext().all { (previous, next) ->
                    next.scheduledAt().toInstant().isAfter(previous.scheduledAt().toInstant())
                }
        }
    }

    private fun validateChecklist(checklist: List<PlannedJourneyChecklistItem>, issues: MutableList<PlannedJourneyValidationIssue>) {
        if (checklist.size > PlannedJourneyPersistenceSchema.MAX_CHECKLIST_ITEMS) {
            issues += issue(PlannedJourneyValidationCode.INVALID_CHECKLIST, "checklist", "At most ${PlannedJourneyPersistenceSchema.MAX_CHECKLIST_ITEMS} checklist items are supported")
        }
        val ids = mutableSetOf<String>()
        checklist.forEachIndexed { index, item ->
            val field = "checklist[$index]"
            if (item.id.trim().isEmpty() || item.id.trim().length > 120 || item.label.trim().length !in 1..160 || item.label.any { it.isISOControl() } || !ids.add(item.id.trim())) {
                issues += issue(PlannedJourneyValidationCode.INVALID_CHECKLIST, field, "Checklist ids must be unique and labels must be short plain text")
            }
            if (containsSensitiveChecklistContent(item.label)) {
                issues += issue(PlannedJourneyValidationCode.SENSITIVE_CHECKLIST_CONTENT, "$field.label", "Checklist labels may not store credentials, payment, identity, or contact data")
            }
        }
    }

    private fun validateState(journey: PlannedJourney, issues: MutableList<PlannedJourneyValidationIssue>) {
        val statusIsConsistent = when (journey.status) {
            PlannedJourneyStatus.ACTIVE -> journey.completedAt == null && journey.archivedAt == null
            PlannedJourneyStatus.COMPLETED -> journey.completedAt != null && journey.archivedAt == null
            PlannedJourneyStatus.ARCHIVED -> journey.archivedAt != null
        }
        val timestampsAreOrdered = (journey.completedAt == null || !journey.completedAt.isBefore(journey.createdAt)) &&
            (journey.archivedAt == null || !journey.archivedAt.isBefore(journey.createdAt)) &&
            (journey.completedAt == null || journey.archivedAt == null || !journey.archivedAt.isBefore(journey.completedAt))
        if (!statusIsConsistent || !timestampsAreOrdered) {
            issues += issue(PlannedJourneyValidationCode.INVALID_STATUS_TIMESTAMPS, "status", "Status and lifecycle timestamps are inconsistent")
        }
        val completedDates = journey.completedOccurrenceDates
        val validDates = journey.recurrence?.let(PlannedJourneyOccurrences::dates).orEmpty().toSet()
        if ((journey.recurrence == null && completedDates.isNotEmpty()) || completedDates.size != completedDates.distinct().size ||
            completedDates.size > PlannedJourneyPersistenceSchema.MAX_RECURRENCE_OCCURRENCES || !validDates.containsAll(completedDates)
        ) {
            issues += issue(PlannedJourneyValidationCode.INVALID_COMPLETED_OCCURRENCES, "completedOccurrenceDates", "Completed dates must be distinct generated recurrence dates")
        }
    }

    private fun containsSensitiveChecklistContent(label: String): Boolean {
        val normalized = label.lowercase(Locale.ROOT)
        val prohibitedTerms = listOf("password", "passcode", "pin", "cvv", "iban", "codice fiscale", "numero carta", "credit card")
        val hasSensitiveTerm = prohibitedTerms.any { term -> Regex("\\b${Regex.escape(term)}\\b").containsMatchIn(normalized) }
        val hasEmail = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE).containsMatchIn(label)
        val hasLongNumber = Regex("(?:\\d[ -]?){13,19}").containsMatchIn(label)
        return hasSensitiveTerm || hasEmail || hasLongNumber
    }

    private fun issue(code: PlannedJourneyValidationCode, field: String, detail: String) = PlannedJourneyValidationIssue(code, field, detail)
}

/** Recurrence generation is deterministic and bounded before any agenda filtering occurs. */
object PlannedJourneyOccurrences {
    fun dates(recurrence: PlannedJourneyRecurrence): List<LocalDate> {
        if (recurrence.weekdays.isEmpty() || recurrence.occurrenceLimit !in 1..PlannedJourneyPersistenceSchema.MAX_RECURRENCE_OCCURRENCES ||
            (recurrence.endsOn != null && recurrence.endsOn.isBefore(recurrence.startsOn))
        ) return emptyList()
        val enabledDays = recurrence.weekdays.toSet()
        val result = mutableListOf<LocalDate>()
        var cursor = recurrence.startsOn
        while (result.size < recurrence.occurrenceLimit && (recurrence.endsOn == null || !cursor.isAfter(recurrence.endsOn))) {
            if (cursor.dayOfWeek in enabledDays) result += cursor
            if (result.size >= recurrence.occurrenceLimit || cursor == recurrence.endsOn || cursor == LocalDate.MAX) break
            cursor = runCatching { cursor.plusDays(1) }.getOrNull() ?: break
        }
        return result
    }

    fun forJourney(journey: PlannedJourney): List<PlannedJourneyOccurrence> {
        if (PlannedJourneyValidator.validate(journey).isNotEmpty()) return emptyList()
        val firstSegment = journey.itinerary.segments.firstOrNull() ?: return emptyList()
        val rootDate = firstSegment.departure.date
        val occurrenceDates = journey.recurrence?.let(::dates) ?: listOf(rootDate)
        return occurrenceDates.mapNotNull { occurrenceDate ->
            val days = ChronoUnit.DAYS.between(rootDate, occurrenceDate)
            val shiftedSegments = journey.itinerary.segments.map { segment ->
                runCatching { segment.copy(departure = segment.departure.shiftDays(days)) }.getOrNull()
            }
            if (shiftedSegments.any { it == null }) return@mapNotNull null
            val scheduledItinerary = PlannedJourneyItinerary(shiftedSegments.filterNotNull())
            val scheduledAt = runCatching { scheduledItinerary.segments.first().departure.scheduledAt().toInstant() }
                .getOrNull() ?: return@mapNotNull null
            PlannedJourneyOccurrence(
                sourceJourneyId = journey.id,
                occurrenceDate = occurrenceDate,
                itinerary = scheduledItinerary,
                scheduledAt = scheduledAt,
                isGeneratedOccurrence = journey.recurrence != null && occurrenceDate != rootDate
            )
        }
    }
}

data class PlannedJourneyOccurrence(
    val sourceJourneyId: String,
    val occurrenceDate: LocalDate,
    val itinerary: PlannedJourneyItinerary,
    val scheduledAt: Instant,
    val isGeneratedOccurrence: Boolean
)

enum class PlannedJourneyAgendaFilter {
    UPCOMING,
    ARCHIVED,
    REMINDERS,
    ALL
}

data class PlannedJourneyAgendaEntry(
    val journey: PlannedJourney,
    val occurrence: PlannedJourneyOccurrence
)

/** Ordered local agenda; it is intentionally not a notification scheduler. */
object PlannedJourneyAgenda {
    fun ordered(
        journeys: Iterable<PlannedJourney>,
        filter: PlannedJourneyAgendaFilter = PlannedJourneyAgendaFilter.UPCOMING,
        now: Instant
    ): List<PlannedJourneyAgendaEntry> {
        return journeys.asSequence()
            .filter { journey ->
                when (filter) {
                    PlannedJourneyAgendaFilter.UPCOMING -> journey.status == PlannedJourneyStatus.ACTIVE
                    PlannedJourneyAgendaFilter.ARCHIVED -> journey.status == PlannedJourneyStatus.ARCHIVED
                    PlannedJourneyAgendaFilter.REMINDERS -> journey.status == PlannedJourneyStatus.ACTIVE && journey.reminder != null
                    PlannedJourneyAgendaFilter.ALL -> true
                }
            }
            .flatMap { journey -> PlannedJourneyOccurrences.forJourney(journey).asSequence().map { occurrence -> PlannedJourneyAgendaEntry(journey, occurrence) } }
            .filter { entry ->
                when (filter) {
                    PlannedJourneyAgendaFilter.UPCOMING, PlannedJourneyAgendaFilter.REMINDERS ->
                        !entry.occurrence.scheduledAt.isBefore(now) && entry.occurrence.occurrenceDate !in entry.journey.completedOccurrenceDates
                    PlannedJourneyAgendaFilter.ARCHIVED, PlannedJourneyAgendaFilter.ALL -> true
                }
            }
            .sortedWith(compareBy<PlannedJourneyAgendaEntry> { it.occurrence.scheduledAt }.thenBy { it.journey.id })
            .toList()
    }
}

/** Canonical itinerary/schedule deduplication, preserving the first journey supplied. */
object PlannedJourneyDeduplication {
    fun fingerprint(journey: PlannedJourney): String {
        val itinerary = journey.itinerary.segments.joinToString("|") { segment ->
            listOf(
                segment.origin.normalizedId(),
                segment.destination.normalizedId(),
                segment.departure.date.toString(),
                segment.departure.time.toString(),
                segment.departure.timeZone.id
            ).joinToString("~")
        }
        val recurrence = journey.recurrence?.let { value ->
            listOf(
                value.schemaVersion.toString(),
                value.weekdays.sortedBy { it.value }.joinToString(",") { it.name },
                value.startsOn.toString(),
                value.endsOn?.toString().orEmpty(),
                value.occurrenceLimit.toString()
            ).joinToString("~")
        } ?: "once"
        return "$itinerary#recurrence:$recurrence"
    }

    fun deduplicate(journeys: Iterable<PlannedJourney>): List<PlannedJourney> {
        val seen = mutableSetOf<String>()
        return journeys.filter { seen.add(fingerprint(it)) }
    }

    /** Suitable for a local writer: validates first, deduplicates, and applies the hard local cap. */
    fun normalizedSnapshot(journeys: Iterable<PlannedJourney>): PlannedJourneyPersistenceSnapshot {
        val valid = journeys.filter { PlannedJourneyValidator.validate(it).isEmpty() }
        return PlannedJourneyPersistenceSnapshot(journeys = deduplicate(valid).take(PlannedJourneyPersistenceSchema.MAX_JOURNEYS))
    }
}

/** Lifecycle operations preserve the status/timestamp invariants enforced by [PlannedJourneyValidator]. */
object PlannedJourneyLifecycle {
    fun complete(journey: PlannedJourney, completedAt: Instant): PlannedJourney {
        require(journey.status == PlannedJourneyStatus.ACTIVE) { "Only active journeys can be completed" }
        require(!completedAt.isBefore(journey.createdAt)) { "Completion cannot precede creation" }
        return journey.copy(status = PlannedJourneyStatus.COMPLETED, completedAt = completedAt, archivedAt = null)
    }

    fun completeOccurrence(journey: PlannedJourney, occurrenceDate: LocalDate): PlannedJourney {
        require(journey.status == PlannedJourneyStatus.ACTIVE) { "Only active journeys can have completed occurrences" }
        require(journey.recurrence != null) { "Only recurring journeys have individual occurrences" }
        require(occurrenceDate in PlannedJourneyOccurrences.dates(requireNotNull(journey.recurrence))) { "Date is not a generated occurrence" }
        return journey.copy(completedOccurrenceDates = (journey.completedOccurrenceDates + occurrenceDate).distinct().sorted())
    }

    fun archive(journey: PlannedJourney, archivedAt: Instant): PlannedJourney {
        require(journey.status != PlannedJourneyStatus.ARCHIVED) { "Journey is already archived" }
        require(!archivedAt.isBefore(journey.createdAt)) { "Archival cannot precede creation" }
        require(journey.completedAt == null || !archivedAt.isBefore(journey.completedAt)) { "Archival cannot precede completion" }
        return journey.copy(status = PlannedJourneyStatus.ARCHIVED, archivedAt = archivedAt)
    }
}

/**
 * Return and replanning deliberately retain only canonical route geometry. Dates, local times,
 * time zones, reminders, recurrence, checklist state, and lifecycle state must be chosen anew.
 */
object PlannedJourneyDrafts {
    fun replanFrom(journey: PlannedJourney, newJourneyId: String, createdAt: Instant): PlannedJourneyDraft = PlannedJourneyDraft(
        id = newJourneyId,
        label = "",
        itinerary = PlannedJourneyItineraryDraft(journey.itinerary.segments.map { segment ->
            PlannedJourneySegmentDraft(segment.id, segment.origin, segment.destination)
        }),
        createdAt = createdAt
    )

    fun returnFrom(journey: PlannedJourney, newJourneyId: String, createdAt: Instant): PlannedJourneyDraft = PlannedJourneyDraft(
        id = newJourneyId,
        label = "",
        itinerary = PlannedJourneyItineraryDraft(journey.itinerary.segments.asReversed().mapIndexed { index, segment ->
            PlannedJourneySegmentDraft("return-${index + 1}", segment.destination, segment.origin)
        }),
        createdAt = createdAt
    )
}
