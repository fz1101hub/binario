package it.binario.app.journeys

import it.binario.app.data.RailStopNameMatcher
import it.binario.app.data.RailTime
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.JourneyLeg
import it.binario.app.data.core.LegMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A change of a journey seen with live data: [OK] the next vehicle can be reached with the change time, [AT_RISK]
 * it leaves after the arrival but with less than the change time, [MISSED] it leaves before the arrival (or one of
 * the two runs is cancelled), [UNKNOWN] no live data for either vehicle ("solo orario programmato").
 */
enum class TransferRiskLevel {
    OK, AT_RISK, MISSED, UNKNOWN;

    /** Worst first for a row that has several changes: missed, at risk, confirmed, unknown. */
    val severity: Int get() = when (this) {
        MISSED -> 3
        AT_RISK -> 2
        OK -> 1
        UNKNOWN -> 0
    }

    companion object {
        fun worst(levels: Iterable<TransferRiskLevel>): TransferRiskLevel = levels.maxByOrNull { it.severity } ?: UNKNOWN
    }
}

/** Live times of one leg, from the existing train-number lookup or from the source's own realtime fields. */
data class JourneyLegLive(
    val expectedDeparture: Instant? = null,
    val expectedArrival: Instant? = null,
    val cancelled: Boolean = false
) {
    val hasData: Boolean get() = expectedDeparture != null || expectedArrival != null || cancelled
}

/** The outcome of one change: expected arrival at the change point, expected departure of the next vehicle. */
data class TransferAssessment(
    val level: TransferRiskLevel,
    val expectedArrival: Instant,
    val expectedDeparture: Instant,
    /** Next departure minus (arrival + change time), in minutes; negative when the change time is not met. */
    val marginMinutes: Long
)

/**
 * Pure transfer-risk rules. Without a walk the change time is the planner's default change time (never more than the
 * scheduled wait, which the planner already respected) and the connection is lost only when the next train leaves
 * before the arrival. With a walk between the vehicles the planned walk is the change time (the planner's footpaths
 * already include margin and change time) and the connection is lost with less than half of it.
 */
object TransferRiskCalculator {
    const val DEFAULT_TRANSFER_MINUTES = 5L

    fun assess(
        scheduledArrival: Instant,
        live: JourneyLegLive?,
        scheduledDeparture: Instant,
        nextLive: JourneyLegLive?,
        walk: Duration = Duration.ZERO
    ): TransferAssessment {
        val planned = if (walk.isNegative) Duration.ZERO else walk
        val transfer = if (planned.isZero) defaultTransfer(Duration.between(scheduledArrival, scheduledDeparture)) else planned
        val minimum = planned.dividedBy(2)
        val liveArrival = live?.expectedArrival
        val liveDeparture = nextLive?.expectedDeparture
        val cancelled = live?.cancelled == true || nextLive?.cancelled == true
        val arrival = liveArrival ?: scheduledArrival
        val departure = liveDeparture ?: scheduledDeparture
        val margin = Duration.between(arrival.plus(transfer), departure)
        val level = when {
            liveArrival == null && liveDeparture == null && !cancelled -> TransferRiskLevel.UNKNOWN
            cancelled -> TransferRiskLevel.MISSED
            !margin.isNegative -> TransferRiskLevel.OK
            !departure.isBefore(arrival.plus(minimum)) -> TransferRiskLevel.AT_RISK
            else -> TransferRiskLevel.MISSED
        }
        return TransferAssessment(level, arrival, departure, Math.floorDiv(margin.seconds, 60L))
    }

    /** The change time for a scheduled wait, when no walk separates the two vehicles. */
    fun defaultTransfer(scheduledWait: Duration): Duration {
        val default = Duration.ofMinutes(DEFAULT_TRANSFER_MINUTES)
        return when {
            scheduledWait.isNegative -> Duration.ZERO
            scheduledWait < default -> scheduledWait
            else -> default
        }
    }

    /** Key of a leg in the live map: its run identity, else its number and scheduled departure. */
    fun legKey(leg: JourneyLeg): String =
        leg.runId?.value ?: "${leg.publicNumber.orEmpty()}@${leg.scheduledDeparture.toInstant().epochSecond}"

    /** Live data of a leg: the lookup's when present, else the realtime fields the journey source filled in. */
    fun liveOf(leg: JourneyLeg, live: Map<String, JourneyLegLive>): JourneyLegLive? {
        live[legKey(leg)]?.takeIf { it.hasData }?.let { return it }
        val departure = leg.expectedDeparture?.value?.toInstant()
        val arrival = leg.expectedArrival?.value?.toInstant()
        return if (departure != null || arrival != null) JourneyLegLive(departure, arrival) else null
    }

    /**
     * The change before vehicle leg [leavingIndex] of [legs]: the last vehicle before it arrives, then the walks in
     * between (their total is the change time), then the next vehicle leaves. Null when there is no vehicle before.
     */
    fun assessChange(legs: List<JourneyLeg>, leavingIndex: Int, live: Map<String, JourneyLegLive>): TransferAssessment? {
        val leaving = legs.getOrNull(leavingIndex)?.takeIf { it.mode != LegMode.WALK } ?: return null
        val vehicleIndex = (leavingIndex - 1 downTo 0).firstOrNull { legs[it].mode != LegMode.WALK } ?: return null
        val vehicle = legs[vehicleIndex]
        val walk = legs.subList(vehicleIndex + 1, leavingIndex).filter { it.mode == LegMode.WALK }
            .fold(Duration.ZERO) { total, step -> total.plus(Duration.between(step.scheduledDeparture, step.scheduledArrival)) }
        return assess(vehicle.scheduledArrival.toInstant(), liveOf(vehicle, live), leaving.scheduledDeparture.toInstant(), liveOf(leaving, live), walk)
    }
}

/**
 * Expected times of a train at a named stop, from a train-number lookup. Only verified operational times count: a
 * scheduled-only run gives nothing, so it is never presented as live.
 */
object LiveStopTimes {
    private val clock = DateTimeFormatter.ofPattern("H:mm")
    private val halfDay = Duration.ofHours(12)

    /** Station names as different sources publish them ("Milano Centrale", "MILANO CENTRALE", "Milano C.le"). */
    fun namesMatch(first: String, second: String): Boolean {
        if (first.isBlank() || second.isBlank()) return false
        return RailStopNameMatcher.matches(Station("", first, ""), second) || RailStopNameMatcher.matches(Station("", second, ""), first)
    }

    fun stopNamed(stops: List<TrainStop>, name: String): Int = stops.indexOfFirst { namesMatch(it.station, name) }

    fun isVerified(service: TrainService, stop: TrainStop?): Boolean =
        stop?.operationalStatusVerified ?: service.operationalStatusVerified

    /**
     * The expected time at [station] near [scheduled]: the stop's verified expected time, else the scheduled time
     * plus the train's verified delay when the stop is not published by this source. Null without operational data.
     */
    fun expectedAt(service: TrainService, station: String, scheduled: Instant, zone: ZoneId = RailTime.defaultZone): Instant? {
        val stop = service.stops.getOrNull(stopNamed(service.stops, station))
        if (stop != null) {
            if (!isVerified(service, stop)) return null
            parse(stop.expected)?.let { return nearest(scheduled, it, zone) }
            return scheduled.plus(Duration.ofMinutes(stop.delayMinutes.coerceAtLeast(0).toLong()))
        }
        if (!service.operationalStatusVerified) return null
        val delay = service.officialDelayMinutes ?: service.delayMinutes
        return scheduled.plus(Duration.ofMinutes(delay.coerceAtLeast(0).toLong()))
    }

    fun live(service: TrainService, from: String, to: String, departure: Instant, arrival: Instant, zone: ZoneId = RailTime.defaultZone): JourneyLegLive? {
        val cancelled = service.status == ServiceStatus.CANCELLED && service.operationalStatusVerified
        val result = JourneyLegLive(
            expectedDeparture = if (cancelled) null else expectedAt(service, from, departure, zone),
            expectedArrival = if (cancelled) null else expectedAt(service, to, arrival, zone),
            cancelled = cancelled
        )
        return result.takeIf { it.hasData }
    }

    fun parse(value: String?): LocalTime? = value?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalTime.parse(it, clock) }.getOrNull() }

    /** The occurrence of a clock time nearest to [reference]: "00:10" near 23:55 is the next day. */
    fun nearest(reference: Instant, time: LocalTime, zone: ZoneId = RailTime.defaultZone): Instant {
        val date = reference.atZone(zone).toLocalDate()
        val candidate = LocalDateTime.of(date, time).atZone(zone).toInstant()
        return when {
            Duration.between(reference, candidate) > halfDay -> LocalDateTime.of(date.minusDays(1), time).atZone(zone).toInstant()
            Duration.between(candidate, reference) > halfDay -> LocalDateTime.of(date.plusDays(1), time).atZone(zone).toInstant()
            else -> candidate
        }
    }
}
