package it.binario.app.co2

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Emission factors of the CO2 estimate, per passenger-kilometre, in grams of CO2 equivalent (well-to-wheel; the
 * aviation values include non-CO2 effects). Source: European Environment Agency, "Transport and environment report
 * 2020 – Train or plane?", EEA Report No 19/2020 (published 2021), [SOURCE_URL]. The values are the report's figures
 * for an international high-speed electric train, an average fossil-fuelled car with 1.5 occupants and an A320 on
 * 500 and 1,000 km; they are averages, not the emissions of a given train, car or flight.
 *
 * The distance factors are Binario's own assumptions, stated in the credits: rail lines and roads are longer than
 * the great-circle distance between stations ([RAIL_DETOUR], [ROAD_DETOUR]); a flight adds the usual 8 % to the
 * great circle for routing and holding ([FLIGHT_UPLIFT]).
 */
object EmissionFactors {
    const val SOURCE = "EEA Report No 19/2020, Transport and environment report 2020 – Train or plane?"
    const val SOURCE_URL = "https://www.eea.europa.eu/en/analysis/publications/transport-and-environment-report-2020"
    const val SOURCE_YEAR = 2020

    const val TRAIN_GRAMS_PER_PKM = 18.0
    const val CAR_GRAMS_PER_PKM = 111.0
    /** A320, 500 km flight. */
    const val PLANE_GRAMS_PER_PKM_SHORT = 340.0
    const val PLANE_SHORT_KM = 500.0
    /** A320, 1,000 km flight. */
    const val PLANE_GRAMS_PER_PKM_LONG = 270.0
    const val PLANE_LONG_KM = 1000.0

    const val RAIL_DETOUR = 1.2
    const val ROAD_DETOUR = 1.25
    const val FLIGHT_UPLIFT = 1.08

    /** Grams per passenger-km of a flight of [km], between the two published distances and constant outside them. */
    fun planeGramsPerPkm(km: Double): Double {
        if (km <= PLANE_SHORT_KM) return PLANE_GRAMS_PER_PKM_SHORT
        if (km >= PLANE_LONG_KM) return PLANE_GRAMS_PER_PKM_LONG
        val share = (km - PLANE_SHORT_KM) / (PLANE_LONG_KM - PLANE_SHORT_KM)
        return PLANE_GRAMS_PER_PKM_SHORT + share * (PLANE_GRAMS_PER_PKM_LONG - PLANE_GRAMS_PER_PKM_SHORT)
    }
}

/** A point of a station, in degrees. */
data class Co2Point(val latitude: Double, val longitude: Double)

/** One vehicle leg between two stations of known position. */
data class Co2Leg(val from: Co2Point, val to: Co2Point)

/** Kilograms of CO2e per passenger: the train journey, the same trip by car and by plane, and the rail distance used. */
data class Co2Estimate(val trainKg: Double, val carKg: Double, val planeKg: Double, val railKm: Double)

/**
 * The CO2 estimate of a solution, computed on the device from the positions of its stations: every vehicle leg is the
 * great-circle distance between its stations times [EmissionFactors.RAIL_DETOUR] (the factor of the train for the
 * whole journey, buses included); car and plane cover the great circle between first and last station times their
 * own factor. Null when a station has no position or the trip is shorter than [MIN_KM].
 */
object Co2Estimator {
    const val MIN_KM = 5.0
    private const val EARTH_RADIUS_KM = 6371.0088

    fun estimate(legs: List<Co2Leg>): Co2Estimate? {
        if (legs.isEmpty()) return null
        val railKm = legs.sumOf { greatCircleKm(it.from, it.to) } * EmissionFactors.RAIL_DETOUR
        val directKm = greatCircleKm(legs.first().from, legs.last().to)
        if (railKm < MIN_KM || directKm < MIN_KM) return null
        val flightKm = directKm * EmissionFactors.FLIGHT_UPLIFT
        return Co2Estimate(
            trainKg = railKm * EmissionFactors.TRAIN_GRAMS_PER_PKM / 1000.0,
            carKg = directKm * EmissionFactors.ROAD_DETOUR * EmissionFactors.CAR_GRAMS_PER_PKM / 1000.0,
            planeKg = flightKm * EmissionFactors.planeGramsPerPkm(flightKm) / 1000.0,
            railKm = railKm
        )
    }

    /** [legs] with a missing station position make no estimate rather than a partial one. */
    fun estimateOrNull(legs: List<Pair<Co2Point?, Co2Point?>>): Co2Estimate? {
        val known = legs.map { (from, to) -> if (from == null || to == null) return null else Co2Leg(from, to) }
        return estimate(known)
    }

    /** Haversine distance on the mean Earth radius. */
    fun greatCircleKm(from: Co2Point, to: Co2Point): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** "4.8" under 10 kg, "31" above: the precision an estimate deserves. */
    fun displayKg(kg: Double, locale: java.util.Locale): String =
        if (kg < 10.0) String.format(locale, "%.1f", kg) else (kg.roundToInt()).toString()
}
