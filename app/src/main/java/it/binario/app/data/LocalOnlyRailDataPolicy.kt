package it.binario.app.data

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
import it.binario.app.data.provider.AlertProvider
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.TripUpdateProvider
import it.binario.app.data.provider.VehiclePositionProvider

/**
 * Runtime source policy.
 *
 * Quality rule: official/primary planning sources and coverage fallbacks live in
 * different tiers. A best-effort source is never consulted before all official
 * local/programmed alternatives have failed for the requested route.
 */
object LocalOnlyRailDataPolicy {
    /** Official programmed sources only. */
    fun routeSources(): List<TrainDataSource> = listOf(
        RfiProgrammedTimetableAdapter()
    )

    /** Last-resort route coverage. These sources must never outrank official GTFS/RFI data. */
    fun coverageRouteFallbacks(): List<TrainDataSource> = listOf(
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
