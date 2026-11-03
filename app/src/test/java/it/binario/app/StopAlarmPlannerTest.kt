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
    fun `plan keeps the expected arrival even when the stop is less than five minutes away`() {
        val now = LocalDateTime.of(2026, 3, 2, 10, 0).atZone(zone).toInstant().toEpochMilli()
        val expected = LocalDateTime.of(2026, 3, 2, 10, 3).atZone(zone).toInstant().toEpochMilli()

        val plan = StopAlarmPlanner.plan("4132", "Torino", "Firenze", "10:03", now, zone)!!

        assertEquals(expected, plan.expectedAtEpochMillis)
        assertEquals(now + 1_000L, plan.triggerAtEpochMillis)
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

    private fun millis(hour: Int, minute: Int) = LocalDateTime.of(2026, 11, 3, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun alarm(expected: String, trigger: Long, arrival: Long, train: String = "2153", station: String = "Orte") =
        StopAlarmPlan(train, "Roma Termini", station, expected, trigger, arrival)

    @Test
    fun `one alarm per train and stop, whatever its trigger, and a new plan replaces it`() {
        val first = alarm("11:00", millis(10, 55), millis(11, 0))
        val late = alarm("11:20", millis(11, 15), millis(11, 20))
        val other = alarm("12:00", millis(11, 55), millis(12, 0), station = "Orvieto")

        assertEquals(StopAlarmScheduler.requestCode("2153", "Orte"), StopAlarmScheduler.requestCode(" 2153", "Orte "))
        assertTrue(StopAlarmScheduler.requestCode("2153", "Orte") != StopAlarmScheduler.requestCode("2153", "Orvieto"))
        val plans = StopAlarmPlanner.upsert(StopAlarmPlanner.upsert(listOf(first), other, millis(10, 0)), late, millis(10, 0))
        assertEquals(listOf(late, other), plans)
        // Two hours after the arrival a plan that never rang is dropped.
        assertTrue(StopAlarmPlanner.isOver(first, millis(13, 1)))
        assertEquals(listOf(other), StopAlarmPlanner.upsert(listOf(first), other, millis(13, 1)))
    }

    @Test
    fun `a restored stop alarm rings at its trigger, at once when the trigger passed before the stop, never after it`() {
        val plan = alarm("11:00", millis(10, 55), millis(11, 0))

        assertEquals(millis(10, 55), StopAlarmPlanner.restoreTrigger(plan, millis(10, 0)))
        assertEquals(millis(10, 57), StopAlarmPlanner.restoreTrigger(plan, millis(10, 57)))
        assertNull(StopAlarmPlanner.restoreTrigger(plan, millis(11, 1)))
    }

    @Test
    fun `stored stop alarms survive the codec and damaged entries are skipped`() {
        val plans = listOf(alarm("11:00", millis(10, 55), millis(11, 0)), alarm("12:00", millis(11, 55), millis(12, 0), station = "Orvieto"))

        assertEquals(plans, StopAlarmCodec.decode(StopAlarmCodec.encode(plans)))
        assertEquals(1, StopAlarmCodec.decode("""[{"train":"","station":"Orte","trigger":1,"arrival":2},{"train":"2153","station":"Orte","trigger":1,"arrival":2}]""").size)
        assertTrue(StopAlarmCodec.decode("not json").isEmpty())
    }

    @Test
    fun `a followed-trip refresh moves the alarm of a late train, not of an unchanged or unverified one`() {
        val stored = alarm("11:00", millis(10, 55), millis(11, 0))
        fun service(expected: String, verified: Boolean = true) = it.binario.app.data.TrainService(
            id = "2153", number = "2153", operator = it.binario.app.data.RailOperator.TRENITALIA_REGIONALE,
            trainCategory = it.binario.app.data.TrainCategory.REGIONALE, origin = "Firenze", destination = "Roma Termini",
            scheduledDeparture = "09:00", expectedDeparture = "09:00", platform = null, delayMinutes = 0,
            status = it.binario.app.data.ServiceStatus.DELAYED,
            stops = listOf(
                it.binario.app.data.TrainStop("Firenze", "09:00", "09:00", null, 0, completed = true),
                it.binario.app.data.TrainStop("Orte", "11:00", expected, null, 20, completed = false)
            ),
            operationalStatusVerified = verified,
            scheduledDepartureDateTime = LocalDateTime.of(2026, 11, 3, 9, 0)
        )

        val moved = StopAlarmPlanner.replanned(listOf(stored), listOf(service("11:20")), millis(10, 0), zone).single()
        assertEquals(millis(11, 15), moved.triggerAtEpochMillis)
        assertEquals(millis(11, 20), moved.expectedAtEpochMillis)
        assertEquals("Orte", moved.station)
        assertEquals("Roma Termini", moved.destination)
        assertTrue(StopAlarmPlanner.replanned(listOf(stored), listOf(service("11:00")), millis(10, 0), zone).isEmpty())
        assertTrue(StopAlarmPlanner.replanned(listOf(stored), listOf(service("11:20", verified = false)), millis(10, 0), zone).isEmpty())
    }
}
