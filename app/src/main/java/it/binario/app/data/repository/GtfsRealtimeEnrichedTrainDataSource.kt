package it.binario.app.data.repository

import it.binario.app.data.*
import it.binario.app.data.core.EnrichedRealtimeStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Common search/widget/worker boundary for feeds joined to their exact static timetable. */
class GtfsRealtimeEnrichedTrainDataSource(
    private val plannedSource: TrainDataSource,
    private val realtime: RealtimeEnrichmentService,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = RailTime.defaultZone
) : TrainDataSource {
    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        loadRoute(route, direction, JourneyQuery.now(clock))

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        val result = plannedSource.loadRoute(route, direction, query)
        if (result !is DataResult.Success || query.serviceDate != clock.instant().atZone(zone).toLocalDate()) return result
        val identities = result.snapshot.services.mapNotNull { it.gtfsIdentity }
        if (identities.isEmpty()) return result
        return withContext(Dispatchers.Default) {
            val updates = realtime.collectFor(identities, zone).stops.filter(::fresh)
            val used = mutableListOf<EnrichedRealtimeStop>()
            val services = result.snapshot.services.map { service ->
                val identity = service.gtfsIdentity ?: return@map service
                if (identity.stops.size != service.stops.size) return@map service
                val matches = updates.filter {
                    it.scheduledStop.sourceId.value == identity.sourceId &&
                        it.scheduledStop.sourceTripId == identity.tripId &&
                        it.scheduledStop.serviceDate == identity.serviceDate &&
                        (it.update.serviceDate == null || it.update.serviceDate == identity.serviceDate) &&
                        // Without start_date, never apply today's observation to tomorrow's train.
                        (it.update.serviceDate != null || it.scheduledStop.scheduledAt.toLocalDate() == query.serviceDate)
                }
                val perStop = identity.stops.map { stop ->
                    matches.filter { it.scheduledStop.sourceStopId == stop.stopId && it.scheduledStop.stopSequence == stop.sequence }
                        .maxByOrNull { it.update.observedAt }
                }
                val timed = perStop.map { update -> update?.takeIf { delay(it) != null } }
                if (timed.all { it == null }) return@map service
                used += timed.filterNotNull()
                val stops = service.stops.mapIndexed { index, stop ->
                    val live = timed[index] ?: return@mapIndexed stop
                    stop.copy(expected = expected(live), delayMinutes = delay(live)!!)
                }
                val departure = timed.firstOrNull()
                val departureDelay = departure?.let(::delay)
                service.copy(
                    stops = stops,
                    expectedDeparture = departure?.let(::expected) ?: service.expectedDeparture,
                    delayMinutes = departureDelay ?: service.delayMinutes,
                    officialDelayMinutes = if (departure != null) {
                        departure.update.officialDelay?.value?.toMinutes()?.toInt()
                    } else service.officialDelayMinutes,
                    status = if (departureDelay != null) {
                        if (departureDelay > 0) ServiceStatus.DELAYED else ServiceStatus.ON_TIME
                    } else service.status,
                    operationalStatusVerified = service.operationalStatusVerified || departure != null,
                    operationalSourceIds = service.operationalSourceIds + timed.filterNotNull().map { it.update.source.sourceId.value },
                    operationalObservedAtEpochMillis = (timed.filterNotNull().map { it.update.observedAt.toEpochMilli() } +
                        listOfNotNull(service.operationalObservedAtEpochMillis)).maxOrNull()
                )
            }
            if (used.isEmpty()) return@withContext result
            val sources = used.map { it.update.source.sourceId.value }.distinct()
            val allVerified = services.all { it.operationalStatusVerified }
            DataResult.Success(result.snapshot.copy(
                services = services,
                providerName = result.snapshot.providerName + " + " + sources.joinToString(" / "),
                sourceObservedAtEpochMillis = used.maxOf { it.update.observedAt.toEpochMilli() },
                expiresAtEpochMillis = minOf(result.snapshot.expiresAtEpochMillis,
                    used.minOf { (it.update.source.expiresAt ?: it.update.observedAt.plusSeconds(300)).toEpochMilli() }),
                freshness = if (allVerified) Freshness.FRESH else result.snapshot.freshness,
                confidence = if (allVerified && result.snapshot.confidence == DataConfidence.STATIC_ONLY) DataConfidence.OPEN_DATA else result.snapshot.confidence,
                qualityWarnings = if (allVerified) result.snapshot.qualityWarnings - SnapshotWarning.STATIC_ONLY else result.snapshot.qualityWarnings,
                notice = listOfNotNull(result.snapshot.notice, "Ritardi live GTFS riconciliati per corsa, fermata e data: ${sources.joinToString()}.").joinToString(" ")
            ))
        }
    }

    private fun fresh(stop: EnrichedRealtimeStop): Boolean {
        val now = clock.instant()
        return !stop.update.observedAt.isBefore(now.minusSeconds(300)) &&
            !stop.update.observedAt.isAfter(now.plusSeconds(60)) &&
            stop.update.source.expiresAt?.isAfter(now) != false
    }

    private fun delay(stop: EnrichedRealtimeStop): Int? =
        (stop.update.officialDelay?.value ?: stop.update.predictedAt?.value?.let {
            Duration.between(stop.scheduledStop.scheduledAt, it)
        })?.toMinutes()?.toInt()

    private fun expected(stop: EnrichedRealtimeStop): String =
        (stop.update.predictedAt?.value ?: stop.scheduledStop.scheduledAt.plusMinutes(delay(stop)!!.toLong()))
            .format(DateTimeFormatter.ofPattern("HH:mm"))
}
