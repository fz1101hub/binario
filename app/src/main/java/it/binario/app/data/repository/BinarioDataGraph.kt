package it.binario.app.data.repository

import android.content.Context
import it.binario.app.data.LocalOnlyRailDataPolicy
import it.binario.app.data.RailDataRepository
import it.binario.app.data.TrainDataSource
import it.binario.app.data.gtfs.EmbeddedGtfsRealtimeScheduleResolver
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.gtfs.EmbeddedGtfsStationSearchIndex
import it.binario.app.data.provider.AlertProvider
import it.binario.app.data.provider.AuthorizedExternalProviderFactory
import it.binario.app.data.provider.AuthorizedExternalProviders
import it.binario.app.data.provider.ExternalRailCredentials
import it.binario.app.data.provider.KnownProviders
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.RailProvider
import it.binario.app.data.provider.ProviderRegistry
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.TripUpdateProvider
import it.binario.app.data.provider.VehiclePositionProvider

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
    private val stationIndexesByPackage = mutableMapOf<String, EmbeddedGtfsStationSearchIndex>()

    fun routeDataSource(context: Context): TrainDataSource = synchronized(routeSourcesByPackage) {
        val applicationContext = context.applicationContext
        routeSourcesByPackage.getOrPut(applicationContext.packageName) {
            val officialPlannedSource = RailDataRepository(
                authorisedSources = localOnlyPolicy.routeSources(),
                // Embedded official/open-data GTFS belongs to the primary tier,
                // not to the best-effort coverage tier.
                fallback = EmbeddedGtfsRouteAdapter(openAsset = { assetPath -> applicationContext.assets.open(assetPath) })
            )
            val plannedSource = QualityTieredTrainDataSource(
                primary = officialPlannedSource,
                coverageFallbacks = localOnlyPolicy.coverageRouteFallbacks()
            )
            LegacyTrainDataSourceFacade(
                legacySource = StationBoardEnrichedTrainDataSource(
                    plannedSource = GtfsRealtimeRouteDataSource(
                        plannedSource = plannedSource,
                        providersByStaticFeed = tripUpdateProviders().mapNotNull { provider ->
                            val feed = KnownProviders.staticFeedForRealtime(provider.descriptor.id)
                            feed?.let { it to provider }
                        }.toMap(),
                        resolve = realtimeScheduleResolver(applicationContext)::resolve
                    ),
                    boardProviders = stationBoardProviders()
                ),
                providerRegistry = defaultRegistry
            )
        }
    }

    fun providerRegistry(): ProviderRegistry = defaultRegistry

    fun stationSearchIndex(context: Context): EmbeddedGtfsStationSearchIndex = synchronized(stationIndexesByPackage) {
        val applicationContext = context.applicationContext
        stationIndexesByPackage.getOrPut(applicationContext.packageName) {
            val activeCatalogSources = defaultRegistry.activeFor(ProviderCapability.STATION_CATALOG)
                .mapTo(linkedSetOf()) { it.descriptor.id }
            EmbeddedGtfsStationSearchIndex(
                openAsset = { assetPath -> applicationContext.assets.open(assetPath) },
                activeSourceIds = activeCatalogSources
            )
        }
    }

    fun stationBoardProviders(): List<StationBoardProvider> = activeProviders(
        localOnlyPolicy.stationBoardProviders(),
        ProviderCapability.STATION_BOARD
    )

    fun stationBoardDisplayProviders(): List<StationBoardProvider> =
        activeProviders(localOnlyPolicy.stationBoardDisplayProviders(), ProviderCapability.STATION_BOARD)

    fun alertProviders(): List<AlertProvider> =
        activeProviders(localOnlyPolicy.alertProviders(), ProviderCapability.SERVICE_ALERTS)

    fun vehiclePositionProviders(): List<VehiclePositionProvider> =
        activeProviders(localOnlyPolicy.vehiclePositionProviders(), ProviderCapability.VEHICLE_POSITIONS)

    fun tripUpdateProviders(): List<TripUpdateProvider> =
        activeProviders(localOnlyPolicy.tripUpdateProviders(), ProviderCapability.TRAIN_RUN_REALTIME)

    /**
     * Costruisce provider con credenziali soltanto per la durata del riferimento
     * restituito. Il graph non li cachea e non conserva le credenziali.
     */
    fun authorizedExternalProviders(credentials: ExternalRailCredentials): AuthorizedExternalProviders =
        AuthorizedExternalProviderFactory.create(credentials)

    fun stationBoardProviders(credentials: ExternalRailCredentials): List<StationBoardProvider> =
        stationBoardProviders() + authorizedExternalProviders(credentials).stationBoards

    fun stationBoardDisplayProviders(credentials: ExternalRailCredentials): List<StationBoardProvider> =
        stationBoardDisplayProviders() + authorizedExternalProviders(credentials).stationBoards

    fun alertProviders(credentials: ExternalRailCredentials): List<AlertProvider> =
        alertProviders() + authorizedExternalProviders(credentials).alerts

    fun tripUpdateProviders(credentials: ExternalRailCredentials): List<TripUpdateProvider> =
        tripUpdateProviders() + authorizedExternalProviders(credentials).tripUpdates

    fun realtimeScheduleResolver(context: Context): EmbeddedGtfsRealtimeScheduleResolver = synchronized(realtimeResolversByPackage) {
        val applicationContext = context.applicationContext
        realtimeResolversByPackage.getOrPut(applicationContext.packageName) {
            EmbeddedGtfsRealtimeScheduleResolver(openAsset = { assetPath: String -> applicationContext.assets.open(assetPath) })
        }
    }

    fun realtimeEnrichmentService(context: Context): RealtimeEnrichmentService =
        RealtimeEnrichmentService(tripUpdateProviders(), realtimeScheduleResolver(context))

    /**
     * Versione autorizzata: Swiss GTFS-RT viene riconciliato contro il GTFS
     * svizzero embedded; nessun token viene copiato nel resolver o persistito.
     */
    fun realtimeEnrichmentService(
        context: Context,
        credentials: ExternalRailCredentials
    ): RealtimeEnrichmentService = RealtimeEnrichmentService(
        tripUpdateProviders(credentials),
        realtimeScheduleResolver(context)
    )

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
    val providerRegistry: ProviderRegistry
) : TrainDataSource {
    override suspend fun loadRoute(
        route: it.binario.app.data.SavedRoute,
        direction: it.binario.app.data.Direction
    ): it.binario.app.data.DataResult = legacySource.loadRoute(route, direction)

    override suspend fun loadRoute(
        route: it.binario.app.data.SavedRoute,
        direction: it.binario.app.data.Direction,
        query: it.binario.app.data.JourneyQuery
    ): it.binario.app.data.DataResult = legacySource.loadRoute(route, direction, query)
}
