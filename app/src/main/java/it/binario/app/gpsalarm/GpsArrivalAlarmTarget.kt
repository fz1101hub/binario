package it.binario.app.gpsalarm

import it.binario.app.PreferenceValueCodec
import it.binario.app.StopAlarmPlan
import it.binario.app.data.RailStopNameMatcher
import it.binario.app.data.Station
import it.binario.app.data.StationSearchNormalizer

/** A catalogue station with coordinates, as the GPS alarm needs it. */
internal data class GpsAlarmStation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double
)

/**
 * What a session of the location service is for: the GPS arrival alarm, which rings near the stop,
 * or the position estimate of a followed trip ("Stima dalla tua posizione"), which never rings and
 * ends shortly after the arrival.
 */
internal enum class GpsSessionMode { ALARM, ESTIMATE }

/**
 * What one active GPS arrival alarm watches. It holds only timetable and catalogue data (train,
 * stop, catalogue station and its coordinates, radius, times, and the train's route as stop names,
 * catalogue coordinates and scheduled times); it never contains a position of the user. This is the
 * only GPS-alarm state that is persisted, so that the app can offer to re-enable an alarm the
 * system interrupted (reboot, process death).
 */
internal data class GpsArrivalAlarmTarget(
    val trainNumber: String,
    val trainDestination: String,
    /** The stop name as the timetable shows it; the time-based alarm is keyed on it. */
    val stopName: String,
    val stationId: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val expectedTime: String,
    val arrivalEpochMillis: Long,
    /** Trigger of the time-based stop alarm for the same stop, so the GPS alarm can cancel it. */
    val timeAlarmTriggerEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val mode: GpsSessionMode = GpsSessionMode.ALARM,
    /** The train's stops up to [stopName], for the stop countdown and the position estimate; null when unknown. */
    val route: GpsTrainRoute? = null
) {
    val isEstimate: Boolean get() = mode == GpsSessionMode.ESTIMATE

    fun isFor(trainNumber: String, stopName: String): Boolean =
        this.trainNumber == trainNumber && this.stopName == stopName

    /** The time-based alarm this GPS alarm accompanies, for cancelling or re-scheduling it. */
    fun timeAlarmPlan(): StopAlarmPlan = StopAlarmPlan(
        trainNumber = trainNumber,
        destination = trainDestination,
        station = stopName,
        expectedTime = expectedTime,
        triggerAtEpochMillis = timeAlarmTriggerEpochMillis,
        expectedAtEpochMillis = arrivalEpochMillis
    )

    companion object {
        /** A target for [plan]; the expiry is fixed when the service actually starts. */
        fun from(plan: StopAlarmPlan, station: GpsAlarmStation, radiusMeters: Int): GpsArrivalAlarmTarget = GpsArrivalAlarmTarget(
            trainNumber = plan.trainNumber,
            trainDestination = plan.destination,
            stopName = plan.station,
            stationId = station.id,
            latitude = station.latitude,
            longitude = station.longitude,
            radiusMeters = radiusMeters,
            expectedTime = plan.expectedTime,
            arrivalEpochMillis = plan.expectedAtEpochMillis,
            timeAlarmTriggerEpochMillis = plan.triggerAtEpochMillis,
            expiresAtEpochMillis = plan.expectedAtEpochMillis + GpsArrivalAlarmPolicy.AFTER_ARRIVAL_MILLIS
        )

        /**
         * The estimate session of a followed train ("Stima dalla tua posizione") on [route], which must be
         * estimable. It never rings; its hard expiry is the alarm's, and it ends earlier once the train has
         * arrived ([GpsTrainTracking.estimateEndsAt]).
         */
        fun estimate(route: GpsTrainRoute, trainDestination: String, nowEpochMillis: Long): GpsArrivalAlarmTarget? {
            if (!route.estimable) return null
            val stop = route.stops[route.targetIndex]
            val place = stop.takeIf { it.located } ?: route.stops.last { it.located }
            val arrival = stop.scheduledEpochMillis ?: return null
            return GpsArrivalAlarmTarget(
                trainNumber = route.trainNumber,
                trainDestination = trainDestination,
                stopName = stop.name,
                stationId = place.name,
                latitude = place.latitude ?: return null,
                longitude = place.longitude ?: return null,
                radiusMeters = 0,
                expectedTime = GpsTrainRoutes.clockLabel(arrival),
                arrivalEpochMillis = arrival,
                timeAlarmTriggerEpochMillis = arrival,
                expiresAtEpochMillis = GpsArrivalAlarmPolicy.expiresAtEpochMillis(nowEpochMillis, arrival),
                mode = GpsSessionMode.ESTIMATE,
                route = route
            )
        }
    }
}

/**
 * Versioned, delimiter-separated encoding of [GpsArrivalAlarmTarget] for the service Intent and
 * the private preferences. Text fields are Base64 so they may contain the delimiter; anything
 * malformed decodes to null instead of starting a bogus alarm. An alarm without a route keeps the
 * original `gps1` form; `gps2` adds the session mode and the route.
 */
internal object GpsArrivalAlarmTargetCodec {
    private const val VERSION = "gps1"
    private const val VERSION_ROUTE = "gps2"
    private const val SEPARATOR = "|"
    private const val FIELD_COUNT = 12
    private const val FIELD_COUNT_ROUTE = 14

    fun encode(target: GpsArrivalAlarmTarget): String {
        val base = encodeBase(target)
        if (target.mode == GpsSessionMode.ALARM && target.route == null) return base
        val route = target.route?.let { PreferenceValueCodec.encode(GpsTrainRoutes.encode(it)) }.orEmpty()
        return (listOf(VERSION_ROUTE) + base.split(SEPARATOR).drop(1) + listOf(target.mode.name, route)).joinToString(SEPARATOR)
    }

    private fun encodeBase(target: GpsArrivalAlarmTarget): String = listOf(
        VERSION,
        PreferenceValueCodec.encode(target.trainNumber),
        PreferenceValueCodec.encode(target.trainDestination),
        PreferenceValueCodec.encode(target.stopName),
        PreferenceValueCodec.encode(target.stationId),
        target.latitude.toString(),
        target.longitude.toString(),
        target.radiusMeters.toString(),
        PreferenceValueCodec.encode(target.expectedTime),
        target.arrivalEpochMillis.toString(),
        target.timeAlarmTriggerEpochMillis.toString(),
        target.expiresAtEpochMillis.toString()
    ).joinToString(SEPARATOR)

    fun decode(value: String?): GpsArrivalAlarmTarget? {
        val parts = value?.split(SEPARATOR) ?: return null
        if (parts.size == FIELD_COUNT_ROUTE && parts[0] == VERSION_ROUTE) {
            val mode = runCatching { GpsSessionMode.valueOf(parts[12]) }.getOrNull() ?: return null
            val route = parts[13].takeIf(String::isNotEmpty)?.let { GpsTrainRoutes.decode(PreferenceValueCodec.decode(it)) ?: return null }
            if (mode == GpsSessionMode.ESTIMATE && route?.estimable != true) return null
            return decodeBase(listOf(VERSION) + parts.subList(1, FIELD_COUNT), mode)?.copy(mode = mode, route = route)
        }
        return decodeBase(parts, GpsSessionMode.ALARM)
    }

    private fun decodeBase(parts: List<String>, mode: GpsSessionMode): GpsArrivalAlarmTarget? {
        if (parts.size != FIELD_COUNT || parts[0] != VERSION) return null
        val train = PreferenceValueCodec.decode(parts[1])?.takeIf(String::isNotBlank) ?: return null
        val destination = PreferenceValueCodec.decode(parts[2]) ?: return null
        val stop = PreferenceValueCodec.decode(parts[3])?.takeIf(String::isNotBlank) ?: return null
        val stationId = PreferenceValueCodec.decode(parts[4])?.takeIf(String::isNotBlank) ?: return null
        val latitude = parts[5].toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val longitude = parts[6].toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        // The estimate session has no radius: it never rings.
        val radius = parts[7].toIntOrNull()?.takeIf { if (mode == GpsSessionMode.ESTIMATE) it == 0 else GpsArrivalAlarmPolicy.isRadiusOption(it) } ?: return null
        val expected = PreferenceValueCodec.decode(parts[8]) ?: return null
        val arrival = parts[9].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val trigger = parts[10].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val expires = parts[11].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return GpsArrivalAlarmTarget(train, destination, stop, stationId, latitude, longitude, radius, expected, arrival, trigger, expires)
    }
}

/**
 * Finds the catalogue coordinates of a timetable stop. Among stations with coordinates an exact
 * normalised name or alias wins; otherwise the strict whole-name matcher used for operator
 * timetables decides. Candidates that disagree by more than [SAME_PLACE_METERS] are ambiguous,
 * and then the GPS option is not offered rather than watching the wrong station.
 */
internal object GpsArrivalAlarmStations {
    private const val SAME_PLACE_METERS = 1_000.0

    fun resolve(stopName: String, stations: List<Station>): GpsAlarmStation? = Index(stations).resolve(stopName)

    /** The stops of a whole route at once: the catalogue is indexed a single time. */
    fun resolveAll(stopNames: List<String>, stations: List<Station>): List<GpsAlarmStation?> {
        val index = Index(stations)
        val resolved = HashMap<String, GpsAlarmStation?>()
        return stopNames.map { name -> resolved.getOrPut(name) { index.resolve(name) } }
    }

    private class Index(stations: List<Station>) {
        // Only catalogue entries with coordinates can be watched; a user-added station without
        // them must not hide the curated entry that names the same stop.
        private val located: List<Pair<Station, GpsAlarmStation>> = stations.mapNotNull { station -> located(station)?.let { station to it } }
        private val byName: Map<String, List<Pair<Station, GpsAlarmStation>>> = HashMap<String, MutableList<Pair<Station, GpsAlarmStation>>>().also { index ->
            located.forEach { entry ->
                (sequenceOf(entry.first.name) + entry.first.aliases.asSequence())
                    .map(StationSearchNormalizer::normalize)
                    .filter(String::isNotBlank)
                    .distinct()
                    .forEach { key -> index.getOrPut(key) { mutableListOf() }.add(entry) }
            }
        }

        fun resolve(stopName: String): GpsAlarmStation? {
            val normalized = StationSearchNormalizer.normalize(stopName)
            if (normalized.isBlank()) return null
            val exact = byName[normalized].orEmpty()
            val candidates = exact.ifEmpty { located.filter { (station, _) -> RailStopNameMatcher.matches(station, stopName) } }
                .map { it.second }
                .distinctBy(GpsAlarmStation::id)
            val first = candidates.firstOrNull() ?: return null
            val samePlace = candidates.all { candidate ->
                GpsArrivalAlarmPolicy.distanceMeters(first.latitude, first.longitude, candidate.latitude, candidate.longitude) <= SAME_PLACE_METERS
            }
            return first.takeIf { samePlace }
        }
    }

    private fun located(station: Station): GpsAlarmStation? {
        val latitude = station.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val longitude = station.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        return GpsAlarmStation(station.id, station.name, latitude, longitude)
    }
}
