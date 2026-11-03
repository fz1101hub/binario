package it.binario.app.widget

import it.binario.app.data.Direction
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSettingsTest {
    private val zone = ZoneId.of("Europe/Rome")
    // 2026-11-02 is a Monday; 2026-11-06 a Friday, 2026-11-07 a Saturday.
    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 11): ZonedDateTime =
        ZonedDateTime.of(LocalDate.of(2026, month, day), LocalTime.of(hour, minute), zone)

    private val commuter = AutoRefreshSettings(
        mode = AutoRefreshMode.WINDOWS,
        interval = RefreshInterval.MINUTES_30,
        days = DayPreset.WEEKDAYS.days,
        windows = listOf(TimeWindow(LocalTime.of(7, 0), LocalTime.of(9, 30)), TimeWindow(LocalTime.of(17, 0), LocalTime.of(19, 30)))
    )

    @Test
    fun `windows are active only inside their hours on the chosen days`() {
        assertTrue(commuter.isActiveAt(at(2, 7, 0)))
        assertTrue(commuter.isActiveAt(at(2, 9, 29)))
        assertFalse("the end is excluded", commuter.isActiveAt(at(2, 9, 30)))
        assertFalse(commuter.isActiveAt(at(2, 12, 0)))
        assertTrue(commuter.isActiveAt(at(6, 18, 15)))
        assertFalse("Saturday is not a weekday", commuter.isActiveAt(at(7, 8, 0)))
    }

    @Test
    fun `a window crossing midnight belongs to the day it starts on`() {
        val night = AutoRefreshSettings(AutoRefreshMode.WINDOWS, days = setOf(DayOfWeek.FRIDAY), windows = listOf(TimeWindow(LocalTime.of(22, 0), LocalTime.of(1, 0))))

        assertTrue(night.isActiveAt(at(6, 23, 30)))
        assertTrue("Saturday 00:30 continues Friday's window", night.isActiveAt(at(7, 0, 30)))
        assertFalse(night.isActiveAt(at(7, 1, 0)))
        assertFalse("Friday 00:30 continues Thursday's window, not chosen", night.isActiveAt(at(6, 0, 30)))
        assertFalse(night.isActiveAt(at(7, 23, 0)))
        assertTrue(TimeWindow(LocalTime.of(22, 0), LocalTime.of(1, 0)).crossesMidnight)
    }

    @Test
    fun `off never runs and always always runs`() {
        assertFalse(AutoRefreshSettings().isActiveAt(at(2, 8)))
        assertNull(AutoRefreshSettings().nextDueAt(0L, at(2, 8)))
        assertTrue(AutoRefreshSettings.LEGACY.isActiveAt(at(7, 3)))
        assertEquals(RefreshInterval.MINUTES_15, AutoRefreshSettings.LEGACY.interval)
    }

    @Test
    fun `the next refresh waits for the interval and for the next window`() {
        val last = at(2, 7, 10).toInstant().toEpochMilli()

        assertEquals(at(2, 7, 40), commuter.nextDueAt(last, at(2, 7, 15)))
        assertFalse(commuter.isDue(last, at(2, 7, 39)))
        assertTrue(commuter.isDue(last, at(2, 7, 40)))
        // After the morning window, nothing until the evening window opens.
        assertEquals(at(2, 17, 0), commuter.nextDueAt(at(2, 9, 20).toInstant().toEpochMilli(), at(2, 9, 31)))
        // Friday evening to Monday morning: the weekend has no window.
        assertEquals(at(9, 7, 0), commuter.nextDueAt(at(6, 19, 20).toInstant().toEpochMilli(), at(6, 19, 45)))
        // Never refreshed: due at once inside a window.
        assertEquals(at(2, 8, 0), commuter.nextDueAt(0L, at(2, 8, 0)))
    }

    @Test
    fun `without any chosen day automatic refresh never runs`() {
        val none = commuter.copy(days = emptySet())

        assertFalse(none.isActiveAt(at(2, 8)))
        assertNull(none.nextDueAt(0L, at(2, 8)))
    }

    @Test
    fun `daylight saving days keep local windows and real elapsed time`() {
        // 2026-03-29: clocks go from 02:00 to 03:00 in Europe/Rome; 2026-10-25: from 03:00 back to 02:00.
        val sunday = AutoRefreshSettings(AutoRefreshMode.WINDOWS, RefreshInterval.MINUTES_60, DayPreset.EVERY_DAY.days, listOf(TimeWindow(LocalTime.of(1, 30), LocalTime.of(4, 0))))
        val springDay = LocalDate.of(2026, 3, 29)
        val beforeJump = ZonedDateTime.of(springDay, LocalTime.of(1, 45), zone)
        val afterJump = ZonedDateTime.of(springDay, LocalTime.of(3, 10), zone)

        assertTrue(sunday.isActiveAt(afterJump))
        // 01:45 to 03:10 local is only 25 real minutes: an hourly refresh is not due yet.
        assertEquals(Duration.ofMinutes(25), Duration.between(beforeJump, afterJump))
        assertFalse(sunday.isDue(beforeJump.toInstant().toEpochMilli(), afterJump))
        assertEquals(ZonedDateTime.of(springDay, LocalTime.of(3, 45), zone), sunday.nextDueAt(beforeJump.toInstant().toEpochMilli(), afterJump))

        // A window starting inside the skipped hour starts when the clocks have moved on.
        val gap = AutoRefreshSettings(AutoRefreshMode.WINDOWS, days = DayPreset.EVERY_DAY.days, windows = listOf(TimeWindow(LocalTime.of(2, 30), LocalTime.of(5, 0))))
        val next = gap.nextActiveFrom(ZonedDateTime.of(springDay, LocalTime.of(1, 0), zone))!!
        assertEquals(LocalTime.of(3, 30), next.toLocalTime())

        // On the autumn day the repeated hour is inside the window both times.
        val autumn = ZonedDateTime.of(LocalDate.of(2026, 10, 25), LocalTime.of(2, 30), zone)
        assertTrue(sunday.isActiveAt(autumn.withEarlierOffsetAtOverlap()))
        assertTrue(sunday.isActiveAt(autumn.withLaterOffsetAtOverlap()))
    }

    @Test
    fun `day presets recognise weekdays and every day`() {
        assertEquals(DayPreset.WEEKDAYS, DayPreset.of(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)))
        assertEquals(DayPreset.EVERY_DAY, DayPreset.of(DayOfWeek.entries.toSet()))
        assertEquals(DayPreset.CUSTOM, DayPreset.of(setOf(DayOfWeek.SATURDAY)))
        assertEquals(DayPreset.CUSTOM, DayPreset.of(emptySet()))
    }

    @Test
    fun `route settings survive the codec and unknown text falls back to new defaults`() {
        val settings = RouteWidgetSettings(
            autoRefresh = commuter,
            returnFrom = LocalTime.of(14, 0),
            excludedTypes = setOf(TrainTypeGroup.HIGH_SPEED, TrainTypeGroup.REPLACEMENT_BUS),
            untilArrival = true,
            showPlatform = false,
            showTrainName = false,
            timeRange = RouteTimeRange.WHOLE_DAY
        )

        assertEquals(settings, WidgetSettingsCodec.decodeRoute(WidgetSettingsCodec.encodeRoute(settings)))
        assertEquals(RouteWidgetSettings(), WidgetSettingsCodec.decodeRoute("garbage;ar=TELEPORT;ai=7;tr=FOREVER;sw=25:99"))
        assertEquals(RouteWidgetSettings.LEGACY, WidgetSettingsCodec.decodeRoute(WidgetSettingsCodec.encodeRoute(RouteWidgetSettings.LEGACY)))
    }

    @Test
    fun `station and trains settings survive the codec with their names`() {
        val station = StationWidgetSettings("Casa", commuter, setOf(TrainTypeGroup.ITALO), showPlatform = false, showTrainName = true, showNearestButton = true)
        val trains = TrainsWidgetSettings("Pendolare", TrainsWidgetInfo.LAST_DETECTION, hideNotRunning = true, sort = TrainsWidgetSort.NAME, autoRefresh = commuter, showPlatform = false)

        assertEquals(station, WidgetSettingsCodec.decodeStation(WidgetSettingsCodec.encodeStation(station), "Casa"))
        assertEquals(trains, WidgetSettingsCodec.decodeTrains(WidgetSettingsCodec.encodeTrains(trains), "Pendolare"))
        assertEquals(trains, TrainsWidgetConfigurationStore.decode(TrainsWidgetConfigurationStore.encode(trains)))
    }

    @Test
    fun `at most two windows are kept`() {
        val three = commuter.copy(windows = commuter.windows + TimeWindow(LocalTime.of(21, 0), LocalTime.of(22, 0)))

        assertEquals(2, WidgetSettingsCodec.decodeRoute(WidgetSettingsCodec.encodeRoute(RouteWidgetSettings(autoRefresh = three))).autoRefresh.windows.size)
        assertFalse(three.isActiveAt(at(2, 21, 30)))
    }
}

class WidgetDirectionScheduleTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val returnFrom = LocalTime.of(14, 0)
    private fun at(hour: Int, minute: Int = 0, day: Int = 2): ZonedDateTime =
        ZonedDateTime.of(LocalDate.of(2026, 11, day), LocalTime.of(hour, minute), zone)

    @Test
    fun `outbound before the chosen time and return from it`() {
        assertEquals(Direction.OUTBOUND, WidgetDirectionSchedule.automaticDirection(returnFrom, at(13, 59)))
        assertEquals(Direction.RETURN, WidgetDirectionSchedule.automaticDirection(returnFrom, at(14, 0)))
        assertEquals(Direction.RETURN, WidgetDirectionSchedule.automaticDirection(returnFrom, at(23, 59)))
        assertEquals(Direction.OUTBOUND, WidgetDirectionSchedule.automaticDirection(returnFrom, at(0, 0)))
    }

    @Test
    fun `the next boundary is the chosen time, then midnight`() {
        assertEquals(at(14, 0), WidgetDirectionSchedule.nextBoundary(returnFrom, at(9, 0)))
        assertEquals(at(0, 0, day = 3), WidgetDirectionSchedule.nextBoundary(returnFrom, at(14, 0)))
        assertEquals(at(0, 0, day = 3), WidgetDirectionSchedule.nextBoundary(returnFrom, at(18, 30)))
    }

    @Test
    fun `a manual switch holds until the next boundary`() {
        val until = WidgetDirectionSchedule.nextBoundary(returnFrom, at(9, 0)).toInstant().toEpochMilli()

        assertEquals(Direction.RETURN, WidgetDirectionSchedule.effectiveDirection(returnFrom, Direction.RETURN, Direction.RETURN, until, at(13, 59)))
        // At 14:00 the automatic swap applies again (it agrees here, and it would win otherwise).
        assertEquals(Direction.RETURN, WidgetDirectionSchedule.effectiveDirection(returnFrom, Direction.RETURN, Direction.OUTBOUND, until, at(14, 0)))
        val afternoonOverride = WidgetDirectionSchedule.nextBoundary(returnFrom, at(15, 0)).toInstant().toEpochMilli()
        assertEquals(Direction.OUTBOUND, WidgetDirectionSchedule.effectiveDirection(returnFrom, Direction.OUTBOUND, Direction.OUTBOUND, afternoonOverride, at(23, 0)))
        assertEquals(Direction.OUTBOUND, WidgetDirectionSchedule.effectiveDirection(returnFrom, Direction.OUTBOUND, Direction.OUTBOUND, afternoonOverride, at(0, 1, day = 3)))
        assertEquals(Direction.RETURN, WidgetDirectionSchedule.effectiveDirection(returnFrom, Direction.OUTBOUND, Direction.OUTBOUND, afternoonOverride, at(14, 0, day = 3)))
    }

    @Test
    fun `without an automatic swap the stored direction is kept`() {
        assertEquals(Direction.RETURN, WidgetDirectionSchedule.effectiveDirection(null, Direction.RETURN, null, 0L, at(9, 0)))
        assertEquals(Direction.OUTBOUND, WidgetDirectionSchedule.effectiveDirection(null, Direction.OUTBOUND, Direction.RETURN, Long.MAX_VALUE, at(18, 0)))
    }
}
