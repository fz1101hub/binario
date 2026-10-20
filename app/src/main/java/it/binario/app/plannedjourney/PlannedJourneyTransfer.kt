package it.binario.app.plannedjourney

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object PlannedJourneyTransferContract {
    const val KIND = "binario_planned_journeys"
    const val VERSION = 1
    const val MAX_BYTES = 262_144
}

enum class PlannedJourneyTransferErrorCode {
    TOO_LARGE,
    MALFORMED_DOCUMENT,
    UNSUPPORTED_KIND,
    UNSUPPORTED_VERSION,
    INVALID_JOURNEYS
}

data class PlannedJourneyTransferError(
    val code: PlannedJourneyTransferErrorCode,
    val detail: String
)

sealed interface PlannedJourneyTransferDecodeResult {
    data class Valid(val snapshot: PlannedJourneyPersistenceSnapshot) : PlannedJourneyTransferDecodeResult
    data class Invalid(val errors: List<PlannedJourneyTransferError>) : PlannedJourneyTransferDecodeResult
}

data class PlannedJourneyImportConflict(
    val existing: PlannedJourney,
    val imported: PlannedJourney
)

data class PlannedJourneyImportPreview(
    val accepted: List<PlannedJourney>,
    val duplicates: List<PlannedJourney>,
    val conflicts: List<PlannedJourneyImportConflict>
)

enum class PlannedJourneyConflictPolicy { KEEP_EXISTING, REPLACE_EXISTING }

/**
 * Portable JSON boundary for planned journeys only. It never serializes SavedRoute,
 * realtime state, credentials, location, tickets, notes, or notification identifiers.
 */
object PlannedJourneyTransfer {
    fun encode(snapshot: PlannedJourneyPersistenceSnapshot): String {
        val issues = PlannedJourneyValidator.validate(snapshot)
        require(issues.isEmpty()) { "Cannot export an invalid planned-journey snapshot" }
        val root = JSONObject()
            .put("kind", PlannedJourneyTransferContract.KIND)
            .put("schemaVersion", PlannedJourneyTransferContract.VERSION)
            .put("journeys", JSONArray(snapshot.journeys.map(::journeyToJson)))
        val encoded = root.toString()
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= PlannedJourneyTransferContract.MAX_BYTES) {
            "Planned-journey transfer exceeds ${PlannedJourneyTransferContract.MAX_BYTES} bytes"
        }
        return encoded
    }

    fun decode(encoded: String): PlannedJourneyTransferDecodeResult {
        if (encoded.toByteArray(StandardCharsets.UTF_8).size > PlannedJourneyTransferContract.MAX_BYTES) {
            return invalid(PlannedJourneyTransferErrorCode.TOO_LARGE, "Transfer exceeds the byte limit")
        }
        return try {
            val root = JSONObject(encoded)
            root.requireOnly("kind", "schemaVersion", "journeys")
            if (root.requireString("kind") != PlannedJourneyTransferContract.KIND) {
                return invalid(PlannedJourneyTransferErrorCode.UNSUPPORTED_KIND, "Unexpected transfer kind")
            }
            if (root.requireInt("schemaVersion") != PlannedJourneyTransferContract.VERSION) {
                return invalid(PlannedJourneyTransferErrorCode.UNSUPPORTED_VERSION, "Unsupported transfer version")
            }
            val array = root.requireArray("journeys")
            if (array.length() > PlannedJourneyPersistenceSchema.MAX_JOURNEYS) {
                return invalid(PlannedJourneyTransferErrorCode.INVALID_JOURNEYS, "Too many journeys")
            }
            val journeys = (0 until array.length()).map { index -> parseJourney(array.requireObject(index)) }
            val snapshot = PlannedJourneyPersistenceSnapshot(journeys = journeys)
            val issues = PlannedJourneyValidator.validate(snapshot)
            if (issues.isEmpty()) PlannedJourneyTransferDecodeResult.Valid(snapshot)
            else PlannedJourneyTransferDecodeResult.Invalid(
                issues.map { issue ->
                    PlannedJourneyTransferError(
                        PlannedJourneyTransferErrorCode.INVALID_JOURNEYS,
                        "${issue.field}: ${issue.detail}"
                    )
                }
            )
        } catch (error: RuntimeException) {
            invalid(PlannedJourneyTransferErrorCode.MALFORMED_DOCUMENT, error.message ?: "Malformed transfer")
        }
    }

    fun preview(
        imported: PlannedJourneyPersistenceSnapshot,
        existing: Iterable<PlannedJourney>
    ): PlannedJourneyImportPreview {
        require(PlannedJourneyValidator.validate(imported).isEmpty()) { "Imported snapshot must be valid" }
        val existingList = existing.toList()
        val byId = existingList.associateBy { it.id }
        val fingerprints = existingList.mapTo(mutableSetOf(), PlannedJourneyDeduplication::fingerprint)
        val accepted = mutableListOf<PlannedJourney>()
        val duplicates = mutableListOf<PlannedJourney>()
        val conflicts = mutableListOf<PlannedJourneyImportConflict>()
        imported.journeys.forEach { journey ->
            when {
                PlannedJourneyDeduplication.fingerprint(journey) in fingerprints -> duplicates += journey
                byId[journey.id] != null -> conflicts += PlannedJourneyImportConflict(byId.getValue(journey.id), journey)
                else -> accepted += journey
            }
        }
        return PlannedJourneyImportPreview(accepted, duplicates, conflicts)
    }

    fun merge(
        existing: Iterable<PlannedJourney>,
        preview: PlannedJourneyImportPreview,
        conflictPolicy: PlannedJourneyConflictPolicy
    ): PlannedJourneyPersistenceSnapshot {
        val merged = existing.associateByTo(linkedMapOf()) { it.id }
        preview.accepted.forEach { merged[it.id] = it }
        if (conflictPolicy == PlannedJourneyConflictPolicy.REPLACE_EXISTING) {
            preview.conflicts.forEach { conflict -> merged[conflict.imported.id] = conflict.imported }
        }
        return PlannedJourneyDeduplication.normalizedSnapshot(merged.values)
    }

    private fun journeyToJson(journey: PlannedJourney): JSONObject = JSONObject()
        .put("schemaVersion", journey.schemaVersion)
        .put("id", journey.id)
        .put("label", journey.label)
        .put("itinerary", JSONObject().put("segments", JSONArray(journey.itinerary.segments.map(::segmentToJson))))
        .putOptional("reminderMinutes", journey.reminder?.minutesBeforeDeparture?.minutes)
        .putOptional("recurrence", journey.recurrence?.let(::recurrenceToJson))
        .put("checklist", JSONArray(journey.checklist.map(::checklistItemToJson)))
        .put("status", journey.status.name)
        .put("createdAt", journey.createdAt.toString())
        .putOptional("completedAt", journey.completedAt?.toString())
        .putOptional("archivedAt", journey.archivedAt?.toString())
        .put("completedOccurrenceDates", JSONArray(journey.completedOccurrenceDates.map(LocalDate::toString)))

    private fun segmentToJson(segment: PlannedJourneySegment): JSONObject = JSONObject()
        .put("id", segment.id)
        .put("origin", stationToJson(segment.origin))
        .put("destination", stationToJson(segment.destination))
        .put("departure", dateTimeToJson(segment.departure))

    private fun stationToJson(station: PlannedJourneyStation): JSONObject = JSONObject()
        .put("canonicalId", station.canonicalId)
        .put("displayName", station.displayName)
        .putOptional("railwayCode", station.railwayCode)

    private fun dateTimeToJson(value: PlannedJourneyDateTime): JSONObject = JSONObject()
        .put("date", value.date.toString())
        .put("time", value.time.toString())
        .put("timeZone", value.timeZone.id)

    private fun recurrenceToJson(recurrence: PlannedJourneyRecurrence): JSONObject = JSONObject()
        .put("schemaVersion", recurrence.schemaVersion)
        .put("weekdays", JSONArray(recurrence.weekdays.map(DayOfWeek::name)))
        .put("startsOn", recurrence.startsOn.toString())
        .putOptional("endsOn", recurrence.endsOn?.toString())
        .put("occurrenceLimit", recurrence.occurrenceLimit)

    private fun checklistItemToJson(item: PlannedJourneyChecklistItem): JSONObject = JSONObject()
        .put("id", item.id)
        .put("label", item.label)
        .put("completed", item.completed)

    private fun parseJourney(value: JSONObject): PlannedJourney {
        value.requireOnly(
            "schemaVersion", "id", "label", "itinerary", "reminderMinutes", "recurrence", "checklist",
            "status", "createdAt", "completedAt", "archivedAt", "completedOccurrenceDates"
        )
        val itinerary = value.requireObject("itinerary")
        itinerary.requireOnly("segments")
        val segments = itinerary.requireArray("segments").mapObjects(::parseSegment)
        val checklist = value.requireArray("checklist").mapObjects(::parseChecklistItem)
        val completedDates = value.requireArray("completedOccurrenceDates").mapStrings(LocalDate::parse)
        return PlannedJourney(
            schemaVersion = value.requireInt("schemaVersion"),
            id = value.requireString("id"),
            label = value.requireString("label"),
            itinerary = PlannedJourneyItinerary(segments),
            reminder = value.optionalInt("reminderMinutes")?.let { minutes ->
                PlannedJourneyReminder(
                    PlannedJourneyReminderMinutes.fromMinutes(minutes)
                        ?: throw TransferParseException("Unsupported reminder interval")
                )
            },
            recurrence = value.optionalObject("recurrence")?.let(::parseRecurrence),
            checklist = checklist,
            status = enumValue(value.requireString("status"), "status"),
            createdAt = parseInstant(value.requireString("createdAt"), "createdAt"),
            completedAt = value.optionalString("completedAt")?.let { parseInstant(it, "completedAt") },
            archivedAt = value.optionalString("archivedAt")?.let { parseInstant(it, "archivedAt") },
            completedOccurrenceDates = completedDates
        )
    }

    private fun parseSegment(value: JSONObject): PlannedJourneySegment {
        value.requireOnly("id", "origin", "destination", "departure")
        return PlannedJourneySegment(
            id = value.requireString("id"),
            origin = parseStation(value.requireObject("origin")),
            destination = parseStation(value.requireObject("destination")),
            departure = parseDateTime(value.requireObject("departure"))
        )
    }

    private fun parseStation(value: JSONObject): PlannedJourneyStation {
        value.requireOnly("canonicalId", "displayName", "railwayCode")
        return PlannedJourneyStation(
            canonicalId = value.requireString("canonicalId"),
            displayName = value.requireString("displayName"),
            railwayCode = value.optionalString("railwayCode")
        )
    }

    private fun parseDateTime(value: JSONObject): PlannedJourneyDateTime {
        value.requireOnly("date", "time", "timeZone")
        return PlannedJourneyDateTime(
            date = parseDate(value.requireString("date"), "date"),
            time = runCatching { LocalTime.parse(value.requireString("time")) }
                .getOrElse { throw TransferParseException("Invalid local time") },
            timeZone = runCatching { ZoneId.of(value.requireString("timeZone")) }
                .getOrElse { throw TransferParseException("Invalid time zone") }
        )
    }

    private fun parseRecurrence(value: JSONObject): PlannedJourneyRecurrence {
        value.requireOnly("schemaVersion", "weekdays", "startsOn", "endsOn", "occurrenceLimit")
        return PlannedJourneyRecurrence(
            schemaVersion = value.requireInt("schemaVersion"),
            weekdays = value.requireArray("weekdays").mapStrings { enumValue<DayOfWeek>(it, "weekday") },
            startsOn = parseDate(value.requireString("startsOn"), "startsOn"),
            endsOn = value.optionalString("endsOn")?.let { parseDate(it, "endsOn") },
            occurrenceLimit = value.requireInt("occurrenceLimit")
        )
    }

    private fun parseChecklistItem(value: JSONObject): PlannedJourneyChecklistItem {
        value.requireOnly("id", "label", "completed")
        return PlannedJourneyChecklistItem(
            id = value.requireString("id"),
            label = value.requireString("label"),
            completed = value.requireBoolean("completed")
        )
    }

    private fun parseDate(value: String, field: String): LocalDate = runCatching { LocalDate.parse(value) }
        .getOrElse { throw TransferParseException("Invalid $field") }

    private fun parseInstant(value: String, field: String): Instant = runCatching { Instant.parse(value) }
        .getOrElse { throw TransferParseException("Invalid $field") }

    private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
        runCatching { enumValueOf<T>(value) }.getOrElse { throw TransferParseException("Invalid $field") }

    private fun invalid(code: PlannedJourneyTransferErrorCode, detail: String) =
        PlannedJourneyTransferDecodeResult.Invalid(listOf(PlannedJourneyTransferError(code, detail)))
}

private class TransferParseException(message: String) : IllegalArgumentException(message)

private fun JSONObject.putOptional(name: String, value: Any?): JSONObject = apply {
    if (value != null) put(name, value)
}

private fun JSONObject.requireOnly(vararg allowed: String) {
    val allowedKeys = allowed.toSet()
    val unexpected = keys().asSequence().filter { it !in allowedKeys }.toList()
    if (unexpected.isNotEmpty()) throw TransferParseException("Unexpected fields: ${unexpected.joinToString()}")
}

private fun JSONObject.requireString(name: String): String =
    if (has(name) && !isNull(name) && get(name) is String) getString(name)
    else throw TransferParseException("Missing or invalid $name")

private fun JSONObject.requireInt(name: String): Int =
    if (has(name) && !isNull(name) && get(name) is Number) getInt(name)
    else throw TransferParseException("Missing or invalid $name")

private fun JSONObject.requireBoolean(name: String): Boolean =
    if (has(name) && !isNull(name) && get(name) is Boolean) getBoolean(name)
    else throw TransferParseException("Missing or invalid $name")

private fun JSONObject.requireObject(name: String): JSONObject =
    optJSONObject(name) ?: throw TransferParseException("Missing or invalid $name")

private fun JSONObject.requireArray(name: String): JSONArray =
    optJSONArray(name) ?: throw TransferParseException("Missing or invalid $name")

private fun JSONObject.optionalString(name: String): String? = when {
    !has(name) || isNull(name) -> null
    get(name) is String -> getString(name)
    else -> throw TransferParseException("Invalid $name")
}

private fun JSONObject.optionalInt(name: String): Int? = when {
    !has(name) || isNull(name) -> null
    get(name) is Number -> getInt(name)
    else -> throw TransferParseException("Invalid $name")
}

private fun JSONObject.optionalObject(name: String): JSONObject? = when {
    !has(name) || isNull(name) -> null
    else -> optJSONObject(name) ?: throw TransferParseException("Invalid $name")
}

private fun JSONArray.requireObject(index: Int): JSONObject =
    optJSONObject(index) ?: throw TransferParseException("Expected object at index $index")

private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { index -> transform(requireObject(index)) }

private inline fun <T> JSONArray.mapStrings(transform: (String) -> T): List<T> =
    (0 until length()).map { index ->
        val value = get(index)
        if (value !is String) throw TransferParseException("Expected string at index $index")
        transform(value)
    }
