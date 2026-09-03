package it.binario.app

import org.junit.Assert.assertEquals
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
}
