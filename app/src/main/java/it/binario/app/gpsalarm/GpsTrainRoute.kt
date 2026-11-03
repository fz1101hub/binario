package it.binario.app.gpsalarm

import it.binario.app.data.RailTime
import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainStop
import it.binario.app.journeys.LiveStopTimes
import it.binario.app.notifications.CountdownStop
import it.binario.app.notifications.StopCountdown
import it.binario.app.notifications.StopCountdownCalculator
import it.binario.app.notifications.TrainAlertWatch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * One stop of a train's route as the GPS features need it: the published name, the catalogue coordinates when the stop
 * is in the catalogue and its scheduled time. Timetable and catalogue data only, never a position of the user.
 */
data class GpsRouteStop(
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    /** Scheduled time at the stop (the time the timetable shows for it); null when the source has none. */
    val scheduledEpochMillis: Long?,
    val skipped: Boolean = false
) {
    val located: Boolean get() = latitude != null && longitude != null
}

/**
 * The route of one train from [boardIndex] (where the traveller boards; the origin when unknown) to [targetIndex] (where
 * they get off): the line the device position is projected on. There are no track shapes: the line is piecewise
 * linear between consecutive stops with coordinates.
 */
data class GpsTrainRoute(
    val trainNumber: String,
    val stops: List<GpsRouteStop>,
    val boardIndex: Int,
    val targetIndex: Int
) {
    /** At least two stops with coordinates and a target after the boarding stop: a line exists. */
    val usable: Boolean
        get() = targetIndex in stops.indices && boardIndex in 0 until targetIndex && stops.count { it.located } >= 2

    /** The delay can be estimated only when the target has a scheduled time to add it to. */
    val estimable: Boolean get() = usable && stops[targetIndex].scheduledEpochMillis != null

    fun isFor(number: String): Boolean = TrainAlertWatch.key(number) == TrainAlertWatch.key(trainNumber)

    companion object {
        const val MAX_STOPS = 80
    }
}

/** How the device position relates to the route. */
enum class GpsTrackStatus {
    /** No accepted fix on the route yet (or only fixes that were too inaccurate). */
    WAITING,
    ON_ROUTE,

    /** The last accepted fix was more than [GpsTrainTracking.OFF_ROUTE_METERS] from the line: no estimate. */
    OFF_ROUTE,

    /** The device reached the target stop. */
    ARRIVED
}

/** The delay estimated from the device position: an estimate, never official data, and never stored. */
data class GpsDelayEstimate(
    val delayMinutes: Int,
    /** Scheduled arrival at the target plus the estimated delay. */
    val arrivalEpochMillis: Long,
    /** When the fix behind the estimate was evaluated: an estimate kept through a tunnel shows its age. */
    val atEpochMillis: Long
)

/** What the GPS session knows about the train's progress, in memory only. */
data class GpsTrainTrack(
    val trainNumber: String,
    val status: GpsTrackStatus,
    /** Last stop of the route the device has reached; null without a fix on the route. */
    val passedIndex: Int?,
    val passedStop: String?,
    val estimate: GpsDelayEstimate?,
    val lastFixAtEpochMillis: Long?,
    val arrivedAtEpochMillis: Long? = null
) {
    fun isFor(number: String): Boolean = TrainAlertWatch.key(number) == TrainAlertWatch.key(trainNumber)

    /** The progress by position counts only while fresh: after a long gap (a tunnel) the timetable takes over again. */
    fun freshPassedIndex(nowEpochMillis: Long): Int? {
        val at = lastFixAtEpochMillis ?: return null
        if (status != GpsTrackStatus.ON_ROUTE && status != GpsTrackStatus.ARRIVED) return null
        return passedIndex.takeIf { nowEpochMillis - at <= GpsTrainTracking.POSITION_FRESH_MILLIS }
    }

    companion object {
        fun waiting(trainNumber: String) = GpsTrainTrack(trainNumber, GpsTrackStatus.WAITING, null, null, null, null)
    }
}

/** Builds [GpsTrainRoute]s from a train's stop list and encodes them for the alarm target. */
internal object GpsTrainRoutes {
    private val clock = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT)

    fun clockLabel(epochMillis: Long, zone: ZoneId = RailTime.defaultZone): String = Instant.ofEpochMilli(epochMillis).atZone(zone).format(clock)

    /**
     * The route of [stops] from [boardIndex] to [targetIndex]. [anchor] is the instant of the stop at [anchorIndex]
     * (the departure, or the expected arrival at the target): the other clock times are placed next to it, so a
     * route across midnight stays in order. [locate] gives the catalogue coordinates of a stop name.
     */
    fun build(
        trainNumber: String,
        stops: List<TrainStop>,
        boardIndex: Int,
        targetIndex: Int,
        anchorIndex: Int,
        anchor: Instant,
        locate: (String) -> GpsAlarmStation?,
        zone: ZoneId = RailTime.defaultZone
    ): GpsTrainRoute? {
        if (targetIndex !in stops.indices || boardIndex !in 0 until targetIndex || anchorIndex !in stops.indices) return null
        val first = maxOf(boardIndex, targetIndex + 1 - GpsTrainRoute.MAX_STOPS)
        val slice = stops.subList(first, targetIndex + 1)
        val times = instants(slice.map { LiveStopTimes.parse(it.scheduled) }, (anchorIndex - first).coerceIn(slice.indices), anchor, zone)
        val located = slice.map { locate(it.station) }
        val route = GpsTrainRoute(
            trainNumber = trainNumber.trim(),
            stops = slice.mapIndexed { index, stop ->
                GpsRouteStop(
                    name = stop.station,
                    latitude = located[index]?.latitude,
                    longitude = located[index]?.longitude,
                    scheduledEpochMillis = times[index]?.toEpochMilli(),
                    skipped = stop.routeChange == StopRouteChange.SKIPPED
                )
            },
            boardIndex = 0,
            targetIndex = slice.lastIndex
        )
        return route.takeIf { it.usable }
    }

    /** Clock times placed next to the anchor and then to each other, so they never jump half a day. */
    internal fun instants(times: List<java.time.LocalTime?>, anchorIndex: Int, anchor: Instant, zone: ZoneId): List<Instant?> {
        val result = arrayOfNulls<Instant>(times.size)
        times.getOrNull(anchorIndex)?.let { result[anchorIndex] = LiveStopTimes.nearest(anchor, it, zone) }
        var reference = result[anchorIndex] ?: anchor
        for (index in anchorIndex + 1 until times.size) {
            times[index]?.let { time -> LiveStopTimes.nearest(reference, time, zone).also { result[index] = it; reference = it } }
        }
        reference = result[anchorIndex] ?: anchor
        for (index in anchorIndex - 1 downTo 0) {
            times[index]?.let { time -> LiveStopTimes.nearest(reference, time, zone).also { result[index] = it; reference = it } }
        }
        return result.toList()
    }

    /** The countdown to the target from the position when fresh, else from the scheduled times shifted by the estimate. */
    fun countdown(route: GpsTrainRoute, track: GpsTrainTrack?, nowEpochMillis: Long, zone: ZoneId = RailTime.defaultZone): StopCountdown? {
        val shift = (track?.estimate?.delayMinutes ?: 0) * 60_000L
        val timeline = route.stops.map { stop ->
            val at = stop.scheduledEpochMillis?.let { Instant.ofEpochMilli(it + shift) }
            CountdownStop(stop.name, at?.atZone(zone)?.format(clock), at, stop.skipped)
        }
        return StopCountdownCalculator.countdown(
            stops = timeline,
            board = route.boardIndex,
            alight = route.targetIndex,
            now = Instant.ofEpochMilli(nowEpochMillis),
            positionPassed = track?.freshPassedIndex(nowEpochMillis)
        )
    }

    fun encode(route: GpsTrainRoute): String = JSONObject().apply {
        put("train", route.trainNumber)
        put("board", route.boardIndex)
        put("target", route.targetIndex)
        put("stops", JSONArray().apply {
            route.stops.forEach { stop ->
                put(JSONObject().apply {
                    put("name", stop.name)
                    stop.latitude?.let { put("lat", it) }
                    stop.longitude?.let { put("lon", it) }
                    stop.scheduledEpochMillis?.let { put("at", it) }
                    if (stop.skipped) put("skipped", true)
                })
            }
        })
    }.toString()

    fun decode(raw: String?): GpsTrainRoute? = raw?.takeIf { it.isNotBlank() }?.let { value ->
        runCatching {
            val root = JSONObject(value)
            val stops = root.getJSONArray("stops")
            require(stops.length() in 2..GpsTrainRoute.MAX_STOPS)
            GpsTrainRoute(
                trainNumber = root.getString("train").also { require(it.isNotBlank()) },
                stops = (0 until stops.length()).map { index ->
                    val stop = stops.getJSONObject(index)
                    val latitude = stop.optDouble("lat").takeIf { it.isFinite() && it in -90.0..90.0 }
                    val longitude = stop.optDouble("lon").takeIf { it.isFinite() && it in -180.0..180.0 }
                    GpsRouteStop(
                        name = stop.getString("name"),
                        latitude = latitude.takeIf { longitude != null },
                        longitude = longitude.takeIf { latitude != null },
                        scheduledEpochMillis = stop.optLong("at", 0L).takeIf { it > 0 },
                        skipped = stop.optBoolean("skipped", false)
                    )
                },
                boardIndex = root.getInt("board"),
                targetIndex = root.getInt("target")
            ).takeIf { it.usable }
        }.getOrNull()
    }
}

/**
 * Pure rules of the position estimate ("Stima dalla tua posizione"): the device position is projected on the line
 * between consecutive stops of the route, which gives the progress (the last stop reached) and, compared with the
 * timetable at that point, the delay. Fixes less accurate than [MAX_ACCURACY_METERS] are ignored, a fix within
 * [DWELL_RADIUS_METERS] of a stop counts as standing at that stop, and a fix more than [OFF_ROUTE_METERS] from the line
 * gives no estimate. Everything happens in memory on the device.
 */
internal object GpsTrainTracking {
    const val MAX_ACCURACY_METERS = 200.0
    const val OFF_ROUTE_METERS = 1_500.0
    const val DWELL_RADIUS_METERS = 400.0

    /** Noise that moves the projection back by less than this is ignored: a train does not reverse. */
    const val BACKTRACK_TOLERANCE_METERS = 500.0

    /** The position keeps deciding the countdown for this long after the last accepted fix. */
    const val POSITION_FRESH_MILLIS = 3 * 60_000L

    /** An estimate older than this shows the time it was made. */
    const val ESTIMATE_FRESH_MILLIS = 2 * 60_000L

    const val MAX_DELAY_MINUTES = 600

    /** The estimate session ("Stima dalla tua posizione") ends this long after the arrival. */
    const val AFTER_ARRIVAL_MILLIS = 10 * 60_000L

    /** The session can be started from this long before the departure. */
    const val BEFORE_DEPARTURE_MILLIS = 10 * 60_000L

    private const val EARTH_MEAN_RADIUS_METERS = 6_371_008.8

    /** Where a fix lies on the route. */
    data class Projection(
        /** Distance along the line from its first stop with coordinates. */
        val alongMeters: Double,
        /** Distance of the fix from the line. */
        val offsetMeters: Double
    )

    /**
     * The route as a measured line: [positions] is the along-line distance of every stop. Stops without coordinates
     * are placed between their neighbours in proportion to the scheduled time (or to their order without times).
     */
    class Geometry(val route: GpsTrainRoute) {
        private val located: List<Int> = route.stops.indices.filter { route.stops[it].located }
        val positions: DoubleArray = DoubleArray(route.stops.size)
        private val segments: List<Pair<Int, Int>> = located.zipWithNext()

        init {
            var along = 0.0
            located.forEachIndexed { order, index ->
                if (order > 0) along += planarDistance(route.stops[located[order - 1]], route.stops[index])
                positions[index] = along
            }
            route.stops.indices.forEach { index ->
                if (route.stops[index].located) return@forEach
                val before = located.lastOrNull { it < index }
                val after = located.firstOrNull { it > index }
                positions[index] = when {
                    before == null && after == null -> 0.0
                    before == null -> positions[after!!]
                    after == null -> positions[before]
                    else -> positions[before] + (positions[after] - positions[before]) * fraction(before, index, after)
                }
            }
        }

        /** Every segment's nearest point to the fix, nearest first. */
        fun project(latitude: Double, longitude: Double): List<Projection> = segments.map { (from, to) ->
            val a = route.stops[from]
            val b = route.stops[to]
            val referenceLatitude = Math.toRadians((a.latitude!! + b.latitude!!) / 2)
            val bx = metresEast(a.longitude!!, b.longitude!!, referenceLatitude)
            val by = metresNorth(a.latitude, b.latitude)
            val px = metresEast(a.longitude, longitude, referenceLatitude)
            val py = metresNorth(a.latitude, latitude)
            val lengthSquared = bx * bx + by * by
            val t = if (lengthSquared <= 0.0) 0.0 else ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
            val dx = px - t * bx
            val dy = py - t * by
            Projection(positions[from] + (positions[to] - positions[from]) * t, sqrt(dx * dx + dy * dy))
        }.sortedBy { it.offsetMeters }

        /** The located stop the fix stands at, if it is within the dwell radius of one (the nearest). */
        fun stopAt(latitude: Double, longitude: Double): Int? = located
            .map { it to GpsArrivalAlarmPolicy.distanceMeters(latitude, longitude, route.stops[it].latitude!!, route.stops[it].longitude!!) }
            .filter { it.second <= DWELL_RADIUS_METERS }
            .minByOrNull { it.second }?.first

        /** The last stop at or behind [alongMeters]. */
        fun passedAt(alongMeters: Double): Int = route.stops.indices.lastOrNull { positions[it] <= alongMeters + 1e-6 } ?: 0

        private fun fraction(before: Int, index: Int, after: Int): Double {
            val start = route.stops[before].scheduledEpochMillis
            val time = route.stops[index].scheduledEpochMillis
            val end = route.stops[after].scheduledEpochMillis
            if (start != null && time != null && end != null && end > start) return ((time - start).toDouble() / (end - start)).coerceIn(0.0, 1.0)
            return (index - before).toDouble() / (after - before)
        }
    }

    /**
     * Follows one train with the fixes of a GPS session. Rejected fixes keep the last state (and its estimate), so a
     * tunnel or a poor fix never erases what is known; leaving the line clears progress and estimate.
     */
    class Tracker(val route: GpsTrainRoute) {
        private val geometry = Geometry(route)
        private var alongMeters: Double? = null
        var track: GpsTrainTrack = GpsTrainTrack.waiting(route.trainNumber)
            private set

        fun onFix(fix: GpsFix, nowEpochMillis: Long): GpsTrainTrack {
            if (!route.usable || !accepted(fix)) return track
            val projections = geometry.project(fix.latitude, fix.longitude)
            val nearest = projections.firstOrNull()
            if (nearest == null || nearest.offsetMeters > OFF_ROUTE_METERS) {
                alongMeters = null
                track = GpsTrainTrack(route.trainNumber, GpsTrackStatus.OFF_ROUTE, null, null, null, nowEpochMillis)
                return track
            }
            val previous = alongMeters
            val chosen = previous?.let { last ->
                projections.firstOrNull { it.offsetMeters <= OFF_ROUTE_METERS && it.alongMeters >= last - BACKTRACK_TOLERANCE_METERS }
            } ?: nearest
            val along = if (previous != null && chosen.alongMeters < previous && chosen.alongMeters >= previous - BACKTRACK_TOLERANCE_METERS) previous else chosen.alongMeters
            alongMeters = along
            val atStop = geometry.stopAt(fix.latitude, fix.longitude)
            val reached = atStop ?: geometry.passedAt(along)
            val passed = maxOf(reached, track.passedIndex?.takeIf { previous != null } ?: reached).coerceAtMost(route.targetIndex)
            val arrived = passed >= route.targetIndex
            // At the boarding stop the traveller may still be waiting on the platform: nothing to estimate yet.
            val waitingToBoard = passed == route.boardIndex && atStop == route.boardIndex
            val fresh = if (route.estimable && !waitingToBoard) estimate(route, geometry, along, atStop, passed, nowEpochMillis) else null
            track = GpsTrainTrack(
                trainNumber = route.trainNumber,
                status = if (arrived) GpsTrackStatus.ARRIVED else GpsTrackStatus.ON_ROUTE,
                passedIndex = passed,
                passedStop = route.stops[passed].name,
                // A stop without a scheduled time keeps the last estimate, which then shows its age.
                estimate = if (waitingToBoard) null else fresh ?: track.estimate,
                lastFixAtEpochMillis = nowEpochMillis,
                arrivedAtEpochMillis = if (arrived) track.arrivedAtEpochMillis ?: nowEpochMillis else null
            )
            return track
        }
    }

    /**
     * When the estimate session ends: [AFTER_ARRIVAL_MILLIS] after the arrival, i.e. after the device reached the
     * stop or else after the scheduled arrival plus the larger of the official and the estimated delay; never after
     * the target's hard expiry.
     */
    fun estimateEndsAt(target: GpsArrivalAlarmTarget, track: GpsTrainTrack?, officialDelayMinutes: Int?): Long {
        val delay = maxOf(officialDelayMinutes ?: 0, track?.estimate?.delayMinutes ?: 0, 0).coerceAtMost(MAX_DELAY_MINUTES)
        val arrival = track?.arrivedAtEpochMillis ?: (target.arrivalEpochMillis + delay * 60_000L)
        return minOf(arrival + AFTER_ARRIVAL_MILLIS, target.expiresAtEpochMillis)
    }

    /** The estimate can be started only while the trip is in progress: from shortly before the departure to the end above. */
    fun canStartEstimate(departureEpochMillis: Long, arrivalEpochMillis: Long, officialDelayMinutes: Int?, nowEpochMillis: Long): Boolean {
        val delay = (officialDelayMinutes ?: 0).coerceIn(0, MAX_DELAY_MINUTES) * 60_000L
        return nowEpochMillis >= departureEpochMillis - BEFORE_DEPARTURE_MILLIS && nowEpochMillis < arrivalEpochMillis + delay + AFTER_ARRIVAL_MILLIS
    }

    fun accepted(fix: GpsFix): Boolean {
        val accuracy = fix.accuracyMeters ?: return false
        return fix.latitude.isFinite() && fix.longitude.isFinite() && fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0 &&
            !accuracy.isNaN() && accuracy in 0.0..MAX_ACCURACY_METERS && fix.ageMillis <= GpsArrivalAlarmPolicy.MAX_FIX_AGE_MILLIS
    }

    /**
     * The delay at the projected point: standing at a stop, the time since its scheduled time; between stops, the
     * time since the scheduled time interpolated along the line between the neighbouring timed stops. Never negative.
     */
    internal fun estimate(route: GpsTrainRoute, geometry: Geometry, along: Double, atStop: Int?, passed: Int, nowEpochMillis: Long): GpsDelayEstimate? {
        val target = route.stops[route.targetIndex].scheduledEpochMillis ?: return null
        val scheduledHere = if (atStop != null) {
            route.stops[atStop].scheduledEpochMillis ?: return null
        } else {
            val before = (passed downTo 0).firstOrNull { route.stops[it].scheduledEpochMillis != null } ?: return null
            val after = (passed + 1..route.stops.lastIndex).firstOrNull { route.stops[it].scheduledEpochMillis != null } ?: return null
            val start = route.stops[before].scheduledEpochMillis!!
            val end = route.stops[after].scheduledEpochMillis!!
            val span = geometry.positions[after] - geometry.positions[before]
            val fraction = if (span <= 0.0) 0.0 else ((along - geometry.positions[before]) / span).coerceIn(0.0, 1.0)
            start + ((end - start) * fraction).toLong()
        }
        val minutes = ((nowEpochMillis - scheduledHere) / 60_000.0).roundToInt().coerceIn(0, MAX_DELAY_MINUTES)
        return GpsDelayEstimate(minutes, target + minutes * 60_000L, nowEpochMillis)
    }

    private fun planarDistance(from: GpsRouteStop, to: GpsRouteStop): Double {
        val referenceLatitude = Math.toRadians((from.latitude!! + to.latitude!!) / 2)
        val x = metresEast(from.longitude!!, to.longitude!!, referenceLatitude)
        val y = metresNorth(from.latitude, to.latitude)
        return sqrt(x * x + y * y)
    }

    private fun metresEast(fromLongitude: Double, toLongitude: Double, referenceLatitudeRadians: Double): Double =
        Math.toRadians(toLongitude - fromLongitude) * cos(referenceLatitudeRadians) * EARTH_MEAN_RADIUS_METERS

    private fun metresNorth(fromLatitude: Double, toLatitude: Double): Double =
        Math.toRadians(toLatitude - fromLatitude) * EARTH_MEAN_RADIUS_METERS
}
