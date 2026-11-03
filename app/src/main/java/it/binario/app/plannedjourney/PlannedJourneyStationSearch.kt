package it.binario.app.plannedjourney

import java.text.Normalizer
import java.util.Locale

/** Build once off the UI thread; names, accents and station codes share one deterministic index. */
class PlannedJourneyStationSearch(stations: List<PlannedJourneyStation>) {
    private data class Entry(val station: PlannedJourneyStation, val name: String, val fields: List<String>)
    private val entries = stations.distinctBy { it.canonicalId }.map { station ->
        val name = normalize(station.displayName)
        Entry(station, name, listOfNotNull(name, station.railwayCode?.let(::normalize), normalize(station.canonicalId)))
    }

    fun suggestions(query: String): List<PlannedJourneyStation> {
        if (query.length > 120) return emptyList()
        val normalized = normalize(query)
        if (normalized.isBlank()) return emptyList()
        val terms = normalized.split(' ')
        return entries.mapNotNull { entry ->
            val score = when {
                entry.fields.any { it == normalized } -> 0
                entry.fields.any { it.startsWith(normalized) } -> 1
                terms.all { term -> entry.fields.any { term in it } } -> 2
                else -> return@mapNotNull null
            }
            entry to score
        }.sortedWith(compareBy<Pair<Entry, Int>> { it.second }.thenBy { it.first.name }.thenBy { it.first.station.canonicalId })
            .take(6).map { it.first.station }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
