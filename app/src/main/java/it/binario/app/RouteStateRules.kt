package it.binario.app

import it.binario.app.data.RailTime
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The rules of [TrainViewModel] about the route it shows (which route is active, what is stored, what a saved copy
 * keeps), kept free of Android so that they can be tested on the JVM: the view model only applies them to its stores
 * and state.
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

    /**
     * The saved copy of a route shown after a failed refresh, like the saved copy of a station board: only when it was
     * saved today, and without the trains that left more than ten minutes ago. Null when none is left, so that the
     * morning's trains are never listed in the evening as the next departures. A train whose departure cannot be read
     * stays: nothing says it has left.
     */
    fun savedRouteCopy(saved: RouteSnapshot, now: Instant, zone: ZoneId = RailTime.defaultZone): RouteSnapshot? {
        val savedOn = Instant.ofEpochMilli(saved.retrievedAtEpochMillis).atZone(zone).toLocalDate()
        if (savedOn != now.atZone(zone).toLocalDate()) return null
        val from = now.minus(SAVED_COPY_DEPARTED_GRACE)
        val services = saved.services.filter { service ->
            val departure = service.scheduledDepartureDateTime
                ?: runCatching { savedOn.atTime(LocalTime.parse(service.scheduledDeparture)) }.getOrNull()
            departure == null || !departure.atZone(zone).toInstant().isBefore(from)
        }
        return if (services.isEmpty()) null else saved.copy(services = services)
    }

    private val SAVED_COPY_DEPARTED_GRACE: Duration = Duration.ofMinutes(10)

    /**
     * The service day a search by train number asks for. A new search asks for [today]. Its refreshes keep the day the
     * results on screen are for ([searchedDate]) while a train of that day may still run: the 22:30 train followed
     * past midnight is still the run of the day before, and asking for the new day would lose it. A search older than
     * yesterday is asked again for today.
     */
    fun numberSearchDate(refresh: Boolean, searchedDate: LocalDate, today: LocalDate): LocalDate =
        if (refresh && (searchedDate == today || searchedDate == today.minusDays(1))) searchedDate else today

    /**
     * Whether a route load still in flight is cancelled when a newer one starts. Its answer is thrown away either
     * way; when it asked for another route, direction or moment ([previousKey] against [nextKey]) cancelling it stops
     * requests to the public sources nobody will read. When it asked for the same, the two share their requests in
     * flight, and cancelling the first could make the second ask them again: it is left to run.
     */
    fun cancelsSupersededLoad(previousKey: String, nextKey: String): Boolean = previousKey != nextKey
}
