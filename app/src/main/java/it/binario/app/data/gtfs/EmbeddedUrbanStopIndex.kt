package it.binario.app.data.gtfs

import it.binario.app.data.Station
import it.binario.app.data.StationSearchNormalizer
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/** The badge of a city stop in the station search: "M" metro, "T" tram, "F" funicular or lift. */
enum class UrbanStopBadge(val letter: String) {
    METRO("M"),
    TRAM("T"),
    FUNICULAR("F");

    companion object {
        /** From the modes a pack's `binario_stop_modes.txt` lists for a stop ("MT": metro and tram); metro first. */
        fun of(modes: String): UrbanStopBadge? = when {
            'M' in modes -> METRO
            'T' in modes -> TRAM
            'F' in modes -> FUNICULAR
            else -> null
        }
    }
}

/** A metro, tram or funicular stop of a city pack, as the station search offers it. */
data class UrbanStop(val station: Station, val badge: UrbanStopBadge)

/**
 * The stops of the city packs ([EmbeddedGtfsPacks.urban]) for the station search: one entry per name and pack (the
 * platforms of "Duomo" are one stop), only stops a metro, tram or funicular serves (from the pack's
 * `binario_stop_modes.txt`, written by tools/build_gtfs_packs.py). Read lazily on first use, off the main thread by
 * the caller; never touches the network. The journey planner resolves the returned `gtfs:<pack>:<stop>` ids itself.
 */
class EmbeddedUrbanStopIndex(
    private val openAsset: (String) -> InputStream,
    private val packs: List<EmbeddedGtfsPack> = EmbeddedGtfsPacks.urban
) {
    @Volatile private var entries: List<Entry>? = null
    @Volatile private var badges: Map<String, UrbanStopBadge> = emptyMap()

    /** Stops whose name contains the query (or every word of it), exact names first, at most [limit]. */
    fun search(query: String, limit: Int = 4): List<UrbanStop> {
        val normalized = StationSearchNormalizer.normalize(query)
        if (normalized.length < 2 || limit <= 0) return emptyList()
        val tokens = normalized.split(' ').filter(String::isNotEmpty)
        return load().asSequence()
            .mapNotNull { entry ->
                val rank = when {
                    entry.normalizedName == normalized -> 0
                    entry.normalizedName.startsWith(normalized) -> 1
                    entry.normalizedName.contains(normalized) -> 2
                    tokens.all(entry.normalizedName::contains) -> 3
                    else -> return@mapNotNull null
                }
                rank to entry
            }
            .sortedWith(compareBy({ it.first }, { it.second.normalizedName }))
            .map { it.second.stop }
            .take(limit)
            .toList()
    }

    /** The badge of a stop id this index returned (`gtfs:<pack>:<stop>`), once the index is loaded. */
    fun badgeOf(stationId: String): UrbanStopBadge? = badges[stationId]

    private fun load(): List<Entry> {
        entries?.let { return it }
        synchronized(this) {
            entries?.let { return it }
            val loaded = packs.filter(EmbeddedGtfsPack::isUrban).flatMap { pack ->
                try {
                    readPack(pack)
                } catch (_: java.io.IOException) {
                    emptyList()
                } catch (_: IllegalArgumentException) {
                    emptyList()
                }
            }
            badges = loaded.associate { it.stop.station.id to it.stop.badge }
            entries = loaded
            return loaded
        }
    }

    private fun readPack(pack: EmbeddedGtfsPack): List<Entry> {
        val tables = HashMap<String, List<List<String>>>()
        openAsset(pack.assetPath).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    if (name == "stops.txt" || name == STOP_MODES) {
                        tables[name] = readCsv(BufferedReader(InputStreamReader(zip, Charsets.UTF_8)))
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        val modes = tables[STOP_MODES].orEmpty().let { rows ->
            val header = rows.firstOrNull().orEmpty()
            val idIndex = header.indexOf("stop_id")
            val modesIndex = header.indexOf("modes")
            if (idIndex < 0 || modesIndex < 0) emptyMap()
            else rows.drop(1).associate { it.getOrElse(idIndex) { "" } to it.getOrElse(modesIndex) { "" } }
        }
        val stops = tables["stops.txt"].orEmpty()
        val header = stops.firstOrNull().orEmpty()
        val id = header.indexOf("stop_id")
        val name = header.indexOf("stop_name")
        val latitude = header.indexOf("stop_lat")
        val longitude = header.indexOf("stop_lon")
        val parent = header.indexOf("parent_station")
        if (id < 0 || name < 0) return emptyList()
        val city = pack.areas.firstOrNull()?.name.orEmpty()
        val network = RailSourceCatalogNames.displayName(pack)
        val byName = LinkedHashMap<String, Entry>()
        for (row in stops.drop(1)) {
            val stopId = row.getOrElse(id) { "" }.trim()
            val stopName = StopNameCase.display(row.getOrElse(name) { "" })
            if (stopId.isEmpty() || stopName.isEmpty()) continue
            // A platform without its own modes takes those of its parent station.
            val stopModes = modes[stopId] ?: row.getOrNull(parent)?.trim()?.let(modes::get) ?: continue
            val badge = UrbanStopBadge.of(stopModes) ?: continue
            val key = StationSearchNormalizer.normalize(stopName)
            val existing = byName[key]
            // One entry per name; metro wins over tram when two stops share a name ("Duomo" M1/M3 and the tram stop).
            if (existing != null && existing.stop.badge.ordinal <= badge.ordinal) continue
            byName[key] = Entry(
                normalizedName = key,
                stop = UrbanStop(
                    Station(
                        id = "gtfs:${pack.sourceId.value}:$stopId",
                        name = stopName,
                        city = city.ifEmpty { stopName },
                        region = network,
                        latitude = row.getOrNull(latitude)?.trim()?.toDoubleOrNull(),
                        longitude = row.getOrNull(longitude)?.trim()?.toDoubleOrNull()
                    ),
                    badge
                )
            )
        }
        return byName.values.toList()
    }

    private fun readCsv(reader: BufferedReader): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var line = reader.readLine()
        while (line != null) {
            if (line.length <= MAX_LINE) rows += splitCsv(line.removePrefix("\uFEFF"))
            line = reader.readLine()
        }
        return rows
    }

    private fun splitCsv(line: String): List<String> {
        val values = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && line.getOrNull(index + 1) == '"' -> { current.append('"'); index += 1 }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { values += current.toString(); current.clear() }
                else -> current.append(char)
            }
            index += 1
        }
        values += current.toString()
        return values
    }

    private class Entry(val normalizedName: String, val stop: UrbanStop)

    private companion object {
        const val STOP_MODES = "binario_stop_modes.txt"
        const val MAX_LINE = 32_768
    }
}

/** The network name shown under a city stop ("Metro e tram ATM Milano"), from the source catalogue. */
internal object RailSourceCatalogNames {
    fun displayName(pack: EmbeddedGtfsPack): String =
        it.binario.app.data.sources.RailSourceCatalog.byId(pack.sourceId)?.displayName ?: pack.sourceId.value
}

/**
 * Readable stop names for the packs that publish them all in capitals or all in lower case ("DUOMO M1",
 * "stazione centrale m2 m3", "GENOVA/PIAZZA MANIN"): words are capitalised ("Sant'Olcese", "P.za"), line codes stay
 * in capitals ("M1", "T2", "FS"), a trailing "/" is dropped and "/" between two names becomes a space. Names that
 * already mix cases are kept as published.
 */
object StopNameCase {
    fun display(name: String): String {
        val trimmed = name.trim().trimEnd('/').trim()
        val letters = trimmed.filter(Char::isLetter)
        if (letters.isEmpty() || (letters != letters.uppercase() && letters != letters.lowercase())) return trimmed
        return trimmed.replace('/', ' ').split(' ').filter(String::isNotEmpty).joinToString(" ") { word ->
            if (LINE_CODE.matches(word)) word.uppercase() else capitalise(word.lowercase())
        }
    }

    /** Upper case at the start and after an apostrophe or a hyphen: "sant'olcese" -> "Sant'Olcese". */
    private fun capitalise(word: String): String {
        val result = StringBuilder(word.length)
        var upper = true
        for (char in word) {
            result.append(if (upper && char.isLetter()) char.titlecaseChar() else char)
            if (char.isLetter()) upper = false
            if (char == '\'' || char == '’' || char == '-') upper = true
        }
        return result.toString()
    }

    private val LINE_CODE = Regex("(?i)[a-z]\\d{1,2}|fs")
}
