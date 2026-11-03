package it.binario.app.data.repository

import android.content.Context
import androidx.core.content.edit
import it.binario.app.data.DataConfidence
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.live.textOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last good route result and station board, kept on the device so that a later refresh without network can
 * still show them, marked as a saved copy ("Copia salvata, non in tempo reale"). Only the timetable part is kept:
 * expected times, delays, platforms and states are live claims and are never restored. Local only, app-private,
 * bounded to [maxEntries] routes and as many boards; nothing leaves the phone (PRIVACY.md).
 */
class LastGoodSnapshotStore(context: Context, private val maxEntries: Int = 8) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun saveRoute(snapshot: RouteSnapshot) {
        if (snapshot.services.isEmpty() || snapshot.confidence == DataConfidence.CACHED || snapshot.confidence == DataConfidence.DEMONSTRATION) return
        put(routeKey(snapshot.route, snapshot.direction), LastGoodSnapshotCodec.encodeRoute(snapshot))
    }

    fun route(route: SavedRoute, direction: Direction): RouteSnapshot? =
        preferences.getString(routeKey(route, direction), null)?.let { LastGoodSnapshotCodec.decodeRoute(it, route, direction) }

    fun saveBoard(board: StationBoardAggregate) {
        if (board.entries.isEmpty()) return
        put(boardKey(board.station.id, board.kind), LastGoodSnapshotCodec.encodeBoard(board))
    }

    fun board(station: Station, kind: BoardKind): StationBoardAggregate? =
        preferences.getString(boardKey(station.id, kind), null)?.let { LastGoodSnapshotCodec.decodeBoard(it, station, kind) }

    private fun put(key: String, value: String) {
        val order = preferences.getString(ORDER, "").orEmpty().split('\n').filter { it.isNotBlank() && it != key } + key
        val kept = order.filter { it.startsWith(key.substringBefore(':')) }.takeLast(maxEntries).toSet()
        val dropped = order.filter { it.startsWith(key.substringBefore(':')) && it !in kept }
        preferences.edit {
            putString(key, value)
            dropped.forEach { remove(it) }
            putString(ORDER, (order - dropped.toSet()).joinToString("\n"))
        }
    }

    private fun routeKey(route: SavedRoute, direction: Direction) =
        "route:${route.outwardOrigin.id}>${route.outwardDestination.id}:${direction.name}"

    private fun boardKey(stationId: String, kind: BoardKind) = "board:$stationId:${kind.name}"

    private companion object {
        const val PREFERENCES = "binario_last_good_snapshots"
        const val ORDER = "order"
    }
}

/** JSON of the timetable part of a route result or board; never the live fields. */
internal object LastGoodSnapshotCodec {
    private const val VERSION = 1
    private const val MAX_ROWS = 40

    fun encodeRoute(snapshot: RouteSnapshot): String = JSONObject()
        .put("v", VERSION)
        .put("savedAt", snapshot.retrievedAtEpochMillis)
        .put("provider", snapshot.providerName)
        .put("services", JSONArray().apply {
            snapshot.services.take(MAX_ROWS).forEach { service ->
                put(JSONObject()
                    .put("id", service.id).put("number", service.number)
                    .put("operator", service.operator.name).put("category", service.trainCategory.name)
                    .put("operatorName", service.operatorDisplayName)
                    .put("origin", service.origin).put("destination", service.destination)
                    .put("departure", service.scheduledDeparture)
                    .put("departureAt", service.scheduledDepartureDateTime?.toString())
                    .put("bus", service.scheduledBus)
                    .put("stops", JSONArray().apply {
                        service.stops.forEach { stop -> put(JSONObject().put("station", stop.station).put("time", stop.scheduled)) }
                    }))
            }
        })
        .toString()

    fun decodeRoute(json: String, route: SavedRoute, direction: Direction): RouteSnapshot? = runCatching {
        val root = JSONObject(json)
        if (root.optInt("v") != VERSION) return null
        val savedAt = root.optLong("savedAt").takeIf { it > 0 } ?: return null
        val services = objects(root.optJSONArray("services")).mapNotNull { item ->
            val departure = item.textOrNull("departure") ?: return@mapNotNull null
            TrainService(
                id = item.textOrNull("id") ?: return@mapNotNull null,
                number = item.textOrNull("number").orEmpty(),
                operator = enumOr(item.textOrNull("operator"), RailOperator.OTHER),
                trainCategory = enumOr(item.textOrNull("category"), TrainCategory.OTHER),
                origin = item.textOrNull("origin").orEmpty(),
                destination = item.textOrNull("destination").orEmpty(),
                scheduledDeparture = departure,
                expectedDeparture = departure,
                platform = null,
                delayMinutes = 0,
                status = ServiceStatus.ON_TIME,
                stops = objects(item.optJSONArray("stops")).mapNotNull { stop ->
                    val time = stop.textOrNull("time") ?: return@mapNotNull null
                    TrainStop(stop.textOrNull("station") ?: return@mapNotNull null, time, time, null, 0, false, operationalStatusVerified = false)
                },
                operatorDisplayName = item.textOrNull("operatorName"),
                officialDelayMinutes = null,
                // A saved copy is a timetable: no observed operational state.
                operationalStatusVerified = false,
                scheduledDepartureDateTime = item.textOrNull("departureAt")?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
                scheduledBus = item.optBoolean("bus", false)
            )
        }
        if (services.isEmpty()) return null
        RouteSnapshot(
            route = route, direction = direction, services = services,
            providerName = root.textOrNull("provider").orEmpty(),
            retrievedAtEpochMillis = savedAt, expiresAtEpochMillis = savedAt, freshness = Freshness.STALE,
            sourceObservedAtEpochMillis = savedAt, confidence = DataConfidence.CACHED
        )
    }.getOrNull()

    fun encodeBoard(board: StationBoardAggregate): String = JSONObject()
        .put("v", VERSION)
        .put("savedAt", board.fetchedAt.toEpochMilli())
        .put("providers", JSONArray(board.providerNames))
        .put("entries", JSONArray().apply {
            board.entries.take(MAX_ROWS).forEach { entry ->
                put(JSONObject()
                    .put("run", entry.runId.value).put("at", entry.scheduledTime.toString())
                    .put("endpoint", entry.destinationOrOrigin.value).put("label", entry.destinationOrOriginLabel)
                    .put("number", entry.publicNumber).put("operator", entry.operatorId?.value)
                    .put("line", entry.lineLabel).put("kind", entry.serviceKind.name))
            }
        })
        .toString()

    fun decodeBoard(json: String, station: Station, kind: BoardKind): StationBoardAggregate? = runCatching {
        val root = JSONObject(json)
        if (root.optInt("v") != VERSION) return null
        val savedAt = root.optLong("savedAt").takeIf { it > 0 } ?: return null
        val entries = objects(root.optJSONArray("entries")).mapNotNull { item ->
            StationBoardEntry(
                runId = TrainRunId(item.textOrNull("run") ?: return@mapNotNull null),
                scheduledTime = item.textOrNull("at")?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() } ?: return@mapNotNull null,
                destinationOrOrigin = StationId(item.textOrNull("endpoint") ?: return@mapNotNull null),
                destinationOrOriginLabel = item.textOrNull("label"),
                publicNumber = item.textOrNull("number"),
                operatorId = item.textOrNull("operator")?.let(::OperatorId),
                lineLabel = item.textOrNull("line"),
                serviceKind = enumOr(item.textOrNull("kind"), ServiceKind.UNKNOWN)
            )
        }
        val providers = root.optJSONArray("providers")?.let { array -> (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.takeIf(String::isNotBlank) } }.orEmpty()
        StationBoardAggregate(station, kind, entries, providers, emptySet(), Instant.ofEpochMilli(savedAt))
    }.getOrNull()

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { value -> enumValues<E>().firstOrNull { it.name == value } } ?: fallback
}
