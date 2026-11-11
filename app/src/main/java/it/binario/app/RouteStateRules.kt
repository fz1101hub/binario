package it.binario.app

import it.binario.app.data.SavedRoute

/**
 * The rules of [TrainViewModel] about which route is active, kept free of Android so that they can be tested on the
 * JVM: the view model only applies them to its stores and state.
 */
internal object RouteStateRules {
    /** The route the stored identifier names among [routes]; without it the first one; without routes the placeholder. */
    fun storedActiveRoute(routes: List<SavedRoute>, storedId: String?): SavedRoute =
        routes.firstOrNull { it.id == storedId } ?: routes.firstOrNull() ?: unconfiguredRoute()

    /**
     * The active route once the station catalogue has loaded and every saved route can be decoded ([routes]). Before
     * that only the routes with remembered stations decode, so the route resolved at construction ([current]) may be
     * another one than the stored identifier names: unless the user chose a route meanwhile ([routeChosenInSession]),
     * the stored identifier is resolved again. A route chosen meanwhile stays, in its saved or [canonical] form.
     */
    fun activeRouteAfterCatalogue(
        current: SavedRoute,
        routes: List<SavedRoute>,
        storedId: String?,
        routeChosenInSession: Boolean,
        canonical: (SavedRoute) -> SavedRoute
    ): SavedRoute =
        if (routeChosenInSession) routes.firstOrNull { it.id == current.id } ?: canonical(current)
        else storedActiveRoute(routes, storedId)

    /**
     * The active route after a saved route was pinned or unpinned ([routes] is the new list): the same route, in its
     * new form when it is a saved one. A route that is not saved (a search in Pianifica, a widget's route) stays as
     * it is: pinning another route is not a reason to forget it.
     */
    fun activeRouteAfterPin(current: SavedRoute, routes: List<SavedRoute>): SavedRoute =
        routes.firstOrNull { it.id == current.id } ?: current

    /**
     * Whether the direction shown is stored. One direction is stored, for the route the next start opens
     * ([storedActiveRoute]): a search in Pianifica, or a change of direction on a searched route, is not that route
     * and must not turn «Casa – Lavoro» left on «Ritorno» back to «Andata».
     */
    fun storesDirection(active: SavedRoute, routes: List<SavedRoute>, storedId: String?): Boolean =
        active.id != UNCONFIGURED_ROUTE_ID && storedActiveRoute(routes, storedId).id == active.id
}
