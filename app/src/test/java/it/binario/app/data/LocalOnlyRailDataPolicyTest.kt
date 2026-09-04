package it.binario.app.data

import it.binario.app.BuildConfig
import it.binario.app.data.live.RfiInfomobilityRssAdapter
import it.binario.app.data.live.SncfGtfsRealtimeAdapter
import it.binario.app.data.live.StaRealtimeAdapter
import org.junit.Assert.assertEquals
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
            assertEquals(setOf("RfiStationBoardAdapter", "ItaloPublicStationAdapter", "ViaggiaTrenoStationAdapter"), boardProviderNames)
        } else {
            assertTrue(boardProviderNames.isEmpty())
        }

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
}
