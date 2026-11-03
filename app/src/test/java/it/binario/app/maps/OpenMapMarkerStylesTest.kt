package it.binario.app.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Locale

class OpenMapMarkerStylesTest {
    private val palette = OpenMapPalette(primary = "#007F5F", tertiary = "#A55400", error = "#B3261E", surface = "#FFFFFF", secondary = "#4A635A")

    @Test
    fun `colours are written as opaque css hex regardless of alpha and locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("#007F5F", OpenMapMarkerStyles.cssHex(0xFF007F5F.toInt()))
            assertEquals("#0000FF", OpenMapMarkerStyles.cssHex(0x330000FF))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `live progress roles stand out from the route ends and plain stops`() {
        val next = OpenMapMarkerStyles.routeStop(RouteStopRole.NEXT, selected = false, palette = palette)
        val confirmed = OpenMapMarkerStyles.routeStop(RouteStopRole.LAST_CONFIRMED, selected = false, palette = palette)
        val origin = OpenMapMarkerStyles.routeStop(RouteStopRole.ORIGIN, selected = false, palette = palette)
        val plain = OpenMapMarkerStyles.routeStop(null, selected = false, palette = palette)
        val skipped = OpenMapMarkerStyles.routeStop(RouteStopRole.SKIPPED, selected = false, palette = palette)

        assertEquals(palette.tertiary, next.strokeColor)
        assertEquals(palette.tertiary, confirmed.color)
        assertEquals(palette.primary, origin.color)
        assertEquals(origin, OpenMapMarkerStyles.routeStop(RouteStopRole.DESTINATION, selected = false, palette = palette))
        assertEquals(palette.error, skipped.strokeColor)
        assertNotEquals(plain, origin)
        assertEquals(true, next.radius > plain.radius && confirmed.radius > plain.radius)
    }

    @Test
    fun `a tapped stop grows without changing colour`() {
        val normal = OpenMapMarkerStyles.routeStop(null, selected = false, palette = palette)
        val selected = OpenMapMarkerStyles.routeStop(null, selected = true, palette = palette)

        assertEquals(normal.copy(radius = normal.radius + 3f), selected)
    }

    @Test
    fun `the approximate position is a translucent disc larger than any station`() {
        val position = OpenMapMarkerStyles.approximatePosition(palette)

        assertEquals(true, position.opacity < 1f)
        assertEquals(true, position.radius > OpenMapMarkerStyles.focusStation(palette).radius)
        assertEquals(true, position.radius > OpenMapMarkerStyles.station(palette).radius)
    }
}
