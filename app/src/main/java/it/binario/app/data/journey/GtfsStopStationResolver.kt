package it.binario.app.data.journey

import it.binario.app.data.RailStopNameMatcher
import it.binario.app.data.Station
import it.binario.app.data.isNamedBy
import java.text.Normalizer
import java.util.Locale

/**
 * Resolves stop names published by the embedded GTFS packs to stations of the curated catalogue, so that the
 * stops of different packs and the platforms of one station become a single transfer point.
 *
 * A stop resolves only when exactly one catalogue station answers, tried in this order:
 * 1. normalized equality with a catalogue name or a same-station alias (the aliases the route search uses: no
 *    codes, no translations that share no word with the name), on the published name and on its variants: the
 *    part after "Stazione di" ("Brennero, Stazione di Brennero"), the part after "Town, ", without a one- or
 *    two-digit platform suffix ("Linz Centrale 1"), a country suffix ("Luino (I)") or a trailing "stazione";
 * 2. the bilingual names of South Tyrol, where the catalogue writes "Merano Meran" or "Laces/Latsch" and the
 *    packs only the Italian half: the published words must be the leading words of exactly one station inside
 *    the province (the one with the fewest extra words), so "Verona" never becomes "Verona Parona";
 * 3. the strict whole-name matcher of the timetables ([RailStopNameMatcher]: "Bologna C.le", "S. Vitale").
 * Anything else stays a station of its own pack (never merged across packs by a guess).
 */
internal class GtfsStopStationResolver(stations: List<Station>) {
    private val byId: Map<String, Station> = stations.associateBy(Station::id)
    private val byKey: Map<String, Station>
    private val ambiguousKeys: Set<String>
    private val bilingualByPrefix: Map<String, List<Pair<Station, Int>>>
    private val byToken: Map<String, List<Station>>

    init {
        val keyed = HashMap<String, MutableSet<String>>()
        for (station in stations) {
            for (key in candidateKeys(station)) keyed.getOrPut(key) { linkedSetOf() } += station.id
        }
        byKey = keyed.filterValues { it.size == 1 }.mapValues { byId.getValue(it.value.single()) }
        ambiguousKeys = keyed.filterValues { it.size > 1 }.keys

        val prefixes = HashMap<String, MutableList<Pair<Station, Int>>>()
        for (station in stations) {
            if (!inSouthTyrol(station)) continue
            val tokens = RailStopNameMatcher.tokens(station.name)
            for (length in 1 until tokens.size) {
                prefixes.getOrPut(tokens.subList(0, length).joinToString(" ")) { mutableListOf() } += station to (tokens.size - length)
            }
        }
        bilingualByPrefix = prefixes

        val tokens = HashMap<String, MutableList<Station>>()
        for (station in stations) {
            RailStopNameMatcher.tokens(station.name).filter { it.length >= 4 }.toSet().forEach { token ->
                tokens.getOrPut(token) { mutableListOf() } += station
            }
        }
        byToken = tokens
    }

    fun station(id: String): Station? = byId[id]

    fun resolve(stopName: String): Station? {
        val variants = variants(stopName)
        for (variant in variants) byKey[normalize(variant)]?.let { return it }
        if (variants.any { normalize(it) in ambiguousKeys }) return null
        for (variant in variants) {
            val candidates = bilingualByPrefix[RailStopNameMatcher.tokens(variant).joinToString(" ")] ?: continue
            val fewest = candidates.minOf { it.second }
            val best = candidates.filter { it.second == fewest }.map { it.first }.distinctBy(Station::id)
            if (best.size == 1) return best.single()
        }
        for (variant in variants) {
            val candidates = RailStopNameMatcher.tokens(variant).filter { it.length >= 4 }
                .flatMap { byToken[it].orEmpty() }
                .distinctBy(Station::id)
            val matches = candidates.filter { it.isNamedBy(variant) }
            if (matches.size == 1) return matches.single()
        }
        return null
    }

    /** Normalized keys of the published name and its variants, compared with [candidateKeys] of an endpoint. */
    fun matchKeys(stopName: String): List<String> = variants(stopName).map(::normalize).filter(String::isNotEmpty).distinct()

    /** A readable label for a stop the catalogue does not know: "Innsbruck Centrale" for STA's long form. */
    fun displayName(stopName: String): String {
        val trimmed = stopName.trim()
        val afterStation = STATION_OF.find(trimmed)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty) ?: trimmed
        return afterStation.replace(TRAILING_PLATFORM, "").trim().ifEmpty { trimmed }
    }

    companion object {
        private val combiningMarks = Regex("\\p{M}+")
        private val nonAlphanumeric = Regex("[^a-z0-9]")
        private val STATION_OF = Regex("(?i)\\bstazione(?:\\s+di)?\\s+(.+)$")
        private val TRAILING_PLATFORM = Regex("\\s+\\d{1,2}[A-Za-z]?$")
        private val COUNTRY_SUFFIX = Regex("\\s*\\((?:I|IT|CH|A|AT|F|FR|D|DE|SLO|HR)\\)$", RegexOption.IGNORE_CASE)
        private val STATION_SUFFIX = Regex("(?i)\\s+(?:stazione|bahnhof|bhf)$")
        private val MAIN_STATION_ABBREVIATION = Regex("(?i)\\bHbf\\b\\.?")

        /** The same normalization as the route search's GTFS stop matching. */
        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
            .replace(nonAlphanumeric, "")

        /**
         * The catalogue name plus the aliases that are other spellings of the same station, as the route search
         * matches GTFS stops: aliases sharing no word with the name are translations ("Mailand", "Chaumont") and
         * would bind namesakes abroad; code-like aliases never take part.
         */
        fun candidateKeys(station: Station): Set<String> {
            val nameWords = RailStopNameMatcher.tokens(station.name).filter { it.length >= 4 }.toSet()
            val names = listOf(station.name) + station.aliases.filter { alias ->
                !alias.contains('_') && !alias.startsWith("@") && normalize(alias).length >= 3 &&
                    RailStopNameMatcher.tokens(alias).any { it in nameWords }
            }
            return names.flatMap { listOf(it, expandAbbreviations(it)) }
                .map(::normalize).filter(String::isNotEmpty).toCollection(linkedSetOf())
        }

        /** "Innsbruck Hbf." and the ÖBB "Innsbruck Hauptbahnhof" are the same station. */
        private fun expandAbbreviations(value: String): String = value.replace(MAIN_STATION_ABBREVIATION, "Hauptbahnhof")

        internal fun variants(stopName: String): List<String> {
            val trimmed = stopName.trim()
            if (trimmed.isEmpty()) return emptyList()
            val base = linkedSetOf(trimmed)
            STATION_OF.find(trimmed)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)?.let(base::add)
            if (',' in trimmed) trimmed.substringAfter(',').trim().takeIf(String::isNotEmpty)?.let(base::add)
            val all = linkedSetOf<String>()
            for (value in base) {
                all += value
                val cleaned = value.replace(TRAILING_PLATFORM, "").replace(COUNTRY_SUFFIX, "").replace(STATION_SUFFIX, "").trim()
                if (cleaned.isNotEmpty()) all += cleaned
                val expanded = expandAbbreviations(cleaned)
                if (expanded.isNotEmpty()) all += expanded
            }
            return all.toList()
        }

        /** Autonomous Province of Bolzano, where station names are officially bilingual. */
        private fun inSouthTyrol(station: Station): Boolean {
            val latitude = station.latitude ?: return false
            val longitude = station.longitude ?: return false
            return latitude in 46.20..47.10 && longitude in 10.35..12.50
        }
    }
}
