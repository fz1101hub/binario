package it.binario.app

import it.binario.app.data.RailTime
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class StopAlarmPlan(
    val trainNumber: String,
    val destination: String,
    val station: String,
    val expectedTime: String,
    val triggerAtEpochMillis: Long
)

object StopAlarmPlanner {
    private const val LEAD_MINUTES = 5L
    private val timeFormatter = DateTimeFormatter.ofPattern("H:mm")

    fun plan(
        trainNumber: String,
        destination: String,
        station: String,
        expectedTime: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = RailTime.defaultZone
    ): StopAlarmPlan? {
        val time = parse(expectedTime) ?: return null
        val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId)
        val todayExpected = LocalDateTime.of(now.toLocalDate(), time).atZone(zoneId).toInstant().toEpochMilli()
        // Timetables only expose a clock time. A small early-morning time while it is late at
        // night is therefore the next service day, not an already-passed daytime stop.
        val expected = if (
            todayExpected <= nowEpochMillis &&
            now.toLocalTime() >= LocalTime.of(18, 0) &&
            time < LocalTime.of(6, 0)
        ) {
            LocalDateTime.of(now.toLocalDate().plusDays(1), time).atZone(zoneId).toInstant().toEpochMilli()
        } else {
            todayExpected
        }
        return planAt(trainNumber, destination, station, expectedTime, expected, nowEpochMillis)
    }

    /**
     * Plans the alarm on the train's own service date: a train planned for another day, or the
     * return leg of a round trip, must not ring today (Opus audit N-M7). Stops whose clock time is
     * earlier than the departure are after midnight and roll to the next date.
     */
    fun plan(
        service: TrainService,
        stop: TrainStop,
        nowEpochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = RailTime.defaultZone
    ): StopAlarmPlan? {
        val departure = service.scheduledDepartureDateTime
            ?: return plan(service.number, service.destination, stop.station, stop.expected, nowEpochMillis, zoneId)
        val time = parse(stop.expected) ?: return null
        val date = if (time < departure.toLocalTime()) departure.toLocalDate().plusDays(1) else departure.toLocalDate()
        val expected = LocalDateTime.of(date, time).atZone(zoneId).toInstant().toEpochMilli()
        return planAt(service.number, service.destination, stop.station, stop.expected, expected, nowEpochMillis)
    }

    private fun planAt(
        trainNumber: String,
        destination: String,
        station: String,
        expectedTime: String,
        expectedEpochMillis: Long,
        nowEpochMillis: Long
    ): StopAlarmPlan? {
        if (expectedEpochMillis <= nowEpochMillis) return null
        val trigger = maxOf(nowEpochMillis + 1_000L, expectedEpochMillis - LEAD_MINUTES * 60_000L)
        return StopAlarmPlan(trainNumber, destination, station, expectedTime, trigger)
    }

    private fun parse(value: String): LocalTime? = runCatching { LocalTime.parse(value, timeFormatter) }.getOrNull()
}
