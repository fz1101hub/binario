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
    val triggerAtEpochMillis: Long,
    val alarmId: String = "$trainNumber|$station|$expectedTime"
)

object StopAlarmPlanner {
    const val DEFAULT_LEAD_MINUTES = 5
    val leadMinutesRange: IntRange = 1..60
    private val timeFormatter = DateTimeFormatter.ofPattern("H:mm")

    fun plan(
        trainNumber: String,
        destination: String,
        station: String,
        expectedTime: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = RailTime.defaultZone,
        leadMinutes: Int = DEFAULT_LEAD_MINUTES
    ): StopAlarmPlan? {
        if (leadMinutes !in leadMinutesRange) return null
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
        val expectedDate = Instant.ofEpochMilli(expected).atZone(zoneId).toLocalDate()
        return planAt(trainNumber, destination, station, expectedTime, expected, nowEpochMillis, leadMinutes,
            "$trainNumber|$station|$expectedDate")
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
        zoneId: ZoneId = RailTime.defaultZone,
        leadMinutes: Int = DEFAULT_LEAD_MINUTES
    ): StopAlarmPlan? {
        if (stop.completed || leadMinutes !in leadMinutesRange) return null
        val departure = service.scheduledDepartureDateTime
            ?: return plan(service.number, service.destination, stop.station, stop.referenceTime, nowEpochMillis, zoneId, leadMinutes)
                ?.let { it.copy(alarmId = "${it.alarmId}|${service.id}|${stop.scheduled}") }
        val time = parse(stop.referenceTime) ?: return null
        val date = if (time < departure.toLocalTime()) departure.toLocalDate().plusDays(1) else departure.toLocalDate()
        val expected = LocalDateTime.of(date, time).atZone(zoneId).toInstant().toEpochMilli()
        return planAt(service.number, service.destination, stop.station, stop.referenceTime, expected, nowEpochMillis, leadMinutes,
            "${service.id}|${departure.toLocalDate()}|${stop.station}|${stop.scheduled}")
    }

    private fun planAt(
        trainNumber: String,
        destination: String,
        station: String,
        expectedTime: String,
        expectedEpochMillis: Long,
        nowEpochMillis: Long,
        leadMinutes: Int,
        alarmId: String
    ): StopAlarmPlan? {
        if (expectedEpochMillis <= nowEpochMillis) return null
        val trigger = minOf(expectedEpochMillis, maxOf(nowEpochMillis + 1_000L, expectedEpochMillis - leadMinutes * 60_000L))
        return StopAlarmPlan(trainNumber, destination, station, expectedTime, trigger, alarmId)
    }

    private fun parse(value: String): LocalTime? = runCatching { LocalTime.parse(value, timeFormatter) }.getOrNull()
}
