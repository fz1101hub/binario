package it.binario.app

import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerDateTimeTest {
    @Test
    fun `formats ISO date and time for the planner in Italian and English`() {
        assertEquals("martedì 18 agosto", PlannerDateTime.dateLabel("2026-08-18", Locale.ITALIAN, "Data da scegliere"))
        assertEquals("Tuesday 18 August", PlannerDateTime.dateLabel("2026-08-18", Locale.ENGLISH, "Choose a date"))
        assertEquals("07:05", PlannerDateTime.timeLabel("07:05", Locale.ITALIAN, "Ora da scegliere"))
        assertEquals("07:05", PlannerDateTime.timeLabel("07:05", Locale.ENGLISH, "Choose a time"))
    }

    @Test
    fun `falls back safely with localized planner labels`() {
        assertEquals("Data da scegliere", PlannerDateTime.dateLabel("domani", Locale.ITALIAN, "Data da scegliere"))
        assertEquals("Choose a date", PlannerDateTime.dateLabel("tomorrow", Locale.ENGLISH, "Choose a date"))
        assertEquals("Ora da scegliere", PlannerDateTime.timeLabel("adesso", Locale.ITALIAN, "Ora da scegliere"))
        assertEquals("Choose a time", PlannerDateTime.timeLabel("now", Locale.ENGLISH, "Choose a time"))
    }

    @Test
    fun `return must be strictly after outbound departure`() {
        val date = LocalDate.of(2026, 9, 10)
        val time = LocalTime.of(18, 0)

        assertFalse(PlannerDateTime.isReturnAfterDeparture(date, time, date, time))
        assertFalse(PlannerDateTime.isReturnAfterDeparture(date, time, date, LocalTime.of(17, 59)))
        assertTrue(PlannerDateTime.isReturnAfterDeparture(date, time, date, LocalTime.of(18, 1)))
        assertTrue(PlannerDateTime.isReturnAfterDeparture(date, time, date.plusDays(1), LocalTime.of(8, 0)))
    }
}
