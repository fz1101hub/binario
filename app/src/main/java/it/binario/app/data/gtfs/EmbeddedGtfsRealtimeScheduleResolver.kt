package it.binario.app.data.gtfs

import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.SourceId
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap

/** Orario statico risolto per la medesima corsa e fermata di un Trip Update. */
data class ResolvedScheduledStop(
    val sourceId: SourceId,
    val sourceTripId: String,
    val sourceStopId: String,
    val stopSequence: Int,
    val locationLabel: String,
    val scheduledAt: ZonedDateTime,
    val serviceDate: LocalDate = scheduledAt.toLocalDate()
)

/**
 * Risolve identificativi GTFS realtime contro i data pack embedded.
 * Non tenta confronti fuzzy fra fonti: trip_id e stop_id/sequence devono appartenere al medesimo feed statico.
 *
 * Per ogni pack e giorno di servizio un indice compatto, costruito alla prima richiesta, contiene le sole
 * stop_times delle corse attive quel giorno (calendar.txt e calendar_dates.txt; tutte le corse se il pack non
 * pubblica un calendario): due letture dell'archivio per giorno invece di una scansione completa di
 * stop_times.txt per ogni corsa con aggiornamenti (78 MB per SNCF). L'indice di un giorno SNCF occupa circa
 * 90 000 righe da 24 byte; ne restano in memoria al più [dayIndexLimit].
 */
class EmbeddedGtfsRealtimeScheduleResolver(
    private val openAsset: (String) -> InputStream,
    private val packs: List<EmbeddedGtfsPack> = EmbeddedGtfsPacks.all,
    private val cacheLimit: Int = 3,
    private val limits: GtfsCompactIndexLimits = GtfsCompactIndexLimits(),
    private val dayIndexLimit: Int = 4
) {
    private val cache = object : LinkedHashMap<SourceId, CompactStopNames>(cacheLimit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SourceId, CompactStopNames>?): Boolean = size > cacheLimit
    }
    private val dayIndexes = object : LinkedHashMap<Pair<SourceId, LocalDate>, DayStopTimes>(dayIndexLimit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<SourceId, LocalDate>, DayStopTimes>?): Boolean = size > dayIndexLimit
    }
    /** One build per pack at a time; other packs and cached days are not blocked by it. */
    private val buildLocks = HashMap<SourceId, Any>()

    fun resolve(
        staticSourceId: SourceId,
        update: RealtimeStopUpdate,
        serviceDate: LocalDate,
        zone: ZoneId
    ): ResolvedScheduledStop? {
        val pack = packs.firstOrNull { it.sourceId == staticSourceId } ?: return null
        val (names, day) = try {
            synchronized(synchronized(buildLocks) { buildLocks.getOrPut(staticSourceId) { Any() } }) {
                val names = synchronized(cache) { cache[staticSourceId] } ?: readStopNames(openAsset(pack.assetPath)).also { built ->
                    synchronized(cache) { cache[staticSourceId] = built }
                }
                val key = staticSourceId to serviceDate
                val day = synchronized(dayIndexes) { dayIndexes[key] } ?: readDay(pack, serviceDate).also { built ->
                    synchronized(dayIndexes) { dayIndexes[key] = built }
                }
                names to day
            }
        } catch (_: GtfsCompactIndexLimitException) {
            return null
        }
        val candidates = day.rows(update.sourceTripId)
        val match = candidates.firstOrNull { row ->
            val idMatches = update.sourceStopId != null && row.stopId == update.sourceStopId
            val sequenceMatches = update.stopSequence != null && row.sequence == update.stopSequence
            idMatches && (update.stopSequence == null || sequenceMatches) ||
                (update.sourceStopId == null && sequenceMatches)
        } ?: return null
        val name = names.name(match.stopId).takeIf(String::isNotBlank) ?: match.stopId
        val seconds = if (match.departureSeconds != BLANK) match.departureSeconds else match.arrivalSeconds
        if (seconds < 0) return null
        // GTFS Time is elapsed time from "noon minus 12h" of the service day,
        // not a civil LocalDateTime. The distinction is observable in DST gaps
        // and overlaps, where atTime(...).atZone(...) would pick wall-clock rules.
        val scheduledAt = serviceDate.atTime(12, 0).atZone(zone).minusHours(12).plusSeconds(seconds.toLong())
        return ResolvedScheduledStop(
            sourceId = staticSourceId,
            sourceTripId = update.sourceTripId,
            sourceStopId = match.stopId,
            stopSequence = match.sequence,
            locationLabel = name,
            scheduledAt = scheduledAt,
            serviceDate = serviceDate
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

    /** The trips that run on [date] (null: the pack has no calendar, so every trip), then their stop times. */
    private fun readDay(pack: EmbeddedGtfsPack, date: LocalDate): DayStopTimes {
        val active = activeTrips(openAsset(pack.assetPath), date)
        val day = DayStopTimes(limits.maxStopTimeRows)
        BoundedGtfsZipScanner(limits).scan(openAsset(pack.assetPath), setOf("stop_times.txt")) { _, rows ->
            val tripIndex = rows.header.indexOf("trip_id")
            val arrivalIndex = rows.header.indexOf("arrival_time")
            val departureIndex = rows.header.indexOf("departure_time")
            val stopIndex = rows.header.indexOf("stop_id")
            val sequenceIndex = rows.header.indexOf("stop_sequence")
            rows.forEachRow(limits.maxStopTimeRows) { row ->
                val tripId = row.gtfsRawValue(tripIndex)
                if (active != null && tripId !in active) return@forEachRow
                val stopId = row.gtfsRawValue(stopIndex)
                val sequence = row.getOrNull(sequenceIndex)?.toIntOrNull()
                if (stopId.isNotBlank() && sequence != null) {
                    day.add(tripId, stopId, sequence, gtfsSeconds(row.gtfsRawValue(arrivalIndex)), gtfsSeconds(row.gtfsRawValue(departureIndex)))
                }
            }
        }
        return day
    }

    private fun activeTrips(stream: InputStream, date: LocalDate): Set<String>? {
        val ymd = date.format(DateTimeFormatter.BASIC_ISO_DATE).toInt()
        // calendar.txt names its weekday columns "monday" … "sunday".
        val weekday = date.dayOfWeek.name.lowercase(java.util.Locale.ROOT)
        val byCalendar = HashSet<String>()
        val added = HashSet<String>()
        val removed = HashSet<String>()
        var hasCalendar = false
        val tripServices = ArrayList<Pair<String, String>>()
        var hasTrips = false
        BoundedGtfsZipScanner(limits).scan(stream, setOf("calendar.txt", "calendar_dates.txt", "trips.txt")) { table, rows ->
            when (table) {
                "calendar.txt" -> {
                    hasCalendar = true
                    val service = rows.header.indexOf("service_id")
                    val day = rows.header.indexOf(weekday)
                    val start = rows.header.indexOf("start_date")
                    val end = rows.header.indexOf("end_date")
                    rows.forEachRow(limits.maxCalendars) { row ->
                        val from = row.gtfsValue(start).toIntOrNull() ?: return@forEachRow
                        val to = row.gtfsValue(end).toIntOrNull() ?: return@forEachRow
                        if (row.gtfsValue(day) == "1" && ymd in from..to) byCalendar += row.gtfsRawValue(service)
                    }
                }
                "calendar_dates.txt" -> {
                    hasCalendar = true
                    val service = rows.header.indexOf("service_id")
                    val dateColumn = rows.header.indexOf("date")
                    val type = rows.header.indexOf("exception_type")
                    rows.forEachRow(limits.maxCalendarDateRows) { row ->
                        if (row.gtfsValue(dateColumn).toIntOrNull() != ymd) return@forEachRow
                        when (row.gtfsValue(type)) {
                            "1" -> added += row.gtfsRawValue(service)
                            "2" -> removed += row.gtfsRawValue(service)
                        }
                    }
                }
                "trips.txt" -> {
                    hasTrips = true
                    val trip = rows.header.indexOf("trip_id")
                    val service = rows.header.indexOf("service_id")
                    rows.forEachRow(limits.maxTrips) { row -> tripServices += row.gtfsRawValue(trip) to row.gtfsRawValue(service) }
                }
            }
        }
        if (!hasCalendar || !hasTrips) return null
        val services = (byCalendar - removed) + added
        return tripServices.asSequence().filter { it.second in services }.mapTo(HashSet()) { it.first }
    }

    /** Stop times of one pack and day in int columns, chained per trip; built once and then only read. */
    private class DayStopTimes(private val maximum: Int) {
        private val tripHeads = HashMap<String, Int>()
        private val stopIds = HashMap<String, String>()
        private var stops = arrayOfNulls<String>(1024)
        private var sequences = IntArray(1024)
        private var arrivals = IntArray(1024)
        private var departures = IntArray(1024)
        private var next = IntArray(1024)
        private var count = 0

        fun add(tripId: String, stopId: String, sequence: Int, arrival: Int, departure: Int) {
            if (count >= maximum) throw GtfsCompactIndexLimitException("stop_times.txt supera il limite di righe")
            if (count == sequences.size) {
                val capacity = (count * 2).coerceAtMost(maximum).coerceAtLeast(count + 1)
                stops = stops.copyOf(capacity); sequences = sequences.copyOf(capacity); arrivals = arrivals.copyOf(capacity)
                departures = departures.copyOf(capacity); next = next.copyOf(capacity)
            }
            stops[count] = stopIds.getOrPut(stopId) { stopId }
            sequences[count] = sequence
            arrivals[count] = arrival
            departures[count] = departure
            next[count] = tripHeads[tripId] ?: -1
            tripHeads[tripId] = count
            count++
        }

        fun rows(tripId: String): List<RealtimeStopTime> {
            val out = ArrayList<RealtimeStopTime>()
            var row = tripHeads[tripId] ?: return emptyList()
            while (row >= 0) {
                out += RealtimeStopTime(stops[row]!!, sequences[row], arrivals[row], departures[row])
                row = next[row]
            }
            // The chain runs from the last row read; the file order breaks ties between equal sequences.
            out.reverse()
            return out.sortedBy(RealtimeStopTime::sequence)
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

    private data class RealtimeStopTime(val stopId: String, val sequence: Int, val arrivalSeconds: Int, val departureSeconds: Int)

    private companion object {
        const val BLANK = -2
        const val MALFORMED = -1

        /** Seconds of a GTFS time (H:MM or H:MM:SS, up to 99 hours); [BLANK] when empty, [MALFORMED] when unreadable. */
        fun gtfsSeconds(value: String): Int {
            if (value.isBlank()) return BLANK
            val parts = value.split(':')
            if (parts.size !in 2..3) return MALFORMED
            val hours = parts[0].toIntOrNull() ?: return MALFORMED
            val minutes = parts[1].toIntOrNull() ?: return MALFORMED
            val seconds = parts.getOrElse(2) { "0" }.toIntOrNull() ?: return MALFORMED
            if (hours !in 0..99 || minutes !in 0..59 || seconds !in 0..59) return MALFORMED
            return hours * 3_600 + minutes * 60 + seconds
        }
    }
}
