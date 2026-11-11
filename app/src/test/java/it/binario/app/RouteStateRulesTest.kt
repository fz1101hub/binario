package it.binario.app

import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The view model's rules about the active route, as pure functions (second audit, android-state). */
class RouteStateRulesTest {
    private fun station(id: String) = Station(id, id.replaceFirstChar(Char::uppercase), id)
    private fun route(id: String, from: String = "$id-from", to: String = "$id-to") = SavedRoute(id, id, station(from), station(to))

    private val imported = route("A")
    private val created = route("B")

    @Test fun `the stored route wins again once the catalogue can decode it`() {
        // Cold start after an import: only B (remembered stations) decoded, so B was taken although A is stored.
        val atConstruction = RouteStateRules.storedActiveRoute(listOf(created), storedId = "A")
        assertSame(created, atConstruction)
        val resolved = RouteStateRules.activeRouteAfterCatalogue(
            current = atConstruction, routes = listOf(imported, created), storedId = "A", routeChosenInSession = false
        ) { error("a saved route needs no canonical form") }
        assertSame(imported, resolved)
    }

    @Test fun `a route chosen while the catalogue was loading is kept`() {
        val searched = route("search-x-y")
        var canonicalized = 0
        val resolved = RouteStateRules.activeRouteAfterCatalogue(searched, listOf(imported, created), "A", routeChosenInSession = true) {
            canonicalized += 1
            it.copy(name = "canonical")
        }
        assertEquals("canonical", resolved.name)
        assertEquals(1, canonicalized)
        // A saved route chosen meanwhile is taken in its decoded form.
        assertSame(created, RouteStateRules.activeRouteAfterCatalogue(created.copy(name = "stale"), listOf(imported, created), "A", true) { it })
    }

    @Test fun `without a stored route the first one is active and without routes the placeholder`() {
        assertSame(imported, RouteStateRules.storedActiveRoute(listOf(imported, created), storedId = null))
        assertSame(imported, RouteStateRules.storedActiveRoute(listOf(imported, created), storedId = "removed"))
        assertEquals(UNCONFIGURED_ROUTE_ID, RouteStateRules.storedActiveRoute(emptyList(), storedId = "A").id)
        assertEquals(
            UNCONFIGURED_ROUTE_ID,
            RouteStateRules.activeRouteAfterCatalogue(unconfiguredRoute(), emptyList(), "A", routeChosenInSession = false) { it }.id
        )
    }

    @Test fun `pinning a route keeps an unsaved search active`() {
        // Milano → Roma searched in Pianifica (not saved), then «Casa – Lavoro» pinned in Per te.
        val searched = route(searchedRouteId(station("milano"), station("roma")), "milano", "roma")
        val pinned = listOf(imported.copy(isPinned = true), created)
        assertSame(searched, RouteStateRules.activeRouteAfterPin(searched, pinned))
        // The active saved route takes its new form (the pin).
        assertEquals(true, RouteStateRules.activeRouteAfterPin(imported, pinned).isPinned)
        // The placeholder stays the placeholder.
        assertEquals(UNCONFIGURED_ROUTE_ID, RouteStateRules.activeRouteAfterPin(unconfiguredRoute(), pinned).id)
    }

    @Test fun `only the route the next start opens stores its direction`() {
        val home = route("home")
        val trip = route("trip")
        val routes = listOf(home, trip)
        // «Casa – Lavoro» is the stored route: its direction is stored when it changes.
        assertEquals(true, RouteStateRules.storesDirection(home, routes, storedId = "home"))
        // A search of other stations, and a change of direction on it, leave the stored direction alone.
        val searched = route(searchedRouteId(station("milano"), station("roma")), "milano", "roma")
        assertEquals(false, RouteStateRules.storesDirection(searched, routes, storedId = "home"))
        // So does a search that happens to be another saved route: the next start still opens «Casa – Lavoro».
        assertEquals(false, RouteStateRules.storesDirection(trip, routes, storedId = "home"))
        // Without a stored route the first one is the one the next start opens.
        assertEquals(true, RouteStateRules.storesDirection(home, routes, storedId = null))
        assertEquals(false, RouteStateRules.storesDirection(trip, routes, storedId = null))
        assertEquals(false, RouteStateRules.storesDirection(unconfiguredRoute(), emptyList(), storedId = null))
    }
}
