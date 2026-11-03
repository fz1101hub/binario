package it.binario.app.notifications

import it.binario.app.ConnectionRiskClassifier
import it.binario.app.data.RailTime
import it.binario.app.data.ServiceStatus
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainService
import it.binario.app.data.core.LegMode
import it.binario.app.gpsalarm.GpsDelayEstimate
import it.binario.app.gpsalarm.GpsTrackStatus
import it.binario.app.gpsalarm.GpsTrainTrack
import it.binario.app.data.repository.TrainNumberQuery
import it.binario.app.journeys.JourneyLegLive
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.journeys.LiveStopTimes
import it.binario.app.journeys.TransferAssessment
import it.binario.app.journeys.TransferRiskCalculator
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** One train of a followed trip: where the traveller boards and alights, at the scheduled times. */
data class FollowedTripLeg(
    val number: String,
    /** "REG 2153", as the app shows the train. */
    val label: String,
    val boardStation: String,
    val alightStation: String,
    val departure: Instant,
    val arrival: Instant
)

/**
 * The trip of the ongoing notification: a followed train ("Segui viaggio") or a journey with changes. Nothing
 * here leaves the device; the worker only asks the train-status sources for the trains' numbers.
 */
data class FollowedTrip(
    val id: String,
    val title: String,
    val legs: List<FollowedTripLeg>,
    /** Walk before each leg after the first, in minutes; 0 for a change at the same station. */
    val transferMinutes: List<Int> = emptyList()
) {
    init {
        require(legs.isNotEmpty()) { "Un viaggio seguito ha almeno un treno" }
    }

    val departure: Instant get() = legs.first().departure
    val arrival: Instant get() = legs.last().arrival
    val trainNumbers: Set<String> get() = legs.mapTo(linkedSetOf()) { TrainAlertWatch.key(it.number) }

    fun walkBefore(legIndex: Int): Duration = Duration.ofMinutes((transferMinutes.getOrNull(legIndex - 1) ?: 0).toLong().coerceIn(0, 120))

    companion object {
        const val MAX_LEGS = 6

        /** The train of the detail, from its first shown stop to its last one; null without usable times. */
        fun fromService(service: TrainService, label: String, today: LocalDate, zone: ZoneId = RailTime.defaultZone): FollowedTrip? {
            val first = service.stops.firstOrNull()
            val last = service.stops.lastOrNull()?.takeIf { service.stops.size > 1 } ?: return null
            val departureTime = LiveStopTimes.parse(service.scheduledDeparture) ?: LiveStopTimes.parse(first?.scheduled) ?: return null
            val departure = (service.scheduledDepartureDateTime ?: LocalDateTime.of(today, departureTime)).atZone(zone).toInstant()
            val arrival = ConnectionRiskClassifier.arrival(service, last, today, zone)?.scheduled ?: return null
            if (!arrival.isAfter(departure)) return null
            val leg = FollowedTripLeg(
                number = service.number.trim(),
                label = label,
                boardStation = first?.station ?: service.origin,
                alightStation = last.station,
                departure = departure,
                arrival = arrival
            )
            return FollowedTrip(UUID.randomUUID().toString(), "${leg.boardStation} → ${leg.alightStation}", listOf(leg))
        }

        /**
         * A journey with changes: its train legs with a public number (the ones the status sources know), with the
         * walks and waits between them as change times. Null when no leg can be followed.
         */
        fun fromJourney(row: JourneyOptionRow, labelOf: (Int) -> String): FollowedTrip? {
            val followed = row.legs.withIndex().filter { (_, leg) -> leg.mode == LegMode.TRAIN && !leg.number.isNullOrBlank() }.take(MAX_LEGS)
            if (followed.isEmpty()) return null
            val legs = followed.map { (index, leg) ->
                FollowedTripLeg(
                    number = leg.number.orEmpty().trim(),
                    label = labelOf(index),
                    boardStation = leg.fromLabel,
                    alightStation = leg.toLabel,
                    departure = leg.departure.toInstant(),
                    arrival = leg.arrival.toInstant()
                )
            }
            val transfers = followed.zipWithNext().map { (before, after) ->
                row.legs.subList(before.index + 1, after.index).filter { it.mode == LegMode.WALK }.sumOf { it.durationMinutes }.toInt()
            }
            val title = "${row.legs.first().fromLabel} → ${row.legs.last().toLabel}"
            return FollowedTrip(UUID.randomUUID().toString(), title, legs, transfers)
        }
    }
}

/**
 * When the ongoing notification works: from [LEAD] before the departure to [TRAIL] after the arrival, stretched by
 * the last known delay (at most [MAX_DELAY_EXTENSION]), refreshed every [INTERVAL]. Outside the window nothing runs.
 */
object FollowedTripPlanner {
    val LEAD: Duration = Duration.ofMinutes(30)
    val TRAIL: Duration = Duration.ofMinutes(30)
    val INTERVAL: Duration = Duration.ofMinutes(15)
    val MAX_DELAY_EXTENSION: Duration = Duration.ofHours(6)

    sealed interface Step {
        /** Before the window: the first run is at [at], nothing is shown yet. */
        data class WaitUntil(val at: Instant) : Step
        /** Inside the window: show the status now and run again at [nextAt]. */
        data class Refresh(val nextAt: Instant) : Step
        /** After the window: remove the notification and stop. */
        data object Finish : Step
    }

    fun windowStart(trip: FollowedTrip): Instant = trip.departure.minus(LEAD)

    fun windowEnd(trip: FollowedTrip, lastDelayMinutes: Int?): Instant {
        val delay = Duration.ofMinutes((lastDelayMinutes ?: 0).coerceAtLeast(0).toLong())
        return trip.arrival.plus(TRAIL).plus(if (delay > MAX_DELAY_EXTENSION) MAX_DELAY_EXTENSION else delay)
    }

    fun step(trip: FollowedTrip, now: Instant, lastDelayMinutes: Int? = null): Step {
        val start = windowStart(trip)
        val end = windowEnd(trip, lastDelayMinutes)
        return when {
            now.isBefore(start) -> Step.WaitUntil(start)
            now.isAfter(end) -> Step.Finish
            else -> {
                // The run after the end removes the notification promptly instead of a full interval later.
                val next = now.plus(INTERVAL)
                Step.Refresh(if (next.isAfter(end)) end.plusSeconds(60) else next)
            }
        }
    }

    /** True while the window is open or still ahead: the toggle is offered only for such trips. */
    fun isUpcomingOrRunning(trip: FollowedTrip, now: Instant): Boolean = !now.isAfter(windowEnd(trip, null))

    /** In the last [NEAR_ALIGHT_STOPS] stops before getting off, the refresh follows the stops. */
    const val NEAR_ALIGHT_STOPS = 3
    val NEAR_ALIGHT_DELAY: Duration = Duration.ofSeconds(30)
    val MIN_REFRESH: Duration = Duration.ofMinutes(2)

    /**
     * The next refresh: the regular one, or, in the last stops before the traveller gets off, just after the next
     * stop's expected time (never sooner than [MIN_REFRESH]) so that "Scendi alla prossima fermata" comes in time.
     */
    fun nextRefreshAt(regular: Instant, now: Instant, countdown: StopCountdown?): Instant {
        val nextStop = countdown?.takeIf { it.stopsLeft <= NEAR_ALIGHT_STOPS }?.nextStopAt ?: return regular
        val sooner = maxOf(nextStop.plus(NEAR_ALIGHT_DELAY), now.plus(MIN_REFRESH))
        return minOf(regular, sooner)
    }
}

/** What the notification shows for one refresh. Pure, so the content is the same in tests and on the phone. */
data class FollowedTripView(
    val legIndex: Int,
    val leg: FollowedTripLeg,
    /** Verified operational data for the current train. */
    val live: Boolean,
    val cancelled: Boolean,
    val delayMinutes: Int?,
    val nextStop: String?,
    /** "HH:mm": expected with live data, scheduled otherwise. */
    val nextStopTime: String?,
    val platform: String?,
    /** The next change of a journey with changes, with live data or without (UNKNOWN). */
    val transfer: TransferAssessment? = null,
    val transferStation: String? = null,
    val nextLegLabel: String? = null,
    /** "Scendi tra N fermate" for the current train, when the progress is known and the traveller is on board. */
    val countdown: StopCountdown? = null,
    /** The delay estimated from the device position for the current train: an estimate, never official. */
    val estimate: GpsDelayEstimate? = null,
    /** Where the device position stands for the current train, when a GPS session follows it. */
    val trackStatus: GpsTrackStatus? = null
)

object FollowedTripStatus {
    /** The run of [leg] among the train-number matches: the one calling at both stations in order, else the only one. */
    fun pick(services: List<TrainService>, leg: FollowedTripLeg): TrainService? {
        val sameNumber = services.filter { TrainNumberQuery(leg.number, LocalDate.MIN).matches(it.number) }.ifEmpty { services }
        return sameNumber.firstOrNull { service ->
            val board = LiveStopTimes.stopNamed(service.stops, leg.boardStation)
            val alight = LiveStopTimes.stopNamed(service.stops, leg.alightStation)
            board >= 0 && alight > board
        } ?: sameNumber.singleOrNull()
    }

    fun liveOf(leg: FollowedTripLeg, service: TrainService?, zone: ZoneId = RailTime.defaultZone): JourneyLegLive? =
        service?.let { LiveStopTimes.live(it, leg.boardStation, leg.alightStation, leg.departure, leg.arrival, zone) }

    /** The train the traveller is on or waiting for: the first whose (expected) arrival is still ahead, else the last. */
    fun currentLegIndex(trip: FollowedTrip, lives: List<JourneyLegLive?>, now: Instant): Int =
        trip.legs.indices.firstOrNull { index ->
            val arrival = lives.getOrNull(index)?.expectedArrival ?: trip.legs[index].arrival
            arrival.isAfter(now)
        } ?: trip.legs.lastIndex

    /**
     * @param position the progress a GPS session (alarm or estimate) derived from the device position; used only when
     * it follows the current train.
     */
    fun view(
        trip: FollowedTrip,
        services: List<TrainService?>,
        now: Instant,
        zone: ZoneId = RailTime.defaultZone,
        position: GpsTrainTrack? = null
    ): FollowedTripView {
        val lives = trip.legs.mapIndexed { index, leg -> liveOf(leg, services.getOrNull(index), zone) }
        val index = currentLegIndex(trip, lives, now)
        val leg = trip.legs[index]
        val service = services.getOrNull(index)
        val verified = service?.operationalStatusVerified == true
        val cancelled = verified && service?.status == ServiceStatus.CANCELLED
        val stops = service?.stops.orEmpty()
        val board = LiveStopTimes.stopNamed(stops, leg.boardStation).takeIf { it >= 0 } ?: 0
        val alight = LiveStopTimes.stopNamed(stops, leg.alightStation).takeIf { it >= board } ?: stops.lastIndex
        val window = if (stops.isEmpty()) emptyList() else stops.subList(board, alight + 1)
        val track = position?.takeIf { it.isFor(leg.number) }
        val countdown = if (service != null && !cancelled) countdown(service, board, alight, leg.departure, now, zone, track) else null
        val next = countdown?.let { stops[it.nextIndex] } ?: window.firstOrNull { !it.completed } ?: window.lastOrNull()
        val nextVerified = next != null && service != null && LiveStopTimes.isVerified(service, next)
        val transfer = if (index < trip.legs.lastIndex) {
            val following = trip.legs[index + 1]
            TransferRiskCalculator.assess(leg.arrival, lives[index], following.departure, lives[index + 1], trip.walkBefore(index + 1))
        } else null
        return FollowedTripView(
            legIndex = index,
            leg = leg,
            live = verified,
            cancelled = cancelled,
            delayMinutes = if (verified && !cancelled) (service?.officialDelayMinutes ?: service?.delayMinutes)?.coerceAtLeast(0) else null,
            nextStop = next?.station,
            nextStopTime = next?.let { if (nextVerified) it.expected else it.scheduled }?.takeIf { it.isNotBlank() },
            platform = next?.platform?.takeIf { it.isNotBlank() }
                ?: service?.platform?.takeIf { it.isNotBlank() && (next == null || window.indexOf(next) == 0) },
            transfer = transfer,
            transferStation = transfer?.let { leg.alightStation },
            nextLegLabel = transfer?.let { trip.legs[index + 1].label },
            countdown = countdown,
            estimate = track?.estimate?.takeIf { !cancelled },
            trackStatus = track?.status
        )
    }

    /**
     * The countdown to [alight] of [service]: live progress (the last confirmed stop or the last detected station),
     * the device position when a fresh one follows this train, else the expected or scheduled times against [now].
     * Clock times are placed next to [reference] (the scheduled departure), then to each other across midnight.
     */
    fun countdown(service: TrainService, board: Int, alight: Int, reference: Instant, now: Instant, zone: ZoneId = RailTime.defaultZone, track: GpsTrainTrack? = null): StopCountdown? {
        val stops = service.stops
        if (alight <= board || alight > stops.lastIndex) return null
        var previous = reference
        val timeline = stops.map { stop ->
            val verified = LiveStopTimes.isVerified(service, stop)
            val label = listOf(if (verified) stop.expected else stop.scheduled, stop.scheduled)
                .map(String::trim)
                .firstOrNull { LiveStopTimes.parse(it) != null }
            val at = label?.let(LiveStopTimes::parse)?.let { LiveStopTimes.nearest(previous, it, zone) }?.also { previous = it }
            CountdownStop(stop.station, label, at, stop.routeChange == StopRouteChange.SKIPPED)
        }
        val completed = stops.indices.lastOrNull { stops[it].completed && LiveStopTimes.isVerified(service, stops[it]) }
        val detected = service.lastOperationalDetection?.locationLabel?.let { LiveStopTimes.stopNamed(stops, it) }?.takeIf { it >= 0 }
        val livePassed = listOfNotNull(completed, detected).maxOrNull()
        val positionPassed = track?.freshPassedIndex(now.toEpochMilli())?.let { track.passedStop }?.let { LiveStopTimes.stopNamed(stops, it) }?.takeIf { it >= 0 }
        return StopCountdownCalculator.countdown(timeline, board, alight, now, livePassed, positionPassed)
    }

    /** The arrival delay that stretches the window: the last train's, once known. */
    fun arrivalDelayMinutes(trip: FollowedTrip, services: List<TrainService?>, zone: ZoneId = RailTime.defaultZone): Int? {
        val last = trip.legs.last()
        val arrival = liveOf(last, services.getOrNull(trip.legs.lastIndex), zone)?.expectedArrival ?: return null
        return Duration.between(last.arrival, arrival).toMinutes().toInt().coerceAtLeast(0)
    }
}

/** JSON of the followed trip in local preferences; a damaged value reads as no trip. */
object FollowedTripCodec {
    fun encode(trip: FollowedTrip): String = JSONObject().apply {
        put("id", trip.id)
        put("title", trip.title)
        put("legs", JSONArray().apply {
            trip.legs.forEach { leg ->
                put(JSONObject().apply {
                    put("number", leg.number)
                    put("label", leg.label)
                    put("board", leg.boardStation)
                    put("alight", leg.alightStation)
                    put("departure", leg.departure.toEpochMilli())
                    put("arrival", leg.arrival.toEpochMilli())
                })
            }
        })
        put("transfers", JSONArray().apply { trip.transferMinutes.forEach { put(it) } })
    }.toString()

    fun decode(raw: String?): FollowedTrip? = raw?.let {
        runCatching {
            val root = JSONObject(it)
            val legs = root.getJSONArray("legs")
            val transfers = root.optJSONArray("transfers")
            FollowedTrip(
                id = root.getString("id"),
                title = root.optString("title"),
                legs = (0 until minOf(legs.length(), FollowedTrip.MAX_LEGS)).map { index ->
                    val leg = legs.getJSONObject(index)
                    FollowedTripLeg(
                        number = leg.getString("number").also { number -> require(number.isNotBlank()) },
                        label = leg.optString("label"),
                        boardStation = leg.optString("board"),
                        alightStation = leg.optString("alight"),
                        departure = Instant.ofEpochMilli(leg.getLong("departure")),
                        arrival = Instant.ofEpochMilli(leg.getLong("arrival"))
                    )
                },
                transferMinutes = transfers?.let { array -> (0 until array.length()).map { index -> array.optInt(index, 0) } }.orEmpty()
            )
        }.getOrNull()
    }
}
