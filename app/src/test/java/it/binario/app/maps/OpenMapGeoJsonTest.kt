package it.binario.app.maps

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class OpenMapGeoJsonTest {
    private val style = OpenMapMarkerStyle(color = "#007F5F", radius = 7f, strokeColor = "#FFFFFF", strokeWidth = 2f, opacity = 0.5f)

    @Test
    fun `markers are points with longitude first and their drawing attributes`() {
        val json = JSONObject(OpenMapGeoJson.markers(listOf(GeoPoint(45.4864, 9.2042) to style)))

        assertEquals("FeatureCollection", json.getString("type"))
        val feature = json.getJSONArray("features").getJSONObject(0)
        val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals("Point", feature.getJSONObject("geometry").getString("type"))
        assertEquals(9.2042, coordinates.getDouble(0), 0.0)
        assertEquals(45.4864, coordinates.getDouble(1), 0.0)
        val properties = feature.getJSONObject("properties")
        assertEquals(0, properties.getInt("index"))
        assertEquals("#007F5F", properties.getString("color"))
        assertEquals(7.0, properties.getDouble("radius"), 0.0)
        assertEquals("#FFFFFF", properties.getString("strokeColor"))
        assertEquals(2.0, properties.getDouble("strokeWidth"), 0.0)
        assertEquals(0.5, properties.getDouble("opacity"), 0.0)
    }

    @Test
    fun `numbers never use the decimal comma of the device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ITALY)
            val text = OpenMapGeoJson.line(listOf(GeoPoint(45.5, 9.25), GeoPoint(0.0001, -0.00005)))
            val coordinates = JSONObject(text).getJSONArray("features").getJSONObject(0)
                .getJSONObject("geometry").getJSONArray("coordinates")
            assertEquals(9.25, coordinates.getJSONArray(0).getDouble(0), 0.0)
            assertEquals(-0.00005, coordinates.getJSONArray(1).getDouble(0), 0.0)
            assertEquals(0.0001, coordinates.getJSONArray(1).getDouble(1), 0.0)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `invalid points are left out and a line needs two points`() {
        val markers = JSONObject(OpenMapGeoJson.markers(listOf(GeoPoint(Double.NaN, 1.0) to style, GeoPoint(1.0, 2.0) to style)))
        assertEquals(1, markers.getJSONArray("features").length())
        assertEquals(1, markers.getJSONArray("features").getJSONObject(0).getJSONObject("properties").getInt("index"))

        assertEquals(OpenMapGeoJson.EMPTY_COLLECTION, OpenMapGeoJson.line(emptyList()))
        assertEquals(OpenMapGeoJson.EMPTY_COLLECTION, OpenMapGeoJson.line(listOf(GeoPoint(1.0, 2.0), GeoPoint(Double.NaN, 2.0))))
        assertEquals(0, JSONObject(OpenMapGeoJson.markers(emptyList())).getJSONArray("features").length())
    }

    @Test
    fun `a line keeps its points in route order`() {
        val coordinates = JSONObject(OpenMapGeoJson.line(listOf(GeoPoint(45.0, 9.0), GeoPoint(44.0, 11.0), GeoPoint(43.0, 12.0))))
            .getJSONArray("features").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")

        assertEquals(3, coordinates.length())
        assertEquals(listOf(9.0, 11.0, 12.0), (0 until 3).map { coordinates.getJSONArray(it).getDouble(0) })
    }

    @Test
    fun `string attributes are escaped`() {
        val json = JSONObject(OpenMapGeoJson.markers(listOf(GeoPoint(1.0, 2.0) to style.copy(color = "a\"b\\c\nd"))))

        assertEquals("a\"b\\c\nd", json.getJSONArray("features").getJSONObject(0).getJSONObject("properties").getString("color"))
    }
}
