package it.binario.app.widget

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataQualityBand
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.SnapshotWarning
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateMetadataTest {
    private val state = WidgetState(
        route = SavedRoute(
            id = "roma-torino",
            name = "Roma Torino",
            outwardOrigin = Station("roma", "Roma Termini", "Roma"),
            outwardDestination = Station("torino", "Torino Porta Nuova", "Torino")
        ),
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

    @Test fun `compact metadata keeps provider quality freshness and conflict visible`() {
        val meta = state.compactMeta(italianWidgetStrings(), "PAG 1/1")
        assertTrue(meta.contains("RFI"))
        assertTrue(meta.contains("INFRASTRUTTURA"))
        assertTrue(meta.contains("Q84"))
        assertTrue(meta.contains("IN AGGIORNAMENTO"))
        assertTrue(meta.contains("CONFLITTO FONTI"))
    }

    @Test fun `compact metadata and TalkBack summary follow the catalogue they are given`() {
        assertEquals("RFI · INFRASTRUCTURE · Q84 · UPDATING · SOURCE CONFLICT · PAGE 1/1", state.compactMeta(englishWidgetStrings(), "PAGE 1/1"))
        assertEquals(
            "Source RFI. Data updating. Infrastructure manager primary source. Reliable data, quality score 84 out of 100. Warnings: Sources in conflict",
            state.accessibleDataSummary(englishWidgetStrings())
        )
    }

    @Test fun `service TalkBack text follows the catalogue instead of Italian literals`() {
        val delayed = WidgetService("123", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "Roma", "12:04", ServiceStatus.DELAYED, 4, "7", true)
        val scheduled = WidgetService("456", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "Roma", "12:30", ServiceStatus.ON_TIME, 0, null, false)

        assertEquals(
            "Operator Trenitalia Regionale. Regional 123 to Roma. Expected departure 12:04. Delayed +4 min. Platform 7.",
            delayed.accessibleLabel(englishWidgetStrings())
        )
        assertEquals(
            "Operatore Trenitalia Regionale. Regionale 456 verso Roma. Partenza prevista 12:30. Stato operativo non verificato. Binario non disponibile.",
            scheduled.accessibleLabel(italianWidgetStrings())
        )
    }

    @Test fun `a cancelled service is announced only as cancelled even when its delay is known`() {
        val cancelled = WidgetService("789", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "Roma", "12:45", ServiceStatus.CANCELLED, 12, "7", true)

        assertEquals(
            "Operator Trenitalia Regionale. Regional 789 to Roma. Expected departure 12:45. Cancelled. Platform 7.",
            cancelled.accessibleLabel(englishWidgetStrings())
        )
    }
}
