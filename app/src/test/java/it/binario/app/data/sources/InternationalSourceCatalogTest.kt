package it.binario.app.data.sources

import it.binario.app.data.core.SourceId
import it.binario.app.data.provider.AccessMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternationalSourceCatalogTest {
    @Test
    fun `DELFI e DB sono catalogati ma richiedono accesso autorizzato`() {
        val delfi = InternationalSourceCatalog.byId(SourceId("delfi-gtfs"))
        val db = InternationalSourceCatalog.byId(SourceId("db-timetables"))

        assertNotNull(delfi)
        assertNotNull(db)
        assertEquals(SourceReadiness.AGREEMENT_REQUIRED, delfi?.readiness)
        assertEquals(AccessMode.AGREEMENT_REQUIRED, db?.accessMode)
    }

    @Test
    fun `European Sleeper e fallback esplicito non fonte primaria`() {
        val static = InternationalSourceCatalog.byId(SourceId("european-sleeper-gtfs"))
        val realtime = InternationalSourceCatalog.byId(SourceId("european-sleeper-gtfs-rt"))

        assertEquals(SourceReadiness.BEST_EFFORT_FALLBACK, static?.readiness)
        assertEquals(SourceReadiness.BEST_EFFORT_FALLBACK, realtime?.readiness)
        assertTrue(static?.countryCodes?.contains("IT") == true)
        assertTrue(
            RailSourceSelectionPolicy.score(RailInformationCategory.PLANNED_ITINERARY, SourceId("european-sleeper-gtfs")) <
                RailSourceSelectionPolicy.score(RailInformationCategory.PLANNED_ITINERARY, SourceId("rfi-programmed"))
        )
    }

    @Test
    fun `la gerarchia preferisce feed operatore a DELFI per un treno OEBB`() {
        val oebb = RailSourceSelectionPolicy.score(
            RailInformationCategory.PLANNED_ITINERARY,
            SourceId("oebb-gtfs"),
            "ÖBB Personenverkehr AG"
        )
        val delfi = RailSourceSelectionPolicy.score(
            RailInformationCategory.PLANNED_ITINERARY,
            SourceId("delfi-gtfs"),
            "ÖBB Personenverkehr AG"
        )

        assertTrue(oebb > delfi)
    }

    @Test
    fun `RFI e fonte primaria per tabellone stazione in Italia`() {
        val ranked = RailSourceSelectionPolicy.ranked(RailInformationCategory.STATION_BOARD)
        assertEquals(SourceId("rfi-station-board"), ranked.first().sourceId)
    }

    @Test
    fun `inventario combina fonti attive e candidate internazionali`() {
        assertNotNull(RailSourceInventory.byId(SourceId("sncf-gtfs")))
        assertNotNull(RailSourceInventory.byId(SourceId("delfi-gtfs")))
    }
}
