package it.binario.app.data

/**
 * Reconciles persisted or external station values with the current catalog without
 * using autocomplete's partial matching rules.
 *
 * Stable IDs always win globally. Values whose ID is not in the catalog may fall
 * back only to an unambiguous normalized equality over station names and aliases.
 */
internal class StationCanonicalizer(stations: List<Station>) {
    private val stationsById = stations.associateBy(Station::id)
    private val stationsByUniqueNameOrAlias: Map<String, Station> = buildMap {
        stations.asSequence()
            .flatMap { station -> station.identityNames().map { name -> StationSearchNormalizer.normalize(name) to station } }
            .filter { (normalizedName, _) -> normalizedName.isNotBlank() }
            .groupBy({ it.first }, { it.second })
            .forEach { (normalizedName, matches) ->
                matches.distinctBy(Station::id).singleOrNull()?.let { put(normalizedName, it) }
            }
    }

    fun canonicalize(station: Station): Station {
        stationsById[station.id]?.let { return it }
        // A metro or tram stop stays that stop: "Termini" of the Rome metro is not Roma Termini by name.
        if (it.binario.app.data.gtfs.EmbeddedGtfsPacks.isUrbanStopId(station.id)) return station

        val matches = station.identityNames()
            .asSequence()
            .map(StationSearchNormalizer::normalize)
            .filter(String::isNotBlank)
            .mapNotNull(stationsByUniqueNameOrAlias::get)
            .distinctBy(Station::id)
            .toList()

        return matches.singleOrNull() ?: station
    }

    private fun Station.identityNames(): Sequence<String> = sequence {
        yield(name)
        yieldAll(aliases)
    }
}
