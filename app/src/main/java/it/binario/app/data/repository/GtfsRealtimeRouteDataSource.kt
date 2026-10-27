package it.binario.app.data.repository

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailTime
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.SnapshotQualityAssessor
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import it.binario.app.data.core.EnrichedRealtimeStop
import it.binario.app.data.core.RealtimeOperationalEnricher
import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.RealtimeStopEventKind
import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.ResolvedScheduledStop
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.TripUpdateProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Reuses the existing official STA/SNCF adapters and compact GTFS resolver.
 * Only feeds present in the result are fetched, and only the requested trip ids are resolved.
 * Missing service-day identity, expired observations or an ambiguous origin retain the timetable.
 */
class GtfsRealtimeRouteDataSource(
    private val plannedSource: TrainDataSource,
    private val providersByStaticFeed: Map<SourceId, TripUpdateProvider>,
    private val resolve: (SourceId, RealtimeStopUpdate, LocalDate, ZoneId) -> ResolvedScheduledStop?,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = RailTime.defaultZone
) : TrainDataSource {
    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        loadRoute(route, direction, JourneyQuery.now(clock))

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        val result = plannedSource.loadRoute(route, direction, query)
        if (result !is DataResult.Success || query.serviceDate != clock.instant().atZone(zone).toLocalDate()) return result
        var snapshot = result.snapshot
        for ((staticFeed, provider) in providersByStaticFeed) {
            val identities = snapshot.services.mapNotNull { identity(it, staticFeed) }.toSet()
            if (identities.isEmpty()) continue
            // Fetchers switch to IO themselves; decoding and reconciliation stay off the UI thread.
            val updates = when (val response = withContext(Dispatchers.Default) { provider.getTripUpdates() }) {
                is ProviderResult.Success -> response.value
                is ProviderResult.Partial -> response.value
                is ProviderResult.Failure, is ProviderResult.NotModified -> continue
            }
            val now = clock.instant()
            val resolved = withContext(Dispatchers.Default) {
                val applicable = updates.filter { update ->
                    val day = update.serviceDate
                    day != null && (update.sourceTripId to day) in identities &&
                        update.eventKind == RealtimeStopEventKind.DEPARTURE &&
                        update.source.sourceId == provider.descriptor.id &&
                        update.source.expiresAt?.isAfter(now) == true &&
                        !update.observedAt.isAfter(now.plusSeconds(60)) &&
                        !update.observedAt.isBefore(now.minusSeconds(300))
                }
                applicable.mapNotNull { update ->
                    val day = update.serviceDate ?: return@mapNotNull null
                    val scheduled = resolve(staticFeed, update, day, zone) ?: return@mapNotNull null
                    RealtimeOperationalEnricher.enrich(update, scheduled)
                }
            }
            if (resolved.isEmpty()) continue
            val used = mutableListOf<EnrichedRealtimeStop>()
            val services = snapshot.services.map { service ->
                val (trip, date) = identity(service, staticFeed) ?: return@map service
                val matching = resolved.filter { it.update.sourceTripId == trip && it.update.serviceDate == date }
                enrich(service, matching, used)
            }
            if (used.isEmpty()) continue
            val expiry = used.mapNotNull { it.update.source.expiresAt?.toEpochMilli() }.minOrNull() ?: continue
            val observed = used.maxOf { it.update.observedAt.toEpochMilli() }
            snapshot = snapshot.copy(
                services = services,
                providerName = "${snapshot.providerName} + ${provider.descriptor.displayName}",
                sourceObservedAtEpochMillis = observed,
                expiresAtEpochMillis = minOf(snapshot.expiresAtEpochMillis, expiry),
                confidence = DataConfidence.OPEN_DATA,
                freshness = Freshness.FRESH
            )
        }
        val quality = SnapshotQualityAssessor.assess(snapshot, clock.millis())
        return DataResult.Success(snapshot.copy(qualityScore = quality.score, qualityBand = quality.band, qualityWarnings = quality.warnings))
    }

    private fun identity(service: TrainService, source: SourceId): Pair<String, LocalDate>? {
        val prefix = "${source.value}:"
        if (!service.id.startsWith(prefix)) return null
        val value = service.id.removePrefix(prefix)
        val day = runCatching { LocalDate.parse(value.substringAfterLast(':')) }.getOrNull() ?: return null
        return value.substringBeforeLast(':').takeIf(String::isNotBlank)?.let { it to day }
    }

    private fun enrich(service: TrainService, updates: List<EnrichedRealtimeStop>, used: MutableList<EnrichedRealtimeStop>): TrainService {
        if (service.operationalStatusVerified) return service
        val origin = updates.singleOrNull {
            it.scheduledStop.locationLabel == service.origin &&
                it.scheduledStop.scheduledAt.toLocalDateTime() == service.scheduledDepartureDateTime
        } ?: return service
        val originPrediction = prediction(origin) ?: return service
        val stopUpdates = service.stops.map { stop ->
            val update = updates.singleOrNull {
                it.scheduledStop.locationLabel == stop.station && it.scheduledStop.scheduledAt.format(HH_MM) == stop.scheduled
            } ?: return@map stop
            val prediction = prediction(update) ?: return@map stop
            used += update
            stop.copy(expected = prediction.expected.format(HH_MM), delayMinutes = prediction.delay)
        }
        used += origin
        return service.copy(
            expectedDeparture = originPrediction.expected.format(HH_MM),
            delayMinutes = originPrediction.delay,
            officialDelayMinutes = originPrediction.officialDelay,
            status = if (originPrediction.delay > 0) ServiceStatus.DELAYED else ServiceStatus.ON_TIME,
            stops = stopUpdates,
            operationalStatusVerified = true,
            operationalSourceIds = setOf(origin.update.source.sourceId.value),
            operationalObservedAtEpochMillis = origin.update.observedAt.toEpochMilli()
        )
    }

    private fun prediction(stop: EnrichedRealtimeStop): Prediction? {
        val update = stop.update
        val official = update.officialDelay?.let { boundedMinutes(it.value) ?: return null }
        val scheduled = stop.scheduledStop.scheduledAt
        val expected = update.predictedAt?.value ?: official?.let { scheduled.plusMinutes(it.toLong()) } ?: return null
        val predictedDelay = Duration.between(scheduled, expected)
        val delay = official ?: boundedMinutes(predictedDelay) ?: return null
        if (official != null && update.predictedAt != null &&
            kotlin.math.abs(predictedDelay.seconds - requireNotNull(update.officialDelay).value.seconds) > 60
        ) return null
        return Prediction(expected, delay, official)
    }

    private fun boundedMinutes(value: Duration): Int? = value.toMinutes().takeIf { it in -720L..1440L }?.toInt()

    private data class Prediction(val expected: ZonedDateTime, val delay: Int, val officialDelay: Int?)

    private companion object { val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm") }
}
