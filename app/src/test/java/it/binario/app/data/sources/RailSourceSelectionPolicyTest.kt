package it.binario.app.data.sources

import it.binario.app.data.core.SourceId
import it.binario.app.data.provider.ProviderCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RailSourceSelectionPolicyTest {
    @Test
    fun `preferisce il feed operatore quando due fonti descrivono la stessa corsa`() {
        val oebb = RailSourceSelectionPolicy.score(
            RailInformationCategory.PLANNED_ITINERARY,
            SourceId("oebb-gtfs"),
            "ÖBB Personenverkehr"
        )
        val delfi = RailSourceSelectionPolicy.score(
            RailInformationCategory.PLANNED_ITINERARY,
            SourceId("delfi-gtfs"),
            "ÖBB Personenverkehr"
        )

        assertTrue(oebb > delfi)
    }

    @Test
    fun `il dataset svizzero riconosce affinita TILO`() {
        val swiss = RailSourceSelectionPolicy.score(
            RailInformationCategory.PLANNED_ITINERARY,
            SourceId("ch-otd-gtfs"),
            "TILO"
        )
        assertTrue(swiss >= 150)
    }

    @Test
    fun `European Sleeper resta solo nel tier fallback`() {
        val id = SourceId("european-sleeper-gtfs")
        val europeanSleeper = RailSourceInventory.byId(id)
        assertEquals(SourceReadiness.BEST_EFFORT_FALLBACK, europeanSleeper?.readiness)

        val primaryIds = RailSourceInventory.primaryCandidates(ProviderCapability.JOURNEY_SEARCH).map { it.id }
        val fallbackIds = RailSourceInventory.fallbackCandidates(ProviderCapability.JOURNEY_SEARCH).map { it.id }
        assertTrue(id !in primaryIds)
        assertTrue(id in fallbackIds)
    }

    @Test
    fun `SNCF realtime usa ranking ufficiale sia per provider runtime sia per endpoint canonici`() {
        listOf("sncf-gtfs-rt", "sncf-gtfs-rt-trip-updates").forEach { id ->
            assertTrue(
                RailSourceSelectionPolicy.score(
                    RailInformationCategory.TRAIN_REALTIME,
                    SourceId(id),
                    "SNCF Voyageurs"
                ) >= 145
            )
        }
        listOf("sncf-gtfs-rt", "sncf-gtfs-rt-alerts").forEach { id ->
            assertTrue(
                RailSourceSelectionPolicy.score(
                    RailInformationCategory.SERVICE_ALERTS,
                    SourceId(id),
                    "SNCF Voyageurs"
                ) >= 145
            )
        }
    }

    @Test
    fun `il pack croato HZPP non e piu nel catalogo`() {
        // Removed in November 2026 by the owner's decision: no Italian station, nothing on iPhone.
        assertEquals(null, RailSourceCatalog.byId(SourceId("hzpp-gtfs")))
    }
}
