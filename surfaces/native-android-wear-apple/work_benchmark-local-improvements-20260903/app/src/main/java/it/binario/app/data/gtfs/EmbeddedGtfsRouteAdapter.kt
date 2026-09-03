package it.binario.app.data.gtfs

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.SourceId
import it.binario.app.data.sources.RailSourceCatalog
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.LinkedHashMap
import java.util.zip.ZipInputStream

/**
 * Fallback offline per orari programmati. Legge soltanto i data pack GTFS già
 * verificati e inclusi nell’APK: non effettua download, non richiede token e
 * non attribuisce ritardi o binari non presenti nel feed statico.
 */
class EmbeddedGtfsRouteAdapter(
    private val openAsset: (String) -> InputStream,
    private val packs: List<EmbeddedGtfsPack> = EmbeddedGtfsPacks.all,
    private val clock: () -> Long = System::currentTimeMillis
) : TrainDataSource {
    private val cache = object : LinkedHashMap<SourceId, StaticGtfsIndex>(3, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SourceId, StaticGtfsIndex>?): Boolean = size > 3
    }

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
        val origin = route.origin(direction).name
        val destination = route.destination(direction).name
        packs.forEach { pack ->
            val source = RailSourceCatalog.byId(pack.sourceId) ?: return@forEach
            val index = synchronized(cache) {
                cache[pack.sourceId] ?: parse(openAsset(pack.assetPath)).also { cache[pack.sourceId] = it }
            }
            val services = index.services(origin, destination, source.id.value).take(24)
            if (services.isNotEmpty()) {
                val now = clock()
                return DataResult.Success(
                    RouteSnapshot(
                        route = route,
                        direction = direction,
                        services = services,
                        providerName = "${source.displayName} · orario programmato",
                        retrievedAtEpochMillis = now,
                        sourceObservedAtEpochMillis = now,
                        expiresAtEpochMillis = now + 12 * 60 * 60 * 1000,
                        freshness = Freshness.AGING,
                        confidence = DataConfidence.STATIC_ONLY,
                        notice = "Orario programmato da ${source.displayName}. Ritardi, binari e soppressioni richiedono una fonte realtime."
                    )
                )
            }
        }
        return DataResult.Failure("Nessuna corsa programmata trovata nei data pack GTFS disponibili per questa tratta.")
    }

    private fun parse(stream: InputStream): StaticGtfsIndex {
        val tables = mutableMapOf<String, List<Map<String, String>>>()
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                if (name in requiredTables) {
                    tables[name] = parseCsv(BufferedReader(InputStreamReader(zip, Charsets.UTF_8)))
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return StaticGtfsIndex(
            stops = tables["stops.txt"].orEmpty().associate { it["stop_id"].orEmpty() to it["stop_name"].orEmpty() },
            routes = tables["routes.txt"].orEmpty().associateBy { it["route_id"].orEmpty() },
            trips = tables["trips.txt"].orEmpty(),
            stopTimes = tables["stop_times.txt"].orEmpty().groupBy { it["trip_id"].orEmpty() }
        )
    }

    private fun parseCsv(reader: BufferedReader): List<Map<String, String>> {
        val header = reader.readLine()?.let(::csvLine).orEmpty()
        if (header.isEmpty()) return emptyList()
        return generateSequence { reader.readLine() }
            .filter { it.isNotBlank() }
            .map { row -> header.zip(csvLine(row)).toMap() }
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

    private data class StaticGtfsIndex(
        val stops: Map<String, String>,
        val routes: Map<String, Map<String, String>>,
        val trips: List<Map<String, String>>,
        val stopTimes: Map<String, List<Map<String, String>>>
    ) {
        fun services(origin: String, destination: String, sourceId: String): List<TrainService> {
            val wantedOrigin = normalize(origin)
            val wantedDestination = normalize(destination)
            return trips.asSequence().mapNotNull { trip ->
                val times = stopTimes[trip["trip_id"]].orEmpty()
                    .sortedBy { it["stop_sequence"]?.toIntOrNull() ?: Int.MAX_VALUE }
                val originIndex = times.indexOfFirst { normalize(stops[it["stop_id"]].orEmpty()) == wantedOrigin }
                val destinationIndex = times.indices.firstOrNull { candidateIndex ->
                    candidateIndex > originIndex && normalize(stops[times[candidateIndex]["stop_id"]].orEmpty()) == wantedDestination
                } ?: -1
                if (originIndex < 0 || destinationIndex < 0) return@mapNotNull null
                val route = routes[trip["route_id"]].orEmpty()
                val segment = times.subList(originIndex, destinationIndex + 1)
                val departure = hhmm(segment.first()["departure_time"] ?: segment.first()["arrival_time"] ?: return@mapNotNull null)
                val number = trip["trip_short_name"]?.takeIf { it.isNotBlank() } ?: trip["trip_id"].orEmpty()
                TrainService(
                    id = "$sourceId:${trip["trip_id"]}",
                    number = number,
                    operator = operator(sourceId),
                    trainCategory = category(route["route_type"]),
                    origin = stops[segment.first()["stop_id"]].orEmpty(),
                    destination = stops[segment.last()["stop_id"]].orEmpty(),
                    scheduledDeparture = departure,
                    expectedDeparture = departure,
                    platform = null,
                    delayMinutes = 0,
                    status = ServiceStatus.ON_TIME,
                    stops = segment.map { stop ->
                        val scheduled = hhmm(stop["departure_time"] ?: stop["arrival_time"].orEmpty())
                        TrainStop(stops[stop["stop_id"]].orEmpty(), scheduled, scheduled, null, 0, false)
                    }
                )
            }.toList()
        }

        private fun hhmm(value: String): String {
            val parts = value.split(':')
            val hours = parts.firstOrNull()?.toIntOrNull()?.rem(24) ?: 0
            val minutes = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return "%02d:%02d".format(hours, minutes)
        }

        private fun operator(sourceId: String): RailOperator = when (sourceId) {
            "lombardia-trenord-gtfs" -> RailOperator.TRENORD
            "oebb-gtfs", "ch-otd-gtfs", "alto-adige-sta-gtfs" -> RailOperator.OTHER
            else -> RailOperator.OTHER
        }

        private fun category(routeType: String?): TrainCategory = when (routeType) {
            "2", "100", "101", "102", "103", "104", "105", "106", "107", "108", "109" -> TrainCategory.REGIONALE
            else -> TrainCategory.OTHER
        }
    }

    private companion object {
        val requiredTables = setOf("stops.txt", "routes.txt", "trips.txt", "stop_times.txt")
        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "")
            .lowercase()
            .replace("[^a-z0-9]".toRegex(), "")
    }
}
