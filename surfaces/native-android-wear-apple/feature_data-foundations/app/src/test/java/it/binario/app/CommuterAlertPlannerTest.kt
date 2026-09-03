package it.binario.app

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class CommuterAlertPlannerTest {
    @Test
    fun `quiet hours cover late evening and early morning`() {
        assertTrue(CommuterAlertPlanner.isQuiet(LocalTime.of(22, 0)))
        assertTrue(CommuterAlertPlanner.isQuiet(LocalTime.of(6, 59)))
        assertTrue(!CommuterAlertPlanner.isQuiet(LocalTime.of(7, 0)))
    }

    @Test
    fun `disabled alert has no next trigger`() {
        val alert = CommuterAlert("id", "Casa – lavoro", "Roma Termini", "08:30", 15, AlertRepeat.DAILY, false, enabled = false)
        assertNull(CommuterAlertPlanner.nextTrigger(alert))
    }

    @Test
    fun `weekday repeat only includes working days`() {
        assertTrue(DayOfWeek.MONDAY in AlertRepeat.WEEKDAYS.days)
        assertTrue(DayOfWeek.SUNDAY !in AlertRepeat.WEEKDAYS.days)
    }
}
