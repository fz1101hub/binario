package it.binario.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
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

/**
 * The nearest catalogue station to one position, or null when none with coordinates lies within
 * [maxDistanceKm] (abroad, or a position far from any railway).
 */
internal object NearestStationPicker {
    const val DEFAULT_MAX_DISTANCE_KM = 50.0

    fun pick(stations: List<Station>, latitude: Double, longitude: Double, maxDistanceKm: Double = DEFAULT_MAX_DISTANCE_KM): NearbyStation? =
        StationCoordinates.nearby(stations, latitude, longitude).firstOrNull()?.takeIf { it.distanceKm <= maxDistanceKm }
}

/**
 * One approximate location fix for an explicit user action (nearby stations, the station widget's
 * "Più vicina"): the current network position on Android 11+, otherwise the last known one. Never
 * stored; null without the foreground permission or without a position.
 */
internal fun requestSingleLocationFix(context: Context, onResult: (Location?) -> Unit) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        onResult(null)
        return
    }
    val locationManager = context.getSystemService(LocationManager::class.java) ?: run {
        onResult(null)
        return
    }
    val fallback = {
        onResult(runCatching {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull())
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            locationManager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ContextCompat.getMainExecutor(context)) { location ->
                if (location != null) onResult(location) else fallback()
            }
        }.onFailure { fallback() }
    } else fallback()
}
