package it.binario.app

import it.binario.app.data.Station
import java.text.Collator
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class StationCoordinate(val latitude: Double, val longitude: Double)
data class NearbyStation(val station: Station, val distanceKm: Double)

/** Coordinate curate del catalogo canonico, utilizzate esclusivamente per ordinare la vicinanza. */
internal object StationCoordinates {
    fun nearby(stations: List<Station>, userLatitude: Double, userLongitude: Double): List<NearbyStation> {
        if (!userLatitude.isFinite() || !userLongitude.isFinite()) return emptyList()
        val italianCollator = Collator.getInstance(Locale.ITALIAN).apply { strength = Collator.PRIMARY }
        return stations.mapNotNull { station ->
            station.latitude?.let { latitude ->
                station.longitude?.let { longitude ->
                    if (latitude.isFinite() && longitude.isFinite()) {
                        NearbyStation(station, distanceKm(userLatitude, userLongitude, latitude, longitude))
                    } else null
                }
            }
        }.sortedWith { left, right ->
            left.distanceKm.compareTo(right.distanceKm).takeIf { it != 0 }
                ?: italianCollator.compare(left.station.name, right.station.name).takeIf { it != 0 }
                ?: left.station.id.compareTo(right.station.id)
        }
    }

    private fun distanceKm(latitudeA: Double, longitudeA: Double, latitudeB: Double, longitudeB: Double): Double {
        val earthRadiusKm = 6371.0088
        val latitudeDelta = Math.toRadians(latitudeB - latitudeA)
        val longitudeDelta = Math.toRadians(longitudeB - longitudeA)
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(Math.toRadians(latitudeA)) * cos(Math.toRadians(latitudeB)) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 2 * earthRadiusKm * atan2(sqrt(a), sqrt(1 - a))
    }
}
