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
            val scheduled = scheduleResolver.resolve(staticSourceId, update, serviceDate, zone) ?: return@forEach
            RealtimeOperationalEnricher.enrich(update, scheduled)?.let(destination::add)
        }
    }

    private fun staticFeedFor(providerId: SourceId): SourceId? = when (providerId.value) {
        "alto-adige-sta-realtime" -> SourceId("alto-adige-sta-gtfs")
        "sncf-gtfs-rt" -> SourceId("sncf-gtfs")
        else -> null
    }
}
