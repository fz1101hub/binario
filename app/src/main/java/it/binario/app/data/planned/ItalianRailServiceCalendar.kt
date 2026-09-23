package it.binario.app.data.planned

import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters

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
