package it.binario.app

import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val rome: ZoneId = ZoneId.of("Europe/Rome")
    private fun at(time: String, date: String = "2026-11-12") = LocalDateTime.parse("${date}T$time")
    private fun instant(time: String, date: String = "2026-11-12") = at(time, date).atZone(rome).toInstant()
    private fun train(number: String, departure: String, dated: Boolean = true) = TrainService(
        id = number, number = number, operator = RailOperator.TRENITALIA_REGIONALE, trainCategory = TrainCategory.REGIONALE,
        origin = "Casa", destination = "Lavoro", scheduledDeparture = departure, expectedDeparture = departure, platform = null,
        delayMinutes = 0, status = ServiceStatus.ON_TIME, stops = emptyList(),
        scheduledDepartureDateTime = if (dated) at(departure) else null
    )
    private fun savedAt(time: String, vararg services: TrainService, date: String = "2026-11-12") = RouteSnapshot(
        route = imported, direction = Direction.OUTBOUND, services = services.toList(), providerName = "Test",
        retrievedAtEpochMillis = instant(time, date).toEpochMilli(), expiresAtEpochMillis = instant(time, date).toEpochMilli() + 60_000,
        freshness = Freshness.FRESH
    )

    @Test fun `the saved copy of a route lists only the trains still to leave`() {
        // Saved at 07:40; at 08:00 the 07:45 train left fifteen minutes ago, the 07:52 one eight minutes ago.
        val saved = savedAt("07:40", train("1", "07:45"), train("2", "07:52"), train("3", "08:15"), train("4", "08:30", dated = false))
        val copy = RouteStateRules.savedRouteCopy(saved, instant("08:00"), rome)
        assertEquals(listOf("2", "3", "4"), copy?.services?.map { it.number })
        // In the evening nothing of the morning is left: no saved copy rather than the morning's trains.
        assertNull(RouteStateRules.savedRouteCopy(saved, instant("18:00"), rome))
        // A train whose time cannot be read stays.
        val unreadable = savedAt("07:40", train("5", "--:--", dated = false))
        assertEquals(listOf("5"), RouteStateRules.savedRouteCopy(unreadable, instant("18:00"), rome)?.services?.map { it.number })
    }

    @Test fun `a number search keeps its day across midnight while it is refreshed`() {
        val yesterday = LocalDate.parse("2026-11-11")
        val today = LocalDate.parse("2026-11-12")
        // Train 2658 opened at 23:20, followed past midnight: the refresh still asks for the run of the day before.
        assertEquals(yesterday, RouteStateRules.numberSearchDate(refresh = true, searchedDate = yesterday, today = today))
        assertEquals(today, RouteStateRules.numberSearchDate(refresh = true, searchedDate = today, today = today))
        // A new search asks for today; a search older than yesterday is asked again for today.
        assertEquals(today, RouteStateRules.numberSearchDate(refresh = false, searchedDate = yesterday, today = today))
        assertEquals(today, RouteStateRules.numberSearchDate(refresh = true, searchedDate = today.minusDays(2), today = today))
    }

    @Test fun `only a load of something else is cancelled by a newer one`() {
        // Another route, direction or moment: nobody reads the earlier answer, its requests stop.
        assertEquals(true, RouteStateRules.cancelsSupersededLoad("home|OUTBOUND|now", "trip|OUTBOUND|now"))
        assertEquals(true, RouteStateRules.cancelsSupersededLoad("home|OUTBOUND|now", "home|RETURN|now"))
        assertEquals(true, RouteStateRules.cancelsSupersededLoad("home|OUTBOUND|now", "home|OUTBOUND|2026-11-13T07:00"))
        // The same search asked again (the refresh button during a load) shares the requests in flight.
        assertEquals(false, RouteStateRules.cancelsSupersededLoad("home|OUTBOUND|now", "home|OUTBOUND|now"))
    }

    @Test fun `the saved copy of another day is not shown`() {
        val saved = savedAt("23:40", train("1", "23:50"), date = "2026-11-11")
        assertNull(RouteStateRules.savedRouteCopy(saved, instant("00:05"), rome))
    }
}
