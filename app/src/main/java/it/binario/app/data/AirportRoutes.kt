package it.binario.app.data

import it.binario.app.data.gtfs.EmbeddedGtfsPack

/**
 * A ready route between a city station and an airport station, both of the curated catalogue (ids as the catalogue
 * loader builds them, `curated:<id>`), served by the trains of at least one of [packs].
 */
data class AirportRoute(val cityStationId: String, val airportStationId: String, val packs: Set<String>)

/** The route with its catalogue stations, as the planner list shows it. */
data class AirportLink(val route: AirportRoute, val city: Station, val airport: Station)

/**
 * «Collegamenti con gli aeroporti» of the planner: only pairs whose stations are both in the curated catalogue and
 * whose trains the offline planner finds on a weekday in the packs every build carries (AirportRoutesTest). An entry
 * shows only when one of its [AirportRoute.packs] is in the build, so a pack shipped in personal builds only never
 * makes an entry appear in a store build. Station names are the catalogue's; the timetables carry no service brand
 * ("Leonardo Express", "Malpensa Express"), so none is shown.
 */
object AirportRoutes {
    private const val TRENITALIA = "trenitalia-gtfs"
    private const val TRENORD = "lombardia-trenord-gtfs"
    private const val FERROTRAMVIARIA = "ferrotramviaria-bari-barletta"

    val all: List<AirportRoute> = listOf(
        // Roma Termini ↔ Fiumicino Aeroporto (Trenitalia regional express).
        AirportRoute("curated:S08409", "curated:S08411", setOf(TRENITALIA)),
        // Milano Centrale and Milano Cadorna ↔ Malpensa Aeroporto (Terminal 1; Trenord RE51 and RE54).
        AirportRoute("curated:S01700", "curated:S01139", setOf(TRENORD)),
        AirportRoute("curated:S01066", "curated:S01139", setOf(TRENORD)),
        // Bari ↔ Bari Aeroporto (Ferrotramviaria; "Bari" is the catalogue station of its stop at Bari Centrale).
        AirportRoute("curated:NC000634", "curated:NC001565", setOf(FERROTRAMVIARIA)),
        // Palermo Centrale ↔ Punta Raisi (Palermo Aeroporto).
        AirportRoute("curated:S12002", "curated:S12133", setOf(TRENITALIA)),
        // Cagliari ↔ Elmas Aeroporto.
        AirportRoute("curated:S12891", "curated:S12819", setOf(TRENITALIA)),
        // Trieste Centrale ↔ Trieste Airport.
        AirportRoute("curated:S03317", "curated:S03213", setOf(TRENITALIA)),
        // Torino Porta Susa ↔ Caselle Torinese Aeroporto (SFM A).
        AirportRoute("curated:S00035", "curated:S00088", setOf(TRENITALIA))
    )

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
