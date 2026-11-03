package it.binario.app.notifications

import it.binario.app.data.live.StrikeNotice
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrikeReminderPlannerTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val today = LocalDate.of(2026, 11, 2)
    private fun at(value: String) = LocalDateTime.parse(value).atZone(zone)

    private fun strike(id: String, start: LocalDate?, end: LocalDate? = start) =
        StrikeNotice(id, "Sciopero $id", null, null, null, start, end)

    @Test fun `the reminder runs every day at 18`() {
        assertEquals(at("2026-11-02T18:00"), StrikeReminderPlanner.nextRunAt(at("2026-11-02T09:30")))
        assertEquals(at("2026-11-03T18:00"), StrikeReminderPlanner.nextRunAt(at("2026-11-02T18:00")))
        assertEquals(at("2026-11-03T18:00"), StrikeReminderPlanner.nextRunAt(at("2026-11-02T21:00")))
        // Across the change to summer time the run stays at 18:00 local time.
        assertEquals(at("2027-03-28T18:00"), StrikeReminderPlanner.nextRunAt(at("2027-03-27T19:00")))
    }

    @Test fun `a run delayed into the quiet hours notifies nothing`() {
        assertTrue(StrikeReminderPlanner.mayNotify(at("2026-11-02T18:00")))
        assertTrue(StrikeReminderPlanner.mayNotify(at("2026-11-02T21:59")))
        assertFalse(StrikeReminderPlanner.mayNotify(at("2026-11-02T22:00")))
        assertFalse(StrikeReminderPlanner.mayNotify(at("2026-11-02T17:59")))
    }

    @Test fun `only rail strikes starting tomorrow are notified, once`() {
        val strikes = listOf(
            strike("tomorrow", today.plusDays(1), today.plusDays(2)),
            strike("today", today, today.plusDays(1)),
            strike("next-week", today.plusDays(7)),
            strike("undated", null)
        )

        val first = StrikeReminderPlanner.toNotify(strikes, today, emptyMap())
        val notified = StrikeReminderPlanner.remember(emptyMap(), first, today)

        assertEquals(listOf("tomorrow"), first.map { it.id })
        assertTrue(StrikeReminderPlanner.toNotify(strikes, today, notified).isEmpty())
        // An ongoing strike is not "tomorrow" the day after.
        assertTrue(StrikeReminderPlanner.toNotify(strikes, today.plusDays(1), notified).isEmpty())
        // Old entries are forgotten after three days.
        assertEquals(emptyMap<String, Long>(), StrikeReminderPlanner.remember(notified, emptyList(), today.plusDays(3)))
    }
}
