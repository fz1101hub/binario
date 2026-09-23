package it.binario.app

import it.binario.app.data.SavedRoute
import it.binario.app.data.SavedRouteOrdering
import it.binario.app.data.Station
import org.json.JSONArray
import org.json.JSONObject

data class LocalDataBackup(
    val routes: List<SavedRoute>,
    val favouriteStationIds: Set<String>,
    val monitoredServices: List<ServiceMonitor>,
    val stationMonitors: List<StationMonitor>,
    val commuterAlerts: List<CommuterAlert>,
    val mobilityPreferences: MobilityPreferences
)

sealed interface LocalBackupResult {
    data class Success(val backup: LocalDataBackup) : LocalBackupResult
    data class Failure(val message: String) : LocalBackupResult
}

object LocalDataBackupCodec {
    const val MIME_TYPE = "application/json"
    private const val VERSION = 2

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
        put("mobility", JSONObject().apply { put("maxChanges", backup.mobilityPreferences.maxChanges); put("transferMarginMinutes", backup.mobilityPreferences.transferMarginMinutes); put("stepFreePreferred", backup.mobilityPreferences.stepFreePreferred); put("compactTimetablePreferred", backup.mobilityPreferences.compactTimetablePreferred) })
    }.toString(2)

    fun decode(raw: String, stations: List<Station>): LocalBackupResult = runCatching {
        val root = JSONObject(raw)
        require(root.optString("kind") == "binario-local-backup") { "Il file non è un backup Binario." }
        require(root.optInt("schemaVersion", -1) in 1..VERSION) { "Versione backup non supportata." }
        val routes = root.optJSONArray("routes").orEmpty().mapNotNull { item ->
            val objectValue = item as? JSONObject ?: return@mapNotNull null
            val origin = stations.firstOrNull { it.id == objectValue.optString("originId") } ?: return@mapNotNull null
            val destination = stations.firstOrNull { it.id == objectValue.optString("destinationId") } ?: return@mapNotNull null
            SavedRoute(
                objectValue.optString("id"),
                objectValue.optString("name"),
                origin,
                destination,
                objectValue.optBoolean("pinned"),
                objectValue.optInt("manualOrder", -1).takeIf { it in 0 until SavedRouteOrdering.MAX_ROUTES }
            )
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() && it.outwardOrigin != it.outwardDestination }.distinctBy { it.id }.take(32)
        require(routes.isNotEmpty()) { "Il backup non contiene tratte valide." }
        val favouriteIds = root.optJSONArray("favouriteStationIds").orEmpty().mapNotNull { it as? String }.filter { id -> stations.any { it.id == id } }.toSet()
        val services = root.optJSONArray("monitoredServices").orEmpty().mapNotNull { item -> (item as? JSONObject)?.let { ServiceMonitor(it.optString("number"), it.optString("label")) } }.filter { it.number.isNotBlank() }.distinctBy { it.number }.take(12)
        val stationMonitors = root.optJSONArray("stationMonitors").orEmpty().mapNotNull { item -> (item as? JSONObject)?.let { value ->
            val station = stations.firstOrNull { it.id == value.optString("stationId") } ?: return@let null
            runCatching { StationMonitor(station.id, station.name, StationMonitorMode.valueOf(value.optString("mode"))) }.getOrNull()
        } }.filterNotNull().distinctBy { it.stationId }.take(12)
        val alerts = root.optJSONArray("commuterAlerts").orEmpty().mapNotNull { item -> (item as? JSONObject)?.let { value ->
            runCatching { CommuterAlert(value.optString("id"), value.optString("routeLabel"), value.optString("stationName"), value.optString("time"), value.optInt("leadMinutes", 15).coerceIn(1, 60), AlertRepeat.valueOf(value.optString("repeat")), value.optBoolean("quietHoursEnabled"), value.optBoolean("enabled", true)) }.getOrNull()
        } }.filterNotNull().filter { it.id.isNotBlank() && it.stationName.isNotBlank() }.distinctBy { it.id }.take(24)
        val mobility = root.optJSONObject("mobility")?.let { value -> MobilityPreferences(value.optInt("maxChanges", -1).takeIf { it >= 0 }, value.optInt("transferMarginMinutes", 10).coerceIn(0, 45), value.optBoolean("stepFreePreferred"), value.optBoolean("compactTimetablePreferred")) } ?: MobilityPreferences()
        LocalDataBackup(routes, favouriteIds, services, stationMonitors, alerts, mobility)
    }.fold({ LocalBackupResult.Success(it) }, { LocalBackupResult.Failure(it.message ?: "Backup non leggibile.") })

    private fun JSONArray?.orEmpty(): List<Any?> = this?.let { array -> List(array.length()) { index -> array.opt(index) } } ?: emptyList()
}
