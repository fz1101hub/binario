package it.binario.app.data.gtfs

import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.SourceId
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.LinkedHashMap

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
 *
 * L'indice non conserva stop_times.txt: mantiene i nomi delle fermate e una LRU
 * di sole corse realtime effettivamente richieste. Una cache miss scansiona
 * streaming stop_times.txt, trattenendo soltanto le righe con il trip_id
 * richiesto. Questo evita che un Trip Update possa materializzare tutto il
 * feed AMT in oggetti Kotlin.
 */
class EmbeddedGtfsRealtimeScheduleResolver(
    private val openAsset: (String) -> InputStream,
    private val packs: List<EmbeddedGtfsPack> = EmbeddedGtfsPacks.all,
    private val cacheLimit: Int = 3,
    private val limits: GtfsCompactIndexLimits = GtfsCompactIndexLimits()
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
        val index = try {
            synchronized(cache) {
                cache[staticSourceId] ?: RealtimeScheduleIndex(readStopNames(openAsset(pack.assetPath))).also {
                    cache[staticSourceId] = it
                }
            }
        } catch (_: GtfsCompactIndexLimitException) {
            return null
        }
        val candidates = try {
            index.scheduleFor(update.sourceTripId) { tripId -> readTripSchedule(openAsset(pack.assetPath), tripId) }
        } catch (_: GtfsCompactIndexLimitException) {
            return null
        }
        val match = candidates.firstOrNull { row ->
            val idMatches = update.sourceStopId != null && row.stopId == update.sourceStopId
            val sequenceMatches = update.stopSequence != null && row.sequence == update.stopSequence
            idMatches && (update.stopSequence == null || sequenceMatches) ||
                (update.sourceStopId == null && sequenceMatches)
        } ?: return null
        val name = index.stopName(match.stopId).takeIf(String::isNotBlank) ?: match.stopId
        return ResolvedScheduledStop(
            sourceId = staticSourceId,
            sourceTripId = update.sourceTripId,
            sourceStopId = match.stopId,
            stopSequence = match.sequence,
            locationLabel = name,
            scheduledAt = scheduledDateTime(serviceDate, match.departureTime.ifBlank { match.arrivalTime }, zone)
        )
    }

    private fun readStopNames(stream: InputStream): CompactStopNames {
        val names = CompactStopNames(limits.maxStops)
        BoundedGtfsZipScanner(limits).scan(stream, setOf("stops.txt")) { _, rows ->
            val idIndex = rows.header.indexOf("stop_id")
            val nameIndex = rows.header.indexOf("stop_name")
            rows.forEachRow(limits.maxStops) { row ->
                names.put(row.gtfsRawValue(idIndex), row.gtfsRawValue(nameIndex))
            }
        }
        return names
    }

    private fun readTripSchedule(stream: InputStream, wantedTripId: String): List<RealtimeStopTime> {
        val matches = ArrayList<RealtimeStopTime>()
        BoundedGtfsZipScanner(limits).scan(stream, setOf("stop_times.txt")) { _, rows ->
            val tripIndex = rows.header.indexOf("trip_id")
            val arrivalIndex = rows.header.indexOf("arrival_time")
            val departureIndex = rows.header.indexOf("departure_time")
            val stopIndex = rows.header.indexOf("stop_id")
            val sequenceIndex = rows.header.indexOf("stop_sequence")
            rows.forEachRow(limits.maxStopTimeRows) { row ->
                if (row.gtfsRawValue(tripIndex) == wantedTripId) {
                    val stopId = row.gtfsRawValue(stopIndex)
                    val sequence = row.getOrNull(sequenceIndex)?.toIntOrNull()
                    if (stopId.isNotBlank() && sequence != null) {
                        matches += RealtimeStopTime(
                            stopId = stopId,
                            sequence = sequence,
                            arrivalTime = row.gtfsRawValue(arrivalIndex),
                            departureTime = row.gtfsRawValue(departureIndex)
                        )
                    }
                }
            }
        }
        return matches.sortedBy(RealtimeStopTime::sequence)
    }

    private fun scheduledDateTime(date: LocalDate, value: String, zone: ZoneId): ZonedDateTime {
        val parts = value.split(':')
        require(parts.size >= 2) { "Orario GTFS non valido" }
        val totalHours = parts[0].toInt()
        val minutes = parts[1].toInt()
        val seconds = parts.getOrElse(2) { "0" }.toInt()
        require(totalHours >= 0 && minutes in 0..59 && seconds in 0..59) { "Orario GTFS non valido" }

        // GTFS Time is elapsed time from "noon minus 12h" of the service day,
        // not a civil LocalDateTime. The distinction is observable in DST gaps
        // and overlaps, where atTime(...).atZone(...) would pick wall-clock rules.
        val serviceDayOrigin = date.atTime(12, 0).atZone(zone).minusHours(12)
        return serviceDayOrigin.plusSeconds(totalHours * 3_600L + minutes * 60L + seconds)
    }

    private class RealtimeScheduleIndex(private val stopNames: CompactStopNames) {
        private val tripCache = object : LinkedHashMap<String, List<RealtimeStopTime>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<RealtimeStopTime>>?): Boolean = size > 64
        }

        fun stopName(stopId: String): String = stopNames.name(stopId)

        fun scheduleFor(tripId: String, loader: (String) -> List<RealtimeStopTime>): List<RealtimeStopTime> = synchronized(tripCache) {
            tripCache[tripId] ?: loader(tripId).also { tripCache[tripId] = it }
        }
    }

    private class CompactStopNames(private val maximum: Int) {
        private val values = HashMap<String, String>()

        fun put(id: String, name: String) {
            if (id !in values && values.size >= maximum) {
                throw GtfsCompactIndexLimitException("stops.txt supera il limite di stazioni")
            }
            values[id] = name
        }

        fun name(id: String): String = values[id].orEmpty()
    }

    private data class RealtimeStopTime(val stopId: String, val sequence: Int, val arrivalTime: String, val departureTime: String)
}
