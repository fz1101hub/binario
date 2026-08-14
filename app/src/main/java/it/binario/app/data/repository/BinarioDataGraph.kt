package it.binario.app.data.repository

import it.binario.app.data.DemoTrainDataSource
import it.binario.app.data.RailDataRepository
import it.binario.app.data.TrainDataSource
import it.binario.app.data.live.RfiStationBoardAdapter
import it.binario.app.data.live.ViaggiaTrenoStationAdapter
import it.binario.app.data.provider.KnownProviders
import it.binario.app.data.provider.ProviderRegistry

/**
 * Punto di composizione unico del livello dati. Nessuna UI, worker o companion
 * deve istanziare direttamente repository o provider: questa classe rende la
 * configurazione coerente e sostituibile nei test.
 */
object BinarioDataGraph {
    private val defaultRegistry: ProviderRegistry by lazy { KnownProviders.defaultRegistry() }
    private val defaultRouteDataSource: TrainDataSource by lazy {
        LegacyTrainDataSourceFacade(
            legacySource = RailDataRepository(
                authorisedSources = listOf(
                    ViaggiaTrenoStationAdapter(),
                    RfiStationBoardAdapter()
                ),
                fallback = DemoTrainDataSource()
            ),
            providerRegistry = defaultRegistry
        )
    }

    fun routeDataSource(): TrainDataSource = defaultRouteDataSource

    fun providerRegistry(): ProviderRegistry = defaultRegistry
}

/**
 * Ponte temporaneo verso la UI v0.x. Le future query canoniche vivranno nel
 * repository multi-provider; fino alla migrazione delle schermate questa
 * facade conserva il contratto `TrainDataSource` e l'etichettatura demo.
 */
class LegacyTrainDataSourceFacade(
    private val legacySource: TrainDataSource,
    val providerRegistry: ProviderRegistry
) : TrainDataSource {
    override suspend fun loadRoute(
        route: it.binario.app.data.SavedRoute,
        direction: it.binario.app.data.Direction
    ): it.binario.app.data.DataResult = legacySource.loadRoute(route, direction)
}
