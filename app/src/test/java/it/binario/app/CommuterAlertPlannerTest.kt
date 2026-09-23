package it.binario.app

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

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

    @Test
    fun `quiet hours return an explicit unscheduled outcome`() {
        val zone = ZoneId.of("Europe/Rome")
        val now = LocalDateTime.of(2024, 1, 2, 20, 0).atZone(zone).toInstant().toEpochMilli()
        val alert = CommuterAlert("id", "Casa – lavoro", "Roma Termini", "22:20", 15, AlertRepeat.ONCE, true)

        assertTrue(CommuterAlertPlanner.nextTriggerResult(alert, now, zone) is CommuterAlertTriggerResult.QuietHours)
    }
}
