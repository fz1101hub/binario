package it.binario.app.data.repository

import it.binario.app.data.core.EnrichedRealtimeStop
import it.binario.app.data.core.RealtimeOperationalEnricher
import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.EmbeddedGtfsRealtimeScheduleResolver
import it.binario.app.data.provider.ProviderError
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.TripUpdateProvider
import java.time.LocalDate
import java.time.ZoneId
import it.binario.app.data.GtfsServiceIdentity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Snapshot aggregato dei soli aggiornamenti raccordati al rispettivo orario GTFS. */
data class RealtimeEnrichmentSnapshot(
    val stops: List<EnrichedRealtimeStop>,
    val sourceErrors: Map<SourceId, ProviderError>
)

/**
 * Orchestratore del massimo dettaglio realtime attualmente pubblico.
 * Un update resta fuori dal risultato se non si può dimostrare la corrispondenza
 * con la stessa corsa e fermata nel data pack statico della stessa fonte.
 */
class RealtimeEnrichmentService(
    private val providers: List<TripUpdateProvider>,
    private val scheduleResolver: EmbeddedGtfsRealtimeScheduleResolver
) {
    /** Resolve only trips present in this search, not every trip in a national feed. */
    suspend fun collectFor(identities: List<GtfsServiceIdentity>, zone: ZoneId): RealtimeEnrichmentSnapshot = coroutineScope {
        val results = providers.mapNotNull { provider ->
            val staticId = staticFeedFor(provider.descriptor.id) ?: return@mapNotNull null
            val wanted = identities.filter { it.sourceId == staticId.value }
            if (wanted.isEmpty()) return@mapNotNull null
            async {
                val stops = mutableListOf<EnrichedRealtimeStop>()
                val errors = linkedMapOf<SourceId, ProviderError>()
                val updates = when (val result = provider.getTripUpdates()) {
                    is ProviderResult.Success -> result.value
                    is ProviderResult.Partial -> result.value
                    is ProviderResult.Failure -> {
                        errors[provider.descriptor.id] = result.error
                        emptyList()
                    }
                    is ProviderResult.NotModified -> emptyList()
                }
                val byTrip = wanted.groupBy { it.tripId }
                updates.forEach { update ->
                    if (update.source.sourceId != provider.descriptor.id) return@forEach
                    byTrip[update.sourceTripId].orEmpty().forEach identityLoop@ { identity ->
                        if (update.serviceDate != null && update.serviceDate != identity.serviceDate) return@identityLoop
                        if (identity.stops.none { stop ->
                            (update.sourceStopId == null || update.sourceStopId == stop.stopId) &&
                                (update.stopSequence == null || update.stopSequence == stop.sequence)
                        }) return@identityLoop
                        val scheduled = scheduleResolver.resolve(staticId, update, identity.serviceDate, zone) ?: return@identityLoop
                        RealtimeOperationalEnricher.enrich(update, scheduled)?.let(stops::add)
                    }
                }
                RealtimeEnrichmentSnapshot(stops, errors)
            }
        }.awaitAll()
        RealtimeEnrichmentSnapshot(results.flatMap { it.stops }, results.flatMap { it.sourceErrors.entries }.associate { it.toPair() })
    }

    suspend fun collect(serviceDate: LocalDate, zone: ZoneId): RealtimeEnrichmentSnapshot {
        val resolved = mutableListOf<EnrichedRealtimeStop>()
        val errors = linkedMapOf<SourceId, ProviderError>()
        providers.forEach { provider ->
            when (val result = provider.getTripUpdates()) {
                is ProviderResult.Success -> resolve(provider.descriptor.id, result.value, serviceDate, zone, resolved)
                is ProviderResult.Partial -> resolve(provider.descriptor.id, result.value, serviceDate, zone, resolved)
                is ProviderResult.Failure -> errors[provider.descriptor.id] = result.error
                is ProviderResult.NotModified -> Unit
            }
        }
        return RealtimeEnrichmentSnapshot(
            stops = resolved.distinctBy { item ->
                "${item.update.runId.value}:${item.scheduledStop.sourceStopId}:${item.scheduledStop.stopSequence}"
            },
            sourceErrors = errors
        )
    }

    private fun resolve(
        providerId: SourceId,
        updates: List<it.binario.app.data.core.RealtimeStopUpdate>,
        serviceDate: LocalDate,
        zone: ZoneId,
        destination: MutableList<EnrichedRealtimeStop>
    ) {
        val staticSourceId = staticFeedFor(providerId) ?: return
        updates.forEach { update ->
            val scheduled = listOf(serviceDate, serviceDate.minusDays(1), serviceDate.minusDays(2))
                .firstNotNullOfOrNull { candidate -> scheduleResolver.resolve(staticSourceId, update, candidate, zone) }
                ?: return@forEach
            RealtimeOperationalEnricher.enrich(update, scheduled)?.let(destination::add)
        }
    }

    private fun staticFeedFor(providerId: SourceId): SourceId? = when (providerId.value) {
        "alto-adige-sta-realtime" -> SourceId("alto-adige-sta-gtfs")
        "sncf-gtfs-rt" -> SourceId("sncf-gtfs")
        else -> null
    }
}
