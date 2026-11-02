package it.binario.app.timetables

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * One solution of the route results as the user saw it, resolved to plain text at save time: times, the trains of
 * every leg ("FR 9520 › REG 2153"), changes and where they happen, the departure platform when the source gave one,
 * the source and when that source answered. Saved rows never update.
 */
data class TimetableRow(
    /** "HH:mm", railway local time. */
    val departure: String,
    val arrival: String?,
    /** Days between departure and arrival (1 = the next day). */
    val arrivalDayOffset: Int = 0,
    val durationMinutes: Int?,
    val trains: String,
    val changes: Int,
    /** "Cambio a Bologna Centrale · attesa 12 min", in the language of the save. */
    val transfers: List<String> = emptyList(),
    val platform: String? = null,
    val scheduledPlatform: String? = null,
    val source: String,
    /** When the source answered (epoch millis); 0 when unknown. */
    val fetchedAtEpochMillis: Long = 0L,
    /** Scheduled times only: no delay, platform or cancellation was checked. */
    val scheduledOnly: Boolean = true
)

/** A saved result list of a route search: kept on this device only, read-only and marked as not updated. */
data class SavedTimetable(
    val id: String,
    val name: String,
    val origin: String,
    val destination: String,
    val serviceDate: LocalDate,
    /** "HH:mm" the search started from. */
    val fromTime: String,
    val savedAtEpochMillis: Long,
    val rows: List<TimetableRow>,
    /** The return of a round trip; empty for a one-way search. */
    val returnRows: List<TimetableRow> = emptyList()
) {
    val solutionCount: Int get() = rows.size + returnRows.size
}

/**
 * JSON form of the saved timetables, shared by the local store and the local backup. Bounded on read and write:
 * at most [MAX_TIMETABLES] lists of [MAX_ROWS] rows each, texts cut at [MAX_TEXT] characters; an unreadable entry is
 * dropped on its own, never the whole list.
 */
object SavedTimetableCodec {
    const val MAX_TIMETABLES = 15
    const val MAX_ROWS = 30
    const val MAX_NAME_LENGTH = 60
    private const val MAX_TEXT = 200
    private const val MAX_TRANSFERS = 6
    private val TIME = Regex("\\d{1,2}:\\d{2}")

    fun encode(timetables: List<SavedTimetable>): String = toJson(timetables).toString()

    fun decode(raw: String?): List<SavedTimetable> =
        raw?.takeIf(String::isNotBlank)?.let { runCatching { fromJson(JSONArray(it)) }.getOrNull() }.orEmpty()

    fun toJson(timetables: List<SavedTimetable>): JSONArray = JSONArray().apply {
        timetables.take(MAX_TIMETABLES).forEach { timetable ->
            put(JSONObject().apply {
                put("id", timetable.id)
                put("name", timetable.name.take(MAX_NAME_LENGTH))
                put("origin", timetable.origin.take(MAX_TEXT))
                put("destination", timetable.destination.take(MAX_TEXT))
                put("date", timetable.serviceDate.toString())
                put("from", timetable.fromTime)
                put("savedAt", timetable.savedAtEpochMillis)
                put("rows", rowsJson(timetable.rows))
                if (timetable.returnRows.isNotEmpty()) put("returnRows", rowsJson(timetable.returnRows))
            })
        }
    }

    fun fromJson(array: JSONArray): List<SavedTimetable> = (0 until array.length()).mapNotNull { index ->
        val value = array.optJSONObject(index) ?: return@mapNotNull null
        runCatching {
            val id = value.text("id") ?: return@mapNotNull null
            val origin = value.text("origin") ?: return@mapNotNull null
            val destination = value.text("destination") ?: return@mapNotNull null
            SavedTimetable(
                id = id,
                name = (value.text("name") ?: "$origin → $destination").take(MAX_NAME_LENGTH),
                origin = origin,
                destination = destination,
                serviceDate = LocalDate.parse(value.optString("date")),
                fromTime = value.optString("from").takeIf { TIME.matches(it) } ?: return@mapNotNull null,
                savedAtEpochMillis = value.optLong("savedAt", 0L),
                rows = rows(value.optJSONArray("rows")),
                returnRows = rows(value.optJSONArray("returnRows"))
            ).takeIf { it.rows.isNotEmpty() || it.returnRows.isNotEmpty() }
        }.getOrNull()
    }.distinctBy { it.id }.take(MAX_TIMETABLES)

    private fun rowsJson(rows: List<TimetableRow>): JSONArray = JSONArray().apply {
        rows.take(MAX_ROWS).forEach { row ->
            put(JSONObject().apply {
                put("departure", row.departure)
                row.arrival?.let { put("arrival", it) }
                if (row.arrivalDayOffset != 0) put("arrivalDayOffset", row.arrivalDayOffset)
                row.durationMinutes?.let { put("duration", it) }
                put("trains", row.trains.take(MAX_TEXT))
                put("changes", row.changes)
                if (row.transfers.isNotEmpty()) put("transfers", JSONArray(row.transfers.take(MAX_TRANSFERS).map { it.take(MAX_TEXT) }))
                row.platform?.let { put("platform", it.take(MAX_TEXT)) }
                row.scheduledPlatform?.let { put("scheduledPlatform", it.take(MAX_TEXT)) }
                put("source", row.source.take(MAX_TEXT))
                put("fetchedAt", row.fetchedAtEpochMillis)
                put("scheduledOnly", row.scheduledOnly)
            })
        }
    }

    private fun rows(array: JSONArray?): List<TimetableRow> = array?.let { values ->
        (0 until values.length()).mapNotNull { index ->
            val value = values.optJSONObject(index) ?: return@mapNotNull null
            val departure = value.optString("departure").takeIf { TIME.matches(it) } ?: return@mapNotNull null
            TimetableRow(
                departure = departure,
                arrival = value.optString("arrival").takeIf { TIME.matches(it) },
                arrivalDayOffset = value.optInt("arrivalDayOffset", 0).coerceIn(0, 7),
                durationMinutes = if (value.has("duration")) value.optInt("duration").takeIf { it >= 0 } else null,
                trains = value.text("trains").orEmpty(),
                changes = value.optInt("changes", 0).coerceIn(0, 20),
                transfers = value.optJSONArray("transfers")?.let { list ->
                    (0 until list.length()).mapNotNull { list.optString(it).trim().takeIf(String::isNotEmpty)?.take(MAX_TEXT) }
                }.orEmpty().take(MAX_TRANSFERS),
                platform = value.text("platform"),
                scheduledPlatform = value.text("scheduledPlatform"),
                source = value.text("source").orEmpty(),
                fetchedAtEpochMillis = value.optLong("fetchedAt", 0L).coerceAtLeast(0L),
                scheduledOnly = value.optBoolean("scheduledOnly", true)
            )
        }.take(MAX_ROWS)
    }.orEmpty()

    private fun JSONObject.text(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).trim().takeIf(String::isNotEmpty)?.take(MAX_TEXT) else null

    /** [timetables] after saving [timetable]: newest first, one per id, at most [MAX_TIMETABLES]. */
    fun withSaved(timetables: List<SavedTimetable>, timetable: SavedTimetable): List<SavedTimetable> =
        (listOf(timetable) + timetables.filterNot { it.id == timetable.id }).take(MAX_TIMETABLES)

    /** [timetables] with [id] renamed; a blank name keeps the previous one. */
    fun renamed(timetables: List<SavedTimetable>, id: String, name: String): List<SavedTimetable> {
        val clean = name.trim().take(MAX_NAME_LENGTH)
        if (clean.isEmpty()) return timetables
        return timetables.map { if (it.id == id) it.copy(name = clean) else it }
    }
}
