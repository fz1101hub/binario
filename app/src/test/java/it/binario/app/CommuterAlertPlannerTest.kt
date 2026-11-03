package it.binario.app

import org.junit.Assert.assertEquals
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
        assertTrue(CommuterAlertScheduler.acceptsRestoreAction("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"))
        assertFalse(CommuterAlertScheduler.acceptsRestoreAction(null))
        assertFalse(CommuterAlertScheduler.acceptsRestoreAction("it.binario.app.SPOOFED_RESTORE"))
    }

    private val rome = ZoneId.of("Europe/Rome")
    private fun romeMillis(day: Int, hour: Int, minute: Int) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(rome).toInstant().toEpochMilli()

    @Test
    fun `an app start before a late inexact alarm keeps today's trigger instead of moving it to tomorrow`() {
        // Audit A4-02 probe: the alert for the 07:30 train rings at 07:15; the app is opened at 07:20, before
        // Android delivered the inexact alarm.
        val created = romeMillis(2, 21, 0)
        val openedAt = romeMillis(3, 7, 20)
        listOf(AlertRepeat.WEEKDAYS, AlertRepeat.ONCE).forEach { repeat ->
            val alert = CommuterAlert("id-$repeat", "Bergamo → Milano", "Bergamo", "07:30", 15, repeat, false, true)
            val scheduled = CommuterAlertPlanner.nextTrigger(alert, created, rome)!!
            assertEquals(romeMillis(3, 7, 15), scheduled)

            // Recomputing from now (the earlier behaviour) skipped to tomorrow...
            assertEquals(romeMillis(4, 7, 15), CommuterAlertPlanner.nextTrigger(alert, openedAt, rome))
            // ...the restore keeps the trigger that was scheduled, which Android then delivers at once.
            assertEquals(CommuterAlertRestore.Arm(romeMillis(3, 7, 15)), CommuterAlertPlanner.restore(alert, scheduled, openedAt))
        }
    }

    @Test
    fun `a restore keeps a pending trigger, drops a missed once reminder and moves a missed recurring one`() {
        val weekdays = CommuterAlert("w", "Casa – lavoro", "Bergamo", "07:30", 15, AlertRepeat.WEEKDAYS, false)
        val once = weekdays.copy(id = "o", repeat = AlertRepeat.ONCE)
        val trigger = romeMillis(3, 7, 15)

        assertEquals(CommuterAlertRestore.Arm(trigger), CommuterAlertPlanner.restore(weekdays, trigger, romeMillis(3, 6, 0)))
        assertEquals(CommuterAlertRestore.Arm(trigger), CommuterAlertPlanner.restore(once, trigger, trigger + CommuterAlertPlanner.LATE_DELIVERY_MILLIS))
        // The phone was off past the trigger: a "once" reminder is not moved to another day.
        assertEquals(CommuterAlertRestore.Expire, CommuterAlertPlanner.restore(once, trigger, romeMillis(3, 9, 0)))
        assertEquals(CommuterAlertRestore.Reschedule, CommuterAlertPlanner.restore(weekdays, trigger, romeMillis(3, 9, 0)))
        assertEquals(romeMillis(4, 7, 15), CommuterAlertPlanner.nextTrigger(weekdays, romeMillis(3, 9, 0), rome))
        // Nothing recorded (alerts saved before this version) or disabled: planned from now as before.
        assertEquals(CommuterAlertRestore.Reschedule, CommuterAlertPlanner.restore(weekdays, null, romeMillis(3, 6, 0)))
        assertEquals(CommuterAlertRestore.Reschedule, CommuterAlertPlanner.restore(weekdays.copy(enabled = false), trigger, romeMillis(3, 6, 0)))
    }

    @Test
    fun `an alarm set again after its delivery does not notify twice`() {
        val trigger = romeMillis(3, 7, 15)

        assertTrue(CommuterAlertPlanner.isScheduledDelivery(trigger, trigger))
        // Delivered already: the next trigger is recorded, or none for a "once" reminder.
        assertFalse(CommuterAlertPlanner.isScheduledDelivery(romeMillis(4, 7, 15), trigger))
        assertFalse(CommuterAlertPlanner.isScheduledDelivery(null, trigger))
        // Alarms set by earlier versions carry no trigger.
        assertTrue(CommuterAlertPlanner.isScheduledDelivery(null, 0L))
    }

    @Test
    fun `an imported backup cancels the reminders it no longer contains`() {
        val first = CommuterAlert("a", "Casa – lavoro", "Bergamo", "07:30", 15, AlertRepeat.WEEKDAYS, false)
        val second = first.copy(id = "b")
        val imported = first.copy(id = "c")

        assertEquals(listOf("b"), CommuterAlertPlanner.removedBy(listOf(first, second), listOf(first, imported)))
        assertTrue(CommuterAlertPlanner.removedBy(listOf(first), listOf(first)).isEmpty())
    }
}
