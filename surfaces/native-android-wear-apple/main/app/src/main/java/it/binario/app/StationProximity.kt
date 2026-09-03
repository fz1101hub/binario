package it.binario.app

import it.binario.app.data.Station
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class StationCoordinate(val latitude: Double, val longitude: Double)
data class NearbyStation(val station: Station, val distanceKm: Double)

/** Coordinate statiche del solo catalogo locale, utilizzate esclusivamente per ordinare la vicinanza. */
internal object StationCoordinates {
    private val values = mapOf(
        "roma-termini" to StationCoordinate(41.9008, 12.5016),
        "roma-tiburtina" to StationCoordinate(41.9100, 12.5307),
        "roma-ostiense" to StationCoordinate(41.8769, 12.4817),
        "roma-trastevere" to StationCoordinate(41.8741, 12.4621),
        "roma-san-pietro" to StationCoordinate(41.8978, 12.4548),
        "roma-tuscolana" to StationCoordinate(41.8848, 12.5172),
        "fiumicino-aeroporto" to StationCoordinate(41.8003, 12.2388),
        "ciampino" to StationCoordinate(41.7977, 12.5945),
        "civitavecchia" to StationCoordinate(42.0901, 11.7950),
        "frascati" to StationCoordinate(41.8088, 12.6803),
        "velletri" to StationCoordinate(41.6869, 12.7779),
        "viterbo-pf" to StationCoordinate(42.4261, 12.1071),
        "orte" to StationCoordinate(42.4610, 12.3888),
        "latina" to StationCoordinate(41.4645, 12.9031),
        "frosinone" to StationCoordinate(41.6374, 13.3422),
        "cassino" to StationCoordinate(41.4869, 13.8323),
        "napoli-centrale" to StationCoordinate(40.8527, 14.2725),
        "firenze-smn" to StationCoordinate(43.7766, 11.2477),
        "bologna-centrale" to StationCoordinate(44.5059, 11.3427),
        "milano-centrale" to StationCoordinate(45.4858, 9.2044),
        "venezia-sl" to StationCoordinate(45.4411, 12.3210),
        "genova-pp" to StationCoordinate(44.4166, 8.9217),
        "torino-pn" to StationCoordinate(45.0620, 7.6785),
        "bari-centrale" to StationCoordinate(41.1171, 16.8697),
        "palermo-centrale" to StationCoordinate(38.1197, 13.3611),
        "cagliari" to StationCoordinate(39.2164, 9.1110)
    )

    fun nearby(stations: List<Station>, userLatitude: Double, userLongitude: Double): List<NearbyStation> =
        stations.mapNotNull { station ->
            values[station.id]?.let { coordinate -> NearbyStation(station, distanceKm(userLatitude, userLongitude, coordinate.latitude, coordinate.longitude)) }
        }.sortedBy { it.distanceKm }

    private fun distanceKm(latitudeA: Double, longitudeA: Double, latitudeB: Double, longitudeB: Double): Double {
        val earthRadiusKm = 6371.0088
        val latitudeDelta = Math.toRadians(latitudeB - latitudeA)
        val longitudeDelta = Math.toRadians(longitudeB - longitudeA)
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(Math.toRadians(latitudeA)) * cos(Math.toRadians(latitudeB)) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 2 * earthRadiusKm * atan2(sqrt(a), sqrt(1 - a))
    }
}
