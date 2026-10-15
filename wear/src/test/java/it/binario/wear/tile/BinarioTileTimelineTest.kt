package it.binario.wear.tile

import it.binario.wear.model.WearConfidence
import it.binario.wear.model.WearDataNature
import it.binario.wear.model.WearDirection
import it.binario.wear.model.WearFreshness
import it.binario.wear.model.WearOperationalState
import it.binario.wear.model.WearProvenance
import it.binario.wear.model.WearProvenanceProducer
import it.binario.wear.model.WearSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinarioTileTimelineTest {
    @Test
    fun `snapshot entry expires at its payload deadline and unavailable fallback remains valid`() {
        val expiresAt = 1_893_485_700_000L

        val entries = binarioTileTimeline(snapshot(expiresAt)).timelineEntries

        assertEquals(2, entries.size)
        assertNull(entries[0].validity)
        val validity = requireNotNull(entries[1].validity)
        assertEquals(0L, validity.startMillis)
        assertEquals(expiresAt, validity.endMillis)
    }

    @Test
    fun `fallback used after expiry is the same unavailable layout returned without a snapshot`() {
        val withSnapshot = binarioTileTimeline(snapshot(1_893_485_700_000L)).timelineEntries
        val unavailableOnly = binarioTileTimeline(null).timelineEntries

        assertEquals(1, unavailableOnly.size)
        assertNull(unavailableOnly.single().validity)
        assertArrayEquals(
            requireNotNull(unavailableOnly.single().layout).toByteArray(),
            requireNotNull(withSnapshot.first().layout).toByteArray(),
        )
    }

    private fun snapshot(expiresAt: Long): WearSnapshot = WearSnapshot(
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
        services = emptyList(),
    )
}
