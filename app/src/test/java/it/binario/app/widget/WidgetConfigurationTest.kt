package it.binario.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import it.binario.app.data.Direction
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station

class WidgetConfigurationTest {
    @Test
    fun `custom widget name overrides target label`() {
        val configuration = WidgetDisplayConfiguration(WidgetTargetType.ROUTE, "roma-torino", "Roma — Torino", "Pendolare")

        assertEquals("Pendolare", configuration.widgetTitle())
    }

    @Test
    fun `blank widget name keeps target label`() {
        val configuration = WidgetDisplayConfiguration(WidgetTargetType.ROUTE, "roma-torino", "Roma — Torino", "  ")

        assertEquals("Roma — Torino", configuration.widgetTitle())
    }

    @Test
    fun `free route widget retains both unsaved station endpoints`() {
        val origin = Station("manual:porto-san-giorgio", "Porto San Giorgio", "Porto San Giorgio", "Ricerca libera")
        val destination = Station("manual:ancona", "Ancona", "Ancona", "Ricerca libera")
        val configuration = WidgetDisplayConfiguration(
            WidgetTargetType.ROUTE,
            "route:${origin.id}:${destination.id}",
            "${origin.name} → ${destination.name}",
            routeOrigin = origin,
            routeDestination = destination
        )

        val target = configuration.routeTarget()

        assertNotNull(target)
        assertEquals(origin, target!!.outwardOrigin)
        assertEquals(destination, target.outwardDestination)
        assertTrue(configuration.matchesRoute(target))
    }

    @Test
    fun `canonical route widget retains railway and Trenitalia identities`() {
        val origin = Station(
            id = "curated:S08409",
            name = "Roma Termini",
            city = "Roma",
            railwayCode = "S08409",
            trenitaliaLocationId = "830008409"
        )
        val destination = Station(
            id = "curated:S08671",
            name = "Cassino",
            city = "Cassino",
            railwayCode = "S08671",
            trenitaliaLocationId = "830008671"
        )
        val configuration = WidgetDisplayConfiguration(
            WidgetTargetType.ROUTE,
            "route:${origin.id}:${destination.id}",
            "${origin.name} → ${destination.name}",
            routeOrigin = origin,
            routeDestination = destination
        )

        val target = configuration.routeTarget()!!

        assertEquals("S08409", target.outwardOrigin.railwayCode)
        assertEquals("830008409", target.outwardOrigin.trenitaliaLocationId)
        assertEquals("S08671", target.outwardDestination.railwayCode)
        assertEquals("830008671", target.outwardDestination.trenitaliaLocationId)
    }

    @Test
    fun `route widget does not match a different saved route`() {
        val origin = Station("manual:perugia", "Perugia", "Perugia", "Ricerca libera")
        val destination = Station("manual:foligno", "Foligno", "Foligno", "Ricerca libera")
        val configuration = WidgetDisplayConfiguration(
            WidgetTargetType.ROUTE,
            "route:${origin.id}:${destination.id}",
            "${origin.name} → ${destination.name}",
            routeOrigin = origin,
            routeDestination = destination
        )
        val different = SavedRoute("saved-1", "Altro", origin, Station("manual:spoleto", "Spoleto", "Spoleto", "Ricerca libera"))

        assertFalse(configuration.matchesRoute(different))
    }

    @Test
    fun `snapshot is accepted only for the displayed route direction`() {
        val origin = Station("roma", "Roma Termini", "Roma")
        val destination = Station("napoli", "Napoli Centrale", "Napoli")
        val configuration = WidgetDisplayConfiguration(
            WidgetTargetType.ROUTE,
            "route:${origin.id}:${destination.id}",
            "${origin.name} → ${destination.name}",
            routeOrigin = origin,
            routeDestination = destination
        )
        val route = configuration.routeTarget()!!

        assertTrue(configuration.matchesRouteDirection(route, Direction.RETURN, Direction.RETURN))
        assertFalse(configuration.matchesRouteDirection(route, Direction.OUTBOUND, Direction.RETURN))
    }
}
