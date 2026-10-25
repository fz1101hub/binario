package it.binario.app.maps

import it.binario.app.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMapGeometryTest {
    @Test
    fun `no camera without a usable point`() {
        assertNull(OpenMapCameraPlanner.plan(emptyList()))
        assertNull(OpenMapCameraPlanner.plan(listOf(GeoPoint(Double.NaN, 9.0), GeoPoint(95.0, 9.0), GeoPoint(45.0, 181.0))))
    }

    @Test
    fun `one point or points within a few metres are centred, not fitted`() {
        val milano = GeoPoint(45.4864, 9.2042)
        assertEquals(OpenMapCamera.Center(milano, OpenMapCameraPlanner.SINGLE_POINT_ZOOM), OpenMapCameraPlanner.plan(listOf(milano)))

        val nearlySame = OpenMapCameraPlanner.plan(listOf(milano, GeoPoint(45.4865, 9.2043), GeoPoint(Double.NaN, 0.0)))
        assertTrue(nearlySame is OpenMapCamera.Center)
        nearlySame as OpenMapCamera.Center
        assertEquals(45.48645, nearlySame.point.latitude, 1e-9)
        assertEquals(9.20425, nearlySame.point.longitude, 1e-9)
    }

    @Test
    fun `several points are fitted inside bounds that contain every one`() {
        val points = listOf(GeoPoint(45.4864, 9.2042), GeoPoint(44.5059, 11.3431), GeoPoint(43.7764, 11.2480))
        val camera = OpenMapCameraPlanner.plan(points) as OpenMapCamera.Fit

        points.forEach { point ->
            assertTrue(point.latitude <= camera.bounds.north && point.latitude >= camera.bounds.south)
            assertTrue(point.longitude <= camera.bounds.east && point.longitude >= camera.bounds.west)
        }
        assertEquals(45.4864, camera.bounds.north, 1e-9)
        assertEquals(43.7764, camera.bounds.south, 1e-9)
    }

    @Test
    fun `a line of stations along one parallel still gets a box with height`() {
        val camera = OpenMapCameraPlanner.plan(listOf(GeoPoint(45.0, 9.0), GeoPoint(45.0, 9.5))) as OpenMapCamera.Fit

        assertTrue(camera.bounds.north - camera.bounds.south >= OpenMapCameraPlanner.MIN_FIT_SPAN_DEGREES - 1e-12)
        assertEquals(9.5, camera.bounds.east, 1e-9)
        assertEquals(9.0, camera.bounds.west, 1e-9)
    }

    @Test
    fun `station coordinates become a point only when complete and in range`() {
        assertEquals(GeoPoint(41.9, 12.5), Station("a", "A", "A", latitude = 41.9, longitude = 12.5).mapPoint())
        assertNull(Station("b", "B", "B", latitude = 41.9).mapPoint())
        assertNull(Station("c", "C", "C", latitude = Double.POSITIVE_INFINITY, longitude = 12.5).mapPoint())
        assertNull(Station("d", "D", "D", latitude = 41.9, longitude = 200.0).mapPoint())
    }

    @Test
    fun `a tap picks the nearest marker within the radius`() {
        val markers = listOf(ScreenPoint(10f, 10f), null, ScreenPoint(30f, 10f), ScreenPoint(Float.NaN, 0f))

        assertEquals(2, OpenMapHitTest.nearest(ScreenPoint(26f, 12f), markers, 24f))
        assertEquals(0, OpenMapHitTest.nearest(ScreenPoint(12f, 10f), markers, 24f))
        assertNull(OpenMapHitTest.nearest(ScreenPoint(100f, 100f), markers, 24f))
        assertNull(OpenMapHitTest.nearest(ScreenPoint(10f, 10f), markers, 0f))
        assertNull(OpenMapHitTest.nearest(ScreenPoint(10f, 10f), listOf(null, null), 24f))
    }

    @Test
    fun `equidistant markers resolve to the first in route order`() {
        val markers = listOf(ScreenPoint(0f, 0f), ScreenPoint(20f, 0f))

        assertEquals(0, OpenMapHitTest.nearest(ScreenPoint(10f, 0f), markers, 24f))
    }
}
