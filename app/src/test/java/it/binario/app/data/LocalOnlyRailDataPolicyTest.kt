package it.binario.app.data

import it.binario.app.BuildConfig
import it.binario.app.data.live.ItaloPublicStationAdapter
import it.binario.app.data.live.RfiInfomobilityRssAdapter
import it.binario.app.data.live.SncfGtfsRealtimeAdapter
import it.binario.app.data.live.StaRealtimeAdapter
import it.binario.app.data.live.TrenitaliaInfomobilityNewsAdapter
import it.binario.app.data.live.TrenordLineNoticesAdapter
import it.binario.app.data.provider.KnownProviderIds
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.repository.BinarioDataGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOnlyRailDataPolicyTest {
    @Test
    fun `policy separates primary planning coverage fallback and realtime boards`() {
        assertEquals(
            listOf("RfiProgrammedTimetableAdapter", "RfiSupplementalHubTimetableAdapter"),
            LocalOnlyRailDataPolicy.routeSources().mapNotNull { it::class.simpleName }
        )
        assertEquals(
            listOf("RemoteGtfsFallbackDataSource"),
            LocalOnlyRailDataPolicy.coverageRouteFallbacks().mapNotNull { it::class.simpleName }
        )

        val boardProviderNames = LocalOnlyRailDataPolicy.stationBoardProviders().map { it::class.simpleName }.toSet()
        if (BuildConfig.PERSONAL_REALTIME_SOURCES) {
            assertEquals(setOf("RfiStationBoardAdapter", "ItaloPublicStationAdapter", "ViaggiaTrenoRealtimeAdapter",
                "SwissOpenTransportStationBoardAdapter"), boardProviderNames)
            assertSame(LocalOnlyRailDataPolicy.liveRouteSources().single { it.descriptor.id.value == "viaggiatreno" }, LocalOnlyRailDataPolicy.stationBoardProviders().single { it.descriptor.id.value == "viaggiatreno" })
            assertEquals("viaggiatreno", LocalOnlyRailDataPolicy.liveRouteSources().first().descriptor.id.value)
            // The Italo board lists Italo trains only: the aggregate service knows it by this id.
            assertEquals(KnownProviderIds.ITALO_STATUS,
                LocalOnlyRailDataPolicy.stationBoardProviders().single { it is ItaloPublicStationAdapter }.descriptor.id)
        } else {
            assertTrue(boardProviderNames.isEmpty())
            assertTrue(LocalOnlyRailDataPolicy.liveRouteSources().isEmpty())
        }

        // Live first: the live boards in their order, then the RFI programmed board, which the aggregate service
        // recognises by its source id as the fallback.
        val display = LocalOnlyRailDataPolicy.stationBoardDisplayProviders()
        val displayBoards = display.mapNotNull { it::class.simpleName }
        assertEquals("RfiProgrammedStationBoardAdapter", displayBoards.last())
        assertEquals(KnownProviderIds.RFI_PROGRAMMED, display.last().descriptor.id)
        assertEquals(LocalOnlyRailDataPolicy.stationBoardProviders().map { it::class.simpleName }, displayBoards.dropLast(1))
        assertEquals(1 + boardProviderNames.size, displayBoards.size)

        val personalAlerts = if (BuildConfig.PERSONAL_REALTIME_SOURCES) setOf(TrenitaliaInfomobilityNewsAdapter.SOURCE_ID) else emptySet()
        val trenordAlerts = if (BuildConfig.PERSONAL_REALTIME_SOURCES) setOf(TrenordLineNoticesAdapter.SOURCE_ID) else emptySet()
        assertEquals(trenordAlerts, LocalOnlyRailDataPolicy.trenordLineNoticeProviders().map { it.descriptor.id }.toSet())
        assertEquals(setOf(RfiInfomobilityRssAdapter.SOURCE_ID) + personalAlerts,
            LocalOnlyRailDataPolicy.networkNoticeProviders().map { it.descriptor.id }.toSet())

        assertEquals(
            setOf(StaRealtimeAdapter.SOURCE_ID, SncfGtfsRealtimeAdapter.SOURCE_ID),
            LocalOnlyRailDataPolicy.tripUpdateProviders().map { it.descriptor.id }.toSet()
        )
        // Coverage realtime is owned by the route fallback that produced the
        // same static GTFS route; it must never be exposed as a global provider.
        assertTrue(LocalOnlyRailDataPolicy.coverageTripUpdateProviders().isEmpty())
    }

    @Test
    fun `provider con cache sono riusati tra capability e chiamate`() {
        val firstRoutes = LocalOnlyRailDataPolicy.routeSources()
        val secondRoutes = LocalOnlyRailDataPolicy.routeSources()
        assertSame(firstRoutes[0], secondRoutes[0])
        assertSame(firstRoutes[1], secondRoutes[1])

        val staTripUpdate = LocalOnlyRailDataPolicy.tripUpdateProviders().single { it.descriptor.id == StaRealtimeAdapter.SOURCE_ID }
        assertSame(staTripUpdate, LocalOnlyRailDataPolicy.tripUpdateProviders().single { it.descriptor.id == StaRealtimeAdapter.SOURCE_ID })
        val rfiNotices = LocalOnlyRailDataPolicy.networkNoticeProviders().single { it.descriptor.id == RfiInfomobilityRssAdapter.SOURCE_ID }
        assertSame(rfiNotices, LocalOnlyRailDataPolicy.networkNoticeProviders().single { it.descriptor.id == RfiInfomobilityRssAdapter.SOURCE_ID })
    }

    @Test
    fun `liste runtime seguono provider attivi e priorita del registry`() {
        val registryOrder = BinarioDataGraph.providerRegistry()
            .activeFor(ProviderCapability.TRAIN_RUN_REALTIME)
            .map { it.descriptor.id }
        val runtimeIds = BinarioDataGraph.tripUpdateProviders().map { it.descriptor.id }

        assertEquals(registryOrder.filter { it in runtimeIds }, runtimeIds)
    }
}
