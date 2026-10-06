package it.binario.app.data.gtfs

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.EnrichedRealtimeStop
import it.binario.app.data.core.RealtimeOperationalEnricher
import it.binario.app.data.core.SourceId
import it.binario.app.data.live.EuropeanSleeperGtfsRealtimeAdapter
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.TripUpdateProvider
import it.binario.app.data.sources.InternationalSourceCatalog
import it.binario.app.data.sources.RailSourceDefinition
import java.io.ByteArrayInputStream
import java.text.Normalizer
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Remote GTFS is deliberately a last-resort path. It is used for a source
 * explicitly classified BEST_EFFORT_FALLBACK and only after higher-quality
 * route sources have failed. Bytes are cached in memory to avoid repeated
 * downloads during a session.
 *
 * Optional realtime enrichment is route-scoped and same-feed only: the
 * TripUpdate is resolved against the exact GTFS bytes that produced the route.
 * This prevents a community realtime feed from ever being applied globally or
 * to a service whose static identity cannot be proven.
 */
class RemoteGtfsFallbackDataSource(
    private val source: RailSourceDefinition,
    private val parserSourceId: SourceId,
    private val downloader: GtfsArtifactSource = GtfsArtifactDownloader(),
    private val clock: Clock = Clock.systemUTC(),
    private val notBefore: LocalDate? = null,
    private val cacheTtl: Duration = Duration.ofHours(6),
    private val realtimeProvider: TripUpdateProvider? = null,
    private val realtimeZone: ZoneId = ZoneId.of("Europe/Rome"),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onParsing: (() -> Unit)? = null
) : TrainDataSource {
    @Volatile private var cached: CachedFeed? = null
    private val feedMutex = Mutex()

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        loadRoute(route, direction, JourneyQuery.now())

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        notBefore?.let { if (query.serviceDate < it) return DataResult.Failure("La fonte fallback non è ancora valida per la data selezionata.") }
        val feed = runCatching { cachedFeed() }.getOrElse {
            return DataResult.Failure("La fonte GTFS fallback ${source.displayName} non è disponibile: ${it.message.orEmpty()}")
        }
        return when (val result = feed.adapter.loadRoute(route, direction, query)) {
            is DataResult.Success -> {
                val planned = result.snapshot.copy(
                    providerName = "${source.displayName} · fallback best-effort",
                    notice = listOfNotNull(
                        "Copertura fallback: usata perché le fonti ferroviarie primarie non hanno restituito questa tratta. Verifica l'orario con il vettore prima del viaggio.",
                        result.snapshot.notice
                    ).joinToString(" "),
                    coverageFallback = true
                )
                DataResult.Success(enrichWithSameFeedRealtime(planned, feed.bytes, query))
            }
            is DataResult.Failure -> result
        }
    }

    private suspend fun enrichWithSameFeedRealtime(
        snapshot: RouteSnapshot,
        staticBytes: ByteArray,
        query: JourneyQuery
    ): RouteSnapshot {
        val provider = realtimeProvider ?: return snapshot
        val updates = when (val realtime = provider.getTripUpdates()) {
            is ProviderResult.Success -> realtime.value
            is ProviderResult.Partial -> realtime.value
            is ProviderResult.Failure, is ProviderResult.NotModified -> return snapshot
        }
        if (updates.isEmpty()) return snapshot

        val resolver = EmbeddedGtfsRealtimeScheduleResolver(
            openAsset = { ByteArrayInputStream(staticBytes) },
            packs = listOf(EmbeddedGtfsPack(parserSourceId, "remote/${source.id.value}.zip")),
            cacheLimit = 1
        )
        val enriched = withContext(parsingDispatcher) {
            updates.mapNotNull { update ->
                try {
                    val scheduled = resolver.resolve(parserSourceId, update, query.serviceDate, realtimeZone)
                        ?: return@mapNotNull null
                    RealtimeOperationalEnricher.enrich(update, scheduled)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
        }
        if (enriched.isEmpty()) return snapshot

        val byTrip = enriched.groupBy { it.scheduledStop.sourceTripId }
        val prefix = "${parserSourceId.value}:"
        fun runIdentity(service: TrainService): Pair<String, LocalDate>? {
            if (!service.id.startsWith(prefix)) return null
            val templateAndDate = service.id.removePrefix(prefix)
            val serviceDate = runCatching { LocalDate.parse(templateAndDate.substringAfterLast(':', "")) }.getOrNull()
                ?: return null
            return templateAndDate.substringBeforeLast(':').takeIf(String::isNotBlank)?.let { it to serviceDate }
        }
        val runCounts = snapshot.services.mapNotNull(::runIdentity).groupingBy { it.first }.eachCount()
        val services = snapshot.services.map { service ->
            val (tripId, serviceDate) = runIdentity(service) ?: return@map service
            // GTFS-RT start_date is not carried by the normalized update. If a
            // template appears on two service days in this result, attribution
            // is ambiguous: retain both as programmed static services instead.
            if (runCounts[tripId] != 1 || serviceDate != query.serviceDate) return@map service
            val updatesForTrip = byTrip[tripId].orEmpty()
            val originMatches = updatesForTrip.any { update ->
                normalizeStation(update.scheduledStop.locationLabel) == normalizeStation(service.origin) &&
                    update.scheduledStop.scheduledAt.toLocalDateTime() == service.scheduledDepartureDateTime
            }
            if (!originMatches) return@map service
            enrichService(service, updatesForTrip, provider.descriptor.id)
        }
        val used = services.any { provider.descriptor.id.value in it.operationalSourceIds }
        if (!used) return snapshot

        val observed = services.mapNotNull(TrainService::operationalObservedAtEpochMillis).maxOrNull()
            ?: snapshot.sourceObservedAtEpochMillis
        return snapshot.copy(
            services = services,
            providerName = "${snapshot.providerName} + ${provider.descriptor.displayName}",
            sourceObservedAtEpochMillis = maxOf(snapshot.sourceObservedAtEpochMillis, observed),
            freshness = Freshness.FRESH,
            notice = listOfNotNull(
                snapshot.notice,
                "Realtime fallback applicato soltanto agli aggiornamenti riconciliati con trip_id e fermata dello stesso archivio GTFS statico."
            ).joinToString(" ")
        )
    }

    private fun enrichService(
        service: TrainService,
        updates: List<EnrichedRealtimeStop>,
        realtimeSourceId: SourceId
    ): TrainService {
        if (updates.isEmpty()) return service
        val byStation = updates.associateBy { normalizeStation(it.scheduledStop.locationLabel) }
        val enrichedStops = service.stops.map { stop -> enrichStop(stop, byStation[normalizeStation(stop.station)]) }
        val origin = byStation[normalizeStation(service.origin)]
        val delay = origin?.update?.officialDelay?.value?.toMinutes()?.toInt()
        val predicted = origin?.update?.predictedAt?.value
        val statusVerified = delay != null
        val observed = updates.maxOfOrNull { it.update.observedAt.toEpochMilli() }

        return service.copy(
            expectedDeparture = predicted?.format(HH_MM) ?: service.expectedDeparture,
            delayMinutes = delay ?: service.delayMinutes,
            officialDelayMinutes = delay ?: service.officialDelayMinutes,
            status = when {
                delay == null -> service.status
                delay > 0 -> ServiceStatus.DELAYED
                else -> ServiceStatus.ON_TIME
            },
            stops = enrichedStops,
            operationalStatusVerified = service.operationalStatusVerified || statusVerified,
            operationalSourceIds = service.operationalSourceIds + realtimeSourceId.value,
            operationalObservedAtEpochMillis = listOfNotNull(service.operationalObservedAtEpochMillis, observed).maxOrNull()
        )
    }

    private fun enrichStop(stop: TrainStop, update: EnrichedRealtimeStop?): TrainStop {
        if (update == null) return stop
        val predicted = update.update.predictedAt?.value
        val delay = update.update.officialDelay?.value?.toMinutes()?.toInt()
        return stop.copy(
            expected = predicted?.format(HH_MM) ?: stop.expected,
            delayMinutes = delay ?: stop.delayMinutes
        )
    }

    private suspend fun cachedFeed(): CachedFeed {
        cached?.takeIf { clock.instant().isBefore(it.expiresAt) }?.let { return it }
        return feedMutex.withLock {
            cached?.takeIf { clock.instant().isBefore(it.expiresAt) }?.let { return@withLock it }
            val bytes = withContext(ioDispatcher) {
                downloader.open(source, allowBestEffortFallback = true).use { session -> session.input.readBytes() }
            }
            require(bytes.isNotEmpty()) { "Archivio GTFS vuoto" }
            CachedFeed(
                bytes = bytes,
                adapter = EmbeddedGtfsRouteAdapter(
                    openAsset = { ByteArrayInputStream(bytes) },
                    packs = listOf(EmbeddedGtfsPack(parserSourceId, "remote/${source.id.value}.zip")),
                    clock = { clock.millis() },
                    ioDispatcher = ioDispatcher,
                    parsingDispatcher = parsingDispatcher,
                    onParsing = onParsing
                ),
                expiresAt = clock.instant().plus(cacheTtl)
            ).also { cached = it }
        }
    }

    private fun normalizeStation(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}".toRegex(), "")
        .uppercase(Locale.ROOT)
        .replace(Regex("[^A-Z0-9]"), "")

    private data class CachedFeed(
        val bytes: ByteArray,
        val adapter: EmbeddedGtfsRouteAdapter,
        val expiresAt: java.time.Instant
    )

    companion object {
        private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

        fun europeanSleeper(clock: Clock = Clock.systemUTC()): RemoteGtfsFallbackDataSource =
            RemoteGtfsFallbackDataSource(
                source = requireNotNull(InternationalSourceCatalog.byId(SourceId("european-sleeper-gtfs"))),
                // The legacy source id already exists in RailSourceCatalog and is used only as parser provenance.
                parserSourceId = SourceId("european-sleeper"),
                clock = clock,
                notBefore = LocalDate.of(2026, 9, 9),
                realtimeProvider = EuropeanSleeperGtfsRealtimeAdapter(clock = clock)
            )
    }
}
