package it.binario.app.maps

/** Drawing attributes of one marker; colours are `#RRGGBB` strings read by the circle layer. */
data class OpenMapMarkerStyle(
    val color: String,
    val radius: Float,
    val strokeColor: String,
    val strokeWidth: Float,
    val opacity: Float = 1f
)

/**
 * Hand-built GeoJSON for the two map sources. Only coordinates, a marker index and drawing attributes are
 * written: station names never enter the renderer, so no user-visible text depends on the base map's fonts.
 */
internal object OpenMapGeoJson {
    const val EMPTY_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

    fun markers(points: List<Pair<GeoPoint, OpenMapMarkerStyle>>): String = buildString {
        append("""{"type":"FeatureCollection","features":[""")
        var first = true
        points.forEachIndexed { index, (point, style) ->
            if (!point.isValid) return@forEachIndexed
            if (!first) append(',')
            first = false
            append("""{"type":"Feature","geometry":{"type":"Point","coordinates":""")
            appendPosition(point)
            append("""},"properties":{"index":""").append(index)
            append(""","color":""").appendJsonString(style.color)
            append(""","radius":""").appendNumber(style.radius.toDouble())
            append(""","strokeColor":""").appendJsonString(style.strokeColor)
            append(""","strokeWidth":""").appendNumber(style.strokeWidth.toDouble())
            append(""","opacity":""").appendNumber(style.opacity.toDouble())
            append("}}")
        }
        append("]}")
    }

    /** A LineString through the valid points in order; fewer than two points give an empty collection. */
    fun line(points: List<GeoPoint>): String {
        val valid = points.filter { it.isValid }
        if (valid.size < 2) return EMPTY_COLLECTION
        return buildString {
            append("""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
            valid.forEachIndexed { index, point ->
                if (index > 0) append(',')
                appendPosition(point)
            }
            append("""]},"properties":{}}]}""")
        }
    }

    /** GeoJSON positions are longitude first. */
    private fun StringBuilder.appendPosition(point: GeoPoint) {
        append('[').appendNumber(point.longitude).append(',').appendNumber(point.latitude).append(']')
    }

    /** Locale-independent: `Double.toString` never uses a decimal comma and its exponent form is valid JSON. */
    private fun StringBuilder.appendNumber(value: Double): StringBuilder = append(if (value.isFinite()) value.toString() else "0")

    private fun StringBuilder.appendJsonString(value: String): StringBuilder {
        append('"')
        value.forEach { character ->
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character < ' ' -> append("\\u").append(character.code.toString(16).padStart(4, '0'))
                else -> append(character)
            }
        }
        return append('"')
    }
}
