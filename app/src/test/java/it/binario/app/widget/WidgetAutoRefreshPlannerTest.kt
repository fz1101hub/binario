package it.binario.app.widget

import it.binario.app.data.Direction
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetAutoRefreshPlannerTest {
    private val zone = ZoneId.of("Europe/Rome")
    private fun at(hour: Int, minute: Int = 0, day: Int = 2): ZonedDateTime =
        ZonedDateTime.of(LocalDate.of(2026, 11, day), LocalTime.of(hour, minute), zone)
    private fun millis(hour: Int, minute: Int = 0) = at(hour, minute).toInstant().toEpochMilli()

    private val morning = AutoRefreshSettings(AutoRefreshMode.WINDOWS, RefreshInterval.MINUTES_15, DayPreset.WEEKDAYS.days, listOf(TimeWindow(LocalTime.of(7, 0), LocalTime.of(9, 30))))
    private val evening = morning.copy(interval = RefreshInterval.MINUTES_60, windows = listOf(TimeWindow(LocalTime.of(17, 0), LocalTime.of(19, 0))))

    @Test
    fun `only widgets inside their window and past their interval are refreshed`() {
        val candidates = listOf(
            AutoRefreshCandidate(WidgetKind.ROUTE, 1, morning, millis(7, 40)),
            AutoRefreshCandidate(WidgetKind.STATION, 2, morning, millis(7, 50)),
            AutoRefreshCandidate(WidgetKind.TRAINS, 3, evening, 0L),
            AutoRefreshCandidate(WidgetKind.ROUTE, 4, AutoRefreshSettings(), 0L),
            AutoRefreshCandidate(WidgetKind.STATION, 5, AutoRefreshSettings.LEGACY, millis(7, 30))
        )

        val due = WidgetAutoRefreshPlanner.due(candidates, at(7, 55))

        assertEquals(listOf(1 to WidgetKind.ROUTE, 5 to WidgetKind.STATION), due.map { it.widgetId to it.kind })
    }

    @Test
    fun `never more often than fifteen minutes`() {
        val just = AutoRefreshCandidate(WidgetKind.ROUTE, 1, AutoRefreshSettings.LEGACY, millis(8, 0))

        assertTrue(WidgetAutoRefreshPlanner.due(listOf(just), at(8, 14)).isEmpty())
        assertEquals(1, WidgetAutoRefreshPlanner.due(listOf(just), at(8, 15)).size)
        assertEquals(at(8, 15), WidgetAutoRefreshPlanner.nextRun(listOf(just), at(8, 1)))
    }

    @Test
    fun `the next run is the earliest due widget and nothing is planned outside the windows`() {
        val candidates = listOf(
            AutoRefreshCandidate(WidgetKind.ROUTE, 1, morning, millis(9, 25)),
            AutoRefreshCandidate(WidgetKind.TRAINS, 2, evening, millis(9, 0))
        )

        // After the morning window closes the next run is the evening window, not every fifteen minutes.
        assertEquals(at(17, 0), WidgetAutoRefreshPlanner.nextRun(candidates, at(9, 31)))
        assertEquals(Duration.ofMinutes(449), WidgetAutoRefreshPlanner.delayUntil(at(17, 0), at(9, 31)))
        assertEquals(at(7, 0, day = 3), WidgetAutoRefreshPlanner.nextRun(listOf(candidates.first()), at(19, 0)))
    }

    @Test
    fun `without automatic refresh or direction swap no work is scheduled`() {
        val off = listOf(AutoRefreshCandidate(WidgetKind.ROUTE, 1, AutoRefreshSettings(), 0L), AutoRefreshCandidate(WidgetKind.TRAINS, 2, morning.copy(days = emptySet()), 0L))

        assertNull(WidgetAutoRefreshPlanner.nextRun(off, at(8, 0)))
        assertNull(WidgetAutoRefreshPlanner.nextRun(emptyList(), at(8, 0)))
    }

    @Test
    fun `a direction swap is planned at its boundary and loads the return once`() {
        val swap = AutoRefreshCandidate(WidgetKind.ROUTE, 1, AutoRefreshSettings(), millis(8, 0), nextDirectionBoundary = at(14, 0))

        assertEquals(at(14, 0), WidgetAutoRefreshPlanner.nextRun(listOf(swap), at(9, 0)))
        val toReturn = WidgetAutoRefreshPlanner.plan(listOf(swap.copy(pendingDirection = Direction.RETURN)), at(14, 0)).single()
        assertEquals(Direction.RETURN, toReturn.applyDirection)
        assertTrue(toReturn.fetch)
        // Back to outbound at midnight: only the label changes while automatic refresh is off.
        val toOutbound = WidgetAutoRefreshPlanner.plan(listOf(swap.copy(pendingDirection = Direction.OUTBOUND)), at(0, 0, day = 3)).single()
        assertEquals(Direction.OUTBOUND, toOutbound.applyDirection)
        assertEquals(false, toOutbound.fetch)
        // A pending direction is applied at once.
        assertEquals(at(9, 0), WidgetAutoRefreshPlanner.nextRun(listOf(swap.copy(pendingDirection = Direction.RETURN)), at(9, 0)))
    }

    @Test
    fun `a run never reschedules itself sooner than a minute`() {
        assertEquals(Duration.ofMinutes(1), WidgetAutoRefreshPlanner.delayUntil(at(8, 0), at(8, 0), WidgetAutoRefreshPlanner.MIN_RESCHEDULE))
        assertEquals(Duration.ZERO, WidgetAutoRefreshPlanner.delayUntil(at(7, 0), at(8, 0)))
    }

    @Test
    fun `a manual refresh retries only temporary failures, at most twice and only inside the widget's windows`() {
        val off = AutoRefreshSettings()
        val failed = mapOf(1 to morning, 2 to off, 3 to AutoRefreshSettings.LEGACY)

        val first = WidgetRefreshRetryPolicy.retry(failed, attempt = 1, now = at(7, 0))!!
        assertEquals(setOf(1, 3), first.widgetIds)
        assertEquals(Duration.ofMinutes(15), first.delay)
        val second = WidgetRefreshRetryPolicy.retry(failed, attempt = 2, now = at(7, 15))!!
        assertEquals(Duration.ofMinutes(30), second.delay)
        assertNull(WidgetRefreshRetryPolicy.retry(failed, attempt = WidgetRefreshRetryPolicy.MAX_ATTEMPTS, now = at(7, 45)))
        // The retry at 9:40 falls after the 7:00 - 9:30 window: only the widget refreshed always is asked again.
        assertEquals(setOf(3), WidgetRefreshRetryPolicy.retry(failed, attempt = 1, now = at(9, 25))!!.widgetIds)
        // Automatic refresh off: a failed tap is never retried in the background.
        assertNull(WidgetRefreshRetryPolicy.retry(mapOf(2 to off), attempt = 1, now = at(8, 0)))

        assertEquals(setOf(1, 3), WidgetRefreshRetryPolicy.decodeIds(WidgetRefreshRetryPolicy.encodeIds(first.widgetIds)))
        assertNull(WidgetRefreshRetryPolicy.decodeIds(null))
        assertTrue(WidgetRefreshRetryPolicy.isTransient(it.binario.app.data.RouteFailureReason.NetworkUnavailable))
        assertTrue(WidgetRefreshRetryPolicy.isTransient(it.binario.app.data.RouteFailureReason.Unavailable))
        assertTrue(!WidgetRefreshRetryPolicy.isTransient(it.binario.app.data.RouteFailureReason.RouteNotCovered))
        assertTrue(!WidgetRefreshRetryPolicy.isTransient(it.binario.app.data.RouteFailureReason.ScheduleExpired(LocalDate.of(2026, 12, 12))))
    }
}
