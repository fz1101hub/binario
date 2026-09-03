package it.binario.app.data

import it.binario.app.data.provider.AlertProvider
import it.binario.app.data.provider.TripUpdateProvider
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.VehiclePositionProvider
import it.binario.app.BuildConfig
import it.binario.app.data.live.ItaloPublicStationAdapter
import it.binario.app.data.live.RfiInfomobilityRssAdapter
import it.binario.app.data.live.RfiStationBoardAdapter
import it.binario.app.data.live.SncfGtfsRealtimeAdapter
import it.binario.app.data.live.StaRealtimeAdapter
import it.binario.app.data.live.ViaggiaTrenoStationAdapter

/**
 * Production policy for the local-first build. Network adapters remain outside
 * the execution path until a documented provider is expressly authorised.
 */
object LocalOnlyRailDataPolicy {
    /** Journey planning is always sourced separately from station-board realtime. */
    fun routeSources(): List<TrainDataSource> = emptyList()

    fun stationBoardProviders(): List<StationBoardProvider> =
        if (BuildConfig.PERSONAL_REALTIME_SOURCES) {
            listOf(
                RfiStationBoardAdapter(),
                ItaloPublicStationAdapter(),
                ViaggiaTrenoStationAdapter()
            )
        } else {
            emptyList()
        }
    fun alertProviders(): List<AlertProvider> = listOf(
        RfiInfomobilityRssAdapter(),
        StaRealtimeAdapter(),
        SncfGtfsRealtimeAdapter()
    )

    fun vehiclePositionProviders(): List<VehiclePositionProvider> = listOf(
        StaRealtimeAdapter()
    )

    fun tripUpdateProviders(): List<TripUpdateProvider> = listOf(
        StaRealtimeAdapter(),
        SncfGtfsRealtimeAdapter()
    )
}
