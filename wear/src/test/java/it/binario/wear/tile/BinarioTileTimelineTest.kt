package it.binario.wear.tile

import androidx.wear.protolayout.LayoutElementBuilders
import it.binario.wear.ResourceNameStrings
import it.binario.wear.model.WearConfidence
import it.binario.wear.model.WearDataNature
import it.binario.wear.model.WearDirection
import it.binario.wear.model.WearFreshness
import it.binario.wear.model.WearOperationalState
import it.binario.wear.model.WearProvenance
import it.binario.wear.model.WearProvenanceProducer
import it.binario.wear.model.WearService
import it.binario.wear.model.WearSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BinarioTileTimelineTest {
    @Test
    fun `snapshot entry expires at its payload deadline and unavailable fallback remains valid`() {
        val expiresAt = 1_893_485_700_000L

        val entries = binarioTileTimeline(snapshot(expiresAt), ::productionLayout).timelineEntries

        assertEquals(2, entries.size)
        assertNull(entries[0].validity)
        val validity = requireNotNull(entries[1].validity)
        assertEquals(0L, validity.startMillis)
        assertEquals(expiresAt, validity.endMillis)
    }

    @Test
    fun `fallback used after expiry is the same unavailable layout returned without a snapshot`() {
        val withSnapshot = binarioTileTimeline(snapshot(1_893_485_700_000L), ::productionLayout).timelineEntries
        val unavailableOnly = binarioTileTimeline(null, ::productionLayout).timelineEntries

        assertEquals(1, unavailableOnly.size)
        assertNull(unavailableOnly.single().validity)
        assertArrayEquals(
            requireNotNull(unavailableOnly.single().layout).toByteArray(),
            requireNotNull(withSnapshot.first().layout).toByteArray(),
        )
    }

    @Test
    fun `production layout without a snapshot uses the localized unavailable texts`() {
        assertEquals(
            listOf("app_name", "tile_no_synced_route", "empty_snapshot_message", "tile_snapshot_unavailable"),
            texts(productionLayout(null)),
        )
    }

    @Test
    fun `production layout renders route first service and data status from the snapshot`() {
        assertEquals(
            listOf(
                "app_name",
                "Roma Termini → Firenze S. M. Novella",
                "tile_service_details(08:15, RV 2345, Firenze S. M. Novella)",
                "status_received_from_phone",
            ),
            texts(productionLayout(snapshot(1_893_485_700_000L, services = listOf(service)))),
        )
    }

    private fun productionLayout(snapshot: WearSnapshot?): LayoutElementBuilders.LayoutElement =
        binarioTileLayout(snapshot, ResourceNameStrings)

    private fun texts(element: LayoutElementBuilders.LayoutElement): List<String> =
        (element as LayoutElementBuilders.Column).contents.map { requireNotNull((it as LayoutElementBuilders.Text).text).value }

    private val service = WearService(
        number = "RV 2345",
        operator = "Trenitalia",
        category = "Regionale Veloce",
        destination = "Firenze S. M. Novella",
        scheduledDeparture = "08:05",
        expectedDeparture = "08:15",
        status = "In orario",
        platform = "4",
        delayMinutes = 10,
    )

    private fun snapshot(expiresAt: Long, services: List<WearService> = emptyList()): WearSnapshot = WearSnapshot(
        routeId = "route-id",
        routeLabel = "Roma Termini → Firenze S. M. Novella",
        direction = WearDirection.OUTBOUND,
        provenance = WearProvenance(
            producer = WearProvenanceProducer.ANDROID_APP,
            dataNature = WearDataNature.OPERATIONAL,
            sourceLabel = "Fonte",
            observedAtEpochMillis = expiresAt - 60_000L,
        ),
        operationalState = WearOperationalState.OPERATIONAL,
        freshness = WearFreshness.FRESH,
        confidence = WearConfidence.OPERATOR_PRIMARY,
        generatedAtEpochMillis = expiresAt - 60_000L,
        expiresAtEpochMillis = expiresAt,
        ttlMillis = 60_000L,
        notice = "",
        services = services,
    )
}
