package it.binario.app.data.live

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RfiStationCatalogTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-16T09:00:00Z"), ZoneOffset.UTC)
    private val html = """<select id="ElencoLocalita">
        <option value="0">Seleziona</option><option value="3428">Appiano</option>
        <option value="101">Bressanone</option><option value="102">Forlì</option>
        <option value="200">Castello</option><option value="201">Castello</option>
        </select>"""

    @Test fun `risolve qualsiasi stazione pubblicata per nome o alias e riusa il catalogo`() = runBlocking {
        var calls = 0
        val catalog = RfiStationCatalog(RfiMonitorFetcher { url -> assertEquals(RfiStationCatalog.URL, url); calls++; html }, clock)
        assertEquals(3428, catalog.placeId(listOf("Appiano Proba Petronia", "APPIANO")))
        assertEquals(101, catalog.placeId(listOf("Bressanone")))
        assertEquals(102, catalog.placeId(listOf("FORLI")))
        assertEquals(1, calls)
    }

    @Test fun `rifiuta omonimie nomi parziali e alias contraddittori`() = runBlocking {
        val catalog = RfiStationCatalog(RfiMonitorFetcher { html }, clock)
        assertNull(catalog.placeId(listOf("Castello")))
        assertNull(catalog.placeId(listOf("Appia")))
        assertNull(catalog.placeId(listOf("Appiano", "Bressanone")))
    }

    @Test fun `errore rete ha cooldown e non inventa identificativi`() = runBlocking {
        var calls = 0
        val catalog = RfiStationCatalog(RfiMonitorFetcher { calls++; throw java.io.IOException("offline") }, clock)
        repeat(2) { assertNull(catalog.placeId(listOf("Appiano"))) }
        assertEquals(1, calls)
    }
}
