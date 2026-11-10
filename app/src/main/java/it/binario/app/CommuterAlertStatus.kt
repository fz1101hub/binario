package it.binario.app

import android.content.Context
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainService
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.journeys.LiveStopTimes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** The current state of the train a commuter reminder is about, from verified operational data only. */
data class CommuterLiveStatus(val delayMinutes: Int, val platform: String?, val cancelled: Boolean)

/** The localized pieces of the status sentence; plain functions so the formatting is testable without Android. */
data class CommuterAlertStatusStrings(
    val unavailable: String,
    val onTime: String,
    val cancelled: String,
    val delay: (Int) -> String,
    val withPlatform: (status: String, platform: String) -> String
)

/**
 * Pure rules of the live status of a commuter reminder: which train of the route the reminder means (the one
 * scheduled at the reminder's time at its station) and how its state reads in the notification.
 */
object CommuterAlertStatusRules {
    /** "Milano Centrale → Pavia": the route label of the reminder, split into its two station names. */
    fun routeStations(routeLabel: String): Pair<String, String>? {
        val parts = routeLabel.split(" → ")
        if (parts.size != 2) return null
        val origin = parts[0].trim()
        val destination = parts[1].trim()
        return if (origin.isEmpty() || destination.isEmpty()) null else origin to destination
    }

    /**
     * The two stations of [routeLabel], by name: first among the stations remembered on this device ([known]), else in
     * the embedded catalogue ([catalogue], read only when needed), since a backup import or a new device remembers only
     * the stations outside the catalogue. Null when a name is found in neither.
     */
    fun routeEndpoints(routeLabel: String, known: List<Station>, catalogue: () -> List<Station>): Pair<Station, Station>? {
        val (originName, destinationName) = routeStations(routeLabel) ?: return null
        fun named(stations: List<Station>, name: String) = stations.firstOrNull { it.name.equals(name, ignoreCase = true) }
        var origin = named(known, originName)
        var destination = named(known, destinationName)
        if (origin == null || destination == null) {
            val all = catalogue()
            origin = origin ?: named(all, originName)
            destination = destination ?: named(all, destinationName)
        }
        return if (origin != null && destination != null) origin to destination else null
    }

    /**
     * The train scheduled at [time] ("HH:mm") at [station]: first a stop of that name at that time, else a train
     * leaving the route's origin at that time when the station is the origin. Null rather than a guess.
     */
    fun pick(services: List<TrainService>, station: String, time: String): TrainService? {
        val wanted = LiveStopTimes.parse(time) ?: return null
        val atStop = services.filter { service ->
            service.stops.any { LiveStopTimes.namesMatch(it.station, station) && LiveStopTimes.parse(it.scheduled) == wanted }
        }
        atStop.singleOrNull()?.let { return it }
        if (atStop.size > 1) return atStop.firstOrNull { it.operationalStatusVerified } ?: atStop.first()
        return services.firstOrNull { LiveStopTimes.namesMatch(it.origin, station) && LiveStopTimes.parse(it.scheduledDeparture) == wanted }
    }

    /** The status at [station]; null for scheduled-only data, which says nothing about the train today. */
    fun statusOf(service: TrainService, station: String): CommuterLiveStatus? {
        if (!service.operationalStatusVerified) return null
        if (service.status == ServiceStatus.CANCELLED) return CommuterLiveStatus(0, null, cancelled = true)
        val stop = service.stops.firstOrNull { LiveStopTimes.namesMatch(it.station, station) }
        val verifiedStop = stop?.takeIf { LiveStopTimes.isVerified(service, it) }
        val delay = verifiedStop?.delayMinutes ?: service.officialDelayMinutes ?: service.delayMinutes
        val platform = (stop?.platform ?: service.platform.takeIf { stop == null || stop == service.stops.firstOrNull() })?.trim()?.takeIf { it.isNotEmpty() }
        return CommuterLiveStatus(delay.coerceAtLeast(0), platform, cancelled = false)
    }

    /** "ritardo di 5 minuti, binario 3", "in orario", "treno soppresso" or "stato non disponibile". */
    fun format(status: CommuterLiveStatus?, strings: CommuterAlertStatusStrings): String {
        if (status == null) return strings.unavailable
        if (status.cancelled) return strings.cancelled
        val base = if (status.delayMinutes > 0) strings.delay(status.delayMinutes) else strings.onTime
        return status.platform?.let { strings.withPlatform(base, it) } ?: base
    }
}

/** Android side of the live status: finds the route among the known stations and asks the route sources once. */
internal object CommuterAlertStatusLookup {
    const val TIMEOUT_MILLIS = 7_000L

    /**
     * The status, or null after [TIMEOUT_MILLIS]: the lookup runs in its own scope and is only awaited, so a source
     * busy in blocking work cannot hold the receiver past its time.
     */
    suspend fun fetch(context: Context, alert: CommuterAlert): CommuterLiveStatus? {
        val lookup = CoroutineScope(SupervisorJob() + Dispatchers.IO).async { lookUp(context, alert) }
        return try {
            withTimeoutOrNull(TIMEOUT_MILLIS) { lookup.await() }
        } catch (error: CancellationException) {
            lookup.cancel()
            throw error
        } catch (_: Exception) {
            null
        }.also { if (it == null) lookup.cancel() }
    }

    private suspend fun lookUp(context: Context, alert: CommuterAlert): CommuterLiveStatus? {
        val (origin, destination) = CommuterAlertStatusRules.routeEndpoints(alert.routeLabel, CustomStationStore(context).stations()) {
            runCatching { EmbeddedCuratedRailStationCatalog({ path -> context.assets.open(path) }).load() }.getOrDefault(emptyList())
        } ?: return null
        val route = SavedRoute("commuter-alert-${alert.id}", alert.routeLabel, origin, destination)
        val snapshot = (BinarioDataGraph.routeDataSource(context).loadRoute(route, Direction.OUTBOUND) as? DataResult.Success)?.snapshot ?: return null
        if (snapshot.isExpired) return null
        return CommuterAlertStatusRules.pick(snapshot.services, alert.stationName, alert.time)
            ?.let { CommuterAlertStatusRules.statusOf(it, alert.stationName) }
    }

    fun strings(context: Context): CommuterAlertStatusStrings = CommuterAlertStatusStrings(
        unavailable = context.getString(R.string.commuter_alert_status_unavailable),
        onTime = context.getString(R.string.commuter_alert_status_on_time),
        cancelled = context.getString(R.string.commuter_alert_status_cancelled),
        delay = { minutes -> context.resources.getQuantityString(R.plurals.commuter_alert_status_delay, minutes, minutes) },
        withPlatform = { status, platform -> context.getString(R.string.commuter_alert_status_platform, status, platform) }
    )
}
