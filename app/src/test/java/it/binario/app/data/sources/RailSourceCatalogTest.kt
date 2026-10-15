package it.binario.app.data.sources

import it.binario.app.data.core.SourceId
import it.binario.app.data.provider.ProviderCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RailSourceCatalogTest {
    @Test
    fun `il catalogo usa source id univoci`() {
        val ids = RailSourceCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.size >= 29)
    }

    @Test
    fun `le fonti statiche direttamente testabili hanno un probe url`() {
        val readyStatic = RailSourceCatalog.all.filter { it.readiness == SourceReadiness.READY_FOR_GTFS_PACK }
        assertTrue(readyStatic.isNotEmpty())
        assertTrue(readyStatic.all { SourceFormat.GTFS_ZIP in it.formats && it.downloadOrProbeUri != null })
    }

    @Test
    fun `le fonti best effort restano fuori dai candidati realtime attivi`() {
        val realtimeCandidates = RailSourceCatalog.activeCandidates(ProviderCapability.TRAIN_RUN_REALTIME)
        assertFalse(realtimeCandidates.any { it.readiness == SourceReadiness.BEST_EFFORT_DISABLED })
    }

    @Test
    fun `le nuove fonti ufficiali hanno readiness coerente`() {
        val eav = requireNotNull(RailSourceCatalog.byId(SourceId("eav-gtfs")))
        val lombardia = requireNotNull(RailSourceCatalog.byId(SourceId("lombardia-trenord-gtfs")))
        val toscana = requireNotNull(RailSourceCatalog.byId(SourceId("toscana-trenitalia-gtfs")))
        val tft = requireNotNull(RailSourceCatalog.byId(SourceId("toscana-tft-gtfs")))
        val rfiProgrammed = requireNotNull(RailSourceCatalog.byId(SourceId("rfi-programmed")))
        val rfiRss = requireNotNull(RailSourceCatalog.byId(SourceId("rfi-infomobility-rss")))
        val gtt = requireNotNull(RailSourceCatalog.byId(SourceId("gtt-gtfs")))

        assertEquals(SourceReadiness.READY_FOR_GTFS_PACK, eav.readiness)
        assertEquals(SourceReadiness.READY_FOR_GTFS_PACK, lombardia.readiness)
        assertEquals(SourceReadiness.READY_FOR_GTFS_PACK, toscana.readiness)
        assertEquals(SourceReadiness.READY_FOR_GTFS_PACK, tft.readiness)
        assertEquals(SourceReadiness.READY_FOR_PROTOCOL_TEST, rfiProgrammed.readiness)
        assertTrue(ProviderCapability.STATION_BOARD in rfiProgrammed.capabilities)
        assertEquals(SourceReadiness.READY_FOR_PROTOCOL_TEST, rfiRss.readiness)
        assertEquals(SourceReadiness.AGREEMENT_REQUIRED, gtt.readiness)

        assertTrue(rfiRss.formats.contains(SourceFormat.RSS_XML))
        assertTrue(gtt.downloadOrProbeUri.toString().startsWith("https://"))
    }
}
