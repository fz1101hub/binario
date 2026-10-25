package it.binario.app.data.planned

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ItalianRailServiceCalendarTest {
    // 2026-10-05 Mon .. 2026-10-11 Sun; 2026-06-02 (Tue) and 2026-04-06 (Easter Monday) are holidays.
    private val monday = LocalDate.of(2026, 10, 5)
    private val tuesday = LocalDate.of(2026, 10, 6)
    private val wednesday = LocalDate.of(2026, 10, 7)
    private val friday = LocalDate.of(2026, 10, 9)
    private val saturday = LocalDate.of(2026, 10, 10)
    private val sunday = LocalDate.of(2026, 10, 11)
    private val holidayTuesday = LocalDate.of(2026, 6, 2)
    private val easterMonday = LocalDate.of(2026, 4, 6)

    private fun week(periodicity: String) =
        listOf(monday, tuesday, wednesday, friday, saturday, sunday, holidayTuesday, easterMonday)
            .map { ItalianRailServiceCalendar.runsOnDate(periodicity, it) }

    private val all = List(8) { true }

    @Test
    fun `unknown text, warnings and daily forms stay fail open`() {
        listOf("", "Giornaliero", "Tutti i giorni", "Quotidiano", "Non ferma a Pioltello", "Prenotazione obbligatoria")
            .forEach { assertEquals(it, all, week(it)) }
    }

    @Test
    fun `existing single-day and combined forms keep their meaning`() {
        //                       Mon    Tue    Wed    Fri    Sat    Sun    HolTue EastMon
        assertEquals(listOf(true, true, true, true, false, false, true, true), week("Lun-Ven"))
        assertEquals(listOf(true, true, true, true, true, false, false, false), week("Feriale"))
        assertEquals(listOf(false, false, false, false, false, true, true, true), week("Festivo"))
        assertEquals(listOf(false, false, false, false, true, true, true, true), week("SAB E FEST"))
        assertEquals(listOf(false, false, false, false, true, true, true, true), week("SABATO E FESTIVI"))
        assertEquals(listOf(false, false, false, false, false, true, false, false), week("DOM"))
        assertEquals(listOf(false, false, false, false, false, true, false, false), week("Domenica"))
    }

    @Test
    fun `every festiv form means sundays and holidays`() {
        listOf("festive", "Festiva", "festività", "FESTIVI", "Fest.").forEach {
            assertEquals(it, listOf(false, false, false, false, false, true, true, true), week(it))
        }
    }

    @Test
    fun `4 October is a national holiday from 2026`() {
        // Monday 2027-10-04: "festivo" trains run, "feriale" ones do not; before 2026 it was an ordinary day.
        val monday2027 = LocalDate.of(2027, 10, 4)
        assertEquals(true, ItalianRailServiceCalendar.isHoliday(monday2027))
        assertEquals(true, ItalianRailServiceCalendar.runsOnDate("Festivo", monday2027))
        assertEquals(false, ItalianRailServiceCalendar.runsOnDate("Feriale", monday2027))
        assertEquals(true, ItalianRailServiceCalendar.isHoliday(LocalDate.of(2026, 10, 4)))
        assertEquals(false, ItalianRailServiceCalendar.isHoliday(LocalDate.of(2025, 10, 4)))
        assertEquals(false, ItalianRailServiceCalendar.runsOnDate("Festivo", LocalDate.of(2025, 10, 4)))
    }

    @Test
    fun `written ranges cover every day in between`() {
        assertEquals(listOf(true, true, true, true, true, false, true, true), week("Dal lunedì al sabato"))
        assertEquals(listOf(true, true, true, true, true, false, true, true), week("Lun-Sab"))
        assertEquals(listOf(true, true, true, true, false, false, true, true), week("lu-ve"))
        assertEquals(listOf(false, false, true, true, true, false, false, false), week("Da mercoledì a sabato"))
    }

    @Test
    fun `negations exclude days instead of selecting them`() {
        assertEquals(listOf(true, true, true, true, false, true, true, true), week("Non si effettua il sabato"))
        assertEquals(listOf(true, true, true, true, false, false, false, false), week("Escluso sabato e festivi"))
        assertEquals(listOf(true, true, true, true, true, false, true, true), week("Tutti i giorni escluso domenica"))
        assertEquals(listOf(true, true, true, true, false, false, false, false), week("Feriale escluso sabato"))
        assertEquals(listOf(true, true, true, true, false, false, true, true), week("Lun-Sab; non si effettua il sabato"))
    }

    @Test
    fun `two-letter abbreviations count only inside a day list`() {
        // "ma" is the Italian conjunction here, not Tuesday.
        assertEquals(listOf(true, true, true, true, true, false, false, false), week("Feriale ma non festivo"))
        // Day tokens ignore holidays: Easter Monday is still a Monday.
        assertEquals(listOf(true, true, true, false, false, false, true, true), week("LU MA ME"))
    }

    @Test
    fun `RFI rows earlier than the requested time are the next day's trains`() {
        val thursday = LocalDate.of(2026, 10, 8)
        val requested = 23 * 60 + 30
        assertEquals(thursday, ItalianRailServiceCalendar.rfiDepartureDate(23 * 60 + 45, thursday, requested))
        assertEquals(thursday.plusDays(1), ItalianRailServiceCalendar.rfiDepartureDate(5, thursday, requested))
        // After the day's last train the next morning's departures are the answer, as in the GTFS packs.
        assertEquals(thursday.plusDays(1), ItalianRailServiceCalendar.rfiDepartureDate(8 * 60, thursday, requested))
        assertEquals(thursday.plusDays(1), ItalianRailServiceCalendar.rfiDepartureDate(5, thursday, 8 * 60))
        assertEquals(thursday, ItalianRailServiceCalendar.rfiDepartureDate(8 * 60, thursday, 8 * 60))
        assertEquals(null, ItalianRailServiceCalendar.rfiDepartureDate(25 * 60, thursday, requested))
        assertEquals(thursday, ItalianRailServiceCalendar.rfiDepartureDate(Int.MAX_VALUE, thursday, requested))
    }
}
