package it.binario.app.data

import it.binario.app.data.provider.AlertProvider
import it.binario.app.data.provider.TripUpdateProvider
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.VehiclePositionProvider
import it.binario.app.BuildConfig
import it.binario.app.data.gtfs.RemoteGtfsFallbackDataSource
import it.binario.app.data.live.EuropeanSleeperGtfsRealtimeAdapter
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
    /**
     * Ordering matters: RFI programmed is primary. The remote European Sleeper
     * GTFS is a coverage fallback and is only reached when the primary source
     * cannot satisfy the route/date query.
     */
    fun routeSources(): List<TrainDataSource> = listOf(
        RfiProgrammedTimetableAdapter(),
        RemoteGtfsFallbackDataSource.europeanSleeper()
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
        SncfGtfsRealtimeAdapter(),
        // Low-priority coverage fallback. Before 2026-09-09 the provider returns no updates.
        EuropeanSleeperGtfsRealtimeAdapter()
    )
}
