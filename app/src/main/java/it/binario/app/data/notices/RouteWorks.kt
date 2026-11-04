package it.binario.app.data.notices

import it.binario.app.data.Station
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A saved route as the works matcher reads it: its name and the stations its trains call at (both ends included). */
data class WorksRoute(val id: String, val name: String, val stations: List<Station>)

/** The dates a notice's text names: the earliest and the latest (the same day when it names one). */
data class NoticeDates(val first: LocalDate, val last: LocalDate)

/**
 * Why a notice concerns a route, in the notice's own words: the stations of the route its text names, and the line
 * "A – B" it names when both ends are stations (or places) of the route.
 */
data class RouteWorksReason(val routeId: String, val routeName: String, val stations: List<String>, val line: String?)

/** A notice of works or interruptions that names one or more of the user's routes. */
data class RouteWorksNotice(val notice: RailNotice, val reasons: List<RouteWorksReason>, val dates: NoticeDates?)

/** The works section's answer: the notices that name the routes, and whether a source failed or answered from a stale copy. */
data class RouteWorks(val notices: List<RouteWorksNotice>, val unavailable: Boolean, val stale: Boolean)

/**
 * «Lavori e interruzioni sulle tue tratte»: the network notices about works or interruptions (not strikes, which
 * have their own section) whose text names a station of a saved route, or a line whose two ends the route serves.
 * A station counts only as a whole name in the text ("Pavia", not "Pavia" inside "Pavia Porta Garibaldi" when that is a
 * station of its own), written with a capital, and never a one-word name that is also a common word ("Piano"); city
 * names alone ("linea Milano – Venezia") count only as the two ends of a line. Nothing is inferred beyond the text.
 */
class RouteWorksMatcher(catalogue: List<Station>) {
    /** Folded catalogue names (and their Italian aliases), for the "longer station at the same place" check. */
    private val catalogueNames: List<String> = catalogue.flatMap(::namesOf).distinct()
    private val longer = HashMap<String, Pair<List<String>, List<String>>>()

    fun match(notices: List<RailNotice>, routes: List<WorksRoute>, today: LocalDate): List<RouteWorksNotice> = notices.mapNotNull { notice ->
        val text = listOfNotNull(notice.title, notice.body).joinToString("\n")
        if (!isWorks(text)) return@mapNotNull null
        val folded = FoldedText(text)
        val lines = LINE.findAll(text).map { it.groupValues[1].trim() to it.groupValues[2].trim() }.toList()
        val reasons = routes.mapNotNull { route -> reason(route, folded, lines) }
        if (reasons.isEmpty()) return@mapNotNull null
        val reference = notice.publishedAt?.atZone(ZONE)?.toLocalDate() ?: today
        RouteWorksNotice(notice, reasons, NoticeDateParser.dates(text, reference))
    }.sortedWith(
        compareByDescending<RouteWorksNotice> { works -> works.reasons.maxOf { it.strength() } }
            .thenBy { it.dates?.last?.isBefore(today) == true }
            .thenBy { it.dates?.first ?: LocalDate.MAX }
            .thenByDescending { it.notice.publishedAt ?: Instant.EPOCH }
    )

    private fun RouteWorksReason.strength(): Int = (if (line != null) 2 else 0) + stations.size.coerceAtMost(2)

    private fun reason(route: WorksRoute, text: FoldedText, lines: List<Pair<String, String>>): RouteWorksReason? {
        val named = route.stations.filter { station -> namesOf(station).any { text.names(it, longerThan(it)) } }.map(Station::name).distinct()
        val line = lines.firstOrNull { (from, to) ->
            val first = route.stations.indexOfFirst { endMatches(it, from) }
            val second = route.stations.indexOfFirst { endMatches(it, to) }
            first >= 0 && second >= 0 && first != second
        }?.let { (from, to) -> "$from – $to" }
        if (named.isEmpty() && line == null) return null
        return RouteWorksReason(route.id, route.name, named, line)
    }

    /** A line end ("Milano", "Napoli Centrale", "Firenze AV") is a station named so, or the place a station's name starts with. */
    private fun endMatches(station: Station, end: String): Boolean {
        val words = fold(end).split(' ').filter(String::isNotEmpty)
        return (words.size downTo 1).any { count ->
            val place = words.take(count).joinToString(" ")
            place.length >= MIN_NAME_LENGTH && namesOf(station).any { it == place || it.startsWith("$place ") }
        }
    }

    /** Catalogue names that contain [name] and go on before or after it ("Pavia" in "Pavia Porta Garibaldi"). */
    private fun longerThan(name: String): Pair<List<String>, List<String>> = longer.getOrPut(name) {
        catalogueNames.filter { it.startsWith("$name ") } to catalogueNames.filter { it.endsWith(" $name") }
    }

    /** The folded name of [station] and of its aliases written as Italian station names (same first word: "BOLZANO"). */
    private fun namesOf(station: Station): List<String> {
        val name = fold(station.name)
        val first = name.substringBefore(' ')
        return (listOf(name) + station.aliases.map(::fold).filter { it.substringBefore(' ') == first })
            .filter { it.length >= MIN_NAME_LENGTH && (' ' in it || it !in COMMON_WORDS) }
            .distinct()
    }

    /** The text of one notice folded once: lower case, no accents, every other character a single space. */
    private class FoldedText(original: String) {
        private val folded: String
        /** Index in the original text of each folded character. */
        private val origin: IntArray
        private val original = original

        init {
            val out = StringBuilder(original.length + 2)
            val map = ArrayList<Int>(original.length + 2)
            out.append(' '); map += 0
            original.forEachIndexed { index, char ->
                val base = foldChar(char)
                if (base == ' ' && out.last() == ' ') return@forEachIndexed
                out.append(base); map += index
            }
            if (out.last() != ' ') { out.append(' '); map += original.length - 1 }
            folded = out.toString()
            origin = map.toIntArray()
        }

        /** True when [name] stands in the text as a whole name with a capital and not as part of a [longer] station name. */
        fun names(name: String, longer: Pair<List<String>, List<String>>): Boolean {
            val needle = " $name "
            var from = 0
            while (true) {
                val at = folded.indexOf(needle, from)
                if (at < 0) return false
                from = at + 1
                val start = at + 1
                val end = start + name.length
                if (!original[origin[start]].isUpperCase()) continue
                if (longer.first.any { folded.startsWith(" $it ", at) }) continue
                if (longer.second.any { candidate -> end - candidate.length - 1 >= 0 && folded.startsWith(" $candidate ", end - candidate.length - 1) }) continue
                return true
            }
        }
    }

    companion object {
        private val ZONE: ZoneId = ZoneId.of("Europe/Rome")
        private const val MIN_NAME_LENGTH = 3

        /** One-word station names that are also ordinary words of a notice: never matched alone. */
        private val COMMON_WORDS = setOf(
            "ora", "tra", "via", "per", "con", "piano", "campo", "ponte", "porto", "monte", "borgo", "villa", "valle",
            "colle", "lago", "mare", "bivio", "scalo", "centro", "centrale", "linea", "stazione", "nord", "sud", "est",
            "ovest", "torre", "castello", "croce", "marina", "lido", "porta", "pieve", "sole", "rete", "fiume"
        )

        /** Works and interruptions, by the words RFI uses: lavori, interruzione, sospesa, cantiere, bus sostitutivi… */
        private val WORKS = Regex(
            "\\b(lavori|lavoro|interruzion[ei]|interrott[aoie]|sospes[aoie]|sospension[ei]|cantier[ei]|manutenzion[ei]|" +
                "potenziament[oi]|sostitutiv[aoie])\\b"
        )

        /** A place name of a line end: capitalised words, with "di", "del"… only between two of them ("Reggio di Calabria"). */
        private const val PLACE = "\\p{Lu}[\\p{L}'’.]*(?:(?:\\s+(?:di|de|del|della|sul|in))?\\s+\\p{Lu}[\\p{L}'’.]*)*"

        /** "linea Roma – Napoli via Cassino", "LINEA MILANO-VENEZIA": the two ends of a line named in the text. */
        private val LINE = Regex("\\b(?:[Ll]ine[ae]|LINE[AE])\\s+(?:(?:ferroviari[ae]|FERROVIARI[AE])\\s+)?($PLACE)\\s*[-–—]\\s*($PLACE)")

        internal fun isWorks(text: String): Boolean = !NoticeText.mentionsStrike(text) && WORKS.containsMatchIn(fold(text))

        internal fun fold(value: String): String = buildString(value.length) {
            for (char in value) {
                val base = foldChar(char)
                if (base == ' ' && (isEmpty() || last() == ' ')) continue
                append(base)
            }
        }.trim()

        private fun foldChar(char: Char): Char {
            val base = Normalizer.normalize(char.toString(), Normalizer.Form.NFD).firstOrNull() ?: return ' '
            return if (base.isLetterOrDigit()) base.lowercaseChar() else ' '
        }
    }
}

/**
 * The dates named in a notice's Italian text: "9 novembre 2026", "14/11/2026", "dal 9 al 13 novembre", "tra il 10 e
 * l'11 novembre", "dalle 23.30 di sabato 7 alle 5 di lunedì 9 novembre" (a bare day takes the month of the next full
 * date). A date without a year takes the year that puts it nearest to [reference] (the publication day).
 */
internal object NoticeDateParser {
    private val MONTHS = listOf(
        "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"
    )
    private val MONTH = MONTHS.joinToString("|")
    private val NUMERIC = Regex("(?<![\\d/.])(\\d{1,2})[/.](\\d{1,2})[/.](\\d{4}|\\d{2})(?![\\d/.])")
    private val TEXTUAL = Regex("\\b(\\d{1,2})(?:°|º)?\\s+($MONTH)(?:\\s+(\\d{4}))?\\b")
    private val BARE_DAY = Regex(
        "\\b(?:dal|dall|al|tra il|fra il|tra|fra|il|lunedi|martedi|mercoledi|giovedi|venerdi|sabato|domenica)\\s+(\\d{1,2})\\b(?!\\s*(?:[:.]\\d|°|º|$MONTH|/))"
    )

    fun dates(text: String, reference: LocalDate): NoticeDates? {
        val lower = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT)
            .replace('’', ' ').replace('\'', ' ')
        val found = ArrayList<LocalDate>()
        NUMERIC.findAll(lower).forEach { match ->
            val (day, month, year) = match.destructured
            val fullYear = year.toInt().let { if (it < 100) 2000 + it else it }
            date(fullYear, month.toInt(), day.toInt())?.let(found::add)
        }
        val textual = TEXTUAL.findAll(lower).mapNotNull { match ->
            val day = match.groupValues[1].toInt()
            val month = MONTHS.indexOf(match.groupValues[2]) + 1
            val date = match.groupValues[3].takeIf(String::isNotEmpty)?.let { date(it.toInt(), month, day) } ?: nearest(day, month, reference)
            date?.let { match.range.first to it }
        }.toList()
        found += textual.map { it.second }
        BARE_DAY.findAll(lower).forEach { match ->
            val position = match.range.first
            val next = textual.firstOrNull { it.first > position }?.second ?: return@forEach
            val day = match.groupValues[1].toInt()
            val sameMonth = date(next.year, next.monthValue, day) ?: return@forEach
            found += if (sameMonth.isAfter(next)) date(next.minusMonths(1).year, next.minusMonths(1).monthValue, day) ?: return@forEach else sameMonth
        }
        val plausible = found.filter { !it.isBefore(reference.minusYears(1)) && !it.isAfter(reference.plusYears(2)) }
        if (plausible.isEmpty()) return null
        return NoticeDates(plausible.min(), plausible.max())
    }

    /** [day] [month] in the year that puts it within six months of [reference]. */
    private fun nearest(day: Int, month: Int, reference: LocalDate): LocalDate? {
        val candidate = date(reference.year, month, day) ?: return null
        return when {
            candidate.isBefore(reference.minusMonths(6)) -> date(reference.year + 1, month, day)
            candidate.isAfter(reference.plusMonths(6)) -> date(reference.year - 1, month, day)
            else -> candidate
        }
    }

    private fun date(year: Int, month: Int, day: Int): LocalDate? =
        if (month in 1..12 && day in 1..31) runCatching { LocalDate.of(year, month, day) }.getOrNull() else null
}
