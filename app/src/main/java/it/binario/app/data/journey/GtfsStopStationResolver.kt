package it.binario.app.data.journey

import it.binario.app.data.RailStopNameMatcher
import it.binario.app.data.Station
import it.binario.app.data.gtfs.GtfsStationCodeIndex
import it.binario.app.data.gtfs.GtfsStopCodeScheme
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
 * When the stop and the station both have coordinates, a station a rule names counts only within [MAX_NAME_MATCH_KM] of
 * the stop: the Swiss pack's "Guarda" (Engadin) is not the Guarda near Ferrara, 266 km away, and "Steinach" (SG) is not
 * Steinach in Tirol. A catalogue entry without coordinates is bound by its unique name, as no distance can be checked.
 * Anything else stays a station of its own pack (never merged across packs by a guess), unless [nearbyStation] places
 * it, by its coordinates, at a catalogue station under the same name ("Verona" of the STA pack, 14 m from Verona
 * Porta Nuova).
 *
 * The stops of a pack that publishes station codes ([GtfsStopCodeScheme]: Trenitalia, Italo) are first resolved by
 * their code ([resolveStop], [GtfsStationCodeIndex]); the name rules above only see the stops no code binds.
 */
internal class GtfsStopStationResolver(stations: List<Station>) {
    private val byId: Map<String, Station> = stations.associateBy(Station::id)
    private val byKey: Map<String, Station>
    private val ambiguousKeys: Set<String>
    private val bilingualByPrefix: Map<String, List<Pair<Station, Int>>>
    private val byToken: Map<String, List<Station>>
    private val codes = GtfsStationCodeIndex(stations)
    /** Catalogue stations with coordinates on a grid of [SNAP_CELL_DEGREES], for [nearbyStation]. */
    private val grid: Map<Long, List<Station>> = stations
        .filter { it.latitude != null && it.longitude != null }
        .groupBy { cell(it.latitude!!, it.longitude!!) }

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

    /** A stop of a pack: by the code it publishes under [scheme] when one binds it, otherwise by its name. */
    fun resolveStop(scheme: GtfsStopCodeScheme, code: String, latitude: Double?, longitude: Double?, stopName: String): Station? =
        resolveCode(scheme, code, latitude, longitude, stopName) ?: resolve(stopName, latitude, longitude)

    /** Only the code rule of [resolveStop] (see [GtfsStationCodeIndex]); null when no code binds the stop. */
    fun resolveCode(scheme: GtfsStopCodeScheme, code: String, latitude: Double?, longitude: Double?, stopName: String): Station? =
        codes.resolve(scheme, code, latitude, longitude, stopName)

    /** The station [stopName] names by the rules above, with no position to check it against (an end picked by name). */
    fun resolve(stopName: String): Station? = resolve(stopName, null, null)

    /**
     * The station [stopName] names by the rules above, for a stop at [latitude], [longitude]: a rule whose station is
     * farther than [MAX_NAME_MATCH_KM] from the stop does not bind it, and the next rule is tried ([isWithinNameBound]).
     */
    fun resolve(stopName: String, latitude: Double?, longitude: Double?): Station? {
        val variants = variants(stopName)
        for (variant in variants) byKey[normalize(variant)]?.takeIf { isWithinNameBound(it, latitude, longitude) }?.let { return it }
        if (variants.any { normalize(it) in ambiguousKeys }) return null
        for (variant in variants) {
            val candidates = bilingualByPrefix[RailStopNameMatcher.tokens(variant).joinToString(" ")] ?: continue
            val fewest = candidates.minOf { it.second }
            val best = candidates.filter { it.second == fewest }.map { it.first }.distinctBy(Station::id)
            if (best.size == 1 && isWithinNameBound(best.single(), latitude, longitude)) return best.single()
        }
        for (variant in variants) {
            val candidates = RailStopNameMatcher.tokens(variant).filter { it.length >= 4 }
                .flatMap { byToken[it].orEmpty() }
                .distinctBy(Station::id)
            val matches = candidates.filter { it.isNamedBy(variant) }
            if (matches.size == 1 && isWithinNameBound(matches.single(), latitude, longitude)) return matches.single()
        }
        return null
    }

    /**
     * The catalogue station a stop no name or code binds is part of: the only one within [maxMeters] of the stop whose
     * name starts with the stop's first word, when the rest of the stop's name only says that it is a station
     * ("Verona" for Verona Porta Nuova; "Innsbruck, Stazione di Innsbruck Centrale" for Innsbruck Hbf.; "Caserta RFI").
     * A stop that names something more ("Locarno FART", "Lugano FLP", "Brig Bahnhofplatz": another railway's station
     * next door), or two candidates, stays a point of its own pack and is reached by walking.
     */
    fun nearbyStation(stopName: String, latitude: Double?, longitude: Double?, maxMeters: Double = SNAP_METERS): Station? {
        if (latitude == null || longitude == null || latitude.isNaN() || longitude.isNaN()) return null
        val words = RailStopNameMatcher.tokens(displayName(stopName))
        val first = words.firstOrNull { it.length >= 4 } ?: return null
        if (words.dropWhile { it != first }.drop(1).any { it.length >= 3 && it !in STATION_WORDS }) return null
        val row = Math.floor(latitude / SNAP_CELL_DEGREES).toLong()
        val column = Math.floor(longitude / SNAP_CELL_DEGREES).toLong()
        var match: Station? = null
        for (deltaRow in -1L..1L) for (deltaColumn in -1L..1L) {
            for (station in grid[key(row + deltaRow, column + deltaColumn)] ?: continue) {
                val meters = GtfsStationCodeIndex.distanceKm(latitude, longitude, station.latitude!!, station.longitude!!) * 1000.0
                if (meters > maxMeters) continue
                if (RailStopNameMatcher.tokens(station.name).firstOrNull { it.length >= 4 } != first) continue
                if (match != null && match.id != station.id) return null
                match = station
            }
        }
        return match
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
        /** How far a stop can be from the catalogue station it is placed at ([nearbyStation]). */
        const val SNAP_METERS = 150.0
        /**
         * How far a stop a name rule binds ([resolve]) or a name key matches (the journey ends) can be from the catalogue
         * station, when both have coordinates. The bound of the iPhone index for a catalogue name
         * (tools/build_ios_gtfs_schedule.py) and of a Trenitalia location id ([GtfsStationCodeIndex.MAX_LOCATION_ID_KM]).
         * On the packs of November 2026 the farthest same-station pair is 8.4 km (the Trenitalia pack's Rocchetta
         * S. Antonio-Lacedonia) and the nearest namesake 18.7 km (the Cotral pack's Acqua Acetosa, in Rome's north).
         */
        const val MAX_NAME_MATCH_KM = 10.0

        /**
         * True when [station] can be the station of a stop at [latitude], [longitude] that only its name ties to it:
         * within [MAX_NAME_MATCH_KM], or either position unknown.
         */
        fun isWithinNameBound(station: Station, latitude: Double?, longitude: Double?): Boolean {
            if (latitude == null || longitude == null || latitude.isNaN() || longitude.isNaN()) return true
            val stationLatitude = station.latitude ?: return true
            val stationLongitude = station.longitude ?: return true
            return GtfsStationCodeIndex.distanceKm(latitude, longitude, stationLatitude, stationLongitude) <= MAX_NAME_MATCH_KM
        }

        /** About 1.1 km of latitude: [nearbyStation] reads the 3 x 3 cells around a stop. */
        private const val SNAP_CELL_DEGREES = 0.01
        /** Words that only say "station" in a stop name ([nearbyStation]): uppercase, as [RailStopNameMatcher.tokens]. */
        private val STATION_WORDS = setOf(
            "STAZIONE", "STAZ", "CENTRALE", "FS", "RFI", "FERROVIA", "FTM", "BAHNHOF", "HBF", "HAUPTBAHNHOF", "BF",
            "GARE", "STATION", "CENTRAL"
        )

        private fun key(row: Long, column: Long): Long = (row shl 32) xor (column and 0xFFFFFFFFL)

        private fun cell(latitude: Double, longitude: Double): Long =
            key(Math.floor(latitude / SNAP_CELL_DEGREES).toLong(), Math.floor(longitude / SNAP_CELL_DEGREES).toLong())

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
