package it.binario.app.data.gtfs

import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.SourceId
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.LinkedHashMap
import java.util.zip.ZipInputStream

/** Orario statico risolto per la medesima corsa e fermata di un Trip Update. */
data class ResolvedScheduledStop(
    val sourceId: SourceId,
    val sourceTripId: String,
    val sourceStopId: String,
    val stopSequence: Int,
    val locationLabel: String,
    val scheduledAt: ZonedDateTime
)

/**
 * Risolve identificativi GTFS realtime contro i data pack embedded.
 * Non tenta confronti fuzzy fra fonti: trip_id e stop_id/sequence devono appartenere al medesimo feed statico.
 */
class EmbeddedGtfsRealtimeScheduleResolver(
    private val openAsset: (String) -> InputStream,
    private val packs: List<EmbeddedGtfsPack> = EmbeddedGtfsPacks.all,
    private val cacheLimit: Int = 3
) {
    private val cache = object : LinkedHashMap<SourceId, RealtimeScheduleIndex>(cacheLimit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SourceId, RealtimeScheduleIndex>?): Boolean = size > cacheLimit
    }

    fun resolve(
        staticSourceId: SourceId,
        update: RealtimeStopUpdate,
        serviceDate: LocalDate,
        zone: ZoneId
    ): ResolvedScheduledStop? {
        val pack = packs.firstOrNull { it.sourceId == staticSourceId } ?: return null
        val index = synchronized(cache) {
            cache[staticSourceId] ?: parse(openAsset(pack.assetPath)).also { cache[staticSourceId] = it }
        }
        val candidates = index.stopTimes[update.sourceTripId].orEmpty()
        val match = candidates.firstOrNull { row ->
            val idMatches = update.sourceStopId != null && row.stopId == update.sourceStopId
            val sequenceMatches = update.stopSequence != null && row.sequence == update.stopSequence
            idMatches && (update.stopSequence == null || sequenceMatches) ||
                (update.sourceStopId == null && sequenceMatches)
        } ?: return null
        val name = index.stopNames[match.stopId]?.takeIf(String::isNotBlank) ?: match.stopId
        return ResolvedScheduledStop(
            sourceId = staticSourceId,
            sourceTripId = update.sourceTripId,
            sourceStopId = match.stopId,
            stopSequence = match.sequence,
            locationLabel = name,
            scheduledAt = scheduledDateTime(serviceDate, match.departureTime.ifBlank { match.arrivalTime }, zone)
        )
    }

    private fun parse(stream: InputStream): RealtimeScheduleIndex {
        val tables = mutableMapOf<String, List<Map<String, String>>>()
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                if (name in setOf("stops.txt", "stop_times.txt")) {
                    tables[name] = parseCsv(BufferedReader(InputStreamReader(zip, Charsets.UTF_8)))
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val names = tables["stops.txt"].orEmpty().associate { it["stop_id"].orEmpty() to it["stop_name"].orEmpty() }
        val times = tables["stop_times.txt"].orEmpty().mapNotNull { row ->
            val tripId = row["trip_id"].orEmpty()
            val stopId = row["stop_id"].orEmpty()
            val sequence = row["stop_sequence"]?.toIntOrNull()
            if (tripId.isBlank() || stopId.isBlank() || sequence == null) null else RealtimeStopTime(
                stopId = stopId,
                sequence = sequence,
                arrivalTime = row["arrival_time"].orEmpty(),
                departureTime = row["departure_time"].orEmpty()
            ) to tripId
        }.groupBy({ it.second }, { it.first }).mapValues { (_, value) -> value.sortedBy(RealtimeStopTime::sequence) }
        return RealtimeScheduleIndex(names, times)
    }

    private fun parseCsv(reader: BufferedReader): List<Map<String, String>> {
        val header = reader.readLine()?.let(::csvLine).orEmpty()
        if (header.isEmpty()) return emptyList()
        return generateSequence { reader.readLine() }
            .filter(String::isNotBlank)
            .map { header.zip(csvLine(it)).toMap() }
            .toList()
    }

    private fun csvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            when (val character = line[index]) {
                '"' -> if (quoted && line.getOrNull(index + 1) == '"') {
                    current.append('"')
                    index += 1
                } else quoted = !quoted
                ',' -> if (quoted) current.append(character) else {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(character)
            }
            index += 1
        }
        values += current.toString()
        return values
    }

    private fun scheduledDateTime(date: LocalDate, value: String, zone: ZoneId): ZonedDateTime {
        val parts = value.split(':')
        require(parts.size >= 2) { "Orario GTFS non valido" }
        val totalHours = parts[0].toInt()
        val minutes = parts[1].toInt()
        val seconds = parts.getOrElse(2) { "0" }.toInt()
        return date.plusDays((totalHours / 24).toLong()).atTime(totalHours % 24, minutes, seconds).atZone(zone)
    }

    private data class RealtimeScheduleIndex(
        val stopNames: Map<String, String>,
        val stopTimes: Map<String, List<RealtimeStopTime>>
    )
    private data class RealtimeStopTime(val stopId: String, val sequence: Int, val arrivalTime: String, val departureTime: String)
}
