package it.binario.app.maps

import it.binario.app.data.StopRouteChange
import it.binario.app.data.TrainStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMapPlannerTest {
    private fun stop(
        name: String,
        scheduled: String = "08:00",
        expected: String = scheduled,
        completed: Boolean = false,
        change: StopRouteChange = StopRouteChange.NONE,
        verified: Boolean? = null
    ) = TrainStop(name, scheduled, expected, null, 0, completed, change, verified)

    private val coordinates = mapOf(
        "Milano Centrale" to GeoPoint(45.4864, 9.2042),
        "Lodi" to GeoPoint(45.3096, 9.4987),
        "Piacenza" to GeoPoint(45.0517, 9.6973),
        "Parma" to GeoPoint(44.8094, 10.3289),
        "Bologna Centrale" to GeoPoint(44.5059, 11.3431)
    )
    private val locate: (String) -> GeoPoint? = { coordinates[it] }

    @Test
    fun `without a confirmed stop nothing is highlighted`() {
        val progress = RouteMapPlanner.progress(listOf(stop("Milano Centrale"), stop("Lodi"), stop("Piacenza")))

        assertEquals(RouteProgress(null, null), progress)
    }

    @Test
    fun `the last confirmed stop and the next stop to be served are highlighted`() {
        val stops = listOf(
            stop("Milano Centrale", completed = true),
            stop("Lodi", completed = true),
            stop("Piacenza", change = StopRouteChange.SKIPPED),
            stop("Parma"),
            stop("Bologna Centrale")
        )

        assertEquals(RouteProgress(lastConfirmedIndex = 1, nextIndex = 3), RouteMapPlanner.progress(stops))
    }

    @Test
    fun `a train confirmed at its destination has no next stop`() {
        val stops = listOf(stop("Milano Centrale", completed = true), stop("Bologna Centrale", completed = true))

        assertEquals(RouteProgress(1, null), RouteMapPlanner.progress(stops))
    }

    @Test
    fun `a completed flag on a skipped stop never counts as a confirmed passage`() {
        val stops = listOf(stop("Milano Centrale", completed = true), stop("Lodi", completed = true, change = StopRouteChange.SKIPPED), stop("Piacenza"))

        assertEquals(RouteProgress(0, 2), RouteMapPlanner.progress(stops))
    }

    @Test
    fun `roles combine origin destination progress and skipped stops`() {
        val model = RouteMapPlanner.build(
            listOf(
                stop("Milano Centrale", completed = true),
                stop("Lodi"),
                stop("Piacenza", change = StopRouteChange.SKIPPED),
                stop("Bologna Centrale")
            ),
            serviceVerified = true,
            locate = locate
        )

        assertEquals(setOf(RouteStopRole.ORIGIN, RouteStopRole.LAST_CONFIRMED), model.stops[0].roles)
        assertEquals(RouteStopRole.LAST_CONFIRMED, model.stops[0].primaryRole)
        assertEquals(setOf(RouteStopRole.NEXT), model.stops[1].roles)
        assertEquals(setOf(RouteStopRole.SKIPPED), model.stops[2].roles)
        assertEquals(setOf(RouteStopRole.DESTINATION), model.stops[3].roles)
        assertNull(RouteMapPlanner.build(listOf(stop("Milano Centrale"), stop("Lodi"), stop("Parma")), true, locate).stops[1].primaryRole)
    }

    @Test
    fun `a single stop service is an origin and not also a destination`() {
        val model = RouteMapPlanner.build(listOf(stop("Lodi")), serviceVerified = true, locate = locate)

        assertEquals(setOf(RouteStopRole.ORIGIN), model.stops.single().roles)
        assertTrue(model.line.isEmpty())
        assertTrue(model.camera is OpenMapCamera.Center)
    }

    @Test
    fun `the expected time is shown only when verified, different and not a placeholder`() {
        val model = RouteMapPlanner.build(
            listOf(
                stop("Milano Centrale", "08:00", "08:05", verified = true),
                stop("Lodi", "08:20", "08:25", verified = false),
                stop("Piacenza", "08:40", "08:45"),
                stop("Parma", "09:10", "09:10", verified = true),
                stop("Bologna Centrale", "—", "—", verified = true)
            ),
            serviceVerified = false,
            locate = locate
        )

        assertEquals(listOf("08:05", null, null, null, null), model.stops.map { it.expected })
        assertEquals("08:45", RouteMapPlanner.build(listOf(stop("Piacenza", "08:40", "08:45")), true, locate).stops.single().expected)
    }

    @Test
    fun `unmapped stops stay in the list and the line joins only stops the train calls at`() {
        val model = RouteMapPlanner.build(
            listOf(
                stop("Milano Centrale"),
                stop("Melegnano"),
                stop("Lodi", change = StopRouteChange.SKIPPED),
                stop("Piacenza"),
                stop("Bologna Centrale")
            ),
            serviceVerified = true,
            locate = locate
        )

        assertEquals(5, model.stops.size)
        assertEquals(listOf("Melegnano"), model.unmappedStops.map { it.name })
        assertEquals(listOf("Milano Centrale", "Lodi", "Piacenza", "Bologna Centrale"), model.mappedStops.map { it.name })
        assertEquals(listOf(coordinates.getValue("Milano Centrale"), coordinates.getValue("Piacenza"), coordinates.getValue("Bologna Centrale")), model.line)
        assertTrue(model.camera is OpenMapCamera.Fit)
    }

    @Test
    fun `consecutive stops at the same coordinates do not repeat a line vertex`() {
        val same = GeoPoint(45.0, 9.0)
        val model = RouteMapPlanner.build(
            listOf(stop("A"), stop("A bis"), stop("B")),
            serviceVerified = true,
            locate = { name -> if (name == "B") GeoPoint(44.0, 10.0) else same }
        )

        assertEquals(listOf(same, GeoPoint(44.0, 10.0)), model.line)
    }

    @Test
    fun `no coordinates means no camera and an empty map model`() {
        val model = RouteMapPlanner.build(listOf(stop("Nowhere"), stop("Elsewhere")), serviceVerified = true) { null }

        assertTrue(model.mappedStops.isEmpty())
        assertTrue(model.line.isEmpty())
        assertNull(model.camera)
    }

    @Test
    fun `an invalid coordinate from the lookup is treated as missing`() {
        val model = RouteMapPlanner.build(listOf(stop("Broken"), stop("Lodi")), serviceVerified = true) { name ->
            if (name == "Broken") GeoPoint(Double.NaN, 9.0) else coordinates[name]
        }

        assertEquals(listOf("Broken"), model.unmappedStops.map { it.name })
    }
}
