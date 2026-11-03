package it.binario.app.data.gtfs

import it.binario.app.data.sources.RailSourceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Info credits every timetable pack of a build with its licensor and licence (audit A7-03). */
class GtfsPackCreditsTest {
    private val dedicated = setOf("trenitalia-gtfs", "italo-gtfs")

    @Test fun `every pack of every build has a generated credit`() {
        val ids = EmbeddedGtfsPacks.forBuild(personalSources = true).map { it.sourceId.value }.toSet()
        assertEquals(ids, GtfsPackCredits.all.map(GtfsPackCredit::sourceId).toSet())
    }

    @Test fun `the lines cover the core packs too, each with its source page`() {
        for (personal in listOf(false, true)) {
            val packs = EmbeddedGtfsPacks.forBuild(personal)
            val lines = GtfsPackCredits.lines(packs)
            val expected = packs.map { it.sourceId }.distinct().filterNot { it.value in dedicated }
            assertEquals(expected.size, lines.size)
            expected.forEach { id ->
                val source = requireNotNull(RailSourceCatalog.byId(id))
                assertTrue(id.value, lines.any { it.startsWith(source.displayName + ": ") && it.endsWith(" ${source.landingPage}") })
            }
        }
        val core = GtfsPackCredits.lines(EmbeddedGtfsPacks.forBuild(personalSources = false))
        assertTrue(core.any { "ÖBB Personenverkehr AG — CC BY 4.0" in it })
        assertTrue(core.any { "T.F.T. S.p.A. — Creative Commons Attribution (CC BY)" in it })
        assertTrue(core.any { "Ente Autonomo Volturno S.r.l. — Italian Open Data License (IODL)" in it })
        assertTrue(core.any { "ARST S.p.A. — CC BY 4.0" in it })
        assertTrue(core.none { "da confermare" in it.lowercase() })
        assertTrue(core.none { "venezia-actv" in it || "ACTV" in it })
    }
}
