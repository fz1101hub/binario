package it.binario.app.maps

import it.binario.app.data.RailTime
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.gtfs.GtfsStationCodeIndex
import it.binario.app.data.journey.EmbeddedGtfsJourneyProvider
import it.binario.app.data.journey.ScheduledRailRun
import it.binario.app.data.journey.ScheduledRunRequest
import it.binario.app.data.journey.ScheduledRunStop
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.cos

// Estimated positions of trains in motion. Binario has no vehicle positions: a train is placed where its timetable
// puts it at the current time, shifted by the live delay when the app already has one for that run, on a straight
// line between the two stops around that time (there is no track geometry). Never a GPS position.

/** Where a run is on its timetable at one moment; stop indices refer to the stops the progress was computed on. */
sealed interface RunProgress {
    /** Before the departure from the first stop. */
    data object NotStarted : RunProgress

    /** Stopped at a station (from its arrival to its departure). */
    data class AtStop(val stop: Int) : RunProgress

    /** Between two consecutive stops; [fraction] of the scheduled travel time already run (0..1). */
    data class Between(val from: Int, val to: Int, val fraction: Double) : RunProgress

    /** After the arrival at the last stop. */
    data object Arrived : RunProgress
}

private fun ScheduledRunStop.point(): GeoPoint? {
    val latitude = latitude ?: return null
    val longitude = longitude ?: return null
    return GeoPoint(latitude, longitude).takeIf { it.isValid }
}

internal object LiveTrainEstimator {
    /**
     * Progress of a run whose stops are [stops] at [minute] of its service day (minutes from the service day's midnight,
     * fractional, 24:00+ after midnight). The first stop counts from its departure, the last one until its arrival.
     */
    fun progress(stops: List<ScheduledRunStop>, minute: Double): RunProgress {
        if (stops.isEmpty() || !minute.isFinite()) return RunProgress.NotStarted
        if (minute < stops.first().departureMinute) return RunProgress.NotStarted
        if (minute > stops.last().arrivalMinute) return RunProgress.Arrived
        for (index in stops.indices) {
            val stop = stops[index]
            if (minute >= stop.arrivalMinute && minute <= stop.departureMinute) return RunProgress.AtStop(index)
            val next = stops.getOrNull(index + 1) ?: break
            if (minute > stop.departureMinute && minute < next.arrivalMinute) {
                val span = (next.arrivalMinute - stop.departureMinute).toDouble()
                return RunProgress.Between(index, index + 1, ((minute - stop.departureMinute) / span).coerceIn(0.0, 1.0))
            }
        }
        // Only a timetable whose times go back gets here: such a run is not placed.
        return RunProgress.NotStarted
    }

    /**
     * The estimated point at [minute]: a located stop when the run is stopped there, otherwise the straight line
     * between the located stops before and after, in proportion to the scheduled time. Stops without coordinates are
     * bridged by their located neighbours; null when the run is not under way between two located stops.
     */
    fun position(stops: List<ScheduledRunStop>, minute: Double): GeoPoint? {
        val located = stops.mapNotNull { stop -> stop.point()?.let { stop to it } }
        return when (val progress = progress(located.map { it.first }, minute)) {
            is RunProgress.AtStop -> located[progress.stop].second
            is RunProgress.Between -> interpolate(located[progress.from].second, located[progress.to].second, progress.fraction)
            RunProgress.NotStarted, RunProgress.Arrived -> null
        }
    }

    /** Linear in latitude and longitude: a straight segment at the scale of two neighbouring stations. */
    fun interpolate(from: GeoPoint, to: GeoPoint, fraction: Double): GeoPoint {
        val share = fraction.coerceIn(0.0, 1.0)
        return GeoPoint(
            from.latitude + (to.latitude - from.latitude) * share,
            from.longitude + (to.longitude - from.longitude) * share
        )
    }

    /** Minutes (with seconds as a fraction) from the midnight of [serviceDate] to [now], both railway-local. */
    fun minuteOfServiceDay(serviceDate: LocalDate, now: LocalDateTime): Double =
        Duration.between(serviceDate.atStartOfDay(), now).seconds / 60.0
}

/**
 * A run the app has already loaded live data for: a service of the route board or a row of the station board. Nothing
 * is requested for the map: the observations come from the data on screen, refreshed by the board's own cadence.
 */
data class LiveRunObservation(
    /** Public train number as the source shows it. */
    val number: String,
    /** Scheduled time of the run where the observation is (the board's station), railway-local. */
    val scheduledAt: LocalDateTime,
    /** Delay the source published for the run, in minutes; null when it published none (scheduled data). */
    val delayMinutes: Int?,
    /** The source says the run is cancelled: it is not drawn as moving. */
    val cancelled: Boolean = false,
    /** Position of the row in the caller's list, so a tap opens that row's detail. */
    val ref: Int = -1
)

/** The observations the boards already hold, in the order of their rows. */
internal object LiveTrainObservations {
    /**
     * Services of the route board: the published delay only when the source verified it ([liveTrusted] false for
     * demonstration data, which never moves a train). The scheduled departure is the board's departure station.
     */
    fun fromServices(services: List<TrainService>, liveTrusted: Boolean, today: LocalDate): List<LiveRunObservation> =
        services.mapIndexedNotNull { index, service ->
            val scheduledAt = service.scheduledDepartureDateTime
                ?: runCatching { LocalDateTime.of(today, LocalTime.parse(service.scheduledDeparture.trim())) }.getOrNull()
                ?: return@mapIndexedNotNull null
            val live = liveTrusted && service.operationalStatusVerified
            LiveRunObservation(
                number = service.number,
                scheduledAt = scheduledAt,
                delayMinutes = service.delayMinutes.takeIf { live },
                cancelled = live && service.status == ServiceStatus.CANCELLED,
                ref = index
            )
        }

    /**
     * The row of [current] (the board as it is now) that [observation] was read from: the same number and scheduled
     * time. The board may have been refreshed, and its rows reordered, since the positions were computed.
     */
    fun rowOf(observation: LiveRunObservation, current: List<LiveRunObservation>): Int? =
        current.firstOrNull { it.number == observation.number && it.scheduledAt == observation.scheduledAt }?.ref

    /** Rows of a station board: the delay is the expected time minus the scheduled one, when the source gave one. */
    fun fromBoard(entries: List<StationBoardEntry>): List<LiveRunObservation> =
        entries.mapIndexedNotNull { index, entry ->
            val number = entry.publicNumber?.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
            LiveRunObservation(
                number = number,
                scheduledAt = entry.scheduledTime.withZoneSameInstant(RailTime.defaultZone).toLocalDateTime(),
                delayMinutes = entry.expectedTime?.value?.let { Duration.between(entry.scheduledTime, it).toMinutes().toInt() },
                cancelled = entry.status?.value == RunStatus.CANCELLED,
                ref = index
            )
        }
}

/** How a position was estimated, as the legend tells. */
enum class LiveTrainEstimate {
    /** From the timetable alone. */
    TIMETABLE,

    /** From the timetable shifted by the live delay the app already had for the run. */
    LIVE_DELAY
}

/** One train drawn on the map and listed under it. */
data class LiveTrain(
    val run: ScheduledRailRun,
    val point: GeoPoint,
    /** Progress on [ScheduledRailRun.stops] (indices into that list). */
    val progress: RunProgress,
    /** Live delay applied to the timetable, null when the estimate comes from the timetable alone. */
    val delayMinutes: Int?,
    /** The loaded board row the run matched, if any. */
    val observation: LiveRunObservation?,
    /** Straight-line distance from the map's station, for the list order. */
    val distanceKm: Double
) {
    val estimate: LiveTrainEstimate get() = if (delayMinutes != null) LiveTrainEstimate.LIVE_DELAY else LiveTrainEstimate.TIMETABLE

    /** Name of the stop the train is at, or null between stops. */
    val stopName: String? get() = (progress as? RunProgress.AtStop)?.let { run.stops.getOrNull(it.stop)?.name }

    /** The two stops around the train when it is between stops. */
    val between: Pair<String, String>? get() = (progress as? RunProgress.Between)?.let { segment ->
        val from = run.stops.getOrNull(segment.from)?.name ?: return@let null
        val to = run.stops.getOrNull(segment.to)?.name ?: return@let null
        from to to
    }

    val destination: String get() = run.stops.last().name
}

/** The trains of one refresh: [trains] (at most the cap, nearest first) out of [inBounds] placed in the area. */
data class LiveTrainSelection(val trains: List<LiveTrain>, val inBounds: Int) {
    val capped: Boolean get() = inBounds > trains.size
}

fun GeoBounds.contains(point: GeoPoint): Boolean =
    point.isValid && point.latitude in south..north && point.longitude in west..east

internal object LiveTrainSelector {
    /** Markers drawn at most: the nearest to the map's station. */
    const val MAX_TRAINS = 40

    /** A live delay outside this range is not applied (early trains by more than this are not credible). */
    const val MIN_APPLIED_DELAY = -30
    const val MAX_APPLIED_DELAY = ScheduledRunRequest.DEFAULT_LOOK_BACK_MINUTES

    /** Two copies of one train (two packs publish it) closer than this are one train. */
    const val SAME_TRAIN_KM = 5.0

    /** An observation belongs to a run when its time falls within the run's scheduled span, with this margin. */
    private const val MATCH_MARGIN_MINUTES = 2L

    /**
     * The runs of [runs] under way at [now] (railway-local) whose estimated point is inside [bounds]: delayed by the
     * matching [observations], copies of one train merged, nearest to [focus] first, at most [cap].
     */
    fun select(
        runs: List<ScheduledRailRun>,
        observations: List<LiveRunObservation>,
        now: LocalDateTime,
        bounds: GeoBounds,
        focus: GeoPoint,
        cap: Int = MAX_TRAINS
    ): LiveTrainSelection {
        val byNumber = observations.groupBy { numberKey(it.number) }
        val placed = ArrayList<LiveTrain>()
        for (run in runs) {
            if (run.stops.size < 2) continue
            val observation = match(run, byNumber)
            if (observation?.cancelled == true) continue
            val delay = observation?.delayMinutes?.takeIf { it in MIN_APPLIED_DELAY..MAX_APPLIED_DELAY }
            val minute = LiveTrainEstimator.minuteOfServiceDay(run.serviceDate, now) - (delay ?: 0)
            val progress = LiveTrainEstimator.progress(run.stops, minute)
            if (progress == RunProgress.NotStarted || progress == RunProgress.Arrived) continue
            val point = LiveTrainEstimator.position(run.stops, minute) ?: continue
            if (!bounds.contains(point)) continue
            placed += LiveTrain(
                run = run,
                point = point,
                progress = progress,
                delayMinutes = delay,
                observation = observation,
                distanceKm = GtfsStationCodeIndex.distanceKm(focus.latitude, focus.longitude, point.latitude, point.longitude)
            )
        }
        val trains = merged(placed).sortedWith(compareBy<LiveTrain> { it.distanceKm }.thenBy { it.run.id })
        return LiveTrainSelection(trains.take(cap.coerceAtLeast(0)), trains.size)
    }

    /**
     * The observation of [run]: same number, and a time inside the run's scheduled span from its true origin (so not
     * yesterday's train, and a night train seen at its origin before the stops the map read).
     */
    fun match(run: ScheduledRailRun, byNumber: Map<String?, List<LiveRunObservation>>): LiveRunObservation? {
        val key = run.publicNumber?.let(::numberKey) ?: return null
        val candidates = byNumber[key] ?: return null
        val midnight = run.serviceDate.atStartOfDay()
        val start = midnight.plusMinutes(run.originDepartureMinute - MATCH_MARGIN_MINUTES)
        val end = midnight.plusMinutes(run.lastArrivalMinute + MATCH_MARGIN_MINUTES)
        val inSpan = candidates.filter { !it.scheduledAt.isBefore(start) && !it.scheduledAt.isAfter(end) }
        return inSpan.firstOrNull { it.cancelled } ?: inSpan.firstOrNull { it.delayMinutes != null } ?: inSpan.firstOrNull()
    }

    /** Copies of one train in two packs: the one with a live delay, then the one with more located stops, wins. */
    private fun merged(trains: List<LiveTrain>): List<LiveTrain> {
        val preferred = trains.sortedWith(
            compareBy<LiveTrain> { if (it.delayMinutes != null) 0 else 1 }
                .thenByDescending { train -> train.run.stops.count { it.latitude != null && it.longitude != null } }
                .thenBy { it.run.id }
        )
        val kept = ArrayList<LiveTrain>()
        for (train in preferred) {
            val key = train.run.publicNumber?.let(::numberKey)
            val duplicate = key != null && kept.any { other ->
                other.run.serviceDate == train.run.serviceDate && other.run.publicNumber?.let(::numberKey) == key &&
                    GtfsStationCodeIndex.distanceKm(other.point.latitude, other.point.longitude, train.point.latitude, train.point.longitude) < SAME_TRAIN_KM
            }
            if (!duplicate) kept += train
        }
        return kept
    }

    private fun numberKey(number: String): String? = EmbeddedGtfsJourneyProvider.runNumberKey(number)
}

/** The text drawn next to a marker: category code, number and delay, in characters every map font has. */
internal object LiveTrainLabels {
    private const val MAX_CODE = 6

    /** "REG 2153 +5'", "FR 9558 0'", "RE11 2153" (no delay known). */
    fun marker(train: LiveTrain): String = listOfNotNull(
        title(train.run),
        train.delayMinutes?.let(::delay)
    ).joinToString(" ")

    /** Category code and number: "REG 2153"; the number alone when no code is known. */
    fun title(run: ScheduledRailRun): String {
        val number = run.publicNumber.orEmpty()
        val code = run.lineLabel?.takeIf { it.length <= MAX_CODE && !it.equals(number, ignoreCase = true) }
            ?: categoryCode(run.category)
        return listOf(code, number).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** "+5'", "-2'" or "0'": a machine value, never formatted with the device locale. */
    fun delay(minutes: Int): String = when {
        minutes > 0 -> "+$minutes'"
        minutes < 0 -> "-${-minutes}'"
        else -> "0'"
    }

    private fun categoryCode(category: TrainCategory): String = when (category) {
        TrainCategory.REGIONALE, TrainCategory.TRENORD_REGIO -> "R"
        TrainCategory.REGIONALE_VELOCE -> "RV"
        TrainCategory.INTERCITY -> "IC"
        TrainCategory.FRECCE -> "FR"
        TrainCategory.AV_ITALO -> "ITA"
        TrainCategory.METROPOLITANO -> "S"
        TrainCategory.OTHER -> ""
    }
}

/** The cadence of the map: positions every 30 seconds, the timetable once per quarter of an hour. */
internal object LiveTrainRefresh {
    const val POSITIONS_MILLIS = 30_000L
    private const val SLOT_MINUTES = 15

    /** Start of the quarter hour of [now]: the runs read then cover the whole quarter (see [ScheduledRunRequest]). */
    fun slotStart(now: LocalDateTime): LocalDateTime =
        now.truncatedTo(ChronoUnit.MINUTES).withMinute(now.minute / SLOT_MINUTES * SLOT_MINUTES)
}

/** The area a station's map of trains covers, and when its timetable must be read again. */
internal object LiveTrainAreas {
    /** Around a station: about 15 km in every direction. */
    const val STATION_RADIUS_KM = 15.0

    /** Wider than this (degrees of latitude or longitude) the area map asks to zoom in instead of reading timetables. */
    const val MAX_LATITUDE_SPAN = 1.2
    const val MAX_LONGITUDE_SPAN = 1.8

    /** Grid of the read area, so small pans reuse the runs already read. */
    private const val SNAP_DEGREES = 0.25

    fun around(point: GeoPoint, radiusKm: Double = STATION_RADIUS_KM): GeoBounds {
        val latitudeDelta = radiusKm / KM_PER_DEGREE
        val longitudeDelta = radiusKm / (KM_PER_DEGREE * cos(Math.toRadians(point.latitude)).coerceAtLeast(0.1))
        return GeoBounds(
            north = (point.latitude + latitudeDelta).coerceAtMost(85.0),
            south = (point.latitude - latitudeDelta).coerceAtLeast(-85.0),
            east = (point.longitude + longitudeDelta).coerceAtMost(180.0),
            west = (point.longitude - longitudeDelta).coerceAtLeast(-180.0)
        )
    }

    /** The first area of a route's map: the box of its two ends with a margin, as the fitted camera shows it. */
    fun routeArea(origin: GeoPoint, destination: GeoPoint): GeoBounds {
        val latitudeMargin = maxOf(abs(origin.latitude - destination.latitude) * ROUTE_MARGIN_SHARE, MIN_ROUTE_MARGIN)
        val longitudeMargin = maxOf(abs(origin.longitude - destination.longitude) * ROUTE_MARGIN_SHARE, MIN_ROUTE_MARGIN)
        return GeoBounds(
            north = (maxOf(origin.latitude, destination.latitude) + latitudeMargin).coerceAtMost(85.0),
            south = (minOf(origin.latitude, destination.latitude) - latitudeMargin).coerceAtLeast(-85.0),
            east = (maxOf(origin.longitude, destination.longitude) + longitudeMargin).coerceAtMost(180.0),
            west = (minOf(origin.longitude, destination.longitude) - longitudeMargin).coerceAtLeast(-180.0)
        )
    }

    fun tooWide(bounds: GeoBounds): Boolean =
        bounds.north - bounds.south > MAX_LATITUDE_SPAN || bounds.east - bounds.west > MAX_LONGITUDE_SPAN

    /** The area whose runs are read for [visible]: half a span more on every side, snapped outwards to the grid. */
    fun readArea(visible: GeoBounds): GeoBounds {
        val latitudeMargin = (visible.north - visible.south) / 2
        val longitudeMargin = (visible.east - visible.west) / 2
        return GeoBounds(
            north = snapUp(visible.north + latitudeMargin).coerceAtMost(85.0),
            south = snapDown(visible.south - latitudeMargin).coerceAtLeast(-85.0),
            east = snapUp(visible.east + longitudeMargin).coerceAtMost(180.0),
            west = snapDown(visible.west - longitudeMargin).coerceAtLeast(-180.0)
        )
    }

    /** True when [read] still holds every train that can be inside [visible]. */
    fun covers(read: GeoBounds, visible: GeoBounds): Boolean =
        visible.north <= read.north && visible.south >= read.south && visible.east <= read.east && visible.west >= read.west

    private fun snapUp(value: Double): Double = Math.ceil(value / SNAP_DEGREES) * SNAP_DEGREES
    private fun snapDown(value: Double): Double = Math.floor(value / SNAP_DEGREES) * SNAP_DEGREES

    private const val KM_PER_DEGREE = 111.32
    private const val ROUTE_MARGIN_SHARE = 0.2
    private const val MIN_ROUTE_MARGIN = 0.05
}
