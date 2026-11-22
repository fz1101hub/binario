package it.binario.app.data.repository

import android.content.Context
import it.binario.app.data.LocalOnlyRailDataPolicy
import it.binario.app.data.RailDataRepository
import it.binario.app.data.TrainDataSource
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsRealtimeScheduleResolver
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.gtfs.ForeignPackDownloader
import it.binario.app.data.gtfs.ForeignPackOffers
import it.binario.app.data.gtfs.ForeignPackPins
import it.binario.app.data.gtfs.ForeignPackSnapshot
import it.binario.app.data.gtfs.ForeignPackStore
import it.binario.app.data.gtfs.GtfsPackOpener
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.gtfs.EmbeddedUrbanStopIndex
import it.binario.app.data.journey.EmbeddedGtfsJourneyProvider
import it.binario.app.data.provider.JourneyProvider
import it.binario.app.data.provider.KnownProviders
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.RailProvider
import it.binario.app.data.provider.ProviderRegistry
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.TripUpdateProvider
import it.binario.app.data.core.SourceId
import it.binario.app.data.bikesharing.BikeSharingService
import it.binario.app.data.bikesharing.BikeSharingSystems
import it.binario.app.data.live.HttpPublicSourceFetcher
import it.binario.app.data.live.PublicSourceRequestPolicy
import it.binario.app.data.sources.RailSourceCatalog
import it.binario.app.data.urban.StopScopedBoardProvider
import it.binario.app.data.urban.UrbanGtfsRealtimeAdapter
import it.binario.app.data.urban.UrbanPackRoutes
import it.binario.app.data.urban.UrbanRealtimeFeeds
import it.binario.app.data.urban.UrbanStopBoardAdapter
import it.binario.app.tile.NextTrainTileStore
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Punto di composizione unico del livello dati. Nessuna UI, worker o companion
 * deve istanziare direttamente repository o provider: questa classe rende la
 * configurazione coerente e sostituibile nei test.
 *
 * Le credenziali esterne non vengono mai memorizzate dal graph: i provider
 * Swiss/DB autorizzati vengono creati soltanto a partire da valori runtime
 * esplicitamente consegnati dal chiamante (secure store/backend personale).
 */
object BinarioDataGraph {
    private val defaultRegistry: ProviderRegistry by lazy { KnownProviders.defaultRegistry() }
    private val localOnlyPolicy = LocalOnlyRailDataPolicy
    private val routeSourcesByPackage = mutableMapOf<String, TrainDataSource>()
    private val realtimeResolversByPackage = mutableMapOf<String, EmbeddedGtfsRealtimeScheduleResolver>()
    private val numberSearchByPackage = mutableMapOf<String, TrainNumberSearchService>()
    private val journeySearchByPackage = mutableMapOf<String, JourneySearchService>()
    private val offlinePlannersByPackage = mutableMapOf<String, EmbeddedGtfsJourneyProvider>()
    private val urbanStopIndexesByPackage = mutableMapOf<String, EmbeddedUrbanStopIndex>()
    private val urbanRealtimeByPackage = mutableMapOf<String, Map<SourceId, UrbanGtfsRealtimeAdapter>>()
    private val urbanBoardsByPackage = mutableMapOf<String, List<StopScopedBoardProvider>>()
    private val timetablesLock = Any()
    @Volatile private var foreignStore: ForeignPackStore? = null
    @Volatile private var foreignOffers: ForeignPackOffers? = null
    private var timetables: EmbeddedTimetables? = null

    /**
     * The embedded packs as one generation of readers sees them: the railway packs (a foreign pack as its border subset,
     * or whole once its full pack is installed) and the opener that reads each from the APK or the installed file.
     */
    class EmbeddedTimetables internal constructor(val snapshot: ForeignPackSnapshot, val openPack: GtfsPackOpener) {
        val railPacks: List<EmbeddedGtfsPack> = snapshot.packs(EmbeddedGtfsPacks.all)
        val generation: Long get() = snapshot.generation
    }

    /**
     * The full foreign packs downloaded on request, with the pins of this version; created once per process, when its
     * startup cleaning also removes the files of a pin that changed with an update.
     */
    fun foreignPackStore(context: Context): ForeignPackStore {
        foreignStore?.let { return it }
        val app = context.applicationContext
        return synchronized(timetablesLock) {
            foreignStore ?: ForeignPackStore(
                File(app.noBackupFilesDir, FOREIGN_PACK_DIRECTORY),
                ForeignPackPins.load { path -> app.assets.open(path) }
            ).also { store ->
                if (store.cleanUp()) NextTrainTileStore(app).clearTimetable()
                foreignStore = store
            }
        }
    }

    /**
     * The offers, declines and the one download of the full foreign packs, shared by every screen of the process. A
     * download runs only after a tap, in this process, and stops when cancelled; an install or a delete bumps the
     * store's generation, so the readers are rebuilt and the Quick Settings tile reads the new files at its next update.
     */
    fun foreignPackOffers(context: Context): ForeignPackOffers {
        foreignOffers?.let { return it }
        val app = context.applicationContext
        val store = foreignPackStore(app)
        return synchronized(timetablesLock) {
            foreignOffers ?: run {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val downloader = ForeignPackDownloader(store)
                ForeignPackOffers(
                    pins = store.pins,
                    generation = store.generation,
                    installed = { sourceId -> embeddedTimetables(app).snapshot.isInstalled(sourceId) },
                    status = store::status,
                    download = { sourceId, onProgress -> downloader.download(sourceId, onProgress) },
                    remove = store::delete,
                    scope = scope,
                    today = { LocalDate.now(it.binario.app.data.RailTime.defaultZone) }
                ).also { offers ->
                    foreignOffers = offers
                    scope.launch { store.generation.drop(1).collect { NextTrainTileStore(app).clearTimetable() } }
                }
            }
        }
    }

    /**
     * The packs and opener of the current generation. An install or a delete starts a new one: the readers cached for
     * the previous generation are dropped ([timetablesChanged]) and built again on the next call.
     */
    fun embeddedTimetables(context: Context): EmbeddedTimetables {
        val app = context.applicationContext
        val store = foreignPackStore(app)
        var changed = false
        val current = synchronized(timetablesLock) {
            timetables?.takeIf { it.generation == store.generation.value } ?: run {
                changed = timetables != null
                val snapshot = store.snapshot()
                EmbeddedTimetables(
                    snapshot,
                    GtfsPackOpener({ path -> app.assets.open(path) }, snapshot, EmbeddedGtfsPacks.all, store::verifyOnce)
                ).also { timetables = it }
            }
        }
        // Outside the lock: a cache key carries its generation, so a reader of the old one is never handed out again.
        if (changed) clearTimetableReaders()
        return current
    }

    /** Drops the readers built on the embedded packs; the next call builds them on the current generation. */
    fun timetablesChanged() {
        synchronized(timetablesLock) { timetables = null }
        clearTimetableReaders()
    }

    private fun clearTimetableReaders() {
        synchronized(routeSourcesByPackage) { routeSourcesByPackage.clear() }
        synchronized(numberSearchByPackage) { numberSearchByPackage.clear() }
        synchronized(journeySearchByPackage) { journeySearchByPackage.clear() }
        synchronized(offlinePlannersByPackage) { offlinePlannersByPackage.clear() }
        synchronized(realtimeResolversByPackage) { realtimeResolversByPackage.clear() }
    }

    private fun key(context: Context, timetables: EmbeddedTimetables): String = "${context.packageName}#${timetables.generation}"

    fun routeDataSource(context: Context): TrainDataSource {
        val applicationContext = context.applicationContext
        val timetables = embeddedTimetables(applicationContext)
        return synchronized(routeSourcesByPackage) { routeSourcesByPackage.getOrPut(key(applicationContext, timetables)) {
            val openAsset = timetables.openPack
            // The catalogue is read only for the packs that publish station codes (Trenitalia, Italo).
            val embedded = EmbeddedGtfsRouteAdapter(
                openAsset = openAsset,
                packs = timetables.railPacks,
                stationCatalog = { withContext(Dispatchers.IO) { EmbeddedCuratedRailStationCatalog(openAsset).load() } }
            )
            val officialPlannedSource = RailDataRepository(
                authorisedSources = localOnlyPolicy.routeSources(),
                // Embedded official/open-data GTFS belongs to the primary tier,
                // not to the best-effort coverage tier.
                fallback = embedded
            )
            val plannedSource = QualityTieredTrainDataSource(
                primary = officialPlannedSource,
                coverageFallbacks = localOnlyPolicy.coverageRouteFallbacks()
            )
            LegacyTrainDataSourceFacade(
                legacySource = StationBoardEnrichedTrainDataSource(
                    plannedSource = GtfsRealtimeEnrichedTrainDataSource(
                        plannedSource, realtimeEnrichmentService(applicationContext)
                    ),
                    boardProviders = stationBoardProviders(),
                    liveRouteSources = localOnlyPolicy.liveRouteSources(embedded).filter { defaultRegistry.isEnabled(it.descriptor.id) }
                ),
                providerRegistry = defaultRegistry
            )
        } }
    }

    fun trainNumberSearchService(context: Context): TrainNumberSearchService {
        val app = context.applicationContext
        val timetables = embeddedTimetables(app)
        return synchronized(numberSearchByPackage) { numberSearchByPackage.getOrPut(key(app, timetables)) {
            val static = EmbeddedGtfsRouteAdapter(openAsset = timetables.openPack,
                packs = timetables.railPacks.filter { defaultRegistry.isEnabled(it.sourceId) })
            val source = EmbeddedTrainNumberSearchSource(static, GtfsRealtimeEnrichedTrainDataSource(static, realtimeEnrichmentService(app)))
            TrainNumberSearchService(
                localOnlyPolicy.trainNumberSearchProviders().filter { defaultRegistry.isEnabled(it.descriptor.id) } +
                    listOf(source.provider(true))
            )
        } }
    }

    /**
     * Journeys with changes, separate from [routeDataSource]: the direct-train sources keep their order, priority
     * and gating. The registry decides which journey providers take part and in which order; today only the offline
     * planner on the registry-enabled embedded packs, which sends nothing over the network.
     */
    fun journeySearchService(context: Context): JourneySearchService {
        val app = context.applicationContext
        val timetables = embeddedTimetables(app)
        return synchronized(journeySearchByPackage) { journeySearchByPackage.getOrPut(key(app, timetables)) {
            JourneySearchService(activeProviders(listOf<JourneyProvider>(offlineJourneyPlanner(app)), ProviderCapability.JOURNEY_SEARCH))
        } }
    }

    /**
     * The offline planner on the registry-enabled embedded packs (railway packs, then this build's city networks), shared
     * by the journey search and the static timetable questions of the screens ("Ultimo di oggi", wheelchair marks).
     */
    fun offlineJourneyPlanner(context: Context): EmbeddedGtfsJourneyProvider {
        val app = context.applicationContext
        val timetables = embeddedTimetables(app)
        return synchronized(offlinePlannersByPackage) { offlinePlannersByPackage.getOrPut(key(app, timetables)) {
            EmbeddedGtfsJourneyProvider.create(
                openAsset = timetables.openPack,
                packs = (timetables.railPacks + EmbeddedGtfsPacks.urban).filter { defaultRegistry.isEnabled(it.sourceId) }
            )
        } }
    }

    fun providerRegistry(): ProviderRegistry = defaultRegistry

    /** Metro, tram and funicular stops of this build's city packs, for the station search (read lazily). */
    fun urbanStopIndex(context: Context): EmbeddedUrbanStopIndex = synchronized(urbanStopIndexesByPackage) {
        val app = context.applicationContext
        urbanStopIndexesByPackage.getOrPut(app.packageName) {
            // City packs are never foreign: the opener reads them from the APK.
            EmbeddedUrbanStopIndex(
                openAsset = embeddedTimetables(app).openPack,
                packs = EmbeddedGtfsPacks.urban.filter { defaultRegistry.isEnabled(it.sourceId) }
            )
        }
    }

    /**
     * The open GTFS-Realtime feeds of this build's city packs, by city pack: one adapter (and one 30-second cache) per
     * feed, shared by the stop boards and the journey results. A feed is used only when its city pack is in the build
     * and both are registry-enabled; nothing is fetched until a board or a journey of that city asks.
     */
    fun urbanRealtimeFeeds(context: Context): Map<SourceId, UrbanGtfsRealtimeAdapter> = synchronized(urbanRealtimeByPackage) {
        val app = context.applicationContext
        urbanRealtimeByPackage.getOrPut(app.packageName) {
            val packs = EmbeddedGtfsPacks.urban.filter { defaultRegistry.isEnabled(it.sourceId) }
            UrbanRealtimeFeeds.forBuild(EmbeddedGtfsPacks.personalSourcesIncluded, packs)
                .filter { defaultRegistry.isEnabled(it.sourceId) }
                .associate { feed ->
                    val pack = packs.first { it.sourceId == feed.staticSourceId }
                    feed.staticSourceId to UrbanGtfsRealtimeAdapter(
                        feed = feed,
                        packRoutes = { withContext(Dispatchers.IO) { UrbanPackRoutes.read(embeddedTimetables(app).openPack(pack.assetPath)) } }
                    )
                }
        }
    }

    /** Boards of the metro, tram and funicular stops of this build's city packs, with their network's realtime feed. */
    fun urbanStopBoardProviders(context: Context): List<StopScopedBoardProvider> = synchronized(urbanBoardsByPackage) {
        val app = context.applicationContext
        urbanBoardsByPackage.getOrPut(app.packageName) {
            val packs = EmbeddedGtfsPacks.urban.filter { defaultRegistry.isEnabled(it.sourceId) }
            if (packs.isEmpty()) return@getOrPut emptyList()
            val adapter = EmbeddedGtfsRouteAdapter(openAsset = embeddedTimetables(app).openPack, packs = packs, indexCacheCapacity = 1)
            val realtime = urbanRealtimeFeeds(app)
            packs.map { pack ->
                UrbanStopBoardAdapter(
                    pack = pack,
                    adapter = adapter,
                    realtime = realtime[pack.sourceId],
                    networkName = RailSourceCatalog.byId(pack.sourceId)?.displayName ?: pack.sourceId.value
                )
            }
        }
    }

    fun stationBoardProviders(): List<StationBoardProvider> = activeProviders(
        localOnlyPolicy.stationBoardProviders(),
        ProviderCapability.STATION_BOARD
    )

    fun stationBoardDisplayProviders(): List<StationBoardProvider> =
        activeProviders(localOnlyPolicy.stationBoardDisplayProviders(), ProviderCapability.STATION_BOARD)

    fun stationBoardRunDetailService(): StationBoardRunDetailService = StationBoardRunDetailService(
        activeProviders(localOnlyPolicy.stationBoardRunDetailProviders(), ProviderCapability.TRAIN_RUN_REALTIME)
    )

    /** Notices for the train detail and the notices area, from the registry-enabled public sources only. */
    fun railNoticesService(): it.binario.app.data.notices.RailNoticesService = it.binario.app.data.notices.RailNoticesService(
        trainNoticeProviders = activeProviders(localOnlyPolicy.trainNoticeProviders(), ProviderCapability.SERVICE_ALERTS),
        networkAlertProviders = activeProviders(localOnlyPolicy.networkNoticeProviders(), ProviderCapability.SERVICE_ALERTS),
        trenord = activeProviders(localOnlyPolicy.trenordLineNoticeProviders(), ProviderCapability.SERVICE_ALERTS).firstOrNull()
    )

    /**
     * Per-train notices and Trenitalia infomobility only (no Trenord line notices), for the notifications of the
     * followed and favourite trains; the same registry gating as the train detail.
     */
    fun trainAlertNoticesService(): it.binario.app.data.notices.RailNoticesService = it.binario.app.data.notices.RailNoticesService(
        trainNoticeProviders = activeProviders(localOnlyPolicy.trainNoticeProviders(), ProviderCapability.SERVICE_ALERTS),
        networkAlertProviders = activeProviders(localOnlyPolicy.networkNoticeProviders(), ProviderCapability.SERVICE_ALERTS).filter { provider ->
            it.binario.app.data.notices.RailNoticesService.publisherOf(provider.descriptor.id) == it.binario.app.data.notices.NoticePublisher.TRENITALIA
        },
        trenord = null
    )

    /**
     * RFI's infomobility feed alone (no Trenitalia list, no Trenord lines), for the evening alert of tomorrow's works on
     * the saved routes: the same adapter, and so the same cache, as the notices area.
     */
    fun routeWorksAlertNoticesService(): it.binario.app.data.notices.RailNoticesService = it.binario.app.data.notices.RailNoticesService(
        trainNoticeProviders = emptyList(),
        networkAlertProviders = activeProviders(localOnlyPolicy.networkNoticeProviders(), ProviderCapability.SERVICE_ALERTS).filter { provider ->
            it.binario.app.data.notices.RailNoticesService.publisherOf(provider.descriptor.id) == it.binario.app.data.notices.NoticePublisher.RFI
        },
        trenord = null
    )

    /** The MIT strike calendar (RSS), when the registry enables it. */
    fun strikeCalendar(): it.binario.app.data.live.MitStrikeRssAdapter? =
        activeProviders(listOf(localOnlyPolicy.strikeCalendarProvider()), ProviderCapability.SERVICE_ALERTS).firstOrNull()

    private val bikeSharing: BikeSharingService by lazy {
        BikeSharingService(
            systems = BikeSharingSystems.forBuild(EmbeddedGtfsPacks.personalSourcesIncluded).filter { defaultRegistry.isEnabled(it.id) },
            // A citywide vehicle list (Bird Rome, about 4,300 scooters) is near 1 MB of JSON.
            fetcher = HttpPublicSourceFetcher(PublicSourceRequestPolicy(maxBytes = 4 * 1024 * 1024, readTimeoutMillis = 15_000, totalTimeoutMillis = 20_000))
        )
    }

    /** Shared bikes and scooters near a station (GBFS), from the registry-enabled systems of this build. */
    fun bikeSharingService(): BikeSharingService = bikeSharing

    fun weatherForecastProvider(): it.binario.app.data.weather.OpenMeteoForecastAdapter? =
        activeProviders(listOf(localOnlyPolicy.weatherForecastProvider()), ProviderCapability.WEATHER_FORECAST).firstOrNull()

    fun tripUpdateProviders(): List<TripUpdateProvider> =
        activeProviders(localOnlyPolicy.tripUpdateProviders(), ProviderCapability.TRAIN_RUN_REALTIME)

    fun realtimeScheduleResolver(context: Context): EmbeddedGtfsRealtimeScheduleResolver {
        val applicationContext = context.applicationContext
        val timetables = embeddedTimetables(applicationContext)
        return synchronized(realtimeResolversByPackage) { realtimeResolversByPackage.getOrPut(key(applicationContext, timetables)) {
            EmbeddedGtfsRealtimeScheduleResolver(openAsset = timetables.openPack, packs = timetables.railPacks)
        } }
    }

    fun realtimeEnrichmentService(context: Context): RealtimeEnrichmentService =
        RealtimeEnrichmentService(tripUpdateProviders(), realtimeScheduleResolver(context))

    /** `noBackupFilesDir/gtfs-full/`: the system never backs it up. */
    private const val FOREIGN_PACK_DIRECTORY = "gtfs-full"

    private fun <T : RailProvider> activeProviders(
        candidates: List<T>,
        capability: ProviderCapability
    ): List<T> {
        val priorityById = defaultRegistry.activeFor(capability)
            .mapIndexed { index, policy -> policy.descriptor.id to index }
            .toMap()
        return candidates
            .filter { it.descriptor.id in priorityById }
            .sortedBy { priorityById.getValue(it.descriptor.id) }
    }
}

/**
 * Ponte temporaneo verso la UI v0.x. Le future query canoniche vivranno nel
 * repository multi-provider; fino alla migrazione delle schermate questa
 * facade conserva il contratto `TrainDataSource` senza introdurre dati dimostrativi.
 */
class LegacyTrainDataSourceFacade(
    private val legacySource: TrainDataSource,
    val providerRegistry: ProviderRegistry,
    private val clock: () -> Long = System::currentTimeMillis
) : TrainDataSource {
    override suspend fun loadRoute(
        route: it.binario.app.data.SavedRoute,
        direction: it.binario.app.data.Direction
    ): it.binario.app.data.DataResult = assessed(legacySource.loadRoute(route, direction))

    override suspend fun loadRoute(
        route: it.binario.app.data.SavedRoute,
        direction: it.binario.app.data.Direction,
        query: it.binario.app.data.JourneyQuery
    ): it.binario.app.data.DataResult = assessed(legacySource.loadRoute(route, direction, query))

    override fun observeRoute(
        route: it.binario.app.data.SavedRoute,
        direction: it.binario.app.data.Direction,
        query: it.binario.app.data.JourneyQuery
    ): kotlinx.coroutines.flow.Flow<it.binario.app.data.DataResult> =
        kotlinx.coroutines.flow.flow { legacySource.observeRoute(route, direction, query).collect { emit(assessed(it)) } }

    private fun assessed(result: it.binario.app.data.DataResult): it.binario.app.data.DataResult {
        if (result !is it.binario.app.data.DataResult.Success) return result
        val original = result.snapshot
        val ids = original.services.flatMap { it.operationalSourceIds }.toSet()
        val confidence = if (!original.coverageFallback &&
            original.confidence == it.binario.app.data.DataConfidence.STATIC_ONLY &&
            original.services.any { it.operationalStatusVerified } && ids.isNotEmpty()) {
            when {
                ids.any { it in setOf("viaggiatreno", "italo-status") } -> it.binario.app.data.DataConfidence.OPERATOR_PRIMARY
                "rfi-station-board" in ids -> it.binario.app.data.DataConfidence.INFRASTRUCTURE_PRIMARY
                else -> original.confidence
            }
        } else original.confidence
        val snapshot = original.copy(confidence = confidence)
        val quality = it.binario.app.data.SnapshotQualityAssessor.assess(snapshot, clock())
        return it.binario.app.data.DataResult.Success(snapshot.copy(
            qualityScore = quality.score,
            qualityBand = quality.band,
            qualityWarnings = (snapshot.qualityWarnings - it.binario.app.data.SnapshotWarning.STATIC_ONLY) + quality.warnings
        ))
    }
}
