package it.binario.app.data

import it.binario.app.BuildConfig
import it.binario.app.data.live.RfiInfomobilityRssAdapter
import it.binario.app.data.live.SncfGtfsRealtimeAdapter
import it.binario.app.data.live.StaRealtimeAdapter
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
        } else {
            assertTrue(boardProviderNames.isEmpty())
            assertTrue(LocalOnlyRailDataPolicy.liveRouteSources().isEmpty())
        }

        val displayBoards = LocalOnlyRailDataPolicy.stationBoardDisplayProviders()
            .mapNotNull { it::class.simpleName }
        assertEquals("RfiProgrammedStationBoardAdapter", displayBoards.first())
        assertEquals(1 + boardProviderNames.size, displayBoards.size)

        val alerts = LocalOnlyRailDataPolicy.alertProviders()
        assertEquals(
            setOf(
                RfiInfomobilityRssAdapter.SOURCE_ID,
                StaRealtimeAdapter.SOURCE_ID,
                SncfGtfsRealtimeAdapter.SOURCE_ID
            ),
            alerts.map { it.descriptor.id }.toSet()
        )

        assertEquals(
            setOf(StaRealtimeAdapter.SOURCE_ID),
            LocalOnlyRailDataPolicy.vehiclePositionProviders().map { it.descriptor.id }.toSet()
        )

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

        val staAlert = LocalOnlyRailDataPolicy.alertProviders().single { it.descriptor.id == StaRealtimeAdapter.SOURCE_ID }
        val staTripUpdate = LocalOnlyRailDataPolicy.tripUpdateProviders().single { it.descriptor.id == StaRealtimeAdapter.SOURCE_ID }
        val staVehicle = LocalOnlyRailDataPolicy.vehiclePositionProviders().single()
        assertSame(staAlert, staTripUpdate)
        assertSame(staAlert, staVehicle)
    }

    @Test
    fun `liste runtime seguono provider attivi e priorita del registry`() {
        val registryOrder = BinarioDataGraph.providerRegistry()
            .activeFor(ProviderCapability.SERVICE_ALERTS)
            .map { it.descriptor.id }
        val runtimeIds = BinarioDataGraph.alertProviders().map { it.descriptor.id }

        assertEquals(registryOrder.filter { it in runtimeIds }, runtimeIds)
    }
}
