package it.binario.app.data.gtfs

import it.binario.app.data.Station
import it.binario.app.data.StationSearchNormalizer
import it.binario.app.data.core.SourceId
import it.binario.app.data.sources.RailSourceCatalog
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * Indice lazy dei soli `stops.txt` inclusi nei pack GTFS verificati.
 *
 * Non conserva l'intero dataset in memoria e non apre la rete: una query legge
 * progressivamente i pack abilitati, fermandosi appena ha raccolto il limite
 * richiesto. I risultati vengono poi mantenuti dalla UI per le selezioni e le
 * tratte personali dell'utente.
 */
class EmbeddedGtfsStationSearchIndex(
    private val openAsset: (String) -> InputStream,
    private val packs: List<EmbeddedGtfsPack> = EmbeddedGtfsPacks.all,
    private val activeSourceIds: Set<SourceId> = EmbeddedGtfsPacks.sourceIds
) {
    fun search(query: String, limit: Int = 12): List<Station> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.length < 2 || limit <= 0) return emptyList()

        val results = linkedMapOf<String, Station>()
        packs.asSequence()
            .filter { it.sourceId in activeSourceIds }
            .forEach { pack ->
                if (results.size >= limit) return@forEach
                val source = RailSourceCatalog.byId(pack.sourceId) ?: return@forEach
                searchPack(pack, normalizedQuery, source.displayName, limit - results.size).forEach { station ->
                    results.putIfAbsent(normalize(station.name), station)
                }
            }
        return results.values.take(limit)
    }

    private fun searchPack(
        pack: EmbeddedGtfsPack,
        normalizedQuery: String,
        sourceName: String,
        remaining: Int
    ): List<Station> {
        val matches = mutableListOf<Station>()
        openAsset(pack.assetPath).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                var foundStops = false
                while (entry != null && !foundStops) {
                    if (entry.name.substringAfterLast('/') == "stops.txt") {
                        BufferedReader(InputStreamReader(zip, Charsets.UTF_8)).use { reader ->
                            val header = reader.readLine()?.let(::csvLine).orEmpty()
                            val idIndex = header.indexOf("stop_id")
                            val nameIndex = header.indexOf("stop_name")
                            val locationTypeIndex = header.indexOf("location_type")
                            if (idIndex >= 0 && nameIndex >= 0) {
                                var line = reader.readLine()
                                while (line != null && matches.size < remaining) {
                                    if (line.length > MAX_CSV_LINE_LENGTH) {
                                        line = reader.readLine()
                                        continue
                                    }
                                    val values = csvLine(line)
                                    val nativeId = values.getOrNull(idIndex)?.trim().orEmpty()
                                    val name = values.getOrNull(nameIndex)?.trim().orEmpty()
                                    val locationType = values.getOrNull(locationTypeIndex)?.trim().orEmpty()
                                    if (nativeId.isNotBlank() && name.isNotBlank() && (locationType.isBlank() || locationType in setOf("0", "1")) && matchesQuery(name, normalizedQuery)) {
                                        matches += Station(
                                            id = "gtfs:${pack.sourceId.value}:$nativeId",
                                            name = name,
                                            city = cityLabel(name),
                                            region = sourceName
                                        )
                                    }
                                    line = reader.readLine()
                                }
                            }
                        }
                        foundStops = true
                    }
                    if (!foundStops) {
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        }
        return matches
    }

    private fun cityLabel(name: String): String = name
        .substringBefore(" (")
        .substringBefore(" - ")
        .trim()
        .ifBlank { name }

    private fun csvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            when (val char = line[index]) {
                '"' -> if (quoted && line.getOrNull(index + 1) == '"') {
                    current.append('"')
                    index += 1
                } else quoted = !quoted
                ',' -> if (quoted) current.append(char) else {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index += 1
        }
        values += current.toString()
        return values
    }

    private fun matchesQuery(value: String, normalizedQuery: String): Boolean {
        val normalizedValue = normalize(value)
        return normalizedValue.contains(normalizedQuery) || normalizedQuery.split(' ').all { token -> normalizedValue.contains(token) }
    }

    companion object {
        private const val MAX_CSV_LINE_LENGTH = 32_768

        fun normalize(value: String): String = StationSearchNormalizer.normalize(value)
    }
}
