package it.binario.app.data.planned

import java.time.DayOfWeek
import java.time.LocalDate
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

    /** Shared RFI periodicity matcher. Explicit service-day tokens never fall through to the unknown-text default. */
    fun runsOnDate(periodicity: String, date: LocalDate): Boolean {
        val value = periodicity.uppercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        if (value.isBlank()) return true
        if (listOf("GIORNALIERO", "TUTTI I GIORNI", "QUOTIDIANO").any(value::contains)) return true
        val day = date.dayOfWeek
        if ("LUN-VEN" in value || "LUN VEN" in value) {
            return day.value in DayOfWeek.MONDAY.value..DayOfWeek.FRIDAY.value
        }
        if ("FERIALE" in value) return day != DayOfWeek.SUNDAY && !isHoliday(date)
        val tokens = mapOf(
            DayOfWeek.MONDAY to listOf("LUN", "LU"),
            DayOfWeek.TUESDAY to listOf("MAR", "MA"),
            DayOfWeek.WEDNESDAY to listOf("MER", "ME"),
            DayOfWeek.THURSDAY to listOf("GIO", "GI"),
            DayOfWeek.FRIDAY to listOf("VEN", "VE"),
            DayOfWeek.SATURDAY to listOf("SAB", "SABATO", "SA"),
            DayOfWeek.SUNDAY to listOf("DOM", "DOMENICA", "DO")
        )
        fun hasToken(token: String) = Regex("\\b$token\\b").containsMatchIn(value)

        val hasAnyDayToken = tokens.values.flatten().any(::hasToken)
        val hasHolidayToken = Regex("\\bFEST(?:IVO|IVI)?\\b").containsMatchIn(value)
        if (!hasAnyDayToken && !hasHolidayToken) return true

        val matchesDay = tokens.getValue(day).any(::hasToken)
        val matchesHoliday = hasHolidayToken && (day == DayOfWeek.SUNDAY || isHoliday(date))
        return matchesDay || matchesHoliday
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
