package it.binario.app.data

import it.binario.app.BuildConfig
import it.binario.app.data.gtfs.RemoteGtfsFallbackDataSource
import it.binario.app.data.live.ItaloPublicStationAdapter
import it.binario.app.data.live.RfiInfomobilityRssAdapter
import it.binario.app.data.live.RfiStationBoardAdapter
import it.binario.app.data.live.SncfGtfsRealtimeAdapter
import it.binario.app.data.live.StaRealtimeAdapter
import it.binario.app.data.live.ViaggiaTrenoStationAdapter
import it.binario.app.data.planned.RfiProgrammedTimetableAdapter
import it.binario.app.data.planned.RfiSupplementalHubTimetableAdapter
import it.binario.app.data.provider.AlertProvider
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.TripUpdateProvider
import it.binario.app.data.provider.VehiclePositionProvider

/**
 * Runtime source policy.
 *
 * Quality rule: official/primary sources and coverage fallbacks live in
 * different tiers. A best-effort source is never queried before all official
 * alternatives have failed for the requested route/context.
 */
object LocalOnlyRailDataPolicy {
    /** Official programmed sources only. */
    fun routeSources(): List<TrainDataSource> = listOf(
        RfiProgrammedTimetableAdapter(),
        RfiSupplementalHubTimetableAdapter()
    )

    /** Last-resort route coverage. These sources must never outrank official GTFS/RFI data. */
    fun coverageRouteFallbacks(): List<TrainDataSource> = listOf(
        // European Sleeper owns its realtime reconciliation internally and only
        // after this exact fallback static feed produced the requested route.
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

    /**
     * Only realtime feeds that can currently be reconciled against a trusted
     * static schedule are queried globally.
     */
    fun tripUpdateProviders(): List<TripUpdateProvider> = listOf(
        StaRealtimeAdapter(),
        SncfGtfsRealtimeAdapter()
    )

    /**
     * No coverage realtime provider is global. Each fallback must reconcile its
     * realtime against the exact static bytes that produced the route.
     */
    fun coverageTripUpdateProviders(): List<TripUpdateProvider> = emptyList()
}
