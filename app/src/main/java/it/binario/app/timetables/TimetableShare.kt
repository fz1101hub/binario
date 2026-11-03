package it.binario.app.timetables

import it.binario.app.data.PlatformChange
import it.binario.app.data.RailTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A plain-text share: [title] for the chooser, [text] for the message. */
data class ShareText(val title: String, val text: String)

/**
 * Resource templates of the timetable, solution and stop shares, resolved at the UI boundary so that the text is
 * testable on the JVM. [changes] and [transferWait] are the plurals "1 cambio" / "N cambi" and "attesa di N minuti".
 */
data class TimetableShareStrings(
    val locale: Locale,
    /** "%1$s → %2$s": stations, and times of a solution. */
    val route: String,
    /** "Orari %1$s → %2$s · %3$s": stations and the date with the starting time. */
    val listHeader: String,
    val nextDay: String,
    val platform: String,
    val platformChanged: String,
    /** "Fonte: %1$s". */
    val source: String,
    val scheduledOnly: String,
    /** "Aggiornamento: aggiornato alle %1$s". */
    val updated: String,
    /** "Salvato il %1$s · non aggiornato". */
    val savedNotUpdated: String,
    /** Section title of the return of a round trip. */
    val returnTitle: String,
    val changes: (Int) -> String,
    /** "Treno %1$s, fermata %2$s: %3$s". */
    val stopLine: String,
    val stopArrival: String,
    val stopDeparture: String,
    /** "prevista %1$s". */
    val stopExpected: String,
    val stopPlatform: String,
    val stopPlatformChanged: String,
    /** The compact duration in the app language ("3h 20min", "3 ч 20 мин"). */
    val duration: (Int) -> String = TimetableShareFormatter::duration
) {
    fun format(template: String, vararg values: Any): String = template.format(locale, *values)
}

/** Plain-text shares of the route results: one solution, the whole list (live or saved) and one stop of a train. */
object TimetableShareFormatter {
    /** One solution: stations and date, times, duration and changes, the trains, the changes of train, platform, source. */
    fun solution(row: TimetableRow, origin: String, destination: String, dateLabel: String, strings: TimetableShareStrings): ShareText {
        val title = strings.format(strings.route, origin, destination)
        val lines = buildList {
            add(title)
            add(dateLabel)
            add(times(row, strings))
            row.trains.takeIf(String::isNotBlank)?.let(::add)
            addAll(row.transfers)
            platform(row, strings)?.let(::add)
            row.source.takeIf(String::isNotBlank)?.let { add(strings.format(strings.source, it)) }
            if (row.scheduledOnly) add(strings.scheduledOnly)
        }
        return ShareText(title, lines.joinToString("\n"))
    }

    /**
     * The whole result list: a header with stations, date and starting time, one line per solution (changes of train
     * indented below it), the return of a round trip, the sources, and when the data were read or saved.
     */
    fun list(
        origin: String,
        destination: String,
        dateLabel: String,
        rows: List<TimetableRow>,
        returnRows: List<TimetableRow>,
        strings: TimetableShareStrings,
        savedAtLabel: String? = null
    ): ShareText {
        val title = strings.format(strings.listHeader, origin, destination, dateLabel)
        val all = rows + returnRows
        val lines = buildList {
            add(title)
            rows.forEach { addAll(listLines(it, strings)) }
            if (returnRows.isNotEmpty()) {
                add("")
                add(strings.returnTitle)
                returnRows.forEach { addAll(listLines(it, strings)) }
            }
            add("")
            all.map { it.source }.filter(String::isNotBlank).distinct().takeIf { it.isNotEmpty() }
                ?.let { add(strings.format(strings.source, it.joinToString(", "))) }
            if (all.any { it.scheduledOnly }) add(strings.scheduledOnly)
            when {
                savedAtLabel != null -> add(strings.format(strings.savedNotUpdated, savedAtLabel))
                else -> all.maxOfOrNull { it.fetchedAtEpochMillis }?.takeIf { it > 0L }?.let { fetched ->
                    add(strings.format(strings.updated, clock(fetched, strings.locale)))
                }
            }
        }
        return ShareText(title, lines.joinToString("\n"))
    }

    /** "Treno 2153, fermata Milano Centrale: arr. 10:05 (prevista 10:12), bin. 3 (era 5)". */
    fun stop(
        train: String,
        station: String,
        scheduled: String,
        expected: String?,
        departure: Boolean,
        platform: String?,
        scheduledPlatform: String?,
        strings: TimetableShareStrings
    ): ShareText {
        val time = buildString {
            append(strings.format(if (departure) strings.stopDeparture else strings.stopArrival, scheduled))
            expected?.takeIf { it.isNotBlank() && it != scheduled }?.let { append(" (").append(strings.format(strings.stopExpected, it)).append(")") }
            platform?.trim()?.takeIf(String::isNotEmpty)?.let { shown ->
                val change = PlatformChange.of(shown, scheduledPlatform)
                append(", ")
                append(if (change != null) strings.format(strings.stopPlatformChanged, change.actual, change.scheduled) else strings.format(strings.stopPlatform, shown))
            }
        }
        val text = strings.format(strings.stopLine, train, station, time)
        return ShareText(text, text)
    }

    private fun listLines(row: TimetableRow, strings: TimetableShareStrings): List<String> {
        val main = listOfNotNull(
            times(row, strings),
            row.trains.takeIf(String::isNotBlank),
            platform(row, strings)
        ).joinToString(" · ")
        return listOf("• $main") + row.transfers.map { "   $it" }
    }

    /** "17:40 → 21:00 (il giorno dopo) · 3h 20min · 1 cambio". */
    private fun times(row: TimetableRow, strings: TimetableShareStrings): String = listOfNotNull(
        row.arrival?.let { strings.format(strings.route, row.departure, it) + if (row.arrivalDayOffset > 0) " (${strings.nextDay})" else "" }
            ?: row.departure,
        row.durationMinutes?.let(strings.duration),
        strings.changes(row.changes)
    ).joinToString(" · ")

    private fun platform(row: TimetableRow, strings: TimetableShareStrings): String? {
        val shown = row.platform?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val change = PlatformChange.of(shown, row.scheduledPlatform)
        return if (change != null) strings.format(strings.platformChanged, change.actual, change.scheduled) else strings.format(strings.platform, shown)
    }

    /** "3h 20min": the language-neutral fallback of [TimetableShareStrings.duration]. */
    internal fun duration(minutes: Int): String = when {
        minutes < 60 -> String.format(Locale.ROOT, "%dmin", minutes)
        minutes % 60 == 0 -> String.format(Locale.ROOT, "%dh", minutes / 60)
        else -> String.format(Locale.ROOT, "%dh %dmin", minutes / 60, minutes % 60)
    }

    private fun clock(epochMillis: Long, locale: Locale): String =
        DateTimeFormatter.ofPattern("HH:mm", locale).withZone(RailTime.defaultZone).format(Instant.ofEpochMilli(epochMillis))
}
