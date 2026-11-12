package it.binario.app

import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import it.binario.app.data.TrainStop
import org.junit.Test

class SelectedServiceReconciliationTest {
    @Test
    fun `last known zero is not still verified when selected train disappears from the board`() {
        val selected = service("old", "23263").copy(stops = listOf(TrainStop("Appiano", "10:00", "10:00", null, 0, false)))
        val retained = serviceWithoutCurrentObservation(selected)
        assertFalse(retained.operationalStatusVerified)
        assertNull(retained.officialDelayMinutes)
        assertEquals(false, retained.stops.single().operationalStatusVerified)
        assertEquals("10:00", retained.expectedDeparture)
    }
    @Test
    fun `exact service id wins when snapshot is refreshed`() {
        val selected = service(id = "old", number = "9512")
        val refreshed = service(id = "old", number = "9512", expectedDeparture = "10:07")

        assertSame(refreshed, findRefreshedService(selected, listOf(refreshed)))
    }

    @Test
    fun `stable public identity can reattach when provider regenerates id`() {
        val selected = service(id = "old", number = "9512")
        val refreshed = service(id = "new", number = "9512", expectedDeparture = "10:07")

        assertSame(refreshed, findRefreshedService(selected, listOf(refreshed)))
    }

    @Test
    fun `ambiguous different run is not rebound to new snapshot provenance`() {
        val selected = service(id = "old", number = "9512")
        val differentRun = service(id = "new", number = "9512", scheduledDeparture = "11:00")

        assertNull(findRefreshedService(selected, listOf(differentRun)))
    }

    // --- «Più presto» / «Più tardi»: windows read once must not stay "live" for ever (audit2 android-state-3) ---

    private val route = SavedRoute("r", "Roma – Milano", Station("a", "Roma Termini", "Roma"), Station("b", "Milano Centrale", "Milano"))

    private fun page(expiresAt: Long, vararg services: TrainService) = RouteSnapshot(
        route = route, direction = Direction.OUTBOUND, services = services.toList(), providerName = "Test",
        retrievedAtEpochMillis = expiresAt - 60_000, expiresAtEpochMillis = expiresAt, freshness = Freshness.FRESH
    )

    private fun live(id: String, number: String, delay: Int) = service(id, number).copy(
        delayMinutes = delay, officialDelayMinutes = delay, operationalStatusVerified = true,
        stops = listOf(TrainStop("Roma Termini", "10:00", "10:05", "4", delay, false, operationalStatusVerified = true))
    )

    @Test
    fun `a window keeps its live values only until it expires`() {
        val window = page(expiresAt = 100_000, live("late", "9631", 5))
        // Still current: the very same object, so nothing is rebuilt.
        assertSame(window, pageWithoutExpiredObservations(window, 100_000))
        // An hour and a half later the row no longer claims «+5, in tempo reale».
        val later = pageWithoutExpiredObservations(window, 100_000 + 90 * 60_000)
        assertEquals(Freshness.STALE, later.freshness)
        val row = later.services.single()
        assertFalse(row.operationalStatusVerified)
        assertNull(row.officialDelayMinutes)
        assertEquals(false, row.stops.single().operationalStatusVerified)
        assertEquals("9631", row.number)
        // Already stripped: not rebuilt at every refresh.
        assertSame(later, pageWithoutExpiredObservations(later, 100_000 + 91 * 60_000))
    }

    @Test
    fun `the open train of a window is replaced by its new reading and the others keep their age`() {
        val first = page(100_000, live("open", "9631", 5), live("other", "9633", 0))
        val second = page(200_000, live("alone", "9635", 0))
        val reading = page(900_000, live("open", "9631", 40))
        val pages = pagesWithRefreshedService(listOf(first, second), "open", reading)
        assertEquals(listOf("other", "alone", "open"), pages.flatMap { it.services }.map { it.id })
        assertEquals(40, pages.last().services.single().delayMinutes)
        assertEquals(listOf(100_000L, 200_000L, 900_000L), pages.map { it.expiresAtEpochMillis })
        // A window that held only that train goes; reading it again later replaces the reading, never duplicates it.
        val again = pagesWithRefreshedService(pages, "open", page(950_000, live("open", "9631", 42)))
        assertEquals(listOf("other", "alone", "open"), again.flatMap { it.services }.map { it.id })
        assertEquals(42, again.last().services.single().delayMinutes)
        assertTrue(again.none { it.services.isEmpty() })
    }

    private fun service(
        id: String,
        number: String,
        scheduledDeparture: String = "10:00",
        expectedDeparture: String = scheduledDeparture
    ) = TrainService(
        id = id,
        number = number,
        operator = RailOperator.TRENITALIA_LONG_DISTANCE,
        trainCategory = TrainCategory.OTHER,
        origin = "Roma Termini",
        destination = "Milano Centrale",
        scheduledDeparture = scheduledDeparture,
        expectedDeparture = expectedDeparture,
        platform = null,
        delayMinutes = 0,
        status = ServiceStatus.ON_TIME,
        stops = emptyList()
    )
}
