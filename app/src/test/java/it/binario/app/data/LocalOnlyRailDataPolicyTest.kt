package it.binario.app.data

import it.binario.app.BuildConfig
import it.binario.app.data.live.ItaloPublicStationAdapter
import it.binario.app.data.live.RfiStationBoardAdapter
import it.binario.app.data.live.ViaggiaTrenoStationAdapter

import it.binario.app.data.live.RfiInfomobilityRssAdapter
import it.binario.app.data.live.SncfGtfsRealtimeAdapter
import it.binario.app.data.live.StaRealtimeAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOnlyRailDataPolicyTest {
    @Test
    fun `production policy keeps route planning local but exposes authorised open realtime providers`() {
        val routeSourceNames = LocalOnlyRailDataPolicy.routeSources().map { it::class.simpleName }.toSet()
        if (BuildConfig.PERSONAL_REALTIME_SOURCES) {
            assertEquals(setOf("RfiStationBoardAdapter", "ItaloPublicStationAdapter", "ViaggiaTrenoStationAdapter"), routeSourceNames)
        } else {
            assertTrue(routeSourceNames.isEmpty())
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
    }
}
