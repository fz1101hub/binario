package it.binario.app.data

import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.shared.SharedData

/**
 * A ready route between a city station and an airport station, both of the curated catalogue (ids as the catalogue
 * loader builds them, `curated:<id>`), served by the trains of at least one of [packs] (for Bologna, the Marconi
 * Express people mover of a city pack, reached with short walks from both stations).
 */
data class AirportRoute(val cityStationId: String, val airportStationId: String, val packs: Set<String>)

/** The route with its catalogue stations, as the planner list shows it. */
data class AirportLink(val route: AirportRoute, val city: Station, val airport: Station)

/**
 * «Collegamenti con gli aeroporti» of the planner: only pairs whose stations are both in the curated catalogue and
 * whose trains (or people mover) the offline planner finds on a weekday in the packs every build carries
 * (AirportRoutesTest). An entry
 * shows only when one of its [AirportRoute.packs] is in the build, so a pack shipped in personal builds only never
 * makes an entry appear in a store build. Station names are the catalogue's; the timetables carry no service brand
 * ("Leonardo Express", "Malpensa Express"), so none is shown.
 */
object AirportRoutes {
    /** The shared table contracts/shared-data/v1/airport-routes-v1.json ([SharedData.AirportRoutes]), the iPhone's too. */
    val all: List<AirportRoute> = SharedData.AirportRoutes.ROUTES.map { route ->
        AirportRoute("curated:${route.city}", "curated:${route.airport}", route.packs)
    }

    /**
     * Packs whose airport line is a city line, not a train (the Marconi Express people mover): their rows say «Cerca le
     * corse», every other row «Cerca i treni». Flagged in the table, not inferred from the asset directory:
     * `gtfs-transit/` also holds regional railways (Ferrotramviaria serves Bari Aeroporto with real trains).
     */
    private val CITY_LINE_PACKS = SharedData.AirportRoutes.PACKS.filter { it.cityLine }.mapTo(HashSet()) { it.id }

    /** Whether [route] is served only by a city line (a people mover, a metro), so its rows do not say "trains". */
    fun byCityLine(route: AirportRoute): Boolean = route.packs.isNotEmpty() && route.packs.all(CITY_LINE_PACKS::contains)

    /** The routes whose trains are in [packs] (the packs of this build). */
    fun forBuild(packs: Collection<EmbeddedGtfsPack>): List<AirportRoute> {
        val ids = packs.mapTo(HashSet()) { it.sourceId.value }
        return all.filter { route -> route.packs.any(ids::contains) }
    }

    /** The routes of [routes] whose two stations [station] finds in the catalogue, with those stations. */
    fun links(routes: List<AirportRoute>, station: (String) -> Station?): List<AirportLink> = routes.mapNotNull { route ->
        val city = station(route.cityStationId) ?: return@mapNotNull null
        val airport = station(route.airportStationId) ?: return@mapNotNull null
        AirportLink(route, city, airport)
    }
}
