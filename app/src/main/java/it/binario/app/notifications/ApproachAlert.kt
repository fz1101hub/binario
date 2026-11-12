package it.binario.app.notifications

import it.binario.app.CommuterAlertPlanner
import it.binario.app.FavouriteTrainMatcher
import it.binario.app.ServiceMonitor
import it.binario.app.data.RailTime
import it.binario.app.data.ServiceStatus
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainService
import it.binario.app.data.repository.TrainNumberSearchUpdate
import it.binario.app.journeys.LiveStopTimes
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Avvisami quando sta arrivando a <stazione>" for a favourite train: [leadMinutes] before the train's expected time at
 * [station] ([stationTime] is the scheduled one, [departure] the train's scheduled departure, which tells a stop after
 * midnight from one of the same evening).
 */
data class ApproachAlert(
    val number: String,
    val label: String,
    val station: String,
    val stationTime: String,
    val departure: String,
    val leadMinutes: Int,
    val createdAt: Instant
) {
    val key: String get() = TrainAlertWatch.key(number)
}

/** What one check learned about the run of the day at the alert's station. */
data class ApproachObservation(
    /** Expected time at the station with live data, else the scheduled one. */
    val expectedAt: Instant,
    /** Verified delay at the station, null for a schedule-only answer. */
    val delayMinutes: Int?,
    val platform: String?,
    /** The train has already called at the station. */
    val passed: Boolean,
    val cancelled: Boolean
) {
    val live: Boolean get() = delayMinutes != null
}

/** What the train-number search said about the run of the day at the alert's station. */
sealed interface ApproachLookup {
    /** The run calls at the station. */
    data class Observed(val observation: ApproachObservation) : ApproachLookup

    /** Every source answered: the train has no run that day, or its run does not call at the station. */
    data object NotRunning : ApproachLookup

    /** No usable answer (offline, a source failed or timed out, several candidate runs): the timetable stays the fallback. */
    data object Unknown : ApproachLookup
}

sealed interface ApproachDecision {
    /** Notify now: the train is expected at the station in about [minutesLeft] minutes. */
    data class Notify(val minutesLeft: Int) : ApproachDecision

    /** Check again at [at]: the next regular check, or the moment to notify when that comes first. */
    data class WaitUntil(val at: Instant) : ApproachDecision

    /** Nothing more for this day: notified, passed, cancelled, too late or in the quiet hours. */
    data object Done : ApproachDecision
}

/**
 * Rules of the approach alert. Checks run every [CHECK_INTERVAL] (the cadence of the other live notifications) from
 * [FIRST_CHECK_BEFORE] ahead of the moment to notify; each check reads the live delay and moves that moment, and when it
 * falls before the next check a one-shot run is planned for it. Nothing is notified in the quiet hours (22:00 to
 * 07:00), and at most once a day per train.
 */
object ApproachAlertPlanner {
    val LEAD_OPTIONS: List<Int> = listOf(5, 10, 15)
    const val DEFAULT_LEAD = 10
    val CHECK_INTERVAL: Duration = Duration.ofMinutes(15)
    val FIRST_CHECK_BEFORE: Duration = Duration.ofMinutes(30)

    /** Delays up to this long keep the day's checks going. */
    val MAX_DELAY: Duration = Duration.ofHours(3)

    /** A run this close to the moment counts as on time; one later than this after the expected time is too late. */
    val EARLY_TOLERANCE: Duration = Duration.ofMinutes(1)
    val LATE_TOLERANCE: Duration = Duration.ofMinutes(2)
    val MIN_DELAY_BETWEEN_RUNS: Duration = Duration.ofMinutes(1)

    /** The notification leaves the shade this long after the train's expected time at the station. */
    val NOTIFICATION_TIMEOUT: Duration = Duration.ofMinutes(15)
    const val MAX_ALERTS = 12

    fun lead(alert: ApproachAlert): Duration = Duration.ofMinutes(alert.leadMinutes.coerceIn(LEAD_OPTIONS.first(), LEAD_OPTIONS.last()).toLong())

    /** The scheduled time at the station for the run that departs on [date]. */
    fun scheduledAt(alert: ApproachAlert, date: LocalDate, zone: ZoneId = RailTime.defaultZone): Instant? {
        val time = LiveStopTimes.parse(alert.stationTime) ?: return null
        val departure = LiveStopTimes.parse(alert.departure)
        val day = if (departure != null && time < departure) date.plusDays(1) else date
        return LocalDateTime.of(day, time).atZone(zone).toInstant()
    }

    /**
     * The departure date of the run that reaches the station on [day]: the day before for a station the train reaches
     * after midnight (a station time before the departure time), else [day]. Lookups and the day state use this date.
     */
    fun serviceDate(alert: ApproachAlert, day: LocalDate, zone: ZoneId = RailTime.defaultZone): LocalDate {
        val arrival = scheduledAt(alert, day, zone) ?: return day
        return if (arrival.atZone(zone).toLocalDate().isAfter(day)) day.minusDays(1) else day
    }

    /**
     * When the checks of [date] run: around the scheduled time, inside 07:00 – 22:00 of the day the train gets there.
     * None when nothing could be shown: a punctual train has passed the station before the quiet hours end, or even a
     * punctual train's moment to notify falls after they begin (a delay only moves it later).
     */
    fun window(alert: ApproachAlert, date: LocalDate, zone: ZoneId = RailTime.defaultZone): ClosedRange<Instant>? {
        val scheduled = scheduledAt(alert, date, zone) ?: return null
        val day = scheduled.atZone(zone).toLocalDate()
        val quietEnd = day.atTime(TrainAlertWatch.QUIET_END).atZone(zone).toInstant()
        val quietStart = day.atTime(TrainAlertWatch.QUIET_START).atZone(zone).toInstant()
        if (scheduled.plus(LATE_TOLERANCE).isBefore(quietEnd) || !scheduled.minus(lead(alert)).isBefore(quietStart)) return null
        val start = maxOf(scheduled.minus(lead(alert)).minus(FIRST_CHECK_BEFORE), quietEnd)
        val end = minOf(scheduled.plus(MAX_DELAY), quietStart)
        return if (start < end) start..end else null
    }

    /**
     * True when the timetable moment to notify falls in the quiet hours (22:00 to 07:00): the alert then comes later
     * than chosen, or not at all. The app says so under the alert.
     */
    fun quietOnTimetable(alert: ApproachAlert): Boolean {
        val time = LiveStopTimes.parse(alert.stationTime) ?: return false
        return CommuterAlertPlanner.isQuiet(time.minus(lead(alert)))
    }

    /** The moment to notify: [lead] before the expected time. */
    fun notifyAt(alert: ApproachAlert, expectedAt: Instant): Instant = expectedAt.minus(lead(alert))

    fun decide(
        alert: ApproachAlert,
        date: LocalDate,
        observation: ApproachObservation?,
        now: Instant,
        notified: Boolean,
        zone: ZoneId = RailTime.defaultZone
    ): ApproachDecision {
        val window = window(alert, date, zone) ?: return ApproachDecision.Done
        if (notified || now.isAfter(window.endInclusive)) return ApproachDecision.Done
        if (observation?.passed == true || observation?.cancelled == true) return ApproachDecision.Done
        val expected = observation?.expectedAt ?: scheduledAt(alert, date, zone) ?: return ApproachDecision.Done
        val notifyAt = notifyAt(alert, expected)
        if (!now.isBefore(notifyAt.minus(EARLY_TOLERANCE))) {
            if (now.isAfter(expected.plus(LATE_TOLERANCE)) || TrainAlertWatch.isQuiet(now, zone)) return ApproachDecision.Done
            return ApproachDecision.Notify(minutesUntil(now, expected))
        }
        val nextCheck = if (now.isBefore(window.start)) window.start else now.plus(CHECK_INTERVAL)
        return ApproachDecision.WaitUntil(maxOf(minOf(nextCheck, notifyAt), now.plus(MIN_DELAY_BETWEEN_RUNS)))
    }

    /**
     * The check after a lookup: a day without the train at the station is over; without an answer the last observation
     * of the day ([last]), else the timetable, decides as before.
     */
    fun decide(
        alert: ApproachAlert,
        date: LocalDate,
        lookup: ApproachLookup,
        last: ApproachObservation?,
        now: Instant,
        zone: ZoneId = RailTime.defaultZone
    ): ApproachDecision =
        if (lookup == ApproachLookup.NotRunning) ApproachDecision.Done
        else decide(alert, date, observationAfter(lookup, last), now, notified = false, zone)

    /** The observation a check keeps: the lookup's, else (no answer) the last one of the day; none on a day without the train. */
    fun observationAfter(lookup: ApproachLookup, last: ApproachObservation?): ApproachObservation? = when (lookup) {
        is ApproachLookup.Observed -> lookup.observation
        ApproachLookup.NotRunning -> null
        ApproachLookup.Unknown -> last
    }

    /**
     * Reads the last answer of the train-number search for the run of [date]; [update] is null when the search failed or
     * timed out. Only a complete answer with every source available says that the train does not run that day: a source
     * that failed keeps the timetable fallback. A run that publishes its stops without the station does not call there.
     */
    fun lookupOf(update: TrainNumberSearchUpdate?, monitor: ServiceMonitor, alert: ApproachAlert, date: LocalDate, zone: ZoneId = RailTime.defaultZone): ApproachLookup {
        if (update == null) return ApproachLookup.Unknown
        val service = FavouriteTrainMatcher.pick(update.matches.map { it.service }, monitor)
        if (service != null) {
            observe(service, alert, date, zone)?.let { return ApproachLookup.Observed(it) }
            return if (service.stops.isNotEmpty()) ApproachLookup.NotRunning else ApproachLookup.Unknown
        }
        val everySourceAnswered = update.complete && update.unavailableSources.isEmpty()
        return if (everySourceAnswered && update.matches.isEmpty()) ApproachLookup.NotRunning else ApproachLookup.Unknown
    }

    /** Whole minutes still to go, rounded up: "tra ~4 min" at 3 minutes 20 seconds. */
    fun minutesUntil(now: Instant, expected: Instant): Int {
        val seconds = Duration.between(now, expected).seconds
        return if (seconds <= 0) 0 else ((seconds + 59) / 60).toInt()
    }

    /**
     * The next run for [alert]: the next check or moment to notify of the run reaching the station today (from the last
     * observation), else the window of the next run. [doneOn] and [notifiedOn] are departure dates ([serviceDate]) of
     * the last run the alert finished (notified, passed, cancelled or too late) or notified.
     */
    fun nextRun(alert: ApproachAlert, doneOn: LocalDate?, notifiedOn: LocalDate?, observation: ApproachObservation?, now: Instant, zone: ZoneId = RailTime.defaultZone): Instant? {
        val date = serviceDate(alert, now.atZone(zone).toLocalDate(), zone)
        val window = window(alert, date, zone)
        if (doneOn != date && notifiedOn != date && window != null && !now.isAfter(window.endInclusive)) {
            when (val decision = decide(alert, date, observation, now, notified = false, zone)) {
                is ApproachDecision.WaitUntil -> return decision.at
                is ApproachDecision.Notify -> return now
                ApproachDecision.Done -> Unit
            }
        }
        return (1L..2L).asSequence().mapNotNull { window(alert, date.plusDays(it), zone)?.start }.firstOrNull { it.isAfter(now) }
    }

    /**
     * What [service] (the run of [date]) says about the alert's station: the verified expected time and delay, else the
     * scheduled time; the platform; whether it has already called there or will not (cancelled, stop skipped). Null
     * when the run does not call at the station.
     */
    fun observe(service: TrainService, alert: ApproachAlert, date: LocalDate, zone: ZoneId = RailTime.defaultZone): ApproachObservation? {
        val stop = service.stops.getOrNull(LiveStopTimes.stopNamed(service.stops, alert.station)) ?: return null
        val stored = scheduledAt(alert, date, zone) ?: return null
        // The run's own timetable wins over the time saved with the alert (a timetable change).
        val scheduled = LiveStopTimes.parse(stop.scheduled)?.let { LiveStopTimes.nearest(stored, it, zone) } ?: stored
        val verified = service.operationalStatusVerified && LiveStopTimes.isVerified(service, stop)
        val expected = if (verified) LiveStopTimes.expectedAt(service, stop.station, scheduled, zone) else null
        return ApproachObservation(
            expectedAt = expected ?: scheduled,
            delayMinutes = expected?.let { Duration.between(scheduled, it).toMinutes().toInt().coerceAtLeast(0) },
            platform = stop.platform?.trim()?.takeIf { it.isNotEmpty() },
            passed = verified && stop.completed,
            cancelled = (service.operationalStatusVerified && service.status == ServiceStatus.CANCELLED) || stop.routeChange == StopRouteChange.SKIPPED
        )
    }

    /**
     * When a wake-up alarm delivers the alert of the run reaching the station today: at its moment to notify, once a
     * check of the day stored an [observation] (the alarm posts from it, with no request), in the future, before the
     * window ends and outside the quiet hours. Null when there is nothing to deliver. A WorkManager run planned for the
     * same moment can be deferred by Doze past it, and a run too late drops the day.
     */
    fun alarmAt(alert: ApproachAlert, doneOn: LocalDate?, notifiedOn: LocalDate?, observation: ApproachObservation?, now: Instant, zone: ZoneId = RailTime.defaultZone): Instant? {
        if (observation == null || observation.passed || observation.cancelled) return null
        val date = serviceDate(alert, now.atZone(zone).toLocalDate(), zone)
        if (doneOn == date || notifiedOn == date) return null
        val window = window(alert, date, zone) ?: return null
        val at = notifyAt(alert, observation.expectedAt)
        return at.takeIf { it.isAfter(now) && !it.isAfter(window.endInclusive) && !TrainAlertWatch.isQuiet(it, zone) }
    }

    /** Only the alerts of trains still among the favourites are checked. */
    fun active(alerts: List<ApproachAlert>, favouriteNumbers: Collection<String>): List<ApproachAlert> {
        val keys = favouriteNumbers.mapTo(hashSetOf(), TrainAlertWatch::key)
        return alerts.filter { it.key in keys }
    }
}

/** JSON of the approach alerts and of their per-day state in local preferences; damaged entries are skipped. */
object ApproachAlertCodec {
    fun encode(alerts: List<ApproachAlert>): String = JSONArray().apply {
        alerts.forEach { alert ->
            put(JSONObject().apply {
                put("number", alert.number)
                put("label", alert.label)
                put("station", alert.station)
                put("time", alert.stationTime)
                put("departure", alert.departure)
                put("lead", alert.leadMinutes)
                put("createdAt", alert.createdAt.toEpochMilli())
            })
        }
    }.toString()

    fun decode(raw: String?): List<ApproachAlert> {
        val array = raw?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until minOf(array.length(), ApproachAlertPlanner.MAX_ALERTS)).mapNotNull { index ->
            val value = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                ApproachAlert(
                    number = value.getString("number").also { require(it.isNotBlank()) },
                    label = value.optString("label"),
                    station = value.getString("station").also { require(it.isNotBlank()) },
                    stationTime = value.getString("time").also { require(LiveStopTimes.parse(it) != null) },
                    departure = value.optString("departure"),
                    leadMinutes = value.getInt("lead").also { require(it in ApproachAlertPlanner.LEAD_OPTIONS) },
                    createdAt = Instant.ofEpochMilli(value.getLong("createdAt"))
                )
            }.getOrNull()
        }.distinctBy { it.key }
    }

    /** The day state of one alert: the last day it finished or notified and the last observation of that day. */
    data class DayState(val date: LocalDate?, val doneOn: LocalDate?, val notifiedOn: LocalDate?, val observation: ApproachObservation?)

    fun encodeState(state: DayState): String = JSONObject().apply {
        state.date?.let { put("date", it.toString()) }
        state.doneOn?.let { put("done", it.toString()) }
        state.notifiedOn?.let { put("notified", it.toString()) }
        state.observation?.let { observation ->
            put("expectedAt", observation.expectedAt.toEpochMilli())
            observation.delayMinutes?.let { put("delay", it) }
            observation.platform?.let { put("platform", it) }
            put("passed", observation.passed)
            put("cancelled", observation.cancelled)
        }
    }.toString()

    fun decodeState(raw: String?): DayState {
        val value = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return DayState(null, null, null, null)
        fun day(name: String) = value.optString(name).takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val observation = value.optLong("expectedAt", 0L).takeIf { it > 0 }?.let { at ->
            ApproachObservation(
                expectedAt = Instant.ofEpochMilli(at),
                delayMinutes = if (value.has("delay")) value.optInt("delay") else null,
                platform = value.optString("platform").takeIf { it.isNotBlank() },
                passed = value.optBoolean("passed", false),
                cancelled = value.optBoolean("cancelled", false)
            )
        }
        return DayState(day("date"), day("done"), day("notified"), observation)
    }
}
