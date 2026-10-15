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
