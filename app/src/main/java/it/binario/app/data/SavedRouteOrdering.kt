package it.binario.app.data

/**
 * Keeps personal route ordering local and independent from railway timetable
 * ordering. Pinned routes remain a separate group and never imply live-data
 * priority.
 */
object SavedRouteOrdering {
    const val MAX_ROUTES = 32

    fun ordered(routes: List<SavedRoute>): List<SavedRoute> = routes
        .distinctBy { it.id }
        .take(MAX_ROUTES)
        .sortedWith(
            compareByDescending<SavedRoute> { it.isPinned }
                .thenBy { validRank(it.manualOrder) ?: Int.MAX_VALUE }
                .thenBy { it.name.lowercase() }
        )

    fun canMove(routes: List<SavedRoute>, routeId: String, delta: Int): Boolean {
        if (delta !in setOf(-1, 1)) return false
        val current = ordered(routes)
        val index = current.indexOfFirst { it.id == routeId }
        val neighbor = current.getOrNull(index + delta) ?: return false
        return index >= 0 && current[index].isPinned == neighbor.isPinned
    }

    fun move(routes: List<SavedRoute>, routeId: String, delta: Int): List<SavedRoute> {
        if (!canMove(routes, routeId, delta)) return ordered(routes)
        val reordered = ordered(routes).toMutableList()
        val index = reordered.indexOfFirst { it.id == routeId }
        val neighborIndex = index + delta
        val current = reordered[index]
        reordered[index] = reordered[neighborIndex]
        reordered[neighborIndex] = current
        return normalize(reordered)
    }

    /** Assigns dense, deterministic ranks within the pin group after a user move. */
    fun normalize(routes: List<SavedRoute>): List<SavedRoute> {
        val current = routes.distinctBy { it.id }.take(MAX_ROUTES)
        var pinnedRank = 0
        var regularRank = 0
        return current.map { route ->
            route.copy(manualOrder = if (route.isPinned) pinnedRank++ else regularRank++)
        }
    }

    private fun validRank(value: Int?): Int? = value?.takeIf { it in 0 until MAX_ROUTES }
}
