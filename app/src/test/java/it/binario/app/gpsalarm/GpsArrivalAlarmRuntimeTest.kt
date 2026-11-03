package it.binario.app.gpsalarm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsArrivalAlarmRuntimeTest {
    private val target = GpsArrivalAlarmTarget(
        trainNumber = "2581",
        trainDestination = "Bologna Centrale",
        stopName = "Modena",
        stationId = "curated:S05043",
        latitude = 44.654,
        longitude = 10.932,
        radiusMeters = 2_000,
        expectedTime = "18:12",
        arrivalEpochMillis = 2_000_000L,
        timeAlarmTriggerEpochMillis = 1_700_000L,
        expiresAtEpochMillis = 2_000_000L + GpsArrivalAlarmPolicy.AFTER_ARRIVAL_MILLIS
    )

    @After
    fun reset() {
        GpsArrivalAlarmRuntime.markEnded(null, GpsArrivalAlarmEndReason.CANCELLED)
        GpsArrivalAlarmRuntime.acknowledge()
        GpsArrivalAlarmRuntime.takeStopReason()
    }

    @Test
    fun `an ended alarm stays visible until acknowledged, an active one is never cleared`() {
        GpsArrivalAlarmRuntime.markActive(target, precise = false)
        GpsArrivalAlarmRuntime.acknowledge()
        assertEquals(GpsArrivalAlarmState.Active(target, precise = false), GpsArrivalAlarmRuntime.state.value)

        GpsArrivalAlarmRuntime.markEnded(target, GpsArrivalAlarmEndReason.TRIGGERED)
        assertEquals(GpsArrivalAlarmState.Ended(target, GpsArrivalAlarmEndReason.TRIGGERED), GpsArrivalAlarmRuntime.state.value)
        GpsArrivalAlarmRuntime.acknowledge()
        assertEquals(GpsArrivalAlarmState.Idle, GpsArrivalAlarmRuntime.state.value)
    }

    @Test
    fun `the reason of an external stop is handed over once`() {
        GpsArrivalAlarmRuntime.requestStopReason(GpsArrivalAlarmEndReason.TIME_ALARM_FIRED)
        assertEquals(GpsArrivalAlarmEndReason.TIME_ALARM_FIRED, GpsArrivalAlarmRuntime.takeStopReason())
        assertNull(GpsArrivalAlarmRuntime.takeStopReason())
    }
}
