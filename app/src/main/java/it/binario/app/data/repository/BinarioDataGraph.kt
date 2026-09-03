package it.binario.app.data.repository

import android.content.Context
import it.binario.app.data.LocalOnlyRailDataPolicy
import it.binario.app.data.RailDataRepository
import it.binario.app.data.TrainDataSource
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.gtfs.EmbeddedGtfsRealtimeScheduleResolver
import it.binario.app.data.gtfs.EmbeddedGtfsStationSearchIndex
import it.binario.app.data.provider.AlertProvider
import it.binario.app.data.provider.KnownProviders
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderRegistry
import it.binario.app.data.provider.VehiclePositionProvider
import it.binario.app.data.provider.TripUpdateProvider

/**
 * Punto di composizione unico del livello dati. Nessuna UI, worker o companion
 * deve istanziare direttamente repository o provider: questa classe rende la
 * configurazione coerente e sostituibile nei test.
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
            LegacyTrainDataSourceFacade(
                legacySource = RailDataRepository(
                    authorisedSources = localOnlyPolicy.routeSources(),
                    fallback = EmbeddedGtfsRouteAdapter(openAsset = { assetPath -> applicationContext.assets.open(assetPath) })
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

    fun alertProviders(): List<AlertProvider> = localOnlyPolicy.alertProviders()

    fun vehiclePositionProviders(): List<VehiclePositionProvider> = localOnlyPolicy.vehiclePositionProviders()

    fun tripUpdateProviders(): List<TripUpdateProvider> = localOnlyPolicy.tripUpdateProviders()

    fun realtimeScheduleResolver(context: Context): EmbeddedGtfsRealtimeScheduleResolver = synchronized(realtimeResolversByPackage) {
        val applicationContext = context.applicationContext
        realtimeResolversByPackage.getOrPut(applicationContext.packageName) {
            EmbeddedGtfsRealtimeScheduleResolver(openAsset = { assetPath: String -> applicationContext.assets.open(assetPath) })
        }
    }

    fun realtimeEnrichmentService(context: Context): RealtimeEnrichmentService =
        RealtimeEnrichmentService(tripUpdateProviders(), realtimeScheduleResolver(context))
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
