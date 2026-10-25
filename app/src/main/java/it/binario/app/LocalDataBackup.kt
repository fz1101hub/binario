package it.binario.app

import it.binario.app.data.SavedRoute
import it.binario.app.data.SavedRouteOrdering
import it.binario.app.data.Station
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalTime

data class LocalDataBackup(
    val routes: List<SavedRoute>,
    val favouriteStationIds: Set<String>,
    val monitoredServices: List<ServiceMonitor>,
    val stationMonitors: List<StationMonitor>,
    val commuterAlerts: List<CommuterAlert>,
    val mobilityPreferences: MobilityPreferences,
    /**
     * Full records of the stations the backup refers to. On export, every station used by a
     * route, favourite or monitor; on import, those that the current catalogue does not know
     * (free-text and search-index stations), which the app must remember again.
     */
    val stations: List<Station> = emptyList(),
    /**
     * The local punctuality history. Null when the file has none (written before it existed), so
     * importing such a file keeps the history of this device instead of erasing it.
     */
    val punctualityHistory: List<PunctualityObservation>? = null
)

sealed interface LocalBackupResult {
    data class Success(val backup: LocalDataBackup) : LocalBackupResult
    data class Failure(val message: String) : LocalBackupResult
}

object LocalDataBackupCodec {
    const val MIME_TYPE = "application/json"
    const val MAX_BYTES = 1024 * 1024
    private const val VERSION = 2
    private const val MAX_STATIONS = 128
    private const val MAX_STATION_TEXT = 200
    private const val MAX_STATION_ALIASES = 32

    fun encode(backup: LocalDataBackup): String = JSONObject().apply {
        put("schemaVersion", VERSION)
        put("kind", "binario-local-backup")
        put("routes", JSONArray().apply { backup.routes.forEach { route -> put(JSONObject().apply {
            put("id", route.id); put("name", route.name); put("originId", route.outwardOrigin.id); put("destinationId", route.outwardDestination.id); put("pinned", route.isPinned); route.manualOrder?.takeIf { it in 0 until SavedRouteOrdering.MAX_ROUTES }?.let { put("manualOrder", it) }
        }) } })
        put("favouriteStationIds", JSONArray(backup.favouriteStationIds.sorted()))
        put("monitoredServices", JSONArray().apply { backup.monitoredServices.forEach { monitor -> put(JSONObject().apply { put("number", monitor.number); put("label", monitor.label) }) } })
        put("stationMonitors", JSONArray().apply { backup.stationMonitors.forEach { monitor -> put(JSONObject().apply { put("stationId", monitor.stationId); put("stationName", monitor.stationName); put("mode", monitor.mode.name) }) } })
        put("commuterAlerts", JSONArray().apply { backup.commuterAlerts.forEach { alert -> put(JSONObject().apply {
            put("id", alert.id); put("routeLabel", alert.routeLabel); put("stationName", alert.stationName); put("time", alert.time); put("leadMinutes", alert.leadMinutes); put("repeat", alert.repeat.name); put("quietHoursEnabled", alert.quietHoursEnabled); put("enabled", alert.enabled)
        }) } })
        // Optional since schema 2: older readers ignore it and still resolve catalogue ids.
        put("stations", JSONArray().apply { backup.stations.distinctBy { it.id }.take(MAX_STATIONS).forEach { station -> put(encodeStation(station)) } })
        // Optional since schema 2, like "stations": older readers ignore it.
        backup.punctualityHistory?.let { put("punctualityHistory", PunctualityHistory.toJson(it)) }
        put("mobility", JSONObject().apply { put("maxChanges", backup.mobilityPreferences.maxChanges); put("transferMarginMinutes", backup.mobilityPreferences.transferMarginMinutes); put("stepFreePreferred", backup.mobilityPreferences.stepFreePreferred); put("compactTimetablePreferred", backup.mobilityPreferences.compactTimetablePreferred) })
    }.toString(2)

    /** Reads UTF-8 input with a hard limit before JSON parsing to bound memory use. */
    fun readBoundedUtf8(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_BYTES) { "Il backup supera il limite di 1 MiB." }
                output.write(buffer, 0, count)
            }
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    fun decode(raw: String, stations: List<Station>): LocalBackupResult = runCatching {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Il backup supera il limite di 1 MiB." }
        val root = JSONObject(raw)
        require(root.optString("kind") == "binario-local-backup") { "Il file non è un backup Binario." }
        val schemaVersion = root.optInt("schemaVersion", -1)
        require(schemaVersion in 1..VERSION) { "Versione backup non supportata." }
        val embeddedStations = root.optJSONArray("stations").orEmpty()
            .mapNotNull { (it as? JSONObject)?.let(::decodeStation) }
            .distinctBy { it.id }
            .take(MAX_STATIONS)
            .associateBy { it.id }
        val restoredStations = LinkedHashMap<String, Station>()
        fun station(stationId: String): Station? {
            val currentId = LegacyBackupStationIds.currentId(schemaVersion, stationId)
            stations.firstOrNull { it.id == currentId }?.let { return it }
            // Stations outside the catalogue (free text, search index) only exist in the backup.
            return embeddedStations[stationId]?.also { restoredStations[it.id] = it }
        }
        val routes = root.optJSONArray("routes").orEmpty().mapNotNull { item ->
            val objectValue = item as? JSONObject ?: return@mapNotNull null
            require(!isReservedRouteId(objectValue.optString("id"))) { "Il backup contiene un identificativo di tratta riservato." }
            val origin = station(objectValue.optString("originId")) ?: return@mapNotNull null
            val destination = station(objectValue.optString("destinationId")) ?: return@mapNotNull null
            SavedRoute(
                objectValue.optString("id"),
                objectValue.optString("name"),
                origin,
                destination,
                objectValue.optBoolean("pinned"),
                objectValue.optInt("manualOrder", -1).takeIf { it in 0 until SavedRouteOrdering.MAX_ROUTES }
            )
        }.filter {
            it.id.isNotBlank() &&
                it.name.isNotBlank() &&
                it.outwardOrigin != it.outwardDestination
        }.distinctBy { it.id }.take(32)
        // Schema v1/v2 has always required at least one valid personal route. Keep rejecting
        // route-less files rather than silently applying a partial backup with ambiguous intent.
        require(routes.isNotEmpty()) { "Il backup non contiene tratte valide." }
        val favouriteIds = root.optJSONArray("favouriteStationIds").orEmpty()
            .mapNotNull { (it as? String)?.let(::station)?.id }
            .toSet()
        val services = root.optJSONArray("monitoredServices").orEmpty().mapNotNull { item -> (item as? JSONObject)?.let { ServiceMonitor(it.optString("number"), it.optString("label")) } }.filter { it.number.isNotBlank() }.distinctBy { it.number }.take(12)
        val stationMonitors = root.optJSONArray("stationMonitors").orEmpty().mapNotNull { item -> (item as? JSONObject)?.let { value ->
            val station = station(value.optString("stationId")) ?: return@let null
            runCatching { StationMonitor(station.id, station.name, StationMonitorMode.valueOf(value.optString("mode"))) }.getOrNull()
        } }.filterNotNull().distinctBy { it.stationId }.take(12)
        val alerts = root.optJSONArray("commuterAlerts").orEmpty().mapNotNull { item -> (item as? JSONObject)?.let { value ->
            runCatching {
                val time = LocalTime.parse(value.optString("time")).toString()
                CommuterAlert(value.optString("id"), value.optString("routeLabel"), value.optString("stationName"), time, value.optInt("leadMinutes", 15).coerceIn(1, 60), AlertRepeat.valueOf(value.optString("repeat")), value.optBoolean("quietHoursEnabled"), value.optBoolean("enabled", true))
            }.getOrNull()
        } }.filterNotNull().filter { it.id.isNotBlank() && it.stationName.isNotBlank() }.distinctBy { it.id }.take(24)
        val mobility = root.optJSONObject("mobility")?.let { value -> MobilityPreferences(value.optInt("maxChanges", -1).takeIf { it >= 0 }, value.optInt("transferMarginMinutes", 10).coerceIn(0, 45), value.optBoolean("stepFreePreferred"), value.optBoolean("compactTimetablePreferred")) } ?: MobilityPreferences()
        val usedIds = routes.flatMap { listOf(it.outwardOrigin.id, it.outwardDestination.id) }.toSet() +
            favouriteIds + stationMonitors.map { it.stationId }
        val punctuality = root.optJSONArray("punctualityHistory")?.let(PunctualityHistory::fromJson)
        LocalDataBackup(routes, favouriteIds, services, stationMonitors, alerts, mobility, restoredStations.values.filter { it.id in usedIds }, punctuality)
    }.fold({ LocalBackupResult.Success(it) }, { LocalBackupResult.Failure(it.message ?: "Backup non leggibile.") })

    private fun encodeStation(station: Station): JSONObject = JSONObject().apply {
        put("id", station.id)
        put("name", station.name)
        put("city", station.city)
        station.region?.let { put("region", it) }
        station.railwayCode?.let { put("railwayCode", it) }
        station.trenitaliaLocationId?.let { put("trenitaliaLocationId", it) }
        if (station.aliases.isNotEmpty()) put("aliases", JSONArray(station.aliases.take(MAX_STATION_ALIASES)))
        station.latitude?.let { put("latitude", it) }
        station.longitude?.let { put("longitude", it) }
    }

    private fun decodeStation(value: JSONObject): Station? {
        fun text(key: String): String? = value.optString(key, "").trim().takeIf { it.isNotEmpty() && it.length <= MAX_STATION_TEXT }
        val id = text("id") ?: return null
        val name = text("name") ?: return null
        fun coordinate(key: String, limit: Double): Double? =
            if (value.has(key)) value.optDouble(key).takeIf { !it.isNaN() && it in -limit..limit } else null
        return Station(
            id = id,
            name = name,
            city = text("city") ?: name,
            region = text("region"),
            railwayCode = text("railwayCode"),
            trenitaliaLocationId = text("trenitaliaLocationId"),
            aliases = value.optJSONArray("aliases").orEmpty()
                .mapNotNull { (it as? String)?.trim()?.takeIf { alias -> alias.isNotEmpty() && alias.length <= MAX_STATION_TEXT } }
                .take(MAX_STATION_ALIASES),
            latitude = coordinate("latitude", 90.0),
            longitude = coordinate("longitude", 180.0)
        )
    }

    private fun isReservedRouteId(id: String): Boolean =
        id == UNCONFIGURED_ROUTE_ID || id.startsWith("search-")

    private fun JSONArray?.orEmpty(): List<Any?> = this?.let { array -> List(array.length()) { index -> array.opt(index) } } ?: emptyList()
}
