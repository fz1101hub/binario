package it.binario.app.data.planned

import java.time.DayOfWeek
import java.time.LocalDate
import java.text.Normalizer
import java.time.Month
import java.util.Locale

/** Calendario minimo delle festività nazionali che rendono festivo un servizio RFI. */
internal object ItalianRailServiceCalendar {
    fun isHoliday(date: LocalDate): Boolean {
        val fixed = setOf(
            Month.JANUARY to 1,
            Month.JANUARY to 6,
            Month.APRIL to 25,
            Month.MAY to 1,
            Month.JUNE to 2,
            Month.AUGUST to 15,
            Month.NOVEMBER to 1,
            Month.DECEMBER to 8,
            Month.DECEMBER to 25,
            Month.DECEMBER to 26
        )
        if ((date.month to date.dayOfMonth) in fixed) return true
        val easter = easterSunday(date.year)
        return date == easter || date == easter.plusDays(1)
    }

    /**
     * Shared RFI periodicity matcher for the free-text "Periodicità e avvertenze" column.
     *
     * The text is read as clauses of day terms with a polarity. Positive terms say when a train
     * runs; terms after a negation (NON, ESCLUSO, ECCETTO, TRANNE, SALVO) say when it does not,
     * until the clause ends. Day names, three-letter and two-letter abbreviations, ranges
     * ("LUN-VEN", "DAL LUNEDÌ AL SABATO"), FERIALE and every FESTIV* form are understood.
     * A train runs when no positive term exists or one matches, and no negative term matches.
     * Text with no recognised term (warnings such as "Non ferma a X") stays fail-open.
     */
    fun runsOnDate(periodicity: String, date: LocalDate): Boolean {
        val terms = parsePeriodicity(periodicity)
        if (terms.isEmpty()) return true
        val positives = terms.filter { !it.negated }
        val negatives = terms.filter { it.negated }
        val runsByPositives = positives.isEmpty() || positives.any { it.kind.matches(date) }
        return runsByPositives && negatives.none { it.kind.matches(date) }
    }

    private sealed interface TermKind {
        fun matches(date: LocalDate): Boolean
    }

    private data class Days(val days: Set<DayOfWeek>) : TermKind {
        override fun matches(date: LocalDate) = date.dayOfWeek in days
    }

    private data object Holiday : TermKind {
        override fun matches(date: LocalDate) = date.dayOfWeek == DayOfWeek.SUNDAY || isHoliday(date)
    }

    private data object Weekday : TermKind {
        override fun matches(date: LocalDate) = date.dayOfWeek != DayOfWeek.SUNDAY && !isHoliday(date)
    }

    private data object Daily : TermKind {
        override fun matches(date: LocalDate) = true
    }

    private data class Term(val kind: TermKind, val negated: Boolean)

    private val fullDayWords = mapOf(
        "LUNEDI" to DayOfWeek.MONDAY, "LUN" to DayOfWeek.MONDAY,
        "MARTEDI" to DayOfWeek.TUESDAY, "MAR" to DayOfWeek.TUESDAY,
        "MERCOLEDI" to DayOfWeek.WEDNESDAY, "MER" to DayOfWeek.WEDNESDAY,
        "GIOVEDI" to DayOfWeek.THURSDAY, "GIO" to DayOfWeek.THURSDAY,
        "VENERDI" to DayOfWeek.FRIDAY, "VEN" to DayOfWeek.FRIDAY,
        "SABATO" to DayOfWeek.SATURDAY, "SABATI" to DayOfWeek.SATURDAY, "SAB" to DayOfWeek.SATURDAY,
        "DOMENICA" to DayOfWeek.SUNDAY, "DOMENICHE" to DayOfWeek.SUNDAY, "DOM" to DayOfWeek.SUNDAY
    )

    /** Two-letter forms are ordinary Italian words too ("MA", "ME", "DO"), so they count only next to another day. */
    private val shortDayWords = mapOf(
        "LU" to DayOfWeek.MONDAY, "MA" to DayOfWeek.TUESDAY, "ME" to DayOfWeek.WEDNESDAY,
        "GI" to DayOfWeek.THURSDAY, "VE" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "DO" to DayOfWeek.SUNDAY
    )

    private val negationWords = setOf(
        "NON", "ESCLUSO", "ESCLUSA", "ESCLUSI", "ESCLUSE", "ESCL", "ECCETTO", "TRANNE", "SALVO"
    )
    private const val CLAUSE_END = "|"
    private const val RANGE = "-"

    private fun tokenize(periodicity: String): List<String> =
        Normalizer.normalize(periodicity, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .uppercase(Locale.ROOT)
            .replace(Regex("[.;:]"), " $CLAUSE_END ")
            .replace(RANGE, " $RANGE ")
            .replace(Regex("[^A-Z0-9|\\- ]"), " ")
            .split(' ')
            .filter(String::isNotBlank)

    private fun parsePeriodicity(periodicity: String): List<Term> {
        val tokens = tokenize(periodicity)
        fun fullDay(index: Int) = tokens.getOrNull(index)?.let(fullDayWords::get)
        fun anyDay(index: Int) = tokens.getOrNull(index)?.let { fullDayWords[it] ?: shortDayWords[it] }
        fun dayAt(index: Int): DayOfWeek? {
            fullDay(index)?.let { return it }
            val short = tokens.getOrNull(index)?.let(shortDayWords::get) ?: return null
            val neighbourIsDay = listOf(index - 1, index + 1, index - 2, index + 2).any { neighbour ->
                neighbour in tokens.indices && anyDay(neighbour) != null &&
                    (minOf(index, neighbour) + 1 until maxOf(index, neighbour)).all { tokens[it] == RANGE }
            }
            return short.takeIf { neighbourIsDay }
        }

        val terms = mutableListOf<Term>()
        var negated = false
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token == CLAUSE_END -> negated = false
                token in negationWords -> negated = true
                token == "GIORNALIERO" || token == "QUOTIDIANO" ||
                    token == "TUTTI" && tokens.getOrNull(index + 1) == "I" && tokens.getOrNull(index + 2) == "GIORNI" ->
                    terms += Term(Daily, negated)
                token.startsWith("FEST") -> terms += Term(Holiday, negated)
                token == "FERIALE" || token == "FERIALI" -> terms += Term(Weekday, negated)
                (token == "DAL" || token == "DA") && dayAt(index + 1) != null &&
                    tokens.getOrNull(index + 2).let { it == "AL" || it == "A" } && dayAt(index + 3) != null -> {
                    terms += Term(Days(dayRange(dayAt(index + 1)!!, dayAt(index + 3)!!)), negated)
                    index += 3
                }
                dayAt(index) != null && tokens.getOrNull(index + 1) == RANGE && dayAt(index + 2) != null -> {
                    terms += Term(Days(dayRange(dayAt(index)!!, dayAt(index + 2)!!)), negated)
                    index += 2
                }
                dayAt(index) != null -> terms += Term(Days(setOf(dayAt(index)!!)), negated)
            }
            index++
        }
        return terms
    }

    private fun dayRange(from: DayOfWeek, to: DayOfWeek): Set<DayOfWeek> {
        val days = linkedSetOf(from)
        var day = from
        while (day != to) {
            day = day.plus(1)
            days += day
        }
        return days
    }

    /** Meeus/Jones/Butcher Gregorian computus, valid for every supported timetable year. */
    private fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }
}
