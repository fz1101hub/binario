package it.binario.app.maps

import java.util.Locale

/** Theme colours used on the map, as `#RRGGBB` strings. */
data class OpenMapPalette(
    val primary: String,
    val tertiary: String,
    val error: String,
    val surface: String,
    val secondary: String
)

/** Marker looks shared by the three map cards, so their legends and the maps always agree. */
internal object OpenMapMarkerStyles {
    private const val SELECTED_GROWTH = 3f

    fun cssHex(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    fun routeStop(role: RouteStopRole?, selected: Boolean, palette: OpenMapPalette): OpenMapMarkerStyle {
        val base = when (role) {
            RouteStopRole.NEXT -> OpenMapMarkerStyle(palette.surface, 8f, palette.tertiary, 4f)
            RouteStopRole.LAST_CONFIRMED -> OpenMapMarkerStyle(palette.tertiary, 8f, palette.surface, 2f)
            RouteStopRole.SKIPPED -> OpenMapMarkerStyle(palette.surface, 5f, palette.error, 2f)
            RouteStopRole.ORIGIN, RouteStopRole.DESTINATION -> OpenMapMarkerStyle(palette.primary, 7f, palette.surface, 2f)
            null -> OpenMapMarkerStyle(palette.surface, 5f, palette.primary, 2f)
        }
        return if (selected) base.copy(radius = base.radius + SELECTED_GROWTH) else base
    }

    /** The station a map is about. */
    fun focusStation(palette: OpenMapPalette): OpenMapMarkerStyle = OpenMapMarkerStyle(palette.tertiary, 8f, palette.surface, 2f)

    /** Any other station that opens its board when tapped. */
    fun station(palette: OpenMapPalette): OpenMapMarkerStyle = OpenMapMarkerStyle(palette.primary, 6f, palette.surface, 2f)

    /** The approximate position: a large translucent disc, not a precise dot. */
    fun approximatePosition(palette: OpenMapPalette): OpenMapMarkerStyle =
        OpenMapMarkerStyle(palette.secondary, 14f, palette.secondary, 1f, opacity = 0.3f)
}
