package it.binario.app.data

import java.text.Normalizer
import java.util.Locale

/**
 * Autocomplete locale senza dipendenze Android. I valori cercabili vengono
 * normalizzati una sola volta alla costruzione dell'indice, non per ogni frame
 * Compose o carattere digitato.
 */
class StationAutocompleteEngine(stations: List<Station>) {
    private val indexedStations: List<IndexedStation> = stations.map { station ->
        IndexedStation(
            station = station,
            normalizedValues = station.searchableValues()
                .asSequence()
                .map(StationSearchNormalizer::normalize)
                .filter(String::isNotBlank)
                .distinct()
                .toList()
        )
    }

    private val stationsByName: Map<String, List<Station>> = stations
        .groupBy { StationSearchNormalizer.normalize(it.name) }
        .filterKeys(String::isNotBlank)

    private val stationsByOtherValue: Map<String, List<Station>> = indexedStations
        .flatMap { indexed ->
            val ownName = StationSearchNormalizer.normalize(indexed.station.name)
            indexed.normalizedValues.filter { it != ownName }.map { it to indexed.station }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, matches) -> matches.distinctBy(Station::id) }

    /** The index is immutable, so it can safely be retained across recompositions. */
    fun suggest(query: String, limit: Int = DEFAULT_LIMIT): List<Station> {
        val normalizedQuery = StationSearchNormalizer.normalize(query)
        if (normalizedQuery.length < MIN_QUERY_LENGTH || limit <= 0) return emptyList()
        val tokens = normalizedQuery.split(' ')
        return indexedStations.asSequence()
            .filter { indexed -> indexed.matches(normalizedQuery, tokens) }
            .map(IndexedStation::station)
            .take(limit)
            .toList()
    }

    /**
     * Resolves typed or previously selected text to one station.
     *
     * A station's own name always wins over another station's alias, city or code: "Milano
     * Centrale" must never resolve to whichever earlier catalogue entry lists that text as an
     * alias (Opus audit N-M3). Among several entries with the same name the curated one with a
     * railway code is preferred. An alias shared by stations with different railway codes is
     * ambiguous and resolves to nothing, so the caller can ask instead of guessing.
     */
    fun findExact(value: String): Station? {
        val normalized = StationSearchNormalizer.normalize(value)
        if (normalized.isBlank()) return null
        stationsByName[normalized]?.let { return preferred(it) }
        val others = stationsByOtherValue[normalized] ?: return null
        val identities = others.map { it.railwayCode ?: it.id }.distinct()
        return if (identities.size == 1) preferred(others) else null
    }

    private fun preferred(candidates: List<Station>): Station =
        candidates.firstOrNull { it.railwayCode != null }
            ?: candidates.firstOrNull { it.trenitaliaLocationId != null }
            ?: candidates.first()

    private data class IndexedStation(
        val station: Station,
        val normalizedValues: List<String>
    ) {
        fun matches(normalizedQuery: String, tokens: List<String>): Boolean = normalizedValues.any { candidate ->
            candidate.contains(normalizedQuery) || tokens.all(candidate::contains)
        }
    }

    private companion object {
        const val MIN_QUERY_LENGTH = 2
        const val DEFAULT_LIMIT = 8
    }
}

/** Compiled once and shared by curated, GTFS and free-text station search. */
object StationSearchNormalizer {
    private val combiningMarks = "\\p{M}+".toRegex()
    private val abbreviationDots = "(?<=\\p{L})\\.(?=\\p{L})".toRegex()
    private val nonAlphanumeric = "[^a-z0-9]+".toRegex()

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(combiningMarks, "")
        .lowercase(Locale.ROOT)
        .replace(abbreviationDots, "")
        .replace(nonAlphanumeric, " ")
        .trim()
}
