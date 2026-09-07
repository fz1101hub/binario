package it.binario.app

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerDateTimeTest {
    @Test
    fun `formats ISO date and time for the planner`() {
        assertEquals("martedì 18 agosto", PlannerDateTime.dateLabel("2026-08-18"))
        assertEquals("07:05", PlannerDateTime.timeLabel("07:05"))
    }

    @Test
    fun `falls back safely for invalid planner values`() {
        assertEquals("Data da scegliere", PlannerDateTime.dateLabel("domani"))
        assertEquals("Ora da scegliere", PlannerDateTime.timeLabel("adesso"))
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
