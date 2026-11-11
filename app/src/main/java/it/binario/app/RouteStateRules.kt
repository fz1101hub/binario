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
}
