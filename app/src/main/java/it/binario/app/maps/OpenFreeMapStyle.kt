package it.binario.app.maps

/**
 * OpenFreeMap base maps: open vector tiles from OpenStreetMap data (OpenMapTiles schema), free and without an
 * API key. Style, tiles, glyphs and sprites are all served by this one host. Attribution is required: the
 * MapLibre attribution control stays enabled and every map card shows the credit line.
 */
internal object OpenFreeMapStyle {
    const val HOST = "tiles.openfreemap.org"

    /** Light theme: Liberty, which draws railways and stations in detail. */
    const val LIGHT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    /** Dark theme: OpenFreeMap's Dark (Dark Matter fork), so the map does not flash white in a dark app. */
    const val DARK_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

    fun styleUrl(darkTheme: Boolean): String = if (darkTheme) DARK_STYLE_URL else LIGHT_STYLE_URL
}
