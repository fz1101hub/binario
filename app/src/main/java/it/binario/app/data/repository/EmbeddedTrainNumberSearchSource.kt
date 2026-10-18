package it.binario.app.data.repository

import it.binario.app.data.*
import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.provider.*
import java.time.Duration
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last

/** A shared bounded lookup lets offline rows appear before optional realtime enrichment. */
class EmbeddedTrainNumberSearchSource(
    private val static: EmbeddedGtfsRouteAdapter,
    private val realtime: GtfsRealtimeEnrichedTrainDataSource,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun provider(withRealtime: Boolean): TrainNumberSearchProvider = object : TrainNumberSearchProvider {
        override val descriptor = ProviderDescriptor(SourceId(if (withRealtime) "embedded-number-live" else "embedded-number"),
            if (withRealtime) "Aggiornamenti degli orari inclusi" else "Orari ferroviari inclusi", setOf(ProviderCapability.TRAIN_RUN_STATIC),
            CoverageDescriptor(description = "Ricerca per numero negli archivi ferroviari attivi"), AccessMode.OPEN_DIRECT,
            RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = Duration.ofMinutes(1)), 1)

        override suspend fun searchByNumber(query: TrainNumberQuery): ProviderResult<List<TrainNumberMatch>> = observeByNumber(query).last()

        override fun observeByNumber(query: TrainNumberQuery): Flow<ProviderResult<List<TrainNumberMatch>>> = channelFlow {
            fun result(matches: List<TrainNumberMatch>) = ProviderResult.Success(matches, ProviderResponseMeta(descriptor.id, java.time.Instant.ofEpochMilli(now())))
            val matches = static.searchByNumber(query) { send(result(it)) }
            send(result(matches))
            val enrichedMatches = if (!withRealtime) matches else matches.map { match ->
                val enriched = realtime.enrich(DataResult.Success(match.snapshot), JourneyQuery(query.date, LocalTime.MIN), useLatestStopForService = true)
                if (enriched is DataResult.Success) TrainNumberMatch(enriched.snapshot.services.single(), enriched.snapshot) else match
            }
            if (withRealtime) send(result(enrichedMatches))
        }
    }
}
