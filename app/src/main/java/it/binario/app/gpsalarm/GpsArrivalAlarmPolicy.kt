package it.binario.app.gpsalarm

import it.binario.app.data.RailOperator
import it.binario.app.data.TrainCategory
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Which default radius a train gets. Long-distance and high-speed products cover several
 * kilometres a minute, so a radius that suits a regional train would ring when the train is
 * practically already in the station.
 */
internal enum class GpsAlarmTrainClass { LONG_DISTANCE, REGIONAL }

/** Whether the GPS option can be offered for a stop. */
internal enum class GpsAlarmAvailability { AVAILABLE, NO_COORDINATES, TOO_EARLY }

/**
 * One location fix as the service hands it to [GpsArrivalAlarmPolicy]. It lives only for the
 * duration of one evaluation: it is never stored, logged or sent anywhere.
 */
internal data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy radius in metres; null when the provider did not report one. */
    val accuracyMeters: Double?,
    /** How old the fix is, measured on the elapsed-realtime clock. */
    val ageMillis: Long
)

internal enum class GpsFixRejection { STALE, INACCURATE, INVALID }

internal sealed interface GpsFixDecision {
    data class Rejected(val reason: GpsFixRejection) : GpsFixDecision

    /**
     * Outside the radius. [remainingMeters] is the conservative distance still to travel before
     * the trigger boundary: distance minus accuracy minus the effective radius.
     */
    data class Approaching(val distanceMeters: Double, val remainingMeters: Double) : GpsFixDecision

    data class Arrived(val distanceMeters: Double) : GpsFixDecision
}

/**
 * Pure decision rules of the GPS arrival alarm ("Sveglia GPS"), kept free of Android types so
 * they are unit-tested on the JVM. The foreground service only feeds fixes in and acts on the
 * decisions.
 */
internal object GpsArrivalAlarmPolicy {
    val RADIUS_OPTIONS_METERS: List<Int> = listOf(1_000, 2_000, 3_000, 5_000, 10_000, 15_000, 20_000, 25_000)
    const val REGIONAL_DEFAULT_RADIUS_METERS = 2_000
    const val LONG_DISTANCE_DEFAULT_RADIUS_METERS = 20_000

    /**
     * Added to the chosen radius when only approximate location is granted: Android then serves
     * coarsened fixes and throttles their rate, so the alarm must look further ahead. The
     * effective radius is therefore never smaller than the chosen one.
     */
    const val APPROXIMATE_EXTRA_RADIUS_METERS = 3_000

    const val MAX_FIX_AGE_MILLIS = 2 * 60_000L
    const val MAX_ACCURACY_METERS = 5_000.0

    /** The service stops at the latest this long after the expected arrival... */
    const val AFTER_ARRIVAL_MILLIS = 2 * 60 * 60_000L

    /** ...and never runs longer than this, whatever the arrival time. */
    const val MAX_DURATION_MILLIS = 8 * 60 * 60_000L

    /** 300 km/h: the fastest service the alarm has to keep up with (about 5 km per minute). */
    const val MAX_TRAIN_SPEED_METERS_PER_SECOND = 300_000.0 / 3_600.0

    /** The next fix is requested after at most half the time the fastest train needs to reach the boundary. */
    const val INTERVAL_SAFETY_FACTOR = 0.5
    const val MIN_INTERVAL_MILLIS = 10_000L
    const val MAX_INTERVAL_MILLIS = 120_000L

    /** Intervals are rounded down to this step so the location request is not re-registered on every fix. */
    const val INTERVAL_STEP_MILLIS = 5_000L

    const val GPS_PROVIDER = "gps"
    const val NETWORK_PROVIDER = "network"

    private const val EARTH_MEAN_RADIUS_METERS = 6_371_008.8
    private const val ANDROID_S = 31

    /** Operators that only run long-distance products in the app's sources when the category is unknown. */
    private val LONG_DISTANCE_OPERATORS = setOf(
        RailOperator.TRENITALIA_AV,
        RailOperator.ITALO,
        RailOperator.EUROPEAN_SLEEPER,
        RailOperator.DB,
        RailOperator.OEBB
    )

    /**
     * Classifies a train with the app's existing category/operator model. The category wins
     * because [RailOperator.TRENITALIA_LONG_DISTANCE] is also assigned to regional Trenitalia
     * GTFS routes; the operator only decides when the category is unknown.
     */
    fun trainClass(category: TrainCategory, operator: RailOperator): GpsAlarmTrainClass = when (category) {
        TrainCategory.FRECCE, TrainCategory.AV_ITALO, TrainCategory.INTERCITY -> GpsAlarmTrainClass.LONG_DISTANCE
        TrainCategory.REGIONALE, TrainCategory.REGIONALE_VELOCE, TrainCategory.TRENORD_REGIO,
        TrainCategory.METROPOLITANO -> GpsAlarmTrainClass.REGIONAL
        TrainCategory.OTHER -> if (operator in LONG_DISTANCE_OPERATORS) GpsAlarmTrainClass.LONG_DISTANCE else GpsAlarmTrainClass.REGIONAL
    }

    fun defaultRadiusMeters(trainClass: GpsAlarmTrainClass): Int = when (trainClass) {
        GpsAlarmTrainClass.LONG_DISTANCE -> LONG_DISTANCE_DEFAULT_RADIUS_METERS
        GpsAlarmTrainClass.REGIONAL -> REGIONAL_DEFAULT_RADIUS_METERS
    }

    /** The remembered choice for the class when it is still a valid option, otherwise the class default. */
    fun initialRadiusMeters(trainClass: GpsAlarmTrainClass, remembered: Int?): Int =
        remembered?.takeIf(::isRadiusOption) ?: defaultRadiusMeters(trainClass)

    fun isRadiusOption(radiusMeters: Int): Boolean = radiusMeters in RADIUS_OPTIONS_METERS

    fun effectiveRadiusMeters(radiusMeters: Int, precise: Boolean): Int =
        if (precise) radiusMeters else radiusMeters + APPROXIMATE_EXTRA_RADIUS_METERS

    /** Great-circle (haversine) distance on the mean Earth radius. */
    fun distanceMeters(fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double): Double {
        val lat1 = Math.toRadians(fromLatitude)
        val lat2 = Math.toRadians(toLatitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(toLongitude - fromLongitude)
        val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_MEAN_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * The trigger rule: a fresh, accurate enough fix rings the alarm when the distance to the
     * station minus the fix accuracy is within the effective radius, i.e. when the user may
     * already be inside it.
     */
    fun evaluate(fix: GpsFix, stationLatitude: Double, stationLongitude: Double, effectiveRadiusMeters: Int): GpsFixDecision {
        if (!isCoordinate(fix.latitude, fix.longitude)) return GpsFixDecision.Rejected(GpsFixRejection.INVALID)
        if (fix.ageMillis > MAX_FIX_AGE_MILLIS) return GpsFixDecision.Rejected(GpsFixRejection.STALE)
        val accuracy = fix.accuracyMeters
        if (accuracy == null || accuracy.isNaN() || accuracy < 0.0 || accuracy > MAX_ACCURACY_METERS) {
            return GpsFixDecision.Rejected(GpsFixRejection.INACCURATE)
        }
        val distance = distanceMeters(fix.latitude, fix.longitude, stationLatitude, stationLongitude)
        val remaining = distance - accuracy - effectiveRadiusMeters
        return if (remaining <= 0.0) GpsFixDecision.Arrived(distance) else GpsFixDecision.Approaching(distance, remaining)
    }

    /**
     * Next location request interval: half the time a 300 km/h train needs to cover the distance
     * left to the trigger boundary, clamped to 10..120 s and rounded down to a 5 s step. Basing it
     * on the boundary instead of the station means a large radius is never overshot between two
     * fixes. Without a usable fix yet the shortest interval is used.
     */
    fun nextIntervalMillis(remainingMeters: Double?): Long {
        if (remainingMeters == null || remainingMeters.isNaN()) return MIN_INTERVAL_MILLIS
        val seconds = remainingMeters.coerceAtLeast(0.0) / MAX_TRAIN_SPEED_METERS_PER_SECOND * INTERVAL_SAFETY_FACTOR
        val millis = min(seconds * 1_000.0, MAX_INTERVAL_MILLIS.toDouble()).toLong()
        return (millis / INTERVAL_STEP_MILLIS * INTERVAL_STEP_MILLIS).coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS)
    }

    /** When the service must stop: two hours after the expected arrival, capped at eight hours from the start. */
    fun expiresAtEpochMillis(startedAtEpochMillis: Long, arrivalEpochMillis: Long): Long =
        min(arrivalEpochMillis + AFTER_ARRIVAL_MILLIS, startedAtEpochMillis + MAX_DURATION_MILLIS)

    fun isExpired(nowEpochMillis: Long, target: GpsArrivalAlarmTarget): Boolean =
        nowEpochMillis >= target.expiresAtEpochMillis

    /**
     * The GPS option needs the station coordinates and an arrival the eight-hour cap still
     * covers; an alarm for tomorrow's train would otherwise expire before the train arrives.
     */
    fun availability(nowEpochMillis: Long, arrivalEpochMillis: Long, hasCoordinates: Boolean): GpsAlarmAvailability = when {
        !hasCoordinates -> GpsAlarmAvailability.NO_COORDINATES
        arrivalEpochMillis - nowEpochMillis > MAX_DURATION_MILLIS -> GpsAlarmAvailability.TOO_EARLY
        else -> GpsAlarmAvailability.AVAILABLE
    }

    /** The eight-hour cap expressed in whole hours, for the "too early" explanation. */
    val maxDurationHours: Int get() = (MAX_DURATION_MILLIS / (60 * 60_000L)).toInt()

    /**
     * Providers to listen to. Precise location uses GPS and the network provider (the latter helps
     * in tunnels and stations). With approximate location only the network provider is used; from
     * Android 12 the platform also serves coarsened GPS fixes, which is the fallback on devices
     * without a network provider. Before Android 12 the GPS provider requires precise location.
     */
    fun locationProviders(precise: Boolean, sdkInt: Int, existingProviders: Collection<String>): List<String> {
        val wanted = when {
            precise -> listOf(GPS_PROVIDER, NETWORK_PROVIDER)
            NETWORK_PROVIDER in existingProviders -> listOf(NETWORK_PROVIDER)
            sdkInt >= ANDROID_S -> listOf(GPS_PROVIDER)
            else -> emptyList()
        }
        return wanted.filter { it in existingProviders }
    }

    private fun isCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}
