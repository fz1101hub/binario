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

    private val exactMatches: Map<String, Station> = buildMap {
        indexedStations.forEach { indexed ->
            indexed.normalizedValues.forEach { value -> putIfAbsent(value, indexed.station) }
        }
    }

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

    fun findExact(value: String): Station? = exactMatches[StationSearchNormalizer.normalize(value)]

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
