package it.binario.app.data

import it.binario.app.BuildConfig
import it.binario.app.data.gtfs.RemoteGtfsFallbackDataSource
import it.binario.app.data.live.ItaloPublicStationAdapter
import it.binario.app.data.live.RfiInfomobilityRssAdapter
import it.binario.app.data.live.RfiStationBoardAdapter
import it.binario.app.data.live.RfiLiveRouteDataSource
import it.binario.app.data.live.SncfGtfsRealtimeAdapter
import it.binario.app.data.live.StaRealtimeAdapter
import it.binario.app.data.live.ViaggiaTrenoRealtimeAdapter
import it.binario.app.data.planned.RfiProgrammedStationBoardAdapter
import it.binario.app.data.planned.RfiProgrammedTimetableAdapter
import it.binario.app.data.planned.RfiSupplementalHubTimetableAdapter
import it.binario.app.data.provider.AlertProvider
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.TripUpdateProvider
import it.binario.app.data.provider.VehiclePositionProvider

/**
 * Runtime source policy.
 *
 * Current-day operator observations precede static timetables when personal
 * realtime is enabled. Community coverage fallbacks remain below primary
 * operator/infrastructure sources and official static data.
 */
object LocalOnlyRailDataPolicy {
    private val rfiProgrammed by lazy { RfiProgrammedTimetableAdapter() }
    private val rfiSupplemental by lazy { RfiSupplementalHubTimetableAdapter() }
    private val europeanSleeperFallback by lazy { RemoteGtfsFallbackDataSource.europeanSleeper() }
    private val rfiProgrammedBoard by lazy { RfiProgrammedStationBoardAdapter() }
    private val rfiStationBoard by lazy { RfiStationBoardAdapter() }
    private val rfiLiveRoutes by lazy { RfiLiveRouteDataSource(rfiStationBoard) }
    private val italoStationBoard by lazy { ItaloPublicStationAdapter() }
    private val viaggiaTrenoBoard by lazy { ViaggiaTrenoRealtimeAdapter() }
    private val rfiAlerts by lazy { RfiInfomobilityRssAdapter() }
    private val staRealtime by lazy { StaRealtimeAdapter() }
    private val sncfRealtime by lazy { SncfGtfsRealtimeAdapter() }

    /** Official programmed sources only. */
    fun routeSources(): List<TrainDataSource> = listOf(
        rfiProgrammed,
        rfiSupplemental
    )

    /** Current-day operator data is queried before waiting for programmed timetables. */
    fun liveRouteSources(): List<RealtimeRouteDataSource> =
        if (BuildConfig.PERSONAL_REALTIME_SOURCES) listOf(rfiLiveRoutes, viaggiaTrenoBoard) else emptyList()

    /** Last-resort route coverage. These sources must never outrank official GTFS/RFI data. */
    fun coverageRouteFallbacks(): List<TrainDataSource> = listOf(
        // European Sleeper owns its realtime reconciliation internally and only
        // after this exact fallback static feed produced the requested route.
        europeanSleeperFallback
    )

    /** Realtime/operational boards used to enrich planned route snapshots. */
    fun stationBoardProviders(): List<StationBoardProvider> =
        if (BuildConfig.PERSONAL_REALTIME_SOURCES) {
            listOf(
                rfiStationBoard,
                italoStationBoard,
                viaggiaTrenoBoard
            )
        } else {
            emptyList()
        }

    /**
     * User-facing station board: always starts from the official RFI programmed
     * departure board and overlays explicitly enabled operational providers.
     */
    fun stationBoardDisplayProviders(): List<StationBoardProvider> =
        listOf(rfiProgrammedBoard) + stationBoardProviders()

    fun alertProviders(): List<AlertProvider> = listOf(
        rfiAlerts,
        staRealtime,
        sncfRealtime
    )

    fun vehiclePositionProviders(): List<VehiclePositionProvider> = listOf(
        staRealtime
    )

    /**
     * Only realtime feeds that can currently be reconciled against a trusted
     * static schedule are queried globally.
     */
    fun tripUpdateProviders(): List<TripUpdateProvider> = listOf(
        staRealtime,
        sncfRealtime
    )

    /**
     * No coverage realtime provider is global. Each fallback must reconcile its
     * realtime against the exact static bytes that produced the route.
     */
    fun coverageTripUpdateProviders(): List<TripUpdateProvider> = emptyList()
}
