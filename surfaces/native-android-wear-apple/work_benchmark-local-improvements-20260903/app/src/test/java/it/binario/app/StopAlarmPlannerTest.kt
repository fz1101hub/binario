package it.binario.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
