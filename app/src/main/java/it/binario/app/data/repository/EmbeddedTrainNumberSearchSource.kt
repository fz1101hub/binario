package it.binario.app.data.repository

import it.binario.app.data.*
import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.planned.BoundedSingleFlightCache
import it.binario.app.data.provider.*
import java.time.Duration
import java.time.LocalTime

/** A shared bounded lookup lets offline rows appear before optional realtime enrichment. */
class EmbeddedTrainNumberSearchSource(
    private val static: EmbeddedGtfsRouteAdapter,
    private val realtime: GtfsRealtimeEnrichedTrainDataSource,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val cache = BoundedSingleFlightCache<List<TrainNumberMatch>>(4, 6 * 60 * 60_000L, now)

    fun provider(withRealtime: Boolean): TrainNumberSearchProvider = object : TrainNumberSearchProvider {
        override val descriptor = ProviderDescriptor(SourceId(if (withRealtime) "embedded-number-live" else "embedded-number"),
            if (withRealtime) "Aggiornamenti degli orari inclusi" else "Orari ferroviari inclusi", setOf(ProviderCapability.TRAIN_RUN_STATIC),
            CoverageDescriptor(description = "Ricerca per numero negli archivi ferroviari attivi"), AccessMode.OPEN_DIRECT,
            RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = Duration.ofMinutes(1)), 1)

        override suspend fun searchByNumber(query: TrainNumberQuery): ProviderResult<List<TrainNumberMatch>> {
            val matches = cache.getOrLoad("${query.normalized}:${query.date}") { static.searchByNumber(query) }.value
            val result = if (!withRealtime) matches else matches.map { match ->
                val enriched = realtime.enrich(DataResult.Success(match.snapshot), JourneyQuery(query.date, LocalTime.MIN), useLatestStopForService = true)
                if (enriched is DataResult.Success) TrainNumberMatch(enriched.snapshot.services.single(), enriched.snapshot) else match
            }
            val fetched = java.time.Instant.ofEpochMilli(now())
            return ProviderResult.Success(result, ProviderResponseMeta(descriptor.id, fetched))
        }
    }
}
