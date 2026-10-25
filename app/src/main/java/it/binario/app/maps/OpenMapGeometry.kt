package it.binario.app.maps

import it.binario.app.data.Station

/** WGS84 coordinate as stored in the curated station catalogue. */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    val isValid: Boolean
        get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}

data class GeoBounds(val north: Double, val south: Double, val east: Double, val west: Double)

/** Where the camera goes when a map opens: fitted to several points, or centred on a single one. */
sealed interface OpenMapCamera {
    data class Fit(val bounds: GeoBounds) : OpenMapCamera
    data class Center(val point: GeoPoint, val zoom: Double) : OpenMapCamera
}

/** Catalogue coordinates of a station, or null when they are missing or out of range. */
fun Station.mapPoint(): GeoPoint? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    return GeoPoint(lat, lon).takeIf { it.isValid }
}

internal object OpenMapCameraPlanner {
    /** Zoom for a lone station: the station and the streets around it. */
    const val SINGLE_POINT_ZOOM = 14.0

    /** Points closer than this (about 200 m) are one place: fitting them would zoom to a single building. */
    const val MIN_SPAN_DEGREES = 0.002

    /** Every axis of a fitted box spans at least this much, so a line of stations along a parallel stays visible. */
    const val MIN_FIT_SPAN_DEGREES = 0.01

    /** Null when no point is usable, so the caller never builds a camera from invented coordinates. */
    fun plan(points: List<GeoPoint>): OpenMapCamera? {
        val valid = points.filter { it.isValid }
        if (valid.isEmpty()) return null
        val north = valid.maxOf { it.latitude }
        val south = valid.minOf { it.latitude }
        val east = valid.maxOf { it.longitude }
        val west = valid.minOf { it.longitude }
        val centreLatitude = (north + south) / 2
        val centreLongitude = (east + west) / 2
        if (north - south < MIN_SPAN_DEGREES && east - west < MIN_SPAN_DEGREES) {
            return OpenMapCamera.Center(GeoPoint(centreLatitude, centreLongitude), SINGLE_POINT_ZOOM)
        }
        val halfLatitude = maxOf(north - south, MIN_FIT_SPAN_DEGREES) / 2
        val halfLongitude = maxOf(east - west, MIN_FIT_SPAN_DEGREES) / 2
        return OpenMapCamera.Fit(
            GeoBounds(
                north = (centreLatitude + halfLatitude).coerceAtMost(MAX_LATITUDE),
                south = (centreLatitude - halfLatitude).coerceAtLeast(-MAX_LATITUDE),
                east = (centreLongitude + halfLongitude).coerceAtMost(180.0),
                west = (centreLongitude - halfLongitude).coerceAtLeast(-180.0)
            )
        )
    }

    /** Web Mercator cannot show the poles; the renderer rejects bounds beyond this latitude. */
    private const val MAX_LATITUDE = 85.0
}

/** A marker position on screen, in pixels of the map view. */
data class ScreenPoint(val x: Float, val y: Float)

internal object OpenMapHitTest {
    /** Index of the candidate nearest to the tap within [radiusPx], or null when the tap hit no marker. */
    fun nearest(tap: ScreenPoint, candidates: List<ScreenPoint?>, radiusPx: Float): Int? {
        if (!(radiusPx > 0f)) return null
        var best: Int? = null
        var bestDistance = radiusPx * radiusPx
        candidates.forEachIndexed { index, point ->
            if (point == null || !point.x.isFinite() || !point.y.isFinite()) return@forEachIndexed
            val dx = point.x - tap.x
            val dy = point.y - tap.y
            val distance = dx * dx + dy * dy
            // Ties keep the earlier candidate: markers are listed in route order.
            if (distance < bestDistance || (best == null && distance <= bestDistance)) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }
}
