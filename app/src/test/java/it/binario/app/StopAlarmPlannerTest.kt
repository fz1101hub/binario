package it.binario.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class StopAlarmPlannerTest {
    private val zone = ZoneId.of("Europe/Rome")

    @Test
    fun `alarm is planned five minutes before a future stop`() {
        val now = 1_720_000_000_000L
        val plan = StopAlarmPlanner.plan("4132", "Torino", "Firenze", "23:59", now, zone)

        assertTrue(plan != null)
        assertEquals("Firenze", plan?.station)
        assertTrue(plan!!.triggerAtEpochMillis > now)
    }

    @Test
    fun `past or invalid times are not scheduled`() {
        val now = System.currentTimeMillis()
        assertNull(StopAlarmPlanner.plan("4132", "Torino", "Firenze", "ora non valida", now, zone))
    }

    @Test
    fun `early morning stop after late evening is planned on next service day`() {
        val now = LocalDateTime.of(2024, 1, 1, 23, 40).atZone(zone).toInstant().toEpochMilli()

        val plan = StopAlarmPlanner.plan("4132", "Torino", "Firenze", "00:10", now, zone)

        assertTrue(plan != null)
        assertEquals(
            LocalDateTime.of(2024, 1, 2, 0, 5).atZone(zone).toInstant().toEpochMilli(),
            plan?.triggerAtEpochMillis
        )
    }

    @Test
    fun `implicit stop alarm uses the Italian railway zone`() {
        val now = LocalDateTime.of(2026, 1, 1, 1, 0).atZone(zone).toInstant().toEpochMilli()

        val plan = StopAlarmPlanner.plan("4132", "Torino", "Firenze", "01:30", now)

        assertEquals(
            LocalDateTime.of(2026, 1, 1, 1, 25).atZone(zone).toInstant().toEpochMilli(),
            plan?.triggerAtEpochMillis
        )
    }

    @Test
    fun `custom lead schedules at the requested number of minutes`() {
        val now = LocalDateTime.of(2026, 10, 26, 8, 0).atZone(zone).toInstant().toEpochMilli()
        val plan = StopAlarmPlanner.plan("123", "Como", "Como", "09:00", now, zone, leadMinutes = 20)
        assertEquals(LocalDateTime.of(2026, 10, 26, 8, 40).atZone(zone).toInstant().toEpochMilli(), plan?.triggerAtEpochMillis)
    }

    @Test
    fun `unsupported lead values are rejected by both planner entry points`() {
        val now = LocalDateTime.of(2026, 10, 26, 8, 0).atZone(zone).toInstant().toEpochMilli()
        val train = service(LocalDateTime.of(2026, 10, 26, 8, 20), listOf(stop("Milano", "08:20"), stop("Como", "09:00")))
        for (minutes in listOf(-1, 0, 61, Int.MAX_VALUE)) {
            assertNull(StopAlarmPlanner.plan("123", "Como", "Como", "09:00", now, zone, minutes))
            assertNull(StopAlarmPlanner.plan(train, train.stops.last(), now, zone, minutes))
        }
    }

    @Test
    fun `elapsed advance requests an immediate future reminder instead of a past alarm`() {
        val now = LocalDateTime.of(2026, 10, 26, 8, 0).atZone(zone).toInstant().toEpochMilli()
        val plan = StopAlarmPlanner.plan("123", "Como", "Como", "08:02", now, zone, leadMinutes = 30)
        assertEquals(now + 1_000L, plan?.triggerAtEpochMillis)
    }

    @Test
    fun `a custom lead crossing midnight retains the service date`() {
        val now = LocalDateTime.of(2026, 10, 26, 22, 0).atZone(zone).toInstant().toEpochMilli()
        val train = service(LocalDateTime.of(2026, 10, 26, 23, 40), listOf(stop("Milano", "23:40"), stop("Como", "00:25")))
        val plan = StopAlarmPlanner.plan(train, train.stops.last(), now, zone, leadMinutes = 30)
        assertEquals(LocalDateTime.of(2026, 10, 26, 23, 55).atZone(zone).toInstant().toEpochMilli(), plan?.triggerAtEpochMillis)
    }

    @Test
    fun `changing advance or prediction keeps the same alarm identity`() {
        val now = LocalDateTime.of(2026, 10, 26, 8, 0).atZone(zone).toInstant().toEpochMilli()
        val train = service(LocalDateTime.of(2026, 10, 26, 8, 20), listOf(stop("Milano", "08:20"), stop("Como", "09:00")))
        val original = StopAlarmPlanner.plan(train, train.stops.last(), now, zone, leadMinutes = 5)
        val changed = StopAlarmPlanner.plan(train, train.stops.last().copy(expected = "09:10"), now, zone, leadMinutes = 20)
        assertTrue(original != null && changed != null)
        assertEquals(original?.alarmId, changed?.alarmId)
        assertTrue(original?.triggerAtEpochMillis != changed?.triggerAtEpochMillis)
    }

    @Test
    fun `different service dates cannot replace one another`() {
        val now = LocalDateTime.of(2026, 10, 26, 8, 0).atZone(zone).toInstant().toEpochMilli()
        val train = service(LocalDateTime.of(2026, 10, 27, 8, 20), listOf(stop("Milano", "08:20"), stop("Como", "09:00")))
        val nextDay = train.copy(scheduledDepartureDateTime = LocalDateTime.of(2026, 10, 28, 8, 20))
        val first = StopAlarmPlanner.plan(train, train.stops.last(), now, zone)
        val second = StopAlarmPlanner.plan(nextDay, nextDay.stops.last(), now, zone)
        assertTrue(first != null && second != null)
        assertTrue(first?.alarmId != second?.alarmId)
    }

    @Test
    fun `completed stops do not schedule a reminder`() {
        val now = LocalDateTime.of(2026, 10, 26, 8, 0).atZone(zone).toInstant().toEpochMilli()
        val train = service(LocalDateTime.of(2026, 10, 26, 8, 20), listOf(stop("Milano", "08:20"), stop("Como", "09:00")))
        assertNull(StopAlarmPlanner.plan(train, train.stops.last().copy(completed = true), now, zone))
    }

    @Test
    fun `an imminent reminder is never deliberately scheduled after the stop`() {
        val expected = LocalDateTime.of(2026, 10, 26, 8, 1).atZone(zone).toInstant().toEpochMilli()
        val plan = StopAlarmPlanner.plan("123", "Como", "Como", "08:01", expected - 500L, zone)
        assertEquals(expected, plan?.triggerAtEpochMillis)
    }

    private fun service(departure: LocalDateTime, stops: List<it.binario.app.data.TrainStop>) = it.binario.app.data.TrainService(
        id = "t",
        number = "2581",
        operator = it.binario.app.data.RailOperator.TRENITALIA_REGIONALE,
        trainCategory = it.binario.app.data.TrainCategory.REGIONALE,
        origin = stops.first().station,
        destination = stops.last().station,
        scheduledDeparture = stops.first().scheduled,
        expectedDeparture = stops.first().expected,
        platform = null,
        delayMinutes = 0,
        status = it.binario.app.data.ServiceStatus.ON_TIME,
        stops = stops,
        scheduledDepartureDateTime = departure
    )

    private fun stop(name: String, time: String) = it.binario.app.data.TrainStop(name, time, time, null, 0, completed = false)

    @Test
    fun `a train planned for another day rings on that day, not today`() {
        val now = LocalDateTime.of(2026, 10, 6, 7, 0).atZone(zone).toInstant().toEpochMilli()
        val tomorrow = service(LocalDateTime.of(2026, 10, 7, 8, 5), listOf(stop("Milano Centrale", "08:05"), stop("Como S. Giovanni", "08:50")))

        val plan = StopAlarmPlanner.plan(tomorrow, tomorrow.stops.last(), now, zone)

        assertEquals(
            LocalDateTime.of(2026, 10, 7, 8, 45).atZone(zone).toInstant().toEpochMilli(),
            plan?.triggerAtEpochMillis
        )
    }

    @Test
    fun `a planned train whose stop already passed today is still accepted for its own date`() {
        val now = LocalDateTime.of(2026, 10, 6, 20, 0).atZone(zone).toInstant().toEpochMilli()
        val tomorrow = service(LocalDateTime.of(2026, 10, 7, 8, 5), listOf(stop("Milano Centrale", "08:05"), stop("Como S. Giovanni", "08:50")))

        assertTrue(StopAlarmPlanner.plan(tomorrow, tomorrow.stops.last(), now, zone) != null)
    }

    @Test
    fun `stops after midnight roll to the next date of the service`() {
        val now = LocalDateTime.of(2026, 10, 6, 22, 0).atZone(zone).toInstant().toEpochMilli()
        val night = service(LocalDateTime.of(2026, 10, 6, 23, 40), listOf(stop("Milano Centrale", "23:40"), stop("Como S. Giovanni", "00:25")))

        assertEquals(
            LocalDateTime.of(2026, 10, 7, 0, 20).atZone(zone).toInstant().toEpochMilli(),
            StopAlarmPlanner.plan(night, night.stops.last(), now, zone)?.triggerAtEpochMillis
        )
    }
}
