package it.binario.app.data.sources

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
        assertTrue(ids.size >= 25)
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
}
