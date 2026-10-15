package it.binario.app.widget

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataQualityBand
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.SavedRoute
import it.binario.app.data.SnapshotWarning
import it.binario.app.data.Station
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateMetadataTest {
    @Test fun `compact metadata keeps provider quality freshness and conflict visible`() {
        val route = SavedRoute(
            id = "roma-torino",
            name = "Roma Torino",
            outwardOrigin = Station("roma", "Roma Termini", "Roma"),
            outwardDestination = Station("torino", "Torino Porta Nuova", "Torino")
        )
        val state = WidgetState(
            route = route,
            snapshotDirection = Direction.OUTBOUND,
            direction = Direction.OUTBOUND,
            services = emptyList(),
            freshness = Freshness.AGING,
            confidence = DataConfidence.INFRASTRUCTURE_PRIMARY,
            providerName = "RFI",
            qualityScore = 84,
            qualityBand = DataQualityBand.RELIABLE,
            qualityWarnings = setOf(SnapshotWarning.SOURCE_CONFLICT),
            retrievedAt = 1L,
            rowOffset = 0
        )

        val meta = state.compactMeta("PAG 1/1")
        assertTrue(meta.contains("RFI"))
        assertTrue(meta.contains("INFRASTRUTTURA"))
        assertTrue(meta.contains("Q84"))
        assertTrue(meta.contains("IN AGGIORNAMENTO"))
        assertTrue(meta.contains("CONFLITTO FONTI"))
    }
}
