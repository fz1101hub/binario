package it.binario.app.data.sources

import it.binario.app.data.core.SourceId
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
    fun `European Sleeper resta fallback esplicito e non fonte primaria`() {
        val europeanSleeper = RailSourceInventory.byId(SourceId("european-sleeper-gtfs"))
        assertEquals(SourceReadiness.BEST_EFFORT_FALLBACK, europeanSleeper?.readiness)

        val primaryIds = RailSourceCatalog.activeCandidates(it.binario.app.data.provider.ProviderCapability.JOURNEY_SEARCH)
            .filterNot { it.readiness == SourceReadiness.BEST_EFFORT_FALLBACK }
            .map { it.id }
        assertTrue(SourceId("european-sleeper-gtfs") !in primaryIds)
    }

    @Test
    fun `HZPP resta disponibile come copertura croata anche senza presumere fermate italiane`() {
        val hzpp = RailSourceCatalog.byId(SourceId("hzpp-gtfs"))
        assertEquals(SourceReadiness.READY_FOR_GTFS_PACK, hzpp?.readiness)
        assertTrue("HR" in hzpp!!.countryCodes)
    }
}
