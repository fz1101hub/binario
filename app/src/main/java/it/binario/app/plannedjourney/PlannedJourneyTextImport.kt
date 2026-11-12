package it.binario.app.plannedjourney

import java.text.Normalizer
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** One complete line of pasted or shared text: «Roma Termini → Bologna Centrale · 12/09/2026 alle 08:30». */
data class PlannedJourneyTextLine(
    val origin: String,
    val destination: String,
    val date: LocalDate,
    val time: LocalTime
)

/** Journeys proposed from a text, never saved before the user reviews them; [skipped] lines were not proposed. */
data class PlannedJourneyTextImportPreview(
    val candidates: List<PlannedJourney>,
    val skipped: Int
)

/**
 * Guided import of journeys typed, pasted or shared as text. It reads only complete lines the user supplied
 * (origin, destination, date with the year, time), resolves both stations in the catalogue, refuses what the Agenda
 * already holds, and proposes at most the Agenda's free places: everything else is counted as skipped.
 */
object PlannedJourneyTextImport {
    const val MAX_CHARACTERS = 4_000
    private const val MAX_LINE_CHARACTERS = 480

    private val datePattern = Regex("""(\d{4}-\d{2}-\d{2}|\d{1,2}[/.\-]\d{1,2}[/.\-]\d{4})""")
    private val timePattern = Regex("""(?:\balle\s*|\bore\s*|@\s*|\s)(\d{1,2}[:.]\d{2})\s*$""", RegexOption.IGNORE_CASE)
    private val arrowRoute = Regex("""^(.{2,96}?)\s*(?:→|->)\s*(.{2,96}?)$""")
    private val wordRoute = Regex("""^da\s+(.{2,96}?)\s+(?:a|verso)\s+(.{2,96}?)$""", RegexOption.IGNORE_CASE)

    /**
     * Text received from another app or pasted: at most [MAX_CHARACTERS] characters, NUL removed, line ends unified,
     * trimmed. Null for anything else (nothing, blank, too long).
     */
    fun normalize(raw: CharSequence?): String? {
        if (raw == null || raw.isEmpty() || raw.length > MAX_CHARACTERS) return null
        val text = raw.toString().replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "").trim()
        return text.takeIf { it.isNotEmpty() }
    }

    /** The complete lines of [text], each once (same stations, date and time); the second value counts the rest. */
    fun parse(text: String): Pair<List<PlannedJourneyTextLine>, Int> {
        val lines = text.split(Regex("[\r\n;]+")).map(String::trim).filter(String::isNotEmpty)
        val parsed = mutableListOf<PlannedJourneyTextLine>()
        val keys = mutableSetOf<String>()
        var skipped = 0
        lines.forEach { raw ->
            val line = parseLine(raw)
            if (line == null || !keys.add(listOf(fold(line.origin), fold(line.destination), line.date, line.time).joinToString("|"))) skipped++
            else parsed += line
        }
        return parsed to skipped
    }

    /** «A → B», «A -> B» or «Da A a B»: the two station names of a route, or null. */
    fun parseRoute(routePart: String): Pair<String, String>? {
        val route = arrowRoute.find(routePart.trim()) ?: wordRoute.find(routePart.trim()) ?: return null
        val origin = route.groupValues[1].trim()
        val destination = route.groupValues[2].trim()
        if (origin.length < 2 || destination.length < 2 || fold(origin) == fold(destination)) return null
        return origin to destination
    }

    fun parseLine(raw: String): PlannedJourneyTextLine? {
        val value = raw.replace(Regex("\\s+"), " ").trim()
        if (value.length > MAX_LINE_CHARACTERS) return null
        val dateMatch = datePattern.find(value) ?: return null
        val timeMatch = timePattern.find(value) ?: return null
        if (timeMatch.range.first <= dateMatch.range.first) return null
        val date = parseDate(dateMatch.groupValues[1]) ?: return null
        val time = parseTime(timeMatch.groupValues[1]) ?: return null
        val routePart = value.substring(0, dateMatch.range.first).replace(Regex("""[·,;\-–—\s]+$"""), "").trim()
        val route = arrowRoute.find(routePart) ?: wordRoute.find(routePart) ?: return null
        val origin = route.groupValues[1].trim()
        val destination = route.groupValues[2].trim()
        if (origin.length < 2 || destination.length < 2 || fold(origin) == fold(destination)) return null
        return PlannedJourneyTextLine(origin, destination, date, time)
    }

    /**
     * Proposes the lines of [text] as new journeys: both stations must match one catalogue station each (name, code
     * or id, ignoring case and accents); a line equal to a journey already in [existing] or beyond the Agenda's free
     * places is skipped.
     */
    fun preview(
        text: String,
        stations: List<PlannedJourneyStation>,
        existing: List<PlannedJourney>,
        zone: ZoneId,
        createdAt: Instant,
        newId: () -> String
    ): PlannedJourneyTextImportPreview {
        val (lines, unreadable) = parse(text)
        return previewLines(lines, unreadable, stations, existing, zone, createdAt, newId)
    }

    /** [preview] of lines already read (pasted text or calendar events); [unreadable] counts those that were not. */
    fun previewLines(
        lines: List<PlannedJourneyTextLine>,
        unreadable: Int,
        stations: List<PlannedJourneyStation>,
        existing: List<PlannedJourney>,
        zone: ZoneId,
        createdAt: Instant,
        newId: () -> String
    ): PlannedJourneyTextImportPreview {
        val index = stations.distinctBy { it.canonicalId }.flatMap { station ->
            listOfNotNull(station.displayName, station.railwayCode, station.canonicalId).map { fold(it) to station }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, matches) -> matches.distinctBy { it.canonicalId } }
        val fingerprints = existing.mapTo(mutableSetOf(), PlannedJourneyDeduplication::fingerprint)
        val capacity = (PlannedJourneyPersistenceSchema.MAX_JOURNEYS - existing.size).coerceAtLeast(0)
        val candidates = mutableListOf<PlannedJourney>()
        lines.forEach { line ->
            if (candidates.size >= capacity) return@forEach
            val origin = index[fold(line.origin)]?.singleOrNull() ?: return@forEach
            val destination = index[fold(line.destination)]?.singleOrNull() ?: return@forEach
            val departure = PlannedJourneyDateTime(line.date, line.time, zone)
            val draft = PlannedJourneyDraft(
                id = newId(),
                itinerary = PlannedJourneyItineraryDraft(listOf(PlannedJourneySegmentDraft("segment-1", origin, destination, departure))),
                createdAt = createdAt
            )
            val journey = (PlannedJourneyFactory.create(draft) as? PlannedJourneyValidationResult.Valid)?.value ?: return@forEach
            if (fingerprints.add(PlannedJourneyDeduplication.fingerprint(journey))) candidates += journey
        }
        return PlannedJourneyTextImportPreview(candidates, unreadable + lines.size - candidates.size)
    }

    private fun parseDate(value: String): LocalDate? {
        val iso = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").find(value)
        val local = Regex("""^(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4})$""").find(value)
        val (year, month, day) = when {
            iso != null -> Triple(iso.groupValues[1], iso.groupValues[2], iso.groupValues[3])
            local != null -> Triple(local.groupValues[3], local.groupValues[2], local.groupValues[1])
            else -> return null
        }
        return try {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        } catch (error: DateTimeException) {
            null
        }
    }

    private fun parseTime(value: String): LocalTime? {
        val match = Regex("""^(\d{1,2})[:.](\d{2})$""").find(value) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
    }

    /** Case, accents and punctuation do not distinguish two names. */
    private fun fold(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

/**
 * Text shared to Binario with Android's «Condividi»: only `ACTION_SEND` with `text/plain`, bounded and normalized like
 * a paste. The text is handed to the import preview in memory and never stored; it becomes a journey only after review.
 */
object PlannedJourneySharedText {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val MIME_TYPE = "text/plain"

    fun accept(action: String?, mimeType: String?, text: CharSequence?): String? =
        if (action == ACTION_SEND && mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) == MIME_TYPE) {
            PlannedJourneyTextImport.normalize(text)
        } else {
            null
        }
}
