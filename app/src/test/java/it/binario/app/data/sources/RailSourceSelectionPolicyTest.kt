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
    fun `SNCF realtime usa gli id canonici distinti per trip updates e alerts`() {
        assertTrue(
            RailSourceSelectionPolicy.score(
                RailInformationCategory.TRAIN_REALTIME,
                SourceId("sncf-gtfs-rt-trip-updates"),
                "SNCF Voyageurs"
            ) >= 145
        )
        assertTrue(
            RailSourceSelectionPolicy.score(
                RailInformationCategory.SERVICE_ALERTS,
                SourceId("sncf-gtfs-rt-alerts"),
                "SNCF Voyageurs"
            ) >= 145
        )
    }

    @Test
    fun `HZPP resta disponibile come copertura croata anche senza presumere fermate italiane`() {
        val hzpp = RailSourceCatalog.byId(SourceId("hzpp-gtfs"))
        assertEquals(SourceReadiness.READY_FOR_GTFS_PACK, hzpp?.readiness)
        assertTrue("HR" in hzpp!!.countryCodes)
    }
}
