package it.binario.app.data

import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StationCanonicalizerTest {
    private val stations = EmbeddedCuratedRailStationCatalog(
        openAsset = { assetPath -> File("src/main/assets", assetPath).inputStream() }
    ).load()
    private val canonicalizer = StationCanonicalizer(stations)

    @Test
    fun `every real catalog station preserves its identity`() {
        stations.forEach { station ->
            assertSame(station.id, station, canonicalizer.canonicalize(station))
        }
    }

    @Test
    fun `canonical ids win globally even when the supplied name belongs to another station`() {
        val termini = station("Roma Termini")
        val mismatched = termini.copy(name = "Milano Centrale", aliases = listOf("Torino Porta Nuova"))

        assertSame(termini, canonicalizer.canonicalize(mismatched))
    }

    @Test
    fun `a metro or tram stop keeps its identity even when its name is a station's`() {
        val metro = Station("gtfs:roma-mobilita-gtfs:70948", "Roma Termini", "Roma")
        assertSame(metro, canonicalizer.canonicalize(metro))
        // A stop of a railway pack still resolves to its catalogue station by name.
        assertEquals("Roma Termini", canonicalizer.canonicalize(Station("gtfs:trenitalia-gtfs:x", "Roma Termini", "Roma")).name)
    }

    @Test
    fun `legacy values resolve only by exact normalized name or alias`() {
        assertCanonical("legacy:roma-termini", "Roma Termini", "Roma Termini")
        assertCanonical("legacy:milano-centrale", "  MILANO---CENTRALE  ", "Milano Centrale")
        assertCanonical("legacy:torino-porta-nuova", "Torino Porta Nuova", "Torino Porta Nuova")
        assertCanonical("legacy:roma-trastevere", "Roma Trastevere", "Roma Trastevere")
        assertCanonical("legacy:firenze-smn", "Firenze Santa Maria Novella", "Firenze S. M. Novella")
    }

    @Test
    fun `partial and ambiguous input never changes identity`() {
        val partial = Station("legacy:roma-term", "Roma Term", "Roma")
        val ambiguous = Station("legacy:aeroporto", "Aeroporto", "Aeroporto")

        assertSame(partial, canonicalizer.canonicalize(partial))
        assertSame(ambiguous, canonicalizer.canonicalize(ambiguous))
    }

    private fun assertCanonical(legacyId: String, suppliedName: String, expectedName: String) {
        val expected = station(expectedName)
        val legacy = Station(legacyId, suppliedName, suppliedName)

        assertEquals(expected.id, canonicalizer.canonicalize(legacy).id)
    }

    private fun station(name: String): Station = stations.single { it.name == name }
}
