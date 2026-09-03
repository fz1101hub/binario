package it.binario.app

import it.binario.app.data.Station
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class StationCoordinate(val latitude: Double, val longitude: Double)
data class NearbyStation(val station: Station, val distanceKm: Double)

/** Coordinate curate del catalogo canonico, utilizzate esclusivamente per ordinare la vicinanza. */
internal object StationCoordinates {
    fun nearby(stations: List<Station>, userLatitude: Double, userLongitude: Double): List<NearbyStation> =
        stations.mapNotNull { station ->
            station.latitude?.let { latitude ->
                station.longitude?.let { longitude ->
                    NearbyStation(station, distanceKm(userLatitude, userLongitude, latitude, longitude))
                }
            }
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
