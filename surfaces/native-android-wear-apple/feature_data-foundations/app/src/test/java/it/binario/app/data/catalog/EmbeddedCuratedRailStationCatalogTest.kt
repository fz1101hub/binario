package it.binario.app.data.catalog

import it.binario.app.data.matchesPublishedName
import it.binario.app.data.liveIdentityKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedCuratedRailStationCatalogTest {
    @Test
    fun `catalog asset loads canonical Roma Termini with verified codes`() {
        val assets = File("src/main/assets")
        val catalog = EmbeddedCuratedRailStationCatalog(
            openAsset = { assetPath -> File(assets, assetPath).inputStream() }
        )

        val termini = catalog.load().first { it.name == "Roma Termini" }

        assertEquals("curated:S08409", termini.id)
        assertEquals("S08409", termini.railwayCode)
        assertEquals("830008409", termini.trenitaliaLocationId)
        assertEquals("trenitalia:830008409", termini.liveIdentityKey())
        assertTrue(termini.matchesPublishedName("ROMA TERMINI"))
    }

    @Test
    fun `catalog preserves aliases and coordinates for canonical stations`() {
        val assets = File("src/main/assets")
        val catalog = EmbeddedCuratedRailStationCatalog(
            openAsset = { assetPath -> File(assets, assetPath).inputStream() }
        )

        val abano = catalog.load().first { it.name == "Abano" }

        assertTrue(abano.matchesPublishedName("Abano Terme"))
        assertEquals("830005700", abano.trenitaliaLocationId)
        assertTrue(abano.latitude != null && abano.longitude != null)
    }

    @Test
    fun `catalog returns Appiano Proba Petronia when typing its curated alias`() {
        val assets = File("src/main/assets")
        val catalog = EmbeddedCuratedRailStationCatalog(
            openAsset = { assetPath -> File(assets, assetPath).inputStream() }
        )

        val appiano = catalog.load().first { station ->
            station.name == "Appiano Proba Petronia" && station.aliases.any { it == "APPIANO" }
        }

        assertEquals("curated:S08328", appiano.id)
        assertEquals("S08328", appiano.railwayCode)
        assertTrue(appiano.matchesPublishedName("Appiano"))
    }

    @Test
    fun `catalog excludes ambiguous Trenitalia location identities`() {
        val assets = File("src/main/assets")
        val catalog = EmbeddedCuratedRailStationCatalog(
            openAsset = { assetPath -> File(assets, assetPath).inputStream() }
        )

        val stations = catalog.load()

        assertEquals(5239, stations.size)
        assertTrue(stations.all { it.id.startsWith("curated:") })
    }
}
