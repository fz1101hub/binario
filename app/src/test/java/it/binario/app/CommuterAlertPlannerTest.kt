package it.binario.app

import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
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

    @Test
    fun `implicit alert scheduling uses the Italian railway zone`() {
        val now = LocalDateTime.of(2026, 1, 1, 1, 0).atZone(ZoneId.of("Europe/Rome")).toInstant().toEpochMilli()
        val alert = CommuterAlert("id", "Casa – lavoro", "Roma Termini", "01:30", 5, AlertRepeat.ONCE, false)

        assertTrue(
            CommuterAlertPlanner.nextTrigger(alert, now) ==
                LocalDateTime.of(2026, 1, 1, 1, 25).atZone(ZoneId.of("Europe/Rome")).toInstant().toEpochMilli()
        )
    }

    @Test
    fun `restore receiver accepts only system restore actions`() {
        assertTrue(CommuterAlertScheduler.acceptsRestoreAction("android.intent.action.BOOT_COMPLETED"))
        assertTrue(CommuterAlertScheduler.acceptsRestoreAction("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse(CommuterAlertScheduler.acceptsRestoreAction(null))
        assertFalse(CommuterAlertScheduler.acceptsRestoreAction("it.binario.app.SPOOFED_RESTORE"))
    }
}
