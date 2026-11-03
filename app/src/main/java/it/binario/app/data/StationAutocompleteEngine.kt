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
            normalizedName = StationSearchNormalizer.normalize(station.name),
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

    /**
     * The index is immutable, so it can safely be retained across recompositions.
     *
     * Matches are ranked as on iOS and in the Companion: a station whose own name equals the
     * query comes first, then names starting with it, then names with a word starting with it,
     * then names containing it; a match on an alias, city or code ranks one step behind the same
     * match on the name, and a match spread over several words comes last. Ties keep the
     * alphabetical order of the name, so "Roma" and "Roma Termini" lead the suggestions for
     * "Roma" instead of whichever substring matches the catalogue lists first.
     */
    fun suggest(query: String, limit: Int = DEFAULT_LIMIT): List<Station> {
        val normalizedQuery = StationSearchNormalizer.normalize(query)
        if (normalizedQuery.length < MIN_QUERY_LENGTH || limit <= 0) return emptyList()
        val tokens = normalizedQuery.split(' ')
        return indexedStations.asSequence()
            .mapNotNull { indexed -> indexed.rank(normalizedQuery, tokens)?.let { rank -> rank to indexed } }
            .sortedWith(compareBy({ (rank, _) -> rank }, { (_, indexed) -> indexed.normalizedName }))
            .map { (_, indexed) -> indexed.station }
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
        val normalizedName: String,
        val normalizedValues: List<String>
    ) {
        /** Lower ranks first; null when the station does not match at all. */
        fun rank(normalizedQuery: String, tokens: List<String>): Int? {
            val byName = matchRank(normalizedName, normalizedQuery)
            val byOtherValue = normalizedValues.asSequence()
                .filter { it != normalizedName }
                .mapNotNull { matchRank(it, normalizedQuery) }
                .minOrNull()
                ?.plus(1)
            return listOfNotNull(byName, byOtherValue).minOrNull()
                ?: TOKENS_RANK.takeIf { normalizedValues.any { candidate -> tokens.all(candidate::contains) } }
        }

        private fun matchRank(candidate: String, normalizedQuery: String): Int? = when {
            candidate == normalizedQuery -> EXACT_RANK
            candidate.startsWith(normalizedQuery) -> PREFIX_RANK
            candidate.split(' ').any { word -> word.startsWith(normalizedQuery) } -> WORD_PREFIX_RANK
            candidate.contains(normalizedQuery) -> CONTAINS_RANK
            else -> null
        }
    }

    private companion object {
        const val MIN_QUERY_LENGTH = 2
        const val DEFAULT_LIMIT = 8
        const val EXACT_RANK = 0
        const val PREFIX_RANK = 1
        const val WORD_PREFIX_RANK = 2
        const val CONTAINS_RANK = 3
        const val TOKENS_RANK = 5
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
