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
import it.binario.app.data.planned.RfiProgrammedTimetableAdapter

/**
 * Runtime source policy.
 * Planned journey sources are kept separate from operational station boards.
 */
object LocalOnlyRailDataPolicy {
    /** Official programmed sources may plan a journey; live boards never do. */
    fun routeSources(): List<TrainDataSource> = listOf(
        RfiProgrammedTimetableAdapter()
    )

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
