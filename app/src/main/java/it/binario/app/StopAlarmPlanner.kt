package it.binario.app

import it.binario.app.data.RailTime
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.journeys.LiveStopTimes
import it.binario.app.notifications.TrainAlertWatch
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class StopAlarmPlan(
    val trainNumber: String,
    val destination: String,
    val station: String,
    val expectedTime: String,
    val triggerAtEpochMillis: Long,
    /** The expected arrival itself; the GPS arrival alarm runs until a bounded time after it. */
    val expectedAtEpochMillis: Long
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
        return StopAlarmPlan(trainNumber, destination, station, expectedTime, trigger, expectedEpochMillis)
    }

    private fun parse(value: String): LocalTime? = runCatching { LocalTime.parse(value, timeFormatter) }.getOrNull()

    const val MAX_ALARMS = 8

    /** A plan is kept this long after its expected arrival, then dropped even if it never rang. */
    const val KEEP_AFTER_ARRIVAL_MILLIS = 2 * 60 * 60_000L

    /** A fired alarm whose stored trigger is later than this was planned again meanwhile and stays active. */
    const val REPLANNED_MARGIN_MILLIS = 60_000L

    /** Below this difference a new expected time does not move the alarm. */
    const val REPLAN_THRESHOLD_MILLIS = 60_000L

    /** One stop alarm per train and stop, whatever its expected time. */
    fun key(trainNumber: String, station: String): String = "${trainNumber.trim()}|${station.trim()}"

    fun isOver(plan: StopAlarmPlan, nowEpochMillis: Long): Boolean = nowEpochMillis > plan.expectedAtEpochMillis + KEEP_AFTER_ARRIVAL_MILLIS

    /** [plans] with [plan] replacing the alarm of the same train and stop, without plans that are over, soonest kept. */
    fun upsert(plans: List<StopAlarmPlan>, plan: StopAlarmPlan, nowEpochMillis: Long): List<StopAlarmPlan> {
        val key = key(plan.trainNumber, plan.station)
        return (listOf(plan) + plans.filterNot { key(it.trainNumber, it.station) == key || isOver(it, nowEpochMillis) })
            .sortedBy { it.triggerAtEpochMillis }
            .take(MAX_ALARMS)
    }

    /**
     * When a restored alarm rings: at its trigger while that is ahead; at once when the trigger passed (the phone was
     * off or the app force-stopped) but the train has not reached the stop yet; never once the stop is behind.
     */
    fun restoreTrigger(plan: StopAlarmPlan, nowEpochMillis: Long): Long? = when {
        plan.triggerAtEpochMillis > nowEpochMillis -> plan.triggerAtEpochMillis
        nowEpochMillis < plan.expectedAtEpochMillis -> nowEpochMillis
        else -> null
    }

    /**
     * The stored alarms that [services] (the trains a followed-trip refresh read) move: a verified expected time at
     * the stop that changes the trigger by at least [REPLAN_THRESHOLD_MILLIS]. Each keeps its train and stop.
     */
    fun replanned(stored: List<StopAlarmPlan>, services: List<TrainService>, nowEpochMillis: Long, zoneId: ZoneId = RailTime.defaultZone): List<StopAlarmPlan> =
        stored.mapNotNull { alarm ->
            val service = services.firstOrNull { TrainAlertWatch.key(it.number) == TrainAlertWatch.key(alarm.trainNumber) } ?: return@mapNotNull null
            val stop = service.stops.getOrNull(LiveStopTimes.stopNamed(service.stops, alarm.station)) ?: return@mapNotNull null
            if (stop.completed || !LiveStopTimes.isVerified(service, stop)) return@mapNotNull null
            val updated = plan(service, stop, nowEpochMillis, zoneId) ?: return@mapNotNull null
            if (abs(updated.triggerAtEpochMillis - alarm.triggerAtEpochMillis) < REPLAN_THRESHOLD_MILLIS) return@mapNotNull null
            updated.copy(trainNumber = alarm.trainNumber, station = alarm.station, destination = alarm.destination)
        }
}
