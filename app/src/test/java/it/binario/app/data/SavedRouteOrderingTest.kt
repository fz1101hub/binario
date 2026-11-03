package it.binario.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedRouteOrderingTest {
    private val roma = Station("roma", "Roma Termini", "Roma")
    private val torino = Station("torino", "Torino Porta Nuova", "Torino")

    @Test
    fun `pinned routes precede manual ranks and legacy names remain deterministic`() {
        val routes = listOf(
            route("p1", "Pendolare", pinned = true, order = 1),
            route("b", "Beta"),
            route("p0", "Lavoro", pinned = true, order = 0),
            route("a", "Alfa")
        )

        assertEquals(listOf("p0", "p1", "a", "b"), SavedRouteOrdering.ordered(routes).map { it.id })
    }

    @Test
    fun `move reorders only within the same pinned group and persists dense ranks`() {
        val routes = listOf(
            route("pinned", "Fissata", pinned = true, order = 0),
            route("a", "Alfa", order = 0),
            route("b", "Beta", order = 1)
        )

        assertEquals(listOf("pinned", "a", "b"), SavedRouteOrdering.move(routes, "a", -1).map { it.id })
        val moved = SavedRouteOrdering.move(routes, "b", -1)
        assertEquals(listOf("pinned", "b", "a"), moved.map { it.id })
        assertEquals(listOf(0, 0, 1), moved.map { it.manualOrder })
    }

    @Test
    fun `out of range rank cannot outrank a valid user order`() {
        val routes = listOf(route("invalid", "Alfa", order = 99), route("valid", "Beta", order = 0))

        assertEquals(listOf("valid", "invalid"), SavedRouteOrdering.ordered(routes).map { it.id })
    }

    private fun route(id: String, name: String, pinned: Boolean = false, order: Int? = null) =
        SavedRoute(id, name, roma, torino, pinned, order)
}
