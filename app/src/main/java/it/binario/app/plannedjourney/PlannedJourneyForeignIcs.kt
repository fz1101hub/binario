package it.binario.app.plannedjourney

import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The journeys read from a calendar file of another app; [skipped] events could not become one. */
data class PlannedJourneyForeignCalendar(
    val lines: List<PlannedJourneyTextLine>,
    val skipped: Int
)

/**
 * Calendar files of other apps (not Binario's own export, which keeps its integrity preview): each timed event whose
 * SUMMARY is a route «A → B» becomes a proposed journey at its Italian departure time. UTC times and known IANA
 * `TZID`s (quoted or not, a colon inside quotes allowed) are converted to Italian time; a floating time is read as
 * Italian; an all-day event, an unknown `TZID` or a time that does not exist in its zone is skipped, never guessed.
 * The file is bounded by [MAX_BYTES] and at most [MAX_EVENTS] events are read; the rest count as skipped.
 */
object PlannedJourneyForeignIcs {
    const val MAX_BYTES = PlannedJourneyIcsContract.MAX_BYTES
    const val MAX_EVENTS = PlannedJourneyIcsContract.MAX_EVENTS
    private val italy: ZoneId = ZoneId.of("Europe/Rome")
    private val basic = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** A calendar envelope without Binario's own PRODID. */
    fun isForeign(calendar: String): Boolean {
        val lines = unfold(calendar)
        return lines.firstOrNull()?.equals("BEGIN:VCALENDAR", ignoreCase = true) == true &&
            lines.none { it == "PRODID:" + PlannedJourneyIcsContract.productId() }
    }

    /** Null when the file is too large or is not a calendar. */
    fun read(calendar: String): PlannedJourneyForeignCalendar? {
        if (calendar.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) return null
        val lines = unfold(calendar)
        if (lines.firstOrNull()?.equals("BEGIN:VCALENDAR", ignoreCase = true) != true) return null
        val parsed = mutableListOf<PlannedJourneyTextLine>()
        var skipped = 0
        var events = 0
        var current: MutableList<String>? = null
        lines.forEach { line ->
            when {
                line.equals("BEGIN:VEVENT", ignoreCase = true) -> current = mutableListOf()
                line.equals("END:VEVENT", ignoreCase = true) -> {
                    val event = current ?: return@forEach
                    current = null
                    events++
                    val journey = if (events <= MAX_EVENTS) event(event) else null
                    if (journey == null) skipped++ else parsed += journey
                }
                else -> current?.add(line)
            }
        }
        return PlannedJourneyForeignCalendar(parsed, skipped)
    }

    private fun event(lines: List<String>): PlannedJourneyTextLine? {
        val properties = lines.mapNotNull(::property)
        val summary = properties.firstOrNull { it.name == "SUMMARY" }?.let { unescape(it.value) } ?: return null
        val start = properties.firstOrNull { it.name == "DTSTART" } ?: return null
        val departure = italianDeparture(start) ?: return null
        val (origin, destination) = PlannedJourneyTextImport.parseRoute(summary) ?: return null
        return PlannedJourneyTextLine(origin, destination, departure.toLocalDate(), departure.toLocalTime())
    }

    /** DTSTART as an Italian wall-clock date and time, or null for an all-day, unknown-zone or non-existent time. */
    internal fun italianDeparture(start: Property): LocalDateTime? {
        val valueType = start.parameters["VALUE"]
        if (valueType != null && !valueType.equals("DATE-TIME", ignoreCase = true)) return null
        val raw = start.value.trim()
        return try {
            when {
                raw.endsWith("Z") -> LocalDateTime.parse(raw.dropLast(1), basic).atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(italy).toLocalDateTime()
                start.parameters["TZID"] != null -> {
                    val zone = runCatching { ZoneId.of(start.parameters.getValue("TZID").trim()) }.getOrNull() ?: return null
                    val local = LocalDateTime.parse(raw, basic)
                    val offsets = zone.rules.getValidOffsets(local)
                    if (offsets.isEmpty()) return null
                    local.atOffset(offsets.first()).atZoneSameInstant(italy).toLocalDateTime()
                }
                else -> LocalDateTime.parse(raw, basic)
            }.withSecond(0).withNano(0)
        } catch (error: DateTimeException) {
            null
        }
    }

    internal data class Property(val name: String, val parameters: Map<String, String>, val value: String)

    /** «NAME;PARAM=value;PARAM="quoted:value":VALUE»: the first colon outside quotes ends the parameters. */
    internal fun property(line: String): Property? {
        var inQuotes = false
        var split = -1
        for (index in line.indices) {
            val char = line[index]
            if (char == '"') inQuotes = !inQuotes
            else if (char == ':' && !inQuotes) {
                split = index
                break
            }
        }
        if (split <= 0) return null
        val head = line.substring(0, split)
        val parts = mutableListOf<String>()
        val part = StringBuilder()
        inQuotes = false
        head.forEach { char ->
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ';' && !inQuotes -> { parts += part.toString(); part.clear() }
                else -> part.append(char)
            }
        }
        parts += part.toString()
        val parameters = parts.drop(1).mapNotNull { parameter ->
            val name = parameter.substringBefore('=', "").trim().uppercase()
            if (name.isEmpty()) null else name to parameter.substringAfter('=')
        }.toMap()
        return Property(parts.first().trim().uppercase(), parameters, line.substring(split + 1))
    }

    /** Folded lines joined (a leading space or tab continues the previous line), any line length accepted. */
    internal fun unfold(calendar: String): List<String> {
        val logical = mutableListOf<String>()
        calendar.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            if ((line.startsWith(' ') || line.startsWith('\t')) && logical.isNotEmpty()) logical[logical.lastIndex] += line.drop(1)
            else if (line.isNotEmpty()) logical += line
        }
        return logical
    }

    internal fun unescape(value: String): String {
        val result = StringBuilder()
        var escaped = false
        value.forEach { char ->
            if (escaped) {
                result.append(if (char == 'n' || char == 'N') ' ' else char)
                escaped = false
            } else if (char == '\\') escaped = true
            else result.append(char)
        }
        return result.toString().trim()
    }
}
