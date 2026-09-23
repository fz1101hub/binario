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
}
