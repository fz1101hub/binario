package it.binario.app.data.catalog

import it.binario.app.data.matchesPublishedName
import it.binario.app.data.liveIdentityKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `a station without codes keeps them null, never the text null`() {
        // The shipped catalogue writes "railwayCode": null for such stations; Android's org.json
        // would read that back as the text "null" through a plain optString.
        val document = """
            {"kind":"curated-rail-station-catalog","operationalData":false,"stations":[
              {"id":"NC000001","name":"Fermata Senza Codici","railwayCode":null,"trenitaliaLocationId":null,"aliases":[],"latitude":null,"longitude":null},
              {"id":"S01700","name":"Milano Centrale","railwayCode":" S01700 ","trenitaliaLocationId":"830001700","aliases":["Milano C.le"]}
            ]}
        """.trimIndent()
        val stations = EmbeddedCuratedRailStationCatalog(openAsset = { document.byteInputStream() }).load()

        val bare = stations.first { it.id == "curated:NC000001" }
        assertNull(bare.railwayCode)
        assertNull(bare.trenitaliaLocationId)
        assertNull(bare.latitude)
        assertEquals("S01700", stations.first { it.id == "curated:S01700" }.railwayCode)
        assertTrue(stations.none { it.railwayCode == "null" || it.trenitaliaLocationId == "null" })
    }

    @Test
    fun `catalog satisfies identity alias and coordinate quality gates`() {
        val assets = File("src/main/assets")
        val stations = EmbeddedCuratedRailStationCatalog(
            openAsset = { assetPath -> File(assets, assetPath).inputStream() }
        ).load()

        assertEquals(stations.size, stations.map { it.id }.toSet().size)
        val railwayCodes = stations.mapNotNull { it.railwayCode }
        assertEquals(railwayCodes.size, railwayCodes.toSet().size)
        val trenitaliaLocationIds = stations.mapNotNull { it.trenitaliaLocationId }
        assertEquals(trenitaliaLocationIds.size, trenitaliaLocationIds.toSet().size)
        assertTrue(stations.flatMap { it.aliases }.all { it.isNotBlank() })
        assertTrue(stations.all { station ->
            val latitude = station.latitude
            val longitude = station.longitude
            (latitude == null && longitude == null) ||
                (latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0)
        })
    }
}
